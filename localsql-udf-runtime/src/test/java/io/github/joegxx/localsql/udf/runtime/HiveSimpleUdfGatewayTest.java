package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.ir.type.IntegralType;
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

class HiveSimpleUdfGatewayTest {

    private static final Path TEST_CLASSES = Path.of(System.getProperty("user.dir"), "target", "test-classes");

    private static UdfDefinition def(String id, String name, List<io.github.joegxx.localsql.ir.type.DataType> args) {
        return new UdfDefinition(id, name, TestSimpleUdf.class.getName(), UdfKind.SCALAR,
                args, new StringType(), TEST_CLASSES.toString(), "3.1", 0);
    }

    @Test
    void resolvesOverloadsAndConvertsArgsAndResults() throws Exception {
        assertTrue(Files.isDirectory(TEST_CLASSES));
        try (HiveSimpleUdfGateway gateway = new HiveSimpleUdfGateway()) {
            // STRING -> picks evaluate(Text), result converted Text -> String
            UdfBatch out = gateway.execute(def("t.upper", "test_simple", List.of(new StringType())),
                    new UdfBatch(List.of("s"), List.of(List.of("alice"), java.util.Arrays.asList((Object) null))),
                    10_000);
            assertEquals("ALICE", out.rows().get(0).get(0));
            assertNull(out.rows().get(1).get(0));

            // (STRING, INT) -> picks evaluate(String, Integer); DuckDB ints are Long -> converted
            UdfBatch concat = gateway.execute(
                    def("t.concat", "test_simple", List.of(new StringType(), IntegralType.INT)),
                    new UdfBatch(List.of("s", "n"), List.of(List.of("v", 7L))),
                    10_000);
            assertEquals("v7", concat.rows().get(0).get(0));

            // BIGINT -> picks evaluate(LongWritable), result converted back
            UdfBatch doubled = gateway.execute(def("t.dbl", "test_simple", List.of(IntegralType.BIGINT)),
                    new UdfBatch(List.of("v"), List.of(List.of(21L))),
                    10_000);
            assertEquals(42L, doubled.rows().get(0).get(0));
        }
    }

    @Test
    void rejectsWrongKindAndUnmatchableSignature() {
        try (HiveSimpleUdfGateway gateway = new HiveSimpleUdfGateway()) {
            UdfDefinition generic = new UdfDefinition("bad.kind", "fn", TestSimpleUdf.class.getName(),
                    UdfKind.GENERIC, List.of(new StringType()), new StringType(),
                    TEST_CLASSES.toString(), "3.1", 0);
            IllegalArgumentException kindError = assertThrows(IllegalArgumentException.class,
                    () -> gateway.execute(generic, new UdfBatch(List.of("s"), List.of()), 1_000));
            assertTrue(kindError.getMessage().contains("SCALAR"));

            // no evaluate(String, String) overload exists on TestSimpleUdf
            UdfDefinition noMatch = def("no.match", "test_simple",
                    List.of(new StringType(), new StringType()));
            IllegalStateException sigError = assertThrows(IllegalStateException.class,
                    () -> gateway.execute(noMatch,
                            new UdfBatch(List.of("a", "b"), List.of(List.of("x", "y"))), 1_000));
            assertTrue(sigError.getMessage().contains("No evaluate"));
        }
    }

    @Test
    void registryResolutionFeedsGateway() throws Exception {
        UdfRegistry registry = new UdfRegistry();
        registry.register(def("cfg.star", "test_simple", List.of(new StringType())));
        try (HiveSimpleUdfGateway gateway = new HiveSimpleUdfGateway()) {
            UdfBatch out = gateway.execute(
                    registry.resolveByName("TEST_SIMPLE", 1).orElseThrow(),
                    new UdfBatch(List.of("s"), List.of(List.of("bob"))), 10_000);
            assertEquals("BOB", out.rows().get(0).get(0));
        }
    }
}
