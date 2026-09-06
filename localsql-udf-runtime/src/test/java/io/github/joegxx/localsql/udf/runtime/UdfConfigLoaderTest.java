package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.ir.type.DecimalType;
import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.type.StringType;
import io.github.joegxx.localsql.udf.api.UdfKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdfConfigLoaderTest {

    @Test
    void loadsRegistryAndTimeout() throws Exception {
        Path jar = Files.createTempFile("udf", ".jar");
        Path yaml = Files.createTempFile("udfs", ".yaml");
        Files.writeString(yaml, """
                timeoutMillis: 5000
                udfs:
                  - name: mask_phone
                    class: com.company.MaskPhone
                    jar: %s
                    kind: GENERIC
                    args: [STRING, "DECIMAL(10,2)"]
                    returns: STRING
                    priority: 5
                  - name: split_tags
                    class: com.company.SplitTags
                    jar: %s
                    kind: TABLE
                    args: [BIGINT, STRING]
                """.formatted(jar, jar));

        UdfConfigLoader.UdfConfig config = UdfConfigLoader.load(yaml);
        assertEquals(5000, config.timeoutMillis());
        assertEquals(2, config.registry().definitions().size());

        var generic = config.registry().resolveByName("mask_phone", 2).orElseThrow();
        assertEquals(UdfKind.GENERIC, generic.kind());
        assertEquals(5, generic.priority());
        assertTrue(generic.argumentTypes().get(0) instanceof StringType);
        var decimal = (DecimalType) generic.argumentTypes().get(1);
        assertEquals(10, decimal.precision());
        assertEquals(2, decimal.scale());
        assertEquals("mask_phone/2", generic.id());

        var table = config.registry().resolveByName("split_tags", 2).orElseThrow();
        assertEquals(UdfKind.TABLE, table.kind());
        assertEquals(IntegralType.BIGINT, table.argumentTypes().get(0));
    }

    @Test
    void rejectsMissingJarMissingFileAndBadType() throws Exception {
        Path yaml = Files.createTempFile("bad", ".yaml");
        Files.writeString(yaml, """
                udfs:
                  - name: fn
                    class: com.company.Fn
                    jar: /definitely/not/here.jar
                """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> UdfConfigLoader.load(yaml));
        assertTrue(e.getMessage().contains("jar does not exist"));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> UdfConfigLoader.load(Path.of("/definitely/not/udfs.yaml")));
        assertTrue(missing.getMessage().contains("not found"));

        Path badType = Files.createTempFile("badtype", ".yaml");
        Files.writeString(badType, """
                udfs:
                  - name: fn
                    class: com.company.Fn
                    jar: %s
                    args: [ARRAY<STRING>]
                """.formatted(Files.createTempFile("j", ".jar")));
        IllegalArgumentException typeError = assertThrows(IllegalArgumentException.class,
                () -> UdfConfigLoader.load(badType));
        assertTrue(typeError.getMessage().contains("Unsupported UDF type"));
    }
}
