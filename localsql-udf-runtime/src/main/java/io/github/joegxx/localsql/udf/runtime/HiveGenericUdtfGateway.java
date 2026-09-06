package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.udf.api.UdfBatch;
import io.github.joegxx.localsql.udf.api.UdfDefinition;
import io.github.joegxx.localsql.udf.api.UdfGateway;
import io.github.joegxx.localsql.udf.api.UdtfResult;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDTF;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF.DeferredJavaObject;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF.DeferredObject;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes Hive {@link GenericUDTF} implementations: one instance per batch
 * (UDTFs are stateful), initialize() once, process() per input row with a
 * collector capturing forwarded rows plus their source row index, close()
 * at the end. UDTFs own their outer/zero-row semantics; the gateway only
 * transports them.
 */
public final class HiveGenericUdtfGateway implements UdfGateway {

    private record LoadedClass(GenericUdtfClass type, URLClassLoader loader) {}

    /** Holder so the cache can store the raw Class without generics warnings. */
    private record GenericUdtfClass(Class<?> clazz) {}

    private final Map<String, LoadedClass> classes = new ConcurrentHashMap<>();

    @Override
    public io.github.joegxx.localsql.udf.api.UdfBatch execute(
            io.github.joegxx.localsql.udf.api.UdfDefinition definition,
            io.github.joegxx.localsql.udf.api.UdfBatch input, long timeoutMillis) {
        throw new UnsupportedOperationException(
                "HiveGenericUdtfGateway only executes TABLE udfs; use HiveGenericUdfGateway for GENERIC");
    }

    @Override
    public UdtfResult executeUdtf(UdfDefinition definition, UdfBatch input, long timeoutMillis) throws Exception {
        if (definition.kind() != io.github.joegxx.localsql.udf.api.UdfKind.TABLE) {
            throw new IllegalArgumentException("HiveGenericUdtfGateway only executes TABLE udfs, got: "
                    + definition.kind() + " for " + definition.id());
        }
        long deadline = timeoutMillis > 0 ? System.nanoTime() + timeoutMillis * 1_000_000 : 0L;
        GenericUDTF udtf = newInstance(definition);
        List<Object[]> collected = new ArrayList<>();
        List<Integer> sources = new ArrayList<>();
        int[] currentIndex = new int[1];
        udtf.setCollector(row -> {
            collected.add(toArray(row));
            sources.add(currentIndex[0]);
        });
        StructObjectInspector outInspector;
        try {
            ObjectInspector[] inspectors = new ObjectInspector[definition.argumentTypes().size()];
            for (int i = 0; i < inspectors.length; i++) {
                inspectors[i] = HiveGenericUdfGateway.objectInspector(definition.argumentTypes().get(i));
            }
            outInspector = udtf.initialize(inspectors);
            List<String> names = outInspector.getAllStructFieldRefs().stream().map(StructField::getFieldName).toList();
            List<String> types = outInspector.getAllStructFieldRefs().stream()
                    .map(f -> duckType(f.getFieldObjectInspector())).toList();

            for (int r = 0; r < input.rowCount(); r++) {
                if (deadline != 0 && System.nanoTime() > deadline) {
                    throw new IllegalStateException("UDTF execution timed out: " + definition.id()
                            + " (" + definition.className() + ") after " + timeoutMillis + "ms");
                }
                currentIndex[0] = r;
                DeferredObject[] args = new DeferredObject[inspectors.length];
                for (int i = 0; i < args.length; i++) {
                    Object value = r < input.rows().size() && i < input.rows().get(r).size()
                            ? input.rows().get(r).get(i) : null;
                    args[i] = new DeferredJavaObject(
                            HiveGenericUdfGateway.toHiveValue(definition.argumentTypes().get(i), value));
                }
                try {
                    udtf.process(args);
                } catch (HiveException e) {
                    throw new IllegalStateException("UDTF process failed: " + definition.id()
                            + " class=" + definition.className() + " jar=" + definition.jarPath(), e);
                }
            }
            udtf.close();

            List<List<Object>> rows = new ArrayList<>(collected.size());
            for (Object[] raw : collected) {
                List<Object> std = new ArrayList<>(raw.length);
                for (int c = 0; c < raw.length; c++) {
                    std.add(ObjectInspectorUtils.copyToStandardObject(
                            raw[c], outInspector.getAllStructFieldRefs().get(c).getFieldObjectInspector(),
                            ObjectInspectorUtils.ObjectInspectorCopyOption.JAVA));
                }
                rows.add(std);
            }
            return new UdtfResult(names, types, rows, sources);
        } finally {
            try {
                udtf.close();
            } catch (Exception ignore) {
                // close() may already have run; never mask the original outcome
            }
        }
    }

    private static Object[] toArray(Object row) {
        if (row instanceof Object[] arr) return arr;
        if (row instanceof List<?> list) return list.toArray();
        return new Object[]{row};
    }

    private GenericUDTF newInstance(UdfDefinition definition) {
        GenericUdtfClass type = classes.computeIfAbsent(definition.id(), id -> {
            try {
                Path path = Path.of(definition.jarPath());
                URL url = path.toUri().toURL();
                if (java.nio.file.Files.isDirectory(path) && !url.toString().endsWith("/")) {
                    url = new URL(url + "/");
                }
                URLClassLoader loader = new URLClassLoader(new URL[]{url},
                        HiveGenericUdtfGateway.class.getClassLoader());
                Class<?> clazz = Class.forName(definition.className(), true, loader);
                if (!GenericUDTF.class.isAssignableFrom(clazz)) {
                    throw new IllegalStateException("Class " + definition.className() + " in " + definition.jarPath()
                            + " is not a GenericUDTF");
                }
                return new LoadedClass(new GenericUdtfClass(clazz), loader);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load UDTF " + definition.id()
                        + " class=" + definition.className() + " jar=" + definition.jarPath(), e);
            }
        }).type();
        try {
            return (GenericUDTF) type.clazz().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate UDTF " + definition.id()
                    + " class=" + definition.className(), e);
        }
    }

    /** DuckDB storage type for an output column, used when materializing empty results. */
    static String duckType(ObjectInspector inspector) {
        String type = inspector == null ? "" : inspector.getTypeName();
        return switch (type) {
            case "string", "varchar", "char" -> "VARCHAR";
            case "bigint", "long" -> "BIGINT";
            case "int", "integer" -> "INTEGER";
            case "smallint" -> "SMALLINT";
            case "tinyint" -> "TINYINT";
            case "double" -> "DOUBLE";
            case "float" -> "REAL";
            case "boolean" -> "BOOLEAN";
            case "date" -> "DATE";
            case "timestamp", "timestamp with local time zone" -> "TIMESTAMP";
            default -> type.startsWith("decimal") ? "DECIMAL(38,10)" : "VARCHAR";
        };
    }

    @Override
    public void close() {
        classes.clear();
    }
}
