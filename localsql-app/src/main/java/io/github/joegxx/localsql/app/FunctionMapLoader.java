package io.github.joegxx.localsql.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Loads user function mappings from a YAML config fed to --function-map:
 *
 * <pre>
 * functions:
 *   # rename: the call becomes target(args...)
 *   my_size: array_length
 *   # template: {N} is replaced by the Nth argument
 *   next_friday: "date_add({0}, 7)"
 *   normalize_phone: "regexp_replace({0}, '[^0-9]', '', 'g')"
 * </pre>
 *
 * Values containing {N} placeholders become templates; everything else is a
 * plain rename. Custom entries override built-in mappings.
 */
public final class FunctionMapLoader {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+\\}");

    public record FunctionMap(Map<String, String> renames, Map<String, String> templates) {}

    private FunctionMapLoader() {}

    public static FunctionMap load(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("function map config not found: " + file);
        }
        Object root;
        try (InputStream in = Files.newInputStream(file)) {
            root = new org.yaml.snakeyaml.Yaml().load(in);
        }
        if (!(root instanceof Map<?, ?> map) || !(map.get("functions") instanceof Map<?, ?> functions)) {
            throw new IllegalArgumentException("function map config requires a 'functions' mapping: " + file);
        }
        Map<String, String> renames = new LinkedHashMap<>();
        Map<String, String> templates = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : functions.entrySet()) {
            String name = String.valueOf(e.getKey()).trim();
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            if (name.isEmpty() || value.isEmpty()) {
                throw new IllegalArgumentException("function map entries must be name: mapping: " + file);
            }
            if (PLACEHOLDER.matcher(value).find()) templates.put(name.toLowerCase(), value);
            else renames.put(name.toLowerCase(), value);
        }
        return new FunctionMap(renames, templates);
    }
}
