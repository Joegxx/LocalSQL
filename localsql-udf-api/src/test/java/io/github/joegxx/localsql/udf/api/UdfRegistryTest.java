package io.github.joegxx.localsql.udf.api;

import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.type.StringType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UdfRegistryTest {
    @Test
    void resolvesCaseInsensitivelyAndByPriority() {
        UdfRegistry registry = new UdfRegistry();
        registry.register(udf("low", "MASK", 1, List.of(new StringType())));
        registry.register(udf("high", "mask", 10, List.of(new StringType())));

        assertEquals("high", registry.resolve("Mask", List.of(new StringType())).orElseThrow().id());
        assertEquals(2, registry.definitions().size());
    }

    @Test
    void keepsOverloadsSeparateAndRejectsDuplicateIds() {
        UdfRegistry registry = new UdfRegistry();
        registry.register(udf("string", "cast_value", 0, List.of(new StringType())));
        registry.register(udf("long", "cast_value", 0, List.of(IntegralType.BIGINT)));

        assertEquals("string", registry.resolve("cast_value", List.of(new StringType())).orElseThrow().id());
        assertEquals("long", registry.resolve("cast_value", List.of(IntegralType.BIGINT)).orElseThrow().id());
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(udf("string", "cast_value", 5, List.of(new StringType()))));
    }

    private static UdfDefinition udf(String id, String name, int priority, List<io.github.joegxx.localsql.ir.type.DataType> args) {
        return new UdfDefinition(id, name, "example." + id, UdfKind.GENERIC,
                args, new StringType(), "/tmp/" + id + ".jar", "3.1", priority);
    }
}
