package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the 2026-10-02 review fixes:
 * <ul>
 *   <li>SqlPipeline INSERT/CTAS/CREATE VIEW must apply the ExpressionLowering
 *       result (datediff as the canary lowering)</li>
 *   <li>builtin explode LATERAL VIEW emits a single UNNEST with parenthesized
 *       source (no double UNNEST, no unparenthesized subquery)</li>
 *   <li>Java UDF + ORDER BY + LIMIT combination is supported</li>
 * </ul>
 *
 * 2026-10-16 review round:
 * <ul>
 *   <li>LATERAL VIEW combined with WHERE / GROUP BY / HAVING and chained
 *       lateral views stay in one FROM comma list (no derived-table wrapping)</li>
 *   <li>explode_outer: NULL/empty arrays yield one NULL row, non-empty acts
 *       like explode</li>
 *   <li>posexplode is supported via WITH ORDINALITY (value first, pos last);
 *       posexplode_outer and explode on maps are rejected explicitly</li>
 *   <li>substring_index / format_number reject non-literal count/scale loudly
 *       instead of silently lowering with the -1 sentinel</li>
 * </ul>
 */
class ReviewFixRegressionTest {

    private ThriftServer server;
    private DuckDbExecutor executor;
    private UdfSupport support;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogService catalog = new CatalogService(new DuckDbCatalogStore(executor));
        executor.registerTable("src",
                List.of(new DuckDbExecutor.ColDef("a", "DATE"),
                        new DuckDbExecutor.ColDef("b", "DATE")),
                List.of(List.of(java.sql.Date.valueOf("2024-01-10"),
                        java.sql.Date.valueOf("2024-01-01"))));
        catalog.registerTable("default", "src", "TABLE",
                List.of(new CatalogService.ColumnDef("a", new io.github.joegxx.localsql.ir.type.DateType(), true, null),
                        new CatalogService.ColumnDef("b", new io.github.joegxx.localsql.ir.type.DateType(), true, null)),
                null, "duckdb", null, null, null);
        executor.registerTable("t",
                List.of(new DuckDbExecutor.ColDef("id", "BIGINT"),
                        new DuckDbExecutor.ColDef("name", "VARCHAR")),
                List.of(List.of(3L, "carol"), List.of(1L, "alice"), List.of(2L, "bob")));
        catalog.registerTable("default", "t", "TABLE",
                List.of(new CatalogService.ColumnDef("id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("name", new io.github.joegxx.localsql.ir.type.StringType(), true, null)),
                null, "duckdb", null, null, null);

        server = new ThriftServer(catalog, executor);

        io.github.joegxx.localsql.udf.api.UdfRegistry registry =
                new io.github.joegxx.localsql.udf.api.UdfRegistry();
        java.nio.file.Path testClasses =
                java.nio.file.Path.of(System.getProperty("user.dir"), "target", "test-classes");
        registry.register(new io.github.joegxx.localsql.udf.api.UdfDefinition(
                "test.upper", "test_upper", TestUpperUdf.class.getName(),
                io.github.joegxx.localsql.udf.api.UdfKind.GENERIC,
                List.of(new io.github.joegxx.localsql.ir.type.StringType()),
                new io.github.joegxx.localsql.ir.type.StringType(),
                testClasses.toString(), "3.1", 0));
        support = new UdfSupport(registry, new io.github.joegxx.localsql.udf.runtime.HiveGenericUdfGateway(), 10_000);
        server = new ThriftServer(catalog, executor, "default", support);
    }

    @AfterEach
    void tearDown() throws Exception {
        support.close();
        executor.close();
    }

    // ------------------------------------------------------------------
    // Bug 1: rewrite result must survive on DML/DDL paths
    // ------------------------------------------------------------------

    @Test
    void insertSelectAppliesFunctionLowering() throws Exception {
        server.executeSparkSql("CREATE TABLE dst (d BIGINT)");
        server.executeSparkSql("INSERT INTO dst SELECT datediff(a, b) FROM src");
        assertEquals(9L, ((Number) server.executeSparkSql("SELECT d FROM dst")
                .rows().get(0).get(0)).longValue());
    }

    @Test
    void insertOverwriteAppliesFunctionLowering() throws Exception {
        server.executeSparkSql("CREATE TABLE dst (d BIGINT)");
        server.executeSparkSql("INSERT OVERWRITE dst SELECT 1");
        server.executeSparkSql("INSERT OVERWRITE dst SELECT datediff(a, b) FROM src");
        assertEquals(9L, ((Number) server.executeSparkSql("SELECT d FROM dst")
                .rows().get(0).get(0)).longValue());
    }

    @Test
    void ctasAppliesFunctionLowering() throws Exception {
        server.executeSparkSql("CREATE TABLE ct AS SELECT datediff(a, b) AS dd FROM src");
        assertEquals(9L, ((Number) server.executeSparkSql("SELECT dd FROM ct")
                .rows().get(0).get(0)).longValue());
    }

    @Test
    void createViewAppliesFunctionLowering() throws Exception {
        server.executeSparkSql("CREATE VIEW v1 AS SELECT datediff(a, b) AS dd FROM src");
        assertEquals(9L, ((Number) server.executeSparkSql("SELECT dd FROM v1")
                .rows().get(0).get(0)).longValue());
    }

    // ------------------------------------------------------------------
    // Bug 2/3: builtin explode LATERAL VIEW
    // ------------------------------------------------------------------

    @Test
    void lateralViewExplodeOverBareTable() throws Exception {
        var r = server.executeSparkSql(
                "SELECT id, n FROM t LATERAL VIEW explode(array(id)) e AS n ORDER BY id");
        assertEquals(List.of("id", "n"), r.columns());
        assertEquals(3, r.rows().size());
        assertEquals(1L, r.rows().get(0).get(0));
        assertEquals(1, ((Number) r.rows().get(0).get(1)).intValue());
        assertEquals(3L, r.rows().get(2).get(0));
        assertEquals(3, ((Number) r.rows().get(2).get(1)).intValue());
    }

    @Test
    void lateralViewExplodeOverAliasedSubquery() throws Exception {
        var r = server.executeSparkSql(
                "SELECT id, n FROM (SELECT id, name FROM t) src "
                        + "LATERAL VIEW explode(array(id)) e AS n ORDER BY id");
        assertEquals(3, r.rows().size());
    }

    @Test
    void lateralViewExplodeOverUnaliasedSubquery() throws Exception {
        var r = server.executeSparkSql(
                "SELECT id, n FROM (SELECT id, name FROM t) "
                        + "LATERAL VIEW explode(array(id)) e AS n ORDER BY id");
        assertEquals(3, r.rows().size());
    }

    @Test
    void lateralViewExplodeOverCte() throws Exception {
        // x=1 -> array(1), x=2 -> array(2): each row explodes to exactly one
        var r = server.executeSparkSql(
                "WITH src AS (SELECT 1 AS x UNION ALL SELECT 2) "
                        + "SELECT x, n FROM src LATERAL VIEW explode(array(x)) e AS n ORDER BY x");
        assertEquals(2, r.rows().size());
        assertEquals(2, ((Number) r.rows().get(1).get(1)).intValue());
    }

    @Test
    void posexplodeEmitsPositionColumn() throws Exception {
        // pos and col in user order; physical emission is value-first, pos last
        var r = server.executeSparkSql(
                "SELECT id, pos, n FROM t LATERAL VIEW posexplode(array(id, 100)) e AS pos, n ORDER BY id, n");
        // per source row: pos 1 -> n=id, pos 2 -> n=100
        assertEquals(6, r.rows().size());
        assertEquals(1L, r.rows().get(0).get(0));
        assertEquals(1, ((Number) r.rows().get(0).get(1)).intValue());
        assertEquals(1L, ((Number) r.rows().get(0).get(2)).intValue());
        assertEquals(1L, r.rows().get(1).get(0));
        assertEquals(2, ((Number) r.rows().get(1).get(1)).intValue());
        assertEquals(100, ((Number) r.rows().get(1).get(2)).intValue());
    }

    @Test
    void posexplodeWithoutNamesUsesDefaults() throws Exception {
        var r = server.executeSparkSql(
                "SELECT id, pos, col FROM t LATERAL VIEW posexplode(array(id)) e ORDER BY id");
        assertEquals(3, r.rows().size());
        assertEquals(1, ((Number) r.rows().get(0).get(1)).intValue());
        assertEquals(1L, ((Number) r.rows().get(0).get(2)).intValue());
    }

    @Test
    void posexplodeOuterIsRejectedExplicitly() throws Exception {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> server.executeSparkSql(
                        "SELECT id, pos, n FROM t LATERAL VIEW posexplode_outer(array(id)) e AS pos, n"));
        assertTrue(e.getMessage().contains("posexplode"), () -> "unexpected: " + e.getMessage());
    }

    @Test
    void explodeOnMapIsRejectedExplicitly() throws Exception {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> server.executeSparkSql(
                        "SELECT id, k, v FROM t LATERAL VIEW explode(map_from_arrays(array(id), array(id))) e AS k, v"));
        assertTrue(e.getMessage().contains("maps"), () -> "unexpected: " + e.getMessage());
    }

    @Test
    void unknownGeneratorIsRejectedExplicitly() throws Exception {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> server.executeSparkSql("SELECT id, n FROM t LATERAL VIEW some_udtf(name) e AS n"));
        assertTrue(e.getMessage().contains("some_udtf"), () -> "unexpected: " + e.getMessage());
    }

    // ------------------------------------------------------------------
    // Bug 4: Java UDF + ORDER BY + LIMIT
    // ------------------------------------------------------------------

    @Test
    void udfWithOrderByAndLimit() throws Exception {
        var r = server.executeSparkSql("SELECT test_upper(name) FROM t ORDER BY id LIMIT 2");
        assertEquals(2, r.rows().size());
        assertEquals("ALICE!", r.rows().get(0).get(0));
        assertEquals("BOB!", r.rows().get(1).get(0));
    }

    @Test
    void udfWithOrderByOnlyStillWorks() throws Exception {
        var r = server.executeSparkSql("SELECT test_upper(name) AS up FROM t ORDER BY id");
        assertEquals(3, r.rows().size());
        assertEquals("ALICE!", r.rows().get(0).get(0));
    }

    @Test
    void udfWithLimitOnlyStillWorks() throws Exception {
        var r = server.executeSparkSql("SELECT test_upper(name) FROM t LIMIT 2");
        assertEquals(2, r.rows().size());
    }

    // ------------------------------------------------------------------
    // 2026-10-16 review: LATERAL VIEW combined with WHERE / GROUP BY / chained
    // ------------------------------------------------------------------

    @Test
    void lateralViewExplodeWithWhere() throws Exception {
        // Filter sits between Project and Generate: wrapping the Generate in a
        // derived table would strand the t.id qualifier
        var r = server.executeSparkSql(
                "SELECT id, n FROM t LATERAL VIEW explode(array(id, 100)) e AS n WHERE n > 50 ORDER BY id");
        assertEquals(3, r.rows().size());
        for (var row : r.rows()) {
            assertEquals(100, ((Number) row.get(1)).intValue());
        }
    }

    @Test
    void lateralViewExplodeWithGroupByAndHaving() throws Exception {
        var r = server.executeSparkSql(
                "SELECT id, count(n) AS c FROM t LATERAL VIEW explode(array(id, 100)) e AS n "
                        + "GROUP BY id HAVING count(n) > 1 ORDER BY id");
        assertEquals(3, r.rows().size());
        for (var row : r.rows()) {
            assertEquals(2, ((Number) row.get(1)).intValue());
        }
    }

    @Test
    void chainedLateralViewsWithWhere() throws Exception {
        // Generate(Generate(t)): both views must stay in one FROM comma list
        var r = server.executeSparkSql(
                "SELECT id, m FROM t LATERAL VIEW explode(array(id, 100)) e1 AS n "
                        + "LATERAL VIEW explode(array(n, 200)) e2 AS m WHERE m > 150 ORDER BY id");
        assertEquals(6, r.rows().size());
        for (var row : r.rows()) {
            assertEquals(200, ((Number) row.get(1)).intValue());
        }
    }

    // ------------------------------------------------------------------
    // 2026-10-16 review: explode_outer
    // ------------------------------------------------------------------

    @Test
    void explodeOuterEmptyArrayYieldsNullRow() throws Exception {
        // Spark explode_outer([]) produces one row with NULL, plain explode
        // produces none
        var r = server.executeSparkSql(
                "SELECT id, n FROM t LATERAL VIEW explode_outer(array()) e AS n ORDER BY id");
        assertEquals(3, r.rows().size());
        for (var row : r.rows()) {
            assertNull(row.get(1));
        }
        var plain = server.executeSparkSql(
                "SELECT id, n FROM t LATERAL VIEW explode(array()) e AS n");
        assertEquals(0, plain.rows().size());
    }

    @Test
    void explodeOuterNonEmptyActsLikeExplode() throws Exception {
        var r = server.executeSparkSql(
                "SELECT id, n FROM t LATERAL VIEW explode_outer(array(id)) e AS n ORDER BY id");
        assertEquals(3, r.rows().size());
        assertEquals(1L, r.rows().get(0).get(0));
        assertEquals(1, ((Number) r.rows().get(0).get(1)).intValue());
    }

    // ------------------------------------------------------------------
    // 2026-10-16 review: literal guards must reject non-literals loudly
    // ------------------------------------------------------------------

    @Test
    void substringIndexLiteralCountStillWorks() throws Exception {
        var r = server.executeSparkSql("SELECT substring_index('a-b-c-d', '-', 2) AS v FROM t");
        assertEquals("a-b", r.rows().get(0).get(0));
    }

    @Test
    void substringIndexRejectsNonLiteralCount() throws Exception {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> server.executeSparkSql("SELECT substring_index('a-b-c-d', '-', id) FROM t"));
        assertTrue(e.getMessage().contains("literal"), () -> "unexpected: " + e.getMessage());
    }

    @Test
    void formatNumberLiteralScaleStillWorks() throws Exception {
        var r = server.executeSparkSql("SELECT format_number(1234.5678, 1) AS v FROM t");
        assertEquals("1,234.6", r.rows().get(0).get(0));
    }

    @Test
    void formatNumberRejectsNonLiteralScale() throws Exception {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> server.executeSparkSql("SELECT format_number(1234.5678, id) FROM t"));
        assertTrue(e.getMessage().contains("literal"), () -> "unexpected: " + e.getMessage());
    }

    @Test
    void decimalRejectsNonLiteralScale() throws Exception {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> server.executeSparkSql("SELECT decimal(1.23, 10, id) FROM t"));
        assertTrue(e.getMessage().contains("literal"), () -> "unexpected: " + e.getMessage());
    }
}
