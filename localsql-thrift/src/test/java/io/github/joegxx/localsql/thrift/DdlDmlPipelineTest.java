package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import org.apache.hive.service.rpc.thrift.*;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DdlDmlPipelineTest {

    private ThriftServer server;
    private int port = 10098;
    private CatalogService catalog;
    private DuckDbExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        catalog = new CatalogService(store);
        registerUsers(catalog, executor);
        registerOrders(catalog, executor);
        server = new ThriftServer(catalog, executor);
        server.start(port);
        Thread.sleep(800);
    }

    private void registerUsers(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        executor.registerTable("users",
                List.of(new DuckDbExecutor.ColDef("id", "BIGINT"),
                        new DuckDbExecutor.ColDef("name", "VARCHAR"),
                        new DuckDbExecutor.ColDef("age", "INT")),
                List.<List<Object>>of(List.of(1L, "alice", 30),
                        List.of(2L, "bob", 25),
                        List.of(3L, "carol", 40)));
        var table = catalog.registerTable("default", "users", "TABLE",
                List.of(new CatalogService.ColumnDef("id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, false, null),
                        new CatalogService.ColumnDef("name", new io.github.joegxx.localsql.ir.type.StringType(), true, null),
                        new CatalogService.ColumnDef("age", io.github.joegxx.localsql.ir.type.IntegralType.INT, true, null)),
                null, "duckdb", null, java.util.Map.of(), null);
        catalog.store().saveRuntimeInfo(table.tableId(), "users",
                "CREATE TABLE users (id BIGINT, name VARCHAR, age INT)");
    }

    private void registerOrders(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        executor.registerTable("orders",
                List.of(new DuckDbExecutor.ColDef("id", "BIGINT"),
                        new DuckDbExecutor.ColDef("user_id", "BIGINT"),
                        new DuckDbExecutor.ColDef("amount", "DOUBLE")),
                List.<List<Object>>of(List.of(100L, 1L, 99.5),
                        List.of(101L, 1L, 20.0),
                        List.of(102L, 2L, 150.0)));
        var table = catalog.registerTable("default", "orders", "TABLE",
                List.of(new CatalogService.ColumnDef("id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, false, null),
                        new CatalogService.ColumnDef("user_id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("amount", io.github.joegxx.localsql.ir.type.FractionalType.DOUBLE, true, null)),
                null, "duckdb", null, java.util.Map.of(), null);
        catalog.store().saveRuntimeInfo(table.tableId(), "orders",
                "CREATE TABLE orders (id BIGINT, user_id BIGINT, amount DOUBLE)");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
        executor.close();
    }

    private DuckDbExecutor.QueryResult exec(String sql) throws Exception {
        return server.executeSparkSql(sql);
    }

    @Test
    void createTableThenInsertValuesAndSelect() throws Exception {
        exec("CREATE TABLE t (id BIGINT, name STRING, score DOUBLE)");
        exec("INSERT INTO t VALUES (1, 'alice', 9.5), (2, 'bob', 8.0)");
        var r = exec("SELECT name FROM t ORDER BY id");
        assertEquals(List.of("name"), r.columns());
        assertEquals(List.of("alice", "bob"),
                r.rows().stream().map(row -> String.valueOf(row.get(0))).toList());
    }

    @Test
    void insertIntoSelect() throws Exception {
        exec("CREATE TABLE t (id BIGINT, name STRING, score DOUBLE)");
        exec("INSERT INTO t SELECT o.id, u.name, o.amount FROM orders o JOIN users u ON u.id = o.user_id");
        var r = exec("SELECT count(*) AS c FROM t");
        assertEquals(3L, ((Number) r.rows().get(0).get(0)).longValue());
    }

    @Test
    void insertWithColumnListLeavesOthersNull() throws Exception {
        exec("CREATE TABLE t (id BIGINT, name STRING, score DOUBLE)");
        exec("INSERT INTO t (name, id) VALUES ('x', 42)");
        var r = exec("SELECT id, name, score FROM t");
        assertEquals(42L, ((Number) r.rows().get(0).get(0)).longValue());
        assertEquals("x", r.rows().get(0).get(1));
        assertNull(r.rows().get(0).get(2));
    }

    @Test
    void insertOverwriteReplacesAllData() throws Exception {
        exec("CREATE TABLE t (id BIGINT, name STRING)");
        exec("INSERT INTO t VALUES (1, 'a'), (2, 'b')");
        exec("INSERT OVERWRITE TABLE t SELECT 3, 'c'");
        var r = exec("SELECT count(*) AS c FROM t");
        assertEquals(1L, ((Number) r.rows().get(0).get(0)).longValue());
        assertEquals("c", exec("SELECT name FROM t").rows().get(0).get(0));
    }

    @Test
    void insertOverwriteWithZeroRowsClearsTable() throws Exception {
        exec("CREATE TABLE t (id BIGINT)");
        exec("INSERT INTO t VALUES (1)");
        exec("INSERT OVERWRITE TABLE t SELECT id FROM users WHERE age > 1000");
        var r = exec("SELECT count(*) AS c FROM t");
        assertEquals(0L, ((Number) r.rows().get(0).get(0)).longValue());
    }

    @Test
    void insertIntoMissingTableFails() throws Exception {
        try {
            exec("INSERT INTO missing_table VALUES (1)");
            fail("should have thrown");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("does not exist"));
        }
    }

    @Test
    void insertUnknownColumnFails() throws Exception {
        exec("CREATE TABLE t (id BIGINT)");
        try {
            exec("INSERT INTO t (nope) VALUES (1)");
            fail("should have thrown");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("column"));
        }
    }

    @Test
    void createTableTypesAndDescribe() throws Exception {
        exec("CREATE TABLE t (id BIGINT NOT NULL, name STRING, score DOUBLE)");
        var r = exec("DESCRIBE t");
        List<String> colNames = r.rows().stream().map(row -> String.valueOf(row.get(0))).toList();
        assertEquals(List.of("id", "name", "score"), colNames);
        List<String> colTypes = r.rows().stream().map(row -> String.valueOf(row.get(1))).toList();
        assertEquals("bigint", colTypes.get(0).toLowerCase(), colTypes.toString());
        assertEquals("string", colTypes.get(1).toLowerCase(), colTypes.toString());
        assertEquals("double", colTypes.get(2).toLowerCase(), colTypes.toString());
    }

    @Test
    void createDuplicateTableFails() throws Exception {
        exec("CREATE TABLE t (id BIGINT)");
        try {
            exec("CREATE TABLE t (x STRING)");
            fail("should have thrown");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("already exists"));
        }
    }

    @Test
    void createTableIfNotExistsIsNoop() throws Exception {
        exec("CREATE TABLE t (id BIGINT)");
        exec("INSERT INTO t VALUES (7)");
        exec("CREATE TABLE IF NOT EXISTS t (x STRING)");
        var r = exec("SELECT id FROM t");
        assertEquals(7L, ((Number) r.rows().get(0).get(0)).longValue());
    }

    @Test
    void createTableAsSelect() throws Exception {
        exec("CREATE TABLE t2 AS SELECT u.id, u.name FROM users u WHERE age > 25");
        var r = exec("SELECT count(*) AS c FROM t2");
        assertEquals(2L, ((Number) r.rows().get(0).get(0)).longValue());
        var desc = exec("DESCRIBE t2");
        assertEquals(2, desc.rows().size());
    }

    @Test
    void createTableWithColumnsAsQuery() throws Exception {
        exec("CREATE TABLE t (id BIGINT, nm STRING) AS SELECT id, name FROM users");
        var r = exec("SELECT id, nm FROM t ORDER BY id");
        assertEquals(3, r.rows().size());
        assertEquals("alice", r.rows().get(0).get(1));
    }

    @Test
    void createTableLikeCopiesSchema() throws Exception {
        exec("CREATE TABLE t2 LIKE users");
        var r = exec("SELECT count(*) AS c FROM t2");
        assertEquals(0L, ((Number) r.rows().get(0).get(0)).longValue());
        var desc = exec("DESCRIBE t2");
        assertEquals(3, desc.rows().size());
        // physical table usable for insert
        exec("INSERT INTO t2 SELECT id, name, age FROM users WHERE id = 1");
        assertEquals(1L, ((Number) exec("SELECT count(*) AS c FROM t2").rows().get(0).get(0)).longValue());
    }

    @Test
    void createTablePartitionedByAppendsColumn() throws Exception {
        exec("CREATE TABLE t (id BIGINT, name STRING) PARTITIONED BY (dt STRING)");
        var desc = exec("DESCRIBE t");
        assertEquals(List.of("id", "name", "dt"),
                desc.rows().stream().map(row -> String.valueOf(row.get(0))).toList());
        exec("INSERT INTO t VALUES (1, 'a', '2026-01-01')");
        var r = exec("SELECT dt FROM t");
        assertEquals("2026-01-01", r.rows().get(0).get(0));
    }

    @Test
    void createViewAndQueryIt() throws Exception {
        exec("CREATE VIEW v AS SELECT u.name, count(*) AS cnt FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.name");
        var r = exec("SELECT * FROM v ORDER BY cnt DESC");
        assertEquals(2, r.rows().size());
        assertEquals("alice", r.rows().get(0).get(0));
        // SHOW TABLES lists the view
        var tables = exec("SHOW TABLES");
        assertTrue(tables.rows().stream().map(row -> row.get(0)).anyMatch("v"::equals));
    }

    @Test
    void createOrReplaceView() throws Exception {
        exec("CREATE VIEW v AS SELECT id FROM users");
        exec("CREATE OR REPLACE VIEW v AS SELECT name, age FROM users");
        var r = exec("SELECT * FROM v");
        assertEquals(List.of("name", "age"), r.columns());
    }

    @Test
    void createViewConflictsWithTableFails() throws Exception {
        exec("CREATE TABLE v (id BIGINT)");
        try {
            exec("CREATE VIEW v AS SELECT id FROM users");
            fail("should have thrown");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("already exists"));
        }
    }

    @Test
    void duplicateViewFails() throws Exception {
        exec("CREATE VIEW v AS SELECT id FROM users");
        try {
            exec("CREATE VIEW v AS SELECT name FROM users");
            fail("should have thrown");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("already exists"));
        }
    }

    @Test
    void unsupportedStatementsRejected() throws Exception {
        assertThrows(UnsupportedOperationException.class,
                () -> exec("CREATE TEMPORARY TABLE tmp (id INT)"));
        assertThrows(UnsupportedOperationException.class,
                () -> exec("INSERT INTO users PARTITION (id=1) SELECT 1"));
        assertThrows(UnsupportedOperationException.class,
                () -> exec("CREATE TABLE t (id INT) PARTITIONED BY (dt INT) AS SELECT 1 AS id"));
    }

    @Test
    void dmlCountReturnedOverThrift() throws Exception {
        exec("CREATE TABLE t (id BIGINT, name STRING)");
        try (TTransport transport = new TSocket("localhost", port)) {
            transport.open();
            TCLIService.Client client = new TCLIService.Client(
                    new TBinaryProtocol(transport), new TBinaryProtocol(transport));
            TOpenSessionResp open = client.OpenSession(
                    new TOpenSessionReq(TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11));
            TExecuteStatementResp ins = client.ExecuteStatement(
                    new TExecuteStatementReq(open.getSessionHandle(), "INSERT INTO t VALUES (1, 'a'), (2, 'b')"));
            assertEquals(TStatusCode.SUCCESS_STATUS, ins.getStatus().getStatusCode(), ins.getStatus().toString());
            TFetchResultsResp fetch = client.FetchResults(
                    new TFetchResultsReq(ins.getOperationHandle(), TFetchOrientation.FETCH_FIRST, 10));
            assertEquals(TStatusCode.SUCCESS_STATUS, fetch.getStatus().getStatusCode());
            assertEquals(1, fetch.getResults().getColumns().size());
            assertEquals(List.of("2"), fetch.getResults().getColumns().get(0).getStringVal().getValues());
            client.CloseSession(new TCloseSessionReq(open.getSessionHandle()));
        }
    }
}
