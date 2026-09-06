package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.udf.api.UdfDefinition;
import io.github.joegxx.localsql.udf.api.UdfKind;
import io.github.joegxx.localsql.udf.api.UdfRegistry;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads UDF registrations from a YAML config:
 *
 * <pre>
 * timeoutMillis: 30000        # optional, default 30000
 * udfs:
 *   - name: mask_phone
 *     class: com.company.udf.MaskPhone
 *     jar: /opt/udf/company.jar   # jar file OR exploded class directory
 *     kind: GENERIC               # GENERIC (default) | TABLE | SCALAR
 *     args: [STRING, BIGINT]
 *     returns: STRING
 *     priority: 0                 # optional
 * </pre>
 */
public final class UdfConfigLoader {

    /** Parsed config: the registry plus the per-call timeout. */
    public record UdfConfig(UdfRegistry registry, long timeoutMillis) {}

    private UdfConfigLoader() {}

    public static UdfConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("UDF config not found: " + file);
        }
        Object root;
        try (InputStream in = Files.newInputStream(file)) {
            root = new Yaml().load(in);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("UDF config must be a YAML mapping: " + file);
        }
        long timeout = parseTimeout(map.get("timeoutMillis"));
        UdfRegistry registry = new UdfRegistry();
        Object udfs = map.get("udfs");
        if (!(udfs instanceof List<?> list)) {
            throw new IllegalArgumentException("UDF config requires a 'udfs' list: " + file);
        }
        for (int i = 0; i < list.size(); i++) {
            registry.register(definition(list.get(i), file, i));
        }
        return new UdfConfig(registry, timeout);
    }

    private static long parseTimeout(Object value) {
        if (value == null) return 30_000;
        if (value instanceof Number n && n.longValue() > 0) return n.longValue();
        throw new IllegalArgumentException("timeoutMillis must be a positive number: " + value);
    }

    private static UdfDefinition definition(Object entry, Path file, int index) {
        if (!(entry instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("udfs[" + index + "] must be a mapping in " + file);
        }
        String name = string(m, "name", file, index);
        String className = string(m, "class", file, index);
        String jar = string(m, "jar", file, index);
        if (!Files.exists(Path.of(jar))) {
            throw new IllegalArgumentException(
                    "udfs[" + index + "] jar does not exist: " + jar + " (" + file + ")");
        }
        UdfKind kind = m.get("kind") == null
                ? UdfKind.GENERIC
                : UdfKind.valueOf(String.valueOf(m.get("kind")).trim().toUpperCase(Locale.ROOT));
        List<DataType> args = m.get("args") == null ? List.of()
                : ((List<?>) m.get("args")).stream()
                        .map(a -> UdfTypeParser.parse(String.valueOf(a)))
                        .toList();
        DataType returns = m.get("returns") == null ? null : UdfTypeParser.parse(String.valueOf(m.get("returns")));
        if (kind != UdfKind.TABLE && returns == null) {
            throw new IllegalArgumentException("udfs[" + index + "] requires 'returns' for kind " + kind);
        }
        int priority = m.get("priority") instanceof Number n ? n.intValue() : 0;
        String id = m.get("id") == null ? name + "/" + args.size() : String.valueOf(m.get("id"));
        return new UdfDefinition(id, name, className, kind, args, returns, jar, "3.1", priority);
    }

    private static String string(Map<?, ?> m, String key, Path file, int index) {
        Object v = m.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("udfs[" + index + "] requires '" + key + "' in " + file);
        }
        return String.valueOf(v).trim();
    }
}
