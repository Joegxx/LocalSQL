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
 * 2026-10-16 design round #9: every non-default logical database owns its
 * DuckDB schema, so bare table names no longer collide across databases
 * (the default database keeps the legacy main layout, byte-compatible with
 * existing stores).
 */
class PerDatabaseSchemaTest {

    private ThriftServer server;
    private DuckDbExecutor executor;
    private CatalogService catalog;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        catalog = new CatalogService(new DuckDbCatalogStore(executor));
        server = new ThriftServer(catalog, executor);
    }

    @AfterEach
    void tearDown() throws Exception { executor.close(); }

    @Test
    void sameBareNameInDifferentDatabasesCoexist() throws Exception {
        server.executeSparkSql("CREATE DATABASE sales");
        server.executeSparkSql("CREATE DATABASE hr");
        // before per-database schemas this collided on the shared main schema
        server.executeSparkSql("CREATE TABLE sales.shared (id INT, dept STRING)");
        server.executeSparkSql("CREATE TABLE hr.shared (id INT, person STRING)");
        server.executeSparkSql("INSERT INTO sales.shared VALUES (1, 'eng')");
        server.executeSparkSql("INSERT INTO hr.shared VALUES (2, 'alice')");

        assertEquals(1, server.executeSparkSql("SELECT * FROM sales.shared").rows().size());
        assertEquals(1, server.executeSparkSql("SELECT * FROM hr.shared").rows().size());
        var join = server.executeSparkSql(
                "SELECT s.dept, h.person FROM sales.shared s JOIN hr.shared h ON s.id + 1 = h.id");
        assertEquals(List.of("eng", "alice"), join.rows().get(0));
    }

    @Test
    void defaultAndNamedDatabaseSameBareNameCoexist() throws Exception {
        server.executeSparkSql("CREATE DATABASE other");
        server.executeSparkSql("CREATE TABLE shared (id INT)");          // default -> main
        server.executeSparkSql("CREATE TABLE other.shared (n INT)");      // other schema
        server.executeSparkSql("INSERT INTO shared VALUES (1)");
        server.executeSparkSql("INSERT INTO other.shared VALUES (9)");
        assertEquals(1, server.executeSparkSql("SELECT * FROM shared").rows().size());
        assertEquals(9, ((Number) server.executeSparkSql("SELECT n FROM other.shared")
                .rows().get(0).get(0)).intValue());
    }

    @Test
    void newTableLivesInItsDatabaseSchema() throws Exception {
        server.executeSparkSql("CREATE DATABASE sales");
        server.executeSparkSql("CREATE TABLE sales.t (id INT)");
        var rows = executor.execute(
                "SELECT count(*) FROM duckdb_tables() WHERE schema_name = 'sales' AND table_name = 't'").rows();
        assertEquals(1, ((Number) rows.get(0).get(0)).intValue());
        assertEquals("sales", catalog.getTable("sales", "t").orElseThrow().physicalSchema());
    }

    @Test
    void dmlAndDdlAcrossDatabases() throws Exception {
        server.executeSparkSql("CREATE DATABASE d1");
        server.executeSparkSql("CREATE TABLE d1.src (id INT)");
        server.executeSparkSql("INSERT INTO d1.src VALUES (1), (2)");
        assertEquals(2, count("SELECT count(*) FROM d1.src"));
        // INSERT ... SELECT across databases
        server.executeSparkSql("CREATE TABLE d1.dst (id INT)");
        server.executeSparkSql("INSERT INTO d1.dst SELECT id FROM d1.src");
        assertEquals(2, count("SELECT count(*) FROM d1.dst"));
        // CTAS + view across databases
        server.executeSparkSql("CREATE TABLE d1.ct AS SELECT id AS v FROM d1.src");
        assertEquals(2, count("SELECT count(*) FROM d1.ct"));
        server.executeSparkSql("CREATE VIEW d1.v AS SELECT v FROM d1.ct");
        assertEquals(2, count("SELECT count(*) FROM d1.v"));
        // ALTER inside the schema
        server.executeSparkSql("ALTER TABLE d1.src ADD COLUMNS (name STRING)");
        assertEquals(2, catalog.getTable("d1", "src").orElseThrow().columns().size());
        // rename within the database keeps working
        server.executeSparkSql("ALTER TABLE d1.dst RENAME TO d1.dst2");
        assertEquals(2, count("SELECT count(*) FROM d1.dst2"));
    }

    private int count(String sql) throws Exception {
        return ((Number) server.executeSparkSql(sql).rows().get(0).get(0)).intValue();
    }

    @Test
    void reloadKeepsPhysicalSchema() throws Exception {
        server.executeSparkSql("CREATE DATABASE sales");
        server.executeSparkSql("CREATE TABLE sales.t (id INT)");
        server.executeSparkSql("INSERT INTO sales.t VALUES (5)");
        // reload the catalog from the same DuckDB file (in-memory sim: same executor)
        CatalogService reloaded = new CatalogService(new DuckDbCatalogStore(executor));
        assertEquals("sales", reloaded.getTable("sales", "t").orElseThrow().physicalSchema());
        ThriftServer reloadedServer = new ThriftServer(reloaded, executor);
        assertEquals(5, ((Number) reloadedServer.executeSparkSql("SELECT id FROM sales.t")
                .rows().get(0).get(0)).intValue());
    }

    @Test
    void dropDatabaseCascadeDropsSchema() throws Exception {
        server.executeSparkSql("CREATE DATABASE gone");
        server.executeSparkSql("CREATE TABLE gone.t (id INT)");
        server.executeSparkSql("DROP DATABASE gone CASCADE");
        var rows = executor.execute(
                "SELECT count(*) FROM duckdb_schemas() WHERE schema_name = 'gone'").rows();
        assertEquals(0, ((Number) rows.get(0).get(0)).intValue());
        assertThrows(Exception.class, () -> server.executeSparkSql("SELECT * FROM gone.t"));
    }

    @Test
    void dropDatabaseWithoutCascadeRefusesNonEmpty() throws Exception {
        server.executeSparkSql("CREATE DATABASE keepme");
        server.executeSparkSql("CREATE TABLE keepme.t (id INT)");
        assertThrows(Exception.class, () -> server.executeSparkSql("DROP DATABASE keepme"));
        assertTrue(catalog.getTable("keepme", "t").isPresent());
    }
}
