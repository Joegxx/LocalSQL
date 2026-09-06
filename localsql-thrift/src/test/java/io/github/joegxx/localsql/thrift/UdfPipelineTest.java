package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.ir.type.StringType;
import io.github.joegxx.localsql.udf.api.UdfDefinition;
import io.github.joegxx.localsql.udf.api.UdfKind;
import io.github.joegxx.localsql.udf.api.UdfRegistry;
import io.github.joegxx.localsql.udf.runtime.HiveGenericUdfGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: a registered GenericUDF is split out of the top-level
 * projection, executed through the gateway and reassembled into the result.
 */
class UdfPipelineTest {

    private DuckDbExecutor executor;
    private CatalogService catalog;
    private ThriftServer server;
    private UdfSupport support;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        catalog = new CatalogService(store);
        executor.registerTable("t",
                List.of(new DuckDbExecutor.ColDef("id", "BIGINT"),
                        new DuckDbExecutor.ColDef("name", "VARCHAR")),
                List.of(List.of(1L, "alice"), List.of(2L, "bob"), List.of(3L, "carol")));
        catalog.registerTable("default", "t", "TABLE",
                List.of(new CatalogService.ColumnDef("id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("name", new StringType(), true, null)),
                null, "duckdb", null, null, null);

        UdfRegistry registry = new UdfRegistry();
        Path testClasses = Path.of(System.getProperty("user.dir"), "target", "test-classes");
        registry.register(new UdfDefinition("test.upper", "test_upper", TestUpperUdf.class.getName(),
                UdfKind.GENERIC, List.of(new StringType()), new StringType(),
                testClasses.toString(), "3.1", 0));
        support = new UdfSupport(registry, new HiveGenericUdfGateway(), 10_000);
        server = new ThriftServer(catalog, executor, "default", support);
    }

    @AfterEach
    void tearDown() throws Exception {
        support.close();
    }

    @Test
    void executesUdfInProjectionWithOtherColumnsAndOrder() throws Exception {
        var r = server.executeSparkSql("SELECT id, test_upper(name) AS up FROM t ORDER BY id");
        assertEquals(List.of("id", "up"), r.columns());
        assertEquals(3, r.rows().size());
        assertEquals(List.of("1", "ALICE!"), stringify(r.rows().get(0)));
        assertEquals(List.of("2", "BOB!"), stringify(r.rows().get(1)));
        assertEquals(List.of("3", "CAROL!"), stringify(r.rows().get(2)));
    }

    @Test
    void udfWithWhereFilteringOnNonUdfColumn() throws Exception {
        var r = server.executeSparkSql("SELECT test_upper(name) FROM t WHERE id = 2");
        assertEquals(1, r.rows().size());
        assertEquals("BOB!", r.rows().get(0).get(0));
    }

    @Test
    void limitAppliesBeforeUdfComputation() throws Exception {
        var r = server.executeSparkSql("SELECT test_upper(name) FROM t LIMIT 2");
        assertEquals(2, r.rows().size());
        assertEquals("ALICE!", r.rows().get(0).get(0));
    }

    @Test
    void rejectsUdfInsideWherePredicate() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> server.executeSparkSql("SELECT id FROM t WHERE test_upper(name) = 'X'"));
        assertTrue(e.getMessage().contains("not supported"), () -> "unexpected: " + e.getMessage());
    }

    @Test
    void rejectsNestedUdfInProjectionExpression() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> server.executeSparkSql("SELECT test_upper(name) || 'x' AS mixed FROM t"));
        assertTrue(e.getMessage().contains("only supported as top-level projection items"),
                () -> "unexpected: " + e.getMessage());
    }

    private static List<String> stringify(List<Object> row) {
        return row.stream().map(v -> v == null ? null : v.toString()).toList();
    }
}
