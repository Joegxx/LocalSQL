package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.ir.type.BooleanType;
import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.ir.type.DateType;
import io.github.joegxx.localsql.ir.type.DecimalType;
import io.github.joegxx.localsql.ir.type.FractionalType;
import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.type.StringType;
import io.github.joegxx.localsql.ir.type.TimestampType;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.ql.exec.UDF;
import org.apache.hadoop.io.BooleanWritable;
import org.apache.hadoop.io.ByteWritable;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.ShortWritable;
import org.apache.hadoop.io.Text;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes simple Hive UDFs ({@code org.apache.hadoop.hive.ql.exec.UDF})
 * via reflection on their {@code evaluate} methods, like Hive's
 * GenericUDFBridge. The evaluate overload is matched once against the
 * registered argument types (java boxed > hadoop writable > Object), one
 * UDF instance is created per batch, and NULL arguments produce a NULL
 * result row (Hive semantics for primitive-arg UDFs).
 */
public final class HiveSimpleUdfGateway implements io.github.joegxx.localsql.udf.api.UdfGateway {

    private final Map<String, LoadedUdf> loaded = new ConcurrentHashMap<>();

    private record LoadedUdf(URLClassLoader loader, Class<?> clazz, Method method, UDF instance) {}

    @Override
    public io.github.joegxx.localsql.udf.api.UdfBatch execute(
            io.github.joegxx.localsql.udf.api.UdfDefinition definition,
            io.github.joegxx.localsql.udf.api.UdfBatch input, long timeoutMillis) throws Exception {
        if (definition.kind() != io.github.joegxx.localsql.udf.api.UdfKind.SCALAR) {
            throw new IllegalArgumentException("HiveSimpleUdfGateway only executes SCALAR udfs, got: "
                    + definition.kind() + " for " + definition.id());
        }
        LoadedUdf loadedUdf = loaded.computeIfAbsent(definition.id(), ignored -> load(definition));
        long deadline = timeoutMillis > 0 ? System.nanoTime() + timeoutMillis * 1_000_000 : 0L;

        List<List<Object>> outRows = new ArrayList<>(input.rowCount());
        for (List<Object> row : input.rows()) {
            if (deadline != 0 && System.nanoTime() > deadline) {
                throw new IllegalStateException("UDF execution timed out: " + definition.id()
                        + " (" + definition.className() + ") after " + timeoutMillis + "ms");
            }
            Class<?>[] params = loadedUdf.method().getParameterTypes();
            Object[] args = new Object[params.length];
            boolean anyNull = false;
            for (int i = 0; i < params.length; i++) {
                Object value = i < row.size() ? row.get(i) : null;
                if (value == null) {
                    anyNull = true;
                    break;
                }
                args[i] = toParamValue(definition.argumentTypes().get(i), params[i], value);
            }
            // Hive returns NULL when any primitive-side argument is NULL
            if (anyNull) {
                outRows.add(java.util.Arrays.asList((Object) null));
                continue;
            }
            Object raw;
            try {
                raw = loadedUdf.method().invoke(loadedUdf.instance(), args);
            } catch (Exception e) {
                throw new IllegalStateException("UDF execution failed: " + definition.id()
                        + " class=" + definition.className() + " jar=" + definition.jarPath(), e);
            }
            outRows.add(java.util.Arrays.asList(fromResult(raw)));
        }
        String outputName = definition.name() + "_result";
        return new io.github.joegxx.localsql.udf.api.UdfBatch(List.of(outputName), outRows);
    }

    private LoadedUdf load(io.github.joegxx.localsql.udf.api.UdfDefinition definition) {
        try {
            Path path = Path.of(definition.jarPath());
            URL url = path.toUri().toURL();
            if (Files.isDirectory(path) && !url.toString().endsWith("/")) {
                url = new URL(url + "/");
            }
            URLClassLoader loader = new URLClassLoader(new URL[]{url},
                    HiveSimpleUdfGateway.class.getClassLoader());
            Class<?> clazz = Class.forName(definition.className(), true, loader);
            if (!UDF.class.isAssignableFrom(clazz)) {
                throw new IllegalStateException("Class " + definition.className() + " in " + definition.jarPath()
                        + " is not a hive exec.UDF (kind=" + definition.kind() + ")");
            }
            Method method = matchEvaluate(clazz, definition.argumentTypes());
            UDF instance = (UDF) clazz.getDeclaredConstructor().newInstance();
            return new LoadedUdf(loader, clazz, method, instance);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize simple UDF " + definition.id()
                    + " class=" + definition.className() + " jar=" + definition.jarPath(), e);
        }
    }

    /** Scores evaluate overloads: java boxed (3) > hadoop writable (2) > Object (1). */
    static Method matchEvaluate(Class<?> clazz, List<DataType> argumentTypes) {
        Method best = null;
        int bestScore = -1;
        for (Method m : clazz.getMethods()) {
            if (!"evaluate".equals(m.getName()) || m.isSynthetic() || m.isBridge()
                    || m.isVarArgs() || java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    || m.getReturnType() == void.class) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length != argumentTypes.size()) continue;
            int score = 0;
            boolean compatible = true;
            for (int i = 0; i < params.length && compatible; i++) {
                int tier = tier(argumentTypes.get(i), params[i]);
                if (tier <= 0) compatible = false;
                else score += tier;
            }
            if (compatible && score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        if (best == null) {
            throw new IllegalStateException("No evaluate(" + argumentTypes + ") method found on " + clazz.getName());
        }
        return best;
    }

    private static int tier(DataType type, Class<?> param) {
        if (type instanceof StringType) {
            if (param == String.class) return 3;
            if (param == Text.class) return 2;
        } else if (type instanceof BooleanType) {
            if (param == Boolean.class) return 3;
            if (param == BooleanWritable.class) return 2;
        } else if (type instanceof IntegralType integral) {
            Class<?> boxed = boxed(integral);
            Class<?> writable = writable(integral);
            if (param == boxed) return 3;
            if (param == writable) return 2;
        } else if (type instanceof FractionalType f) {
            if (f == FractionalType.DOUBLE && param == Double.class) return 3;
            if (f == FractionalType.FLOAT && param == Float.class) return 3;
            if (f == FractionalType.DOUBLE && param == DoubleWritable.class) return 2;
            if (f == FractionalType.FLOAT && param == FloatWritable.class) return 2;
        } else if (type instanceof DateType) {
            if (param == Date.class) return 3;
        } else if (type instanceof TimestampType) {
            if (param == Timestamp.class) return 3;
        } else if (type instanceof DecimalType) {
            if (param == HiveDecimal.class) return 3;
        }
        return param == Object.class ? 1 : 0;
    }

    private static Class<?> boxed(IntegralType integral) {
        if (integral == IntegralType.TINYINT) return Byte.class;
        if (integral == IntegralType.SMALLINT) return Short.class;
        if (integral == IntegralType.INT) return Integer.class;
        return Long.class;
    }

    private static Class<?> writable(IntegralType integral) {
        if (integral == IntegralType.TINYINT) return ByteWritable.class;
        if (integral == IntegralType.SMALLINT) return ShortWritable.class;
        if (integral == IntegralType.INT) return IntWritable.class;
        return LongWritable.class;
    }

    private static Object toParamValue(DataType type, Class<?> param, Object value) {
        if (param == Object.class || param.isInstance(value)) return value;
        if (param == String.class) return value.toString();
        if (param == Text.class) return new Text(value.toString());
        if (param == Byte.class || param == ByteWritable.class) {
            byte v = ((Number) value).byteValue();
            return param == Byte.class ? v : new ByteWritable(v);
        }
        if (param == Short.class || param == ShortWritable.class) {
            short v = ((Number) value).shortValue();
            return param == Short.class ? v : new ShortWritable(v);
        }
        if (param == Integer.class || param == IntWritable.class) {
            int v = ((Number) value).intValue();
            return param == Integer.class ? v : new IntWritable(v);
        }
        if (param == Long.class || param == LongWritable.class) {
            long v = ((Number) value).longValue();
            return param == Long.class ? v : new LongWritable(v);
        }
        if (param == Float.class || param == FloatWritable.class) {
            float v = ((Number) value).floatValue();
            return param == Float.class ? v : new FloatWritable(v);
        }
        if (param == Double.class || param == DoubleWritable.class) {
            double v = ((Number) value).doubleValue();
            return param == Double.class ? v : new DoubleWritable(v);
        }
        if (param == Boolean.class || param == BooleanWritable.class) {
            boolean v = (Boolean) value;
            return param == Boolean.class ? v : new BooleanWritable(v);
        }
        if (param == Date.class) return Date.valueOf(value.toString());
        if (param == Timestamp.class) return value instanceof Timestamp t ? t : Timestamp.valueOf(value.toString());
        if (param == HiveDecimal.class) return HiveDecimal.create(value.toString());
        return value;
    }

    private static Object fromResult(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Text t) return t.toString();
        if (raw instanceof IntWritable w) return w.get();
        if (raw instanceof LongWritable w) return w.get();
        if (raw instanceof ShortWritable w) return w.get();
        if (raw instanceof ByteWritable w) return w.get();
        if (raw instanceof DoubleWritable w) return w.get();
        if (raw instanceof FloatWritable w) return w.get();
        if (raw instanceof BooleanWritable w) return w.get();
        if (raw instanceof HiveDecimal d) return d.toString();
        return raw;
    }

    @Override
    public void close() {
        loaded.clear();
    }
}
