package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdminShowCreateTest {

    private ThriftServer server;
    private DuckDbExecutor executor;
    private CatalogService catalog;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        catalog = new CatalogService(store);
        server = new ThriftServer(catalog, executor);
        server.start(10099);
        Thread.sleep(800);
    }

    @AfterEach
    void tearDown() {
        server.stop();
        try { executor.close(); } catch (Exception ignored) {}
    }

    private DuckDbExecutor.QueryResult exec(String sql) throws Exception {
        return server.executeSparkSql(sql);
    }

    @Test
    void showCreateDatabase() throws Exception {
        exec("CREATE DATABASE mydb COMMENT 'my comment' WITH DBPROPERTIES ('k'='v')");
        var r = exec("SHOW CREATE DATABASE mydb");
        assertEquals(1, r.rows().size());
        assertEquals("mydb", r.rows().get(0).get(0));
        String ddl = String.valueOf(r.rows().get(0).get(1));
        assertTrue(ddl.contains("CREATE DATABASE `mydb`"));
        assertTrue(ddl.contains("COMMENT 'my comment'"));
        assertTrue(ddl.contains("WITH DBPROPERTIES ('k'='v')"));
        // SHOW CREATE SCHEMA is an alias
        var r2 = exec("SHOW CREATE SCHEMA mydb");
        assertEquals(ddl, r2.rows().get(0).get(1));
        assertThrows(Exception.class, () -> exec("SHOW CREATE DATABASE nodb"));
        exec("DROP DATABASE mydb");
    }

    @Test
    void showCreateTable() throws Exception {
        exec("CREATE DATABASE mydb");
        exec("CREATE TABLE mydb.t (id INT NOT NULL, name STRING COMMENT 'the name')");
        var r = exec("SHOW CREATE TABLE mydb.t");
        String ddl = String.valueOf(r.rows().get(0).get(1));
        assertTrue(ddl.contains("CREATE TABLE `mydb`.`t`"));
        assertTrue(ddl.contains("`id` INT NOT NULL"));
        assertTrue(ddl.contains("`name` STRING COMMENT 'the name'"));
        assertEquals("t", r.rows().get(0).get(0));
        // qualified against current session db works without prefix
        exec("USE mydb");
        var r2 = exec("SHOW CREATE TABLE t");
        assertEquals(ddl, r2.rows().get(0).get(1));
        assertThrows(Exception.class, () -> exec("SHOW CREATE TABLE mydb.nope"));
        exec("DROP DATABASE mydb CASCADE");
    }

    @Test
    void showCreateView() throws Exception {
        exec("CREATE DATABASE vdb");
        exec("CREATE TABLE vdb.t (id INT)");
        exec("CREATE VIEW vdb.v AS SELECT * FROM vdb.t");
        var r = exec("SHOW CREATE TABLE vdb.v");
        String ddl = String.valueOf(r.rows().get(0).get(1));
        assertTrue(ddl.toUpperCase().contains("CREATE VIEW"));
        exec("DROP VIEW vdb.v");
        exec("DROP DATABASE vdb CASCADE");
    }

    @Test
    void partitionedTableMetadata() throws Exception {
        exec("CREATE DATABASE pdb");
        exec("CREATE TABLE pdb.pt (id INT, name STRING) PARTITIONED BY (`d` string COMMENT 'date part')");
        // partition column is part of the schema (last, Spark style)
        var desc = exec("DESCRIBE pdb.pt");
        assertEquals("d", desc.rows().get(desc.rows().size() - 1).get(0));
        // SHOW CREATE TABLE emits a PARTITIONED BY clause
        var r = exec("SHOW CREATE TABLE pdb.pt");
        String ddl = String.valueOf(r.rows().get(0).get(1));
        assertTrue(ddl.contains("PARTITIONED BY (`d` STRING COMMENT 'date part')"), ddl);
        // DESCRIBE FORMATTED carries the partition block
        var f = exec("DESCRIBE FORMATTED pdb.pt");
        boolean inPartitionSection = false;
        boolean sawPartitionHeader = false;
        boolean sawPartitionCol = false;
        for (var row : f.rows()) {
            if ("# Partition Information".equals(row.get(0))) { sawPartitionHeader = true; inPartitionSection = true; continue; }
            if (inPartitionSection && "d".equals(row.get(0))) sawPartitionCol = true;
        }
        assertTrue(sawPartitionHeader);
        assertTrue(sawPartitionCol);
        // LIKE keeps the partition metadata
        exec("CREATE TABLE pdb.pt2 LIKE pdb.pt");
        var r2 = exec("SHOW CREATE TABLE pdb.pt2");
        assertTrue(String.valueOf(r2.rows().get(0).get(1)).contains("PARTITIONED BY"));
        exec("DROP DATABASE pdb CASCADE");
    }

    @Test
    void showIndexesEmpty() throws Exception {
        exec("CREATE TABLE idxt (id INT)");
        assertEquals(0, exec("SHOW INDEXES ON idxt").rows().size());
        assertEquals(0, exec("SHOW INDEXES").rows().size());
        assertEquals(0, exec("SHOW EXTENDED INDEXES ON idxt").rows().size());
        assertEquals(4, exec("SHOW INDEXES").columns().size());
        exec("DROP TABLE idxt");
    }
}
