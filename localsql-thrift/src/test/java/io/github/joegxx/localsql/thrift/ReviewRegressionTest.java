package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.statement.QueryStatement;
import io.github.joegxx.localsql.ir.statement.Statement;
import io.github.joegxx.localsql.parser.SparkSqlParser;
import io.github.joegxx.localsql.spark.SparkAstBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M0 repro tests for the 2026-09-10 review findings (development plan F1-F5).
 * Each test documents the CURRENT (buggy) behavior so the planned fixes can
 * flip the assertion when the defect is actually repaired. When a fix lands,
 * update the test to assert the corrected behavior instead of deleting it.
 */
class ReviewRegressionTest {

    private SparkAstBuilder astBuilder = new SparkAstBuilder();
    private SparkSqlParser parser = new SparkSqlParser();

    private Statement build(String sql) {
        return astBuilder.buildStatement(sql, s -> parser.parseStatement(s));
    }

    // ------------------------------------------------------------------
    // F4: query organization must be honored or rejected, never dropped
    // ------------------------------------------------------------------

    @Test
    void f4_nonLiteralLimitIsRejected() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> build("SELECT * FROM t LIMIT 1 + 1"));
        assertTrue(e.getMessage().contains("LIMIT"), () -> "unexpected: " + e.getMessage());
    }

    @Test
    void f4_sortByIsRejected() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> build("SELECT id FROM t SORT BY id DESC"));
        assertTrue(e.getMessage().contains("SORT BY"), () -> "unexpected: " + e.getMessage());
    }

    @Test
    void f4_clusterAndDistributeByAreRejected() {
        assertThrows(UnsupportedOperationException.class,
                () -> build("SELECT id FROM t CLUSTER BY id"));
        assertThrows(UnsupportedOperationException.class,
                () -> build("SELECT id FROM t DISTRIBUTE BY id"));
    }

    @Test
    void f4_limitAllIsRejected() {
        assertThrows(UnsupportedOperationException.class,
                () -> build("SELECT id FROM t LIMIT ALL"));
    }

    @Test
    void f4_namedWindowIsRejected() {
        assertThrows(UnsupportedOperationException.class,
                () -> build("SELECT count(*) OVER w FROM t WINDOW w AS (PARTITION BY id)"),
                "F4: named windows are rejected explicitly (expected to stay)");
    }

    @Test
    void f4_literalLimitStillWorks() throws Exception {
        Statement stmt = build("SELECT * FROM t LIMIT 5");
        assertInstanceOf(QueryStatement.class, stmt);
        Relation rel = ((QueryStatement) stmt).query();
        assertInstanceOf(io.github.joegxx.localsql.ir.relation.Limit.class, rel);
        assertEquals(5L, ((io.github.joegxx.localsql.ir.relation.Limit) rel).limit());
    }

    // ------------------------------------------------------------------
    // F5: GetTypeInfo must not crash on null type-info fields
    // ------------------------------------------------------------------

    @Test
    void f5_getTypeInfoMustNotThrow() throws Exception {
        try (ThriftQueryRunner runner = ThriftQueryRunner.start(findFreePort())) {
            var service = new LocalSqlThriftService(
                    runner.catalog(),
                    new SqlPipeline(runner.catalog(), runner.executor()),
                    "default");
            assertDoesNotThrow(() -> {
                var resp = service.GetTypeInfo(new org.apache.hive.service.rpc.thrift.TGetTypeInfoReq());
                assertEquals(org.apache.hive.service.rpc.thrift.TStatusCode.SUCCESS_STATUS,
                        resp.getStatus().getStatusCode(), resp.getStatus().toString());
            });
        }
    }

    private static int findFreePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ------------------------------------------------------------------
    // F1: wrong-database resolution and missing type binding
    // ------------------------------------------------------------------

    /**
     * F1 fixed: a query qualifying a table with a database that does not
     * exist must fail analysis before execution, even though the physical
     * table name alone exists in DuckDB.
     */
    @Test
    void f1_wrongDatabaseQualifierIsRejected() throws Exception {
        try (ThriftQueryRunner runner = ThriftQueryRunner.start(findFreePort())) {
            runner.catalog().createDatabase("other", null, null);
            runner.catalog().registerTable("other", "t", "TABLE",
                    List.of(new CatalogService.ColumnDef("id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null)),
                    null, "duckdb", null, null, null);
            runner.executor().run("CREATE TABLE t (id BIGINT)");
            runner.executor().run("INSERT INTO t VALUES (7)");
            var server = new ThriftServer(runner.catalog(), runner.executor());
            try {
                // WRONG qualifier: only default.t exists; database no_such_db
                // is not registered anywhere. Strict analysis must reject it
                // instead of silently reading the bare physical table.
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> server.executeSparkSql("SELECT id FROM no_such_db.t"));
                assertTrue(e.getMessage().contains("no_such_db"),
                        "F1: error must name the offending database, got: " + e.getMessage());
                // and the correctly qualified reference still works
                assertEquals(1, server.executeSparkSql("SELECT id FROM other.t").rows().size());
            } finally {
                server.stop();
            }
        }
    }

    // ------------------------------------------------------------------
    // F2: failed DDL must leave no ghost state in the in-memory catalog
    // ------------------------------------------------------------------

    /** Failed CTAS must not leave a ghost table in the in-memory catalog. */
    @Test
    void f2_failedCtasLeavesNoGhostTableInMemory() throws Exception {
        try (ThriftQueryRunner runner = ThriftQueryRunner.start(findFreePort())) {
            var catalog = runner.catalog();
            var server = new ThriftServer(catalog, runner.executor());
            try {
                assertThrows(Exception.class,
                        () -> server.executeSparkSql("CREATE TABLE ghost (id INT) AS SELECT 'not_int'"));
                boolean ghostVisible = catalog.catalog().listTables("default").stream()
                        .anyMatch(t -> t.tableName().equalsIgnoreCase("ghost"));
                assertFalse(ghostVisible,
                        "F2: failed CTAS must not leave a ghost table in the in-memory catalog");
                // subsequent statements still see a consistent catalog
                assertEquals(0, catalog.catalog().listTables("default").size());
            } finally {
                server.stop();
            }
        }
    }

    /** A failed view replacement must keep the old view definition everywhere. */
    @Test
    void f2_failedViewReplaceKeepsOldDefinition() throws Exception {
        try (ThriftQueryRunner runner = ThriftQueryRunner.start(findFreePort())) {
            var catalog = runner.catalog();
            var server = new ThriftServer(catalog, runner.executor());
            try {
                runner.executor().run("CREATE TABLE base (id BIGINT, name VARCHAR)");
                runner.catalog().registerTable("default", "base", "TABLE",
                        List.of(new CatalogService.ColumnDef("id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null),
                                new CatalogService.ColumnDef("name", new io.github.joegxx.localsql.ir.type.StringType(), true, null)),
                        null, "duckdb", null, null, null);
                server.executeSparkSql("CREATE VIEW v AS SELECT id FROM base");
                // replacing with a query that fails at execution time
                assertThrows(Exception.class,
                        () -> server.executeSparkSql("CREATE OR REPLACE VIEW v AS SELECT missing_col FROM base"));
                // old definition survives in memory...
                var view = catalog.getTable("default", "v").orElseThrow();
                assertEquals(1, view.columns().size());
                assertEquals("id", view.columns().get(0).name());
                // ...physically...
                var rows = server.executeSparkSql("SELECT id FROM v");
                assertEquals(0, rows.rows().size());
                // ...and the column count is still the old projection
                assertEquals(1, rows.columns().size());
            } finally {
                server.stop();
            }
        }
    }

    // ------------------------------------------------------------------
    // F3: registering an unused UDF must not break ordinary SELECT *
    // ------------------------------------------------------------------

    @Test
    void f3_registeredUdfMustNotRejectPlainSelectStar() throws Exception {
        try (ThriftQueryRunner runner = ThriftQueryRunner.start(findFreePort())) {
            var registry = new io.github.joegxx.localsql.udf.api.UdfRegistry();
            Path testClasses = Path.of("localsql-thrift", "target", "test-classes");
            registry.register(new io.github.joegxx.localsql.udf.api.UdfDefinition(
                    "test.upper", "test_upper", TestUpperUdf.class.getName(),
                    io.github.joegxx.localsql.udf.api.UdfKind.GENERIC,
                    List.of(new io.github.joegxx.localsql.ir.type.StringType()),
                    new io.github.joegxx.localsql.ir.type.StringType(),
                    testClasses.toAbsolutePath().toString(), "3.1", 0));
            var support = new UdfSupport(registry,
                    new io.github.joegxx.localsql.udf.runtime.HiveGenericUdfGateway(), 10_000);
            var server = new ThriftServer(runner.catalog(), runner.executor(), "default", support);
            try {
                runner.executor().run("CREATE TABLE plain (id BIGINT, name VARCHAR)");
                runner.executor().run("INSERT INTO plain VALUES (1, 'a')");
                runner.catalog().registerTable("default", "plain", "TABLE",
                        List.of(new CatalogService.ColumnDef("id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null),
                                new CatalogService.ColumnDef("name", new io.github.joegxx.localsql.ir.type.StringType(), true, null)),
                        null, "duckdb", null, null, null);
                var r = server.executeSparkSql("SELECT * FROM plain");
                assertEquals(1, r.rows().size(),
                        "F3: a registered but unused UDF must not reject an ordinary SELECT *");
            } finally {
                support.close();
                server.stop();
            }
        }
    }

    // ------------------------------------------------------------------
    // F6: database-level metadata persistence
    // ------------------------------------------------------------------

    @Test
    void f6_emptyDatabaseSurvivesReload() throws Exception {
        Path dir = Files.createTempDirectory("localsql-f6-");
        dir.toFile().deleteOnExit();
        Path file = dir.resolve("sandbox.duckdb");
        try (DuckDbExecutor exec = new DuckDbExecutor(file.toString())) {
            CatalogStore store = new DuckDbCatalogStore(exec);
            CatalogService catalog = new CatalogService(store);
            catalog.createDatabase("emptydb", "note", "/tmp/emptydb",
                    java.util.Map.of("owner", "test", "quote'key", "a=b\nc"));
        }
        try (DuckDbExecutor exec = new DuckDbExecutor(file.toString())) {
            CatalogStore store = new DuckDbCatalogStore(exec);
            CatalogService catalog = new CatalogService(store);
            var db = catalog.catalog().getDatabase("emptydb").orElseThrow();
            assertEquals("note", db.description());
            assertEquals("/tmp/emptydb", db.location());
            assertEquals("test", db.properties().get("owner"));
            assertEquals("a=b\nc", db.properties().get("quote'key"));
        }
    }

    /** An old 4-table store (no catalog_database) migrates on first open. */
    @Test
    void f6_legacyStoreMigratesDatabasesFromTables() throws Exception {
        Path dir = Files.createTempDirectory("localsql-f6-legacy-");
        dir.toFile().deleteOnExit();
        Path file = dir.resolve("legacy.duckdb");
        try (DuckDbExecutor exec = new DuckDbExecutor(file.toString())) {
            CatalogStore store = new DuckDbCatalogStore(exec);
            CatalogService catalog = new CatalogService(store);
            catalog.createDatabase("legacydb", null, null);
            catalog.registerTable("legacydb", "t", "TABLE",
                    List.of(new CatalogService.ColumnDef("id", io.github.joegxx.localsql.ir.type.IntegralType.INT, true, null)),
                    null, "duckdb", null, null, null);
            // simulate a pre-database-metadata store: wipe catalog_database
            exec.run("DELETE FROM catalog_database");
            exec.run("DELETE FROM catalog_db_property");
            exec.run("DELETE FROM catalog_schema_version");
        }
        try (DuckDbExecutor exec = new DuckDbExecutor(file.toString())) {
            CatalogStore store = new DuckDbCatalogStore(exec);
            CatalogService catalog = new CatalogService(store);
            assertTrue(catalog.catalog().getDatabase("legacydb").isPresent(),
                    "legacy store must re-derive existing databases from catalog_table");
            assertTrue(catalog.catalog().getDatabase("default").isPresent());
            assertTrue(catalog.getTable("legacydb", "t").isPresent());
        }
    }

    /** Database properties, ALTER and DROP behave consistently across reloads. */
    @Test
    void f6_databaseAlterDropSurviveReload() throws Exception {
        Path dir = Files.createTempDirectory("localsql-f6-alter-");
        dir.toFile().deleteOnExit();
        Path file = dir.resolve("alter.duckdb");
        try (DuckDbExecutor exec = new DuckDbExecutor(file.toString())) {
            CatalogStore store = new DuckDbCatalogStore(exec);
            CatalogService catalog = new CatalogService(store);
            catalog.createDatabase("db1", "first", null);
            catalog.alterDatabase("db1", java.util.Map.of("k", "v"), "/loc1");
        }
        try (DuckDbExecutor exec = new DuckDbExecutor(file.toString())) {
            CatalogStore store = new DuckDbCatalogStore(exec);
            CatalogService catalog = new CatalogService(store);
            var db = catalog.catalog().getDatabase("db1").orElseThrow();
            assertEquals("v", db.properties().get("k"));
            assertEquals("/loc1", db.location());
            assertEquals("first", db.description());
            catalog.dropDatabase("db1", true);
            assertTrue(catalog.catalog().getDatabase("db1").isEmpty());
        }
        try (DuckDbExecutor exec = new DuckDbExecutor(file.toString())) {
            CatalogStore store = new DuckDbCatalogStore(exec);
            CatalogService catalog = new CatalogService(store);
            assertTrue(catalog.catalog().getDatabase("db1").isEmpty(),
                    "dropped database must stay dropped across reload");
            assertTrue(catalog.catalog().getDatabase("default").isPresent());
        }
    }
}
