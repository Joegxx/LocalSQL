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
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF.DeferredJavaObject;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF.DeferredObject;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes Hive {@link GenericUDF} implementations in batches. Each UDF jar
 * gets an isolated URLClassLoader; a GenericUDF instance plus its
 * ObjectInspectors are created once per registered definition and reused for
 * every row of every batch.
 */
public final class HiveGenericUdfGateway implements io.github.joegxx.localsql.udf.api.UdfGateway {

    private final Map<String, LoadedUdf> loaded = new ConcurrentHashMap<>();

    @Override
    public io.github.joegxx.localsql.udf.api.UdfBatch execute(
            io.github.joegxx.localsql.udf.api.UdfDefinition definition,
            io.github.joegxx.localsql.udf.api.UdfBatch input, long timeoutMillis) throws Exception {
        if (definition.kind() != io.github.joegxx.localsql.udf.api.UdfKind.GENERIC) {
            throw new IllegalArgumentException("HiveGenericUdfGateway only executes GENERIC udfs, got: "
                    + definition.kind() + " for " + definition.id());
        }
        LoadedUdf udf = loaded.computeIfAbsent(definition.id(), ignored -> load(definition));
        long deadline = timeoutMillis > 0 ? System.nanoTime() + timeoutMillis * 1_000_000 : 0L;

        List<List<Object>> outRows = new ArrayList<>(input.rowCount());
        for (List<Object> row : input.rows()) {
            if (deadline != 0 && System.nanoTime() > deadline) {
                throw new IllegalStateException("UDF execution timed out: " + definition.id()
                        + " (" + definition.className() + ") after " + timeoutMillis + "ms");
            }
            DeferredObject[] args = new DeferredObject[udf.inspectors().length];
            for (int i = 0; i < args.length; i++) {
                Object value = i < row.size() ? row.get(i) : null;
                args[i] = new DeferredJavaObject(toHiveValue(definition.argumentTypes().get(i), value));
            }
            Object raw;
            try {
                raw = udf.udf().evaluate(args);
            } catch (Exception e) {
                throw new IllegalStateException("UDF execution failed: " + definition.id()
                        + " class=" + definition.className() + " jar=" + definition.jarPath(), e);
            }
            Object converted = ObjectInspectorUtils.copyToStandardObject(
                    raw, udf.returnInspector(), ObjectInspectorUtils.ObjectInspectorCopyOption.JAVA);
            outRows.add(java.util.Arrays.asList(converted));
        }
        String outputName = definition.name() + "_result";
        return new io.github.joegxx.localsql.udf.api.UdfBatch(List.of(outputName), outRows);
    }

    private LoadedUdf load(io.github.joegxx.localsql.udf.api.UdfDefinition definition) {
        try {
            URLClassLoader loader = newClassLoader(definition.jarPath());
            Class<?> clazz = Class.forName(definition.className(), true, loader);
            if (!GenericUDF.class.isAssignableFrom(clazz)) {
                throw new IllegalStateException("Class " + definition.className() + " in " + definition.jarPath()
                        + " is not a GenericUDF (kind=" + definition.kind() + ")");
            }
            GenericUDF udf = (GenericUDF) clazz.getDeclaredConstructor().newInstance();
            ObjectInspector[] inspectors = new ObjectInspector[definition.argumentTypes().size()];
            for (int i = 0; i < inspectors.length; i++) {
                inspectors[i] = objectInspector(definition.argumentTypes().get(i));
            }
            ObjectInspector returnInspector = objectInspector(definition.returnType());
            udf.initialize(inspectors);
            return new LoadedUdf(udf, inspectors, returnInspector, loader);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize UDF " + definition.id()
                    + " class=" + definition.className() + " jar=" + definition.jarPath(), e);
        }
    }

    private static URLClassLoader newClassLoader(String jarPath) throws Exception {
        Path path = Path.of(jarPath);
        URL url = path.toUri().toURL();
        if (java.nio.file.Files.isDirectory(path) && !url.toString().endsWith("/")) {
            url = new URL(url + "/");
        }
        return new URLClassLoader(new URL[]{url}, HiveGenericUdfGateway.class.getClassLoader());
    }

    static ObjectInspector objectInspector(DataType type) {
        if (type instanceof StringType) return PrimitiveObjectInspectorFactory.javaStringObjectInspector;
        if (type instanceof BooleanType) return PrimitiveObjectInspectorFactory.javaBooleanObjectInspector;
        if (type instanceof IntegralType) return PrimitiveObjectInspectorFactory.javaLongObjectInspector;
        if (type instanceof FractionalType f) {
            return f == FractionalType.DOUBLE
                    ? PrimitiveObjectInspectorFactory.javaDoubleObjectInspector
                    : PrimitiveObjectInspectorFactory.javaFloatObjectInspector;
        }
        if (type instanceof DecimalType) return PrimitiveObjectInspectorFactory.javaHiveDecimalObjectInspector;
        if (type instanceof DateType) return PrimitiveObjectInspectorFactory.javaDateObjectInspector;
        if (type instanceof TimestampType) return PrimitiveObjectInspectorFactory.javaTimestampObjectInspector;
        throw new UnsupportedOperationException(
                "Hive UDF type mapping not supported yet: " + type.typeName());
    }

    static Object toHiveValue(DataType type, Object value) {
        if (value == null) return null;
        if (type instanceof StringType) return value.toString();
        if (type instanceof IntegralType) return ((Number) value).longValue();
        if (type instanceof FractionalType f) {
            if (f == FractionalType.DOUBLE) return ((Number) value).doubleValue();
            return ((Number) value).floatValue();
        }
        if (type instanceof DecimalType) return HiveDecimal.create(value.toString());
        if (type instanceof DateType) return Date.valueOf(value.toString());
        if (type instanceof TimestampType) {
            return value instanceof Timestamp t ? t : Timestamp.valueOf(value.toString());
        }
        return value;
    }

    @Override
    public void close() {
        loaded.clear();
    }

    private record LoadedUdf(GenericUDF udf, ObjectInspector[] inspectors,
                             ObjectInspector returnInspector, URLClassLoader loader) {}
}
