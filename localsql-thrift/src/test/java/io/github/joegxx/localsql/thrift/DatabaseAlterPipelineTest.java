package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseAlterPipelineTest {

    private ThriftServer server;
    private final int port = 10097;
    private DuckDbExecutor executor;
    private CatalogService catalog;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        catalog = new CatalogService(store);
        server = new ThriftServer(catalog, executor);
        server.start(port);
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
    void createAndDropDatabase() throws Exception {
        exec("CREATE DATABASE db1");
        assertTrue(exec("SHOW DATABASES").rows().stream().anyMatch(r -> r.get(0).equals("db1")));
        exec("CREATE DATABASE IF NOT EXISTS db1");
        assertThrows(Exception.class, () -> exec("CREATE DATABASE db1"));
        exec("CREATE TABLE db1.t (id INT)");
        assertThrows(Exception.class, () -> exec("DROP DATABASE db1"));
        assertThrows(Exception.class, () -> exec("DROP DATABASE db1 RESTRICT"));
        exec("DROP DATABASE db1 CASCADE");
        assertFalse(exec("SHOW DATABASES").rows().stream().anyMatch(r -> r.get(0).equals("db1")));
        exec("DROP DATABASE IF EXISTS db1");
        assertThrows(Exception.class, () -> exec("DROP DATABASE db1"));
        assertThrows(Exception.class, () -> exec("DROP DATABASE default"));
    }

    @Test
    void alterDatabase() throws Exception {
        exec("CREATE DATABASE db1 COMMENT 'test' LOCATION '/tmp/db1'");
        exec("ALTER DATABASE db1 SET DBPROPERTIES ('k'='v')");
        exec("ALTER DATABASE db1 SET LOCATION '/tmp/db2'");
        exec("CREATE TABLE db1.t (id INT)");
        exec("DROP DATABASE db1 CASCADE");
    }

    @Test
    void renameTableAndView() throws Exception {
        exec("CREATE TABLE t (id INT, name STRING)");
        exec("INSERT INTO t VALUES (1, 'a')");
        exec("ALTER TABLE t RENAME TO t2");
        assertThrows(Exception.class, () -> exec("SELECT * FROM t"));
        assertEquals(1, exec("SELECT * FROM t2").rows().size());
        exec("CREATE VIEW v AS SELECT * FROM t2");
        exec("ALTER VIEW v RENAME TO v2");
        assertEquals(1, exec("SELECT * FROM v2").rows().size());
    }

    @Test
    void addDropColumns() throws Exception {
        exec("CREATE TABLE t (id INT)");
        exec("ALTER TABLE t ADD COLUMNS (name STRING COMMENT 'n', age INT)");
        exec("INSERT INTO t VALUES (1, 'a', 30)");
        assertEquals(3, exec("DESCRIBE t").rows().size());
        exec("ALTER TABLE t DROP COLUMNS (age)");
        assertEquals(2, exec("DESCRIBE t").rows().size());
        exec("ALTER TABLE t ADD COLUMN extra DOUBLE");
        assertEquals(3, exec("DESCRIBE t").rows().size());
        exec("ALTER TABLE t DROP COLUMN extra");
        assertEquals(2, exec("DESCRIBE t").rows().size());
    }

    @Test
    void renameColumn() throws Exception {
        exec("CREATE TABLE t (id INT, name STRING)");
        exec("ALTER TABLE t RENAME COLUMN name TO full_name");
        assertTrue(exec("DESCRIBE t").rows().stream().anyMatch(r -> r.get(0).equals("full_name")));
        assertFalse(exec("DESCRIBE t").rows().stream().anyMatch(r -> r.get(0).equals("name")));
        exec("INSERT INTO t VALUES (1, 'a')");
        assertEquals("a", exec("SELECT full_name FROM t").rows().get(0).get(0));
    }

    @Test
    void alterColumnTypeAndNotNull() throws Exception {
        exec("CREATE TABLE t (id INT, name STRING)");
        exec("ALTER TABLE t ALTER COLUMN name TYPE VARCHAR");
        exec("INSERT INTO t VALUES (1, 'a')");
        exec("ALTER TABLE t ALTER COLUMN name SET NOT NULL");
        exec("ALTER TABLE t ALTER COLUMN name DROP NOT NULL");
        exec("ALTER TABLE t ALTER COLUMN id TYPE BIGINT");
        assertEquals("bigint", exec("DESCRIBE t").rows().stream().filter(r -> r.get(0).equals("id")).findFirst().get().get(1).toString().toLowerCase());
    }

    @Test
    void hiveChangeColumn() throws Exception {
        exec("CREATE TABLE t (id INT, name STRING)");
        exec("ALTER TABLE t CHANGE COLUMN name new_name STRING");
        assertTrue(exec("DESCRIBE t").rows().stream().anyMatch(r -> r.get(0).equals("new_name")));
        exec("ALTER TABLE t CHANGE COLUMN id id BIGINT");
        assertEquals("bigint", exec("DESCRIBE t").rows().stream().filter(r -> r.get(0).equals("id")).findFirst().get().get(1).toString().toLowerCase());
    }

    @Test
    void replaceColumns() throws Exception {
        exec("CREATE TABLE t (id INT, name STRING, age INT)");
        exec("INSERT INTO t VALUES (1, 'a', 30)");
        exec("ALTER TABLE t REPLACE COLUMNS (id BIGINT, full_name STRING)");
        var desc = exec("DESCRIBE t");
        assertEquals(2, desc.rows().size());
        assertTrue(desc.rows().stream().anyMatch(r -> r.get(0).equals("full_name")));
    }

    @Test
    void setUnsetTblProperties() throws Exception {
        exec("CREATE TABLE t (id INT)");
        exec("ALTER TABLE t SET TBLPROPERTIES ('k'='v')");
        exec("ALTER TABLE t UNSET TBLPROPERTIES ('k')");
        exec("ALTER TABLE t UNSET TBLPROPERTIES IF EXISTS ('missing')");
        assertThrows(Exception.class, () -> exec("ALTER TABLE t UNSET TBLPROPERTIES ('missing')"));
    }
}
