package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.ir.type.StringType;
import io.github.joegxx.localsql.udf.api.UdfBatch;
import io.github.joegxx.localsql.udf.api.UdfDefinition;
import io.github.joegxx.localsql.udf.api.UdfKind;
import io.github.joegxx.localsql.udf.api.UdfRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveGenericUdfGatewayTest {

    private static final Path TEST_CLASSES = Path.of(System.getProperty("user.dir"), "target", "test-classes");

    private static UdfDefinition suffixDefinition(String id) {
        return new UdfDefinition(id, "test_suffix", TestSuffixUdf.class.getName(), UdfKind.GENERIC,
                List.of(new StringType(), new StringType()), new StringType(),
                TEST_CLASSES.toString(), "3.1", 0);
    }

    @Test
    void executesGenericUdfBatchWithNullPropagation() throws Exception {
        assertTrue(Files.isDirectory(TEST_CLASSES), "test-classes must exist: " + TEST_CLASSES);
        try (HiveGenericUdfGateway gateway = new HiveGenericUdfGateway()) {
            UdfBatch out = gateway.execute(suffixDefinition("test.suffix"),
                    new UdfBatch(List.of("a", "b"), List.of(
                            List.of("foo", "bar"), java.util.Arrays.asList(null, "baz"), List.of("a", "b"))),
                    10_000);
            assertEquals(List.of("test_suffix_result"), out.columnNames());
            assertEquals(3, out.rowCount());
            assertEquals("foobar", out.rows().get(0).get(0));
            assertNull(out.rows().get(1).get(0));
            assertEquals("ab", out.rows().get(2).get(0));
        }
    }

    @Test
    void resolvesThroughRegistryAndReusesLoadedInstance() throws Exception {
        UdfRegistry registry = new UdfRegistry();
        registry.register(suffixDefinition("first.suffix"));
        registry.register(suffixDefinition("second.suffix"));

        try (HiveGenericUdfGateway gateway = new HiveGenericUdfGateway()) {
            UdfBatch first = gateway.execute(
                    registry.resolve("test_suffix", List.of(new StringType(), new StringType())).orElseThrow(),
                    new UdfBatch(List.of("a", "b"), List.of(List.of("x", "y"))), 10_000);
            UdfBatch second = gateway.execute(
                    registry.resolve("TEST_SUFFIX", List.of(new StringType(), new StringType())).orElseThrow(),
                    new UdfBatch(List.of("a", "b"), List.of(List.of("1", "2"))), 10_000);
            assertEquals("xy", first.rows().get(0).get(0));
            assertEquals("12", second.rows().get(0).get(0));
        }
    }

    @Test
    void enforcesTimeout() throws Exception {
        try (HiveGenericUdfGateway gateway = new HiveGenericUdfGateway()) {
            UdfDefinition slow = new UdfDefinition("test.slow", "test_slow",
                    TestSlowUdf.class.getName(), UdfKind.GENERIC,
                    List.of(io.github.joegxx.localsql.ir.type.IntegralType.BIGINT),
                    io.github.joegxx.localsql.ir.type.IntegralType.BIGINT,
                    TEST_CLASSES.toString(), "3.1", 0);
            UdfBatch batch = new UdfBatch(List.of("v"),
                    java.util.stream.IntStream.range(0, 20)
                            .<List<Object>>mapToObj(i -> List.of(i)).toList());
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> gateway.execute(slow, batch, 5));
            assertTrue(e.getMessage().contains("timed out"));
        }
    }

    @Test
    void rejectsWrongKindAndMissingClass() {
        try (HiveGenericUdfGateway gateway = new HiveGenericUdfGateway()) {
            UdfDefinition scalar = new UdfDefinition("bad.kind", "fn", TestSuffixUdf.class.getName(),
                    UdfKind.SCALAR, List.of(new StringType()), new StringType(), TEST_CLASSES.toString(), "3.1", 0);
            IllegalArgumentException kindError = assertThrows(IllegalArgumentException.class,
                    () -> gateway.execute(scalar, new UdfBatch(List.of("a"), List.of()), 1_000));
            assertTrue(kindError.getMessage().contains("GENERIC"));

            UdfDefinition missing = new UdfDefinition("missing.class", "fn",
                    "io.github.joegxx.localsql.udf.runtime.DoesNotExist", UdfKind.GENERIC,
                    List.of(new StringType()), new StringType(), TEST_CLASSES.toString(), "3.1", 0);
            IllegalStateException classError = assertThrows(IllegalStateException.class,
                    () -> gateway.execute(missing, new UdfBatch(List.of("a"), List.of(List.of("x"))), 1_000));
            assertTrue(classError.getMessage().contains("missing.class"));
        }
    }
}
