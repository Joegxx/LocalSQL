package io.github.joegxx.localsql.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionMapLoaderTest {

    @Test
    void splitsRenamesAndTemplates() throws Exception {
        Path yaml = Files.createTempFile("fmap", ".yaml");
        Files.writeString(yaml, """
                functions:
                  my_size: array_length
                  next_friday: "date_add({0}, 7)"
                  normalize_phone: "regexp_replace({0}, '[^0-9]', '', 'g')"
                """);

        FunctionMapLoader.FunctionMap fm = FunctionMapLoader.load(yaml);
        assertEquals(Map.of("my_size", "array_length"), fm.renames());
        assertEquals(2, fm.templates().size());
        assertTrue(fm.templates().containsKey("next_friday"));
        assertTrue(fm.templates().containsKey("normalize_phone"));
    }

    @Test
    void rejectsMissingFileAndEmptyValues() throws Exception {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> FunctionMapLoader.load(Path.of("/definitely/not/functions.yaml")));
        assertTrue(missing.getMessage().contains("not found"));

        Path empty = Files.createTempFile("fmap", ".yaml");
        Files.writeString(empty, "functions:\n  fn: \"\"\n");
        assertThrows(IllegalArgumentException.class, () -> FunctionMapLoader.load(empty));
    }
}
