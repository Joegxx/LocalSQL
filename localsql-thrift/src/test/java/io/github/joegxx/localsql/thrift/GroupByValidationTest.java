package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-16 design round #4: Aggregate result expressions must be
 * functionally determined by the GROUP BY keys (Spark semantics) —
 * non-aggregate items that do not appear in the grouping list are
 * rejected before execution with a clear message, and GROUP BY ordinals
 * are translated to the referenced select item at analysis time.
 */
class GroupByValidationTest {

    private ThriftServer server;
    private DuckDbExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogService catalog = new CatalogService(new DuckDbCatalogStore(executor));
        executor.registerTable("emp",
                List.of(new DuckDbExecutor.ColDef("dept", "VARCHAR"),
                        new DuckDbExecutor.ColDef("name", "VARCHAR"),
                        new DuckDbExecutor.ColDef("sal", "BIGINT")),
                List.of(List.of("eng", "alice", 100L), List.of("eng", "bob", 200L),
                        List.of("ops", "carol", 150L)));
        catalog.registerTable("default", "emp", "TABLE",
                List.of(new CatalogService.ColumnDef("dept", new io.github.joegxx.localsql.ir.type.StringType(), true, null),
                        new CatalogService.ColumnDef("name", new io.github.joegxx.localsql.ir.type.StringType(), true, null),
                        new CatalogService.ColumnDef("sal", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null)),
                null, "duckdb", null, null, null);
        server = new ThriftServer(catalog, executor);
    }

    @AfterEach
    void tearDown() throws Exception { executor.close(); }

    @Test
    void groupByKeyIsAllowed() throws Exception {
        var r = server.executeSparkSql("SELECT dept, count(*) AS c FROM emp GROUP BY dept ORDER BY dept");
        assertEquals(2, r.rows().size());
        assertEquals(2, ((Number) r.rows().get(0).get(1)).intValue());
    }

    @Test
    void scalarExpressionMatchingGroupingIsAllowed() throws Exception {
        var r = server.executeSparkSql(
                "SELECT upper(dept) AS d, count(*) AS c FROM emp GROUP BY upper(dept) ORDER BY d");
        assertEquals(2, r.rows().size());
        assertEquals("ENG", r.rows().get(0).get(0));
    }

    @Test
    void ungroupedColumnIsRejectedBeforeExecution() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> server.executeSparkSql("SELECT name, count(*) FROM emp GROUP BY dept"));
        assertTrue(e.getMessage().contains("must appear in the GROUP BY clause"),
                () -> "unexpected: " + e.getMessage());
    }

    @Test
    void ungroupedColumnWithoutAnyAggregateIsRejected() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> server.executeSparkSql("SELECT name FROM emp GROUP BY dept"));
        assertTrue(e.getMessage().contains("must appear in the GROUP BY clause"),
                () -> "unexpected: " + e.getMessage());
    }

    @Test
    void groupByOrdinalRefersToSelectItem() throws Exception {
        // GROUP BY 1 = GROUP BY dept: same result, translated at analysis time
        var r = server.executeSparkSql("SELECT dept, count(*) AS c FROM emp GROUP BY 1 ORDER BY dept");
        assertEquals(2, r.rows().size());
        assertEquals("ops", r.rows().get(1).get(0));
    }

    @Test
    void groupByOrdinalOnBareColumn() throws Exception {
        var r = server.executeSparkSql("SELECT name FROM emp GROUP BY 1 ORDER BY name");
        assertEquals(3, r.rows().size());
        assertEquals("alice", r.rows().get(0).get(0));
    }

    @Test
    void literalResultIsAlwaysAllowed() throws Exception {
        var r = server.executeSparkSql("SELECT 1 AS one, count(*) AS c FROM emp GROUP BY dept");
        assertEquals(2, r.rows().size());
    }

    @Test
    void havingWithoutGroupByAllowsAggregatesOnly() throws Exception {
        var r = server.executeSparkSql("SELECT count(*) AS c FROM emp HAVING count(*) > 1");
        assertEquals(1, r.rows().size());
        assertThrows(IllegalArgumentException.class,
                () -> server.executeSparkSql("SELECT name FROM emp HAVING count(*) > 1"));
    }

    @Test
    void groupingSetsUnionCoversResultColumns() throws Exception {
        var r = server.executeSparkSql(
                "SELECT dept, name, count(*) AS c FROM emp GROUP BY GROUPING SETS ((dept), (name)) ORDER BY dept, name");
        // dept rows (name NULL) + name rows (dept NULL)
        assertTrue(r.rows().size() >= 5, () -> "unexpected rows: " + r.rows());
    }

    @Test
    void expressionOverGroupingAttributeIsAllowed() throws Exception {
        // tpcds q54 shape: the result is derived from a bare grouping attribute
        var r = server.executeSparkSql(
                "SELECT sal / 10 AS bucket, count(*) AS c FROM emp GROUP BY sal ORDER BY bucket");
        assertEquals(3, r.rows().size());
        assertEquals(10L, ((Number) r.rows().get(0).get(0)).longValue());
    }

    @Test
    void scalarCallOverGroupingAttributeIsAllowed() throws Exception {
        // Spark allows functions over bare grouping attributes
        var r = server.executeSparkSql("SELECT upper(dept) AS d FROM emp GROUP BY dept ORDER BY d");
        assertEquals(2, r.rows().size());
        assertEquals("ENG", r.rows().get(0).get(0));
    }

    @Test
    void bareAttributeNotCoveredByExpressionGrouping() throws Exception {
        // GROUP BY upper(dept) does not make a bare dept reference legal
        assertThrows(IllegalArgumentException.class,
                () -> server.executeSparkSql("SELECT dept FROM emp GROUP BY upper(dept)"));
    }
}
