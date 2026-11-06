package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.type.StringType;
import io.github.joegxx.localsql.udf.api.UdfBatch;
import io.github.joegxx.localsql.udf.api.UdfDefinition;
import io.github.joegxx.localsql.udf.api.UdfKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real worker-process round trip: a UDF batch executes inside a child JVM,
 * a timeout kills the worker and the next call transparently restarts one.
 * Skipped when the test classpath cannot be used to spawn a worker (e.g.
 * manifest-only booter classpaths).
 */
class UdfWorkerGatewayTest {

    private static final Path TEST_CLASSES = Path.of(System.getProperty("user.dir"), "target", "test-classes");

    /** Fork-able classpath: surefire's multi-entry java.class.path works; booter jars do not. */
    private static String forkableClasspath() {
        String cp = System.getProperty("java.class.path");
        return cp.contains("localsql-udf-runtime") ? cp : null;
    }

    private static UdfDefinition simpleUdf() {
        return new UdfDefinition("t.upper", "test_simple", TestSimpleUdf.class.getName(), UdfKind.SCALAR,
                List.of(new StringType()), new StringType(), TEST_CLASSES.toString(), "3.1", 0);
    }

    private static UdfDefinition slowUdf() {
        return new UdfDefinition("t.slow", "test_slow", TestSlowUdf.class.getName(), UdfKind.GENERIC,
                List.of(IntegralType.BIGINT), IntegralType.BIGINT, TEST_CLASSES.toString(), "3.1", 0);
    }

    @Test
    void executesInARealWorkerProcess() throws Exception {
        String cp = forkableClasspath();
        assumeTrue(cp != null, "test classpath is not forkable");
        try (UdfWorkerGateway gateway = new UdfWorkerGateway(cp, "256m")) {
            UdfBatch out = gateway.execute(simpleUdf(),
                    new UdfBatch(List.of("s"),
                            List.of(List.of("alice"), java.util.Arrays.asList((Object) null))), 30_000);
            assertEquals("ALICE", out.rows().get(0).get(0));
            assertEquals(null, out.rows().get(1).get(0));
        }
    }

    @Test
    void timeoutKillsTheWorkerAndTheNextCallRestarts() throws Exception {
        String cp = forkableClasspath();
        assumeTrue(cp != null, "test classpath is not forkable");
        try (UdfWorkerGateway gateway = new UdfWorkerGateway(cp, "256m")) {
            // a large batch with a tiny budget: 1ms per row times 20_000 rows
            List<List<Object>> rows = new java.util.ArrayList<>();
            for (int i = 0; i < 20_000; i++) rows.add(List.of((Object) (long) i));
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> gateway.execute(slowUdf(), new UdfBatch(List.of("n"), rows), 50));
            assertTrue(e.getMessage().contains("timed out"), () -> "unexpected: " + e.getMessage());

            // the killed worker is replaced transparently on the next call
            UdfBatch out = gateway.execute(simpleUdf(),
                    new UdfBatch(List.of("s"), List.of(List.of("bob"))), 30_000);
            assertEquals("BOB", out.rows().get(0).get(0));
        }
    }
}
