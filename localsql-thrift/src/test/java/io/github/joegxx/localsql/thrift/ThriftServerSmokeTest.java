package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.ir.type.FractionalType;
import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.type.StringType;
import org.apache.hive.service.rpc.thrift.*;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ThriftServerSmokeTest {

    private ThriftServer server;
    private int port = 10099;
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
                List.of(new CatalogService.ColumnDef("id", IntegralType.BIGINT, false, null),
                        new CatalogService.ColumnDef("name", new StringType(), true, null),
                        new CatalogService.ColumnDef("age", IntegralType.INT, true, null)),
                null, "duckdb", null, Map.of(), null);
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
                List.of(new CatalogService.ColumnDef("id", IntegralType.BIGINT, false, null),
                        new CatalogService.ColumnDef("user_id", IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("amount", FractionalType.DOUBLE, true, null)),
                null, "duckdb", null, Map.of(), null);
        catalog.store().saveRuntimeInfo(table.tableId(), "orders",
                "CREATE TABLE orders (id BIGINT, user_id BIGINT, amount DOUBLE)");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
        if (executor != null) executor.close();
    }

    @Test
    void openSessionExecuteFetch() throws Exception {
        try (TTransport transport = new TSocket("localhost", port)) {
            transport.open();
            TCLIService.Client client = new TCLIService.Client(
                    new TBinaryProtocol(transport), new TBinaryProtocol(transport));

            TOpenSessionResp open = client.OpenSession(
                    new TOpenSessionReq(TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11));
            assertEquals(TStatusCode.SUCCESS_STATUS, open.getStatus().getStatusCode(),
                    "OpenSession: " + open.getStatus());
            assertNotNull(open.getSessionHandle());

            String sql = "SELECT u.name, count(*) AS cnt FROM users u " +
                    "JOIN orders o ON u.id = o.user_id GROUP BY u.name ORDER BY cnt DESC";
            TExecuteStatementReq execReq = new TExecuteStatementReq(open.getSessionHandle(), sql);
            TExecuteStatementResp exec = client.ExecuteStatement(execReq);
            assertEquals(TStatusCode.SUCCESS_STATUS, exec.getStatus().getStatusCode(),
                    "ExecuteStatement: " + exec.getStatus());
            assertNotNull(exec.getOperationHandle());

            TOperationHandle op = exec.getOperationHandle();
            TGetResultSetMetadataResp meta = client.GetResultSetMetadata(
                    new TGetResultSetMetadataReq(op));
            assertEquals(TStatusCode.SUCCESS_STATUS, meta.getStatus().getStatusCode());
            assertEquals(2, meta.getSchema().getColumnsSize(), "column count");

            TFetchResultsReq fetch = new TFetchResultsReq(op, TFetchOrientation.FETCH_FIRST, 1000);
            TFetchResultsResp res = client.FetchResults(fetch);
            assertEquals(TStatusCode.SUCCESS_STATUS, res.getStatus().getStatusCode(),
                    "FetchResults: " + res.getStatus());
            assertNotNull(res.getResults());

            List<TColumn> cols = res.getResults().getColumns();
            assertNotNull(cols, "expected columnar results");
            assertEquals(2, cols.size(), "column count in fetched rows");
            List<String> names = cols.get(0).getStringVal().getValues();
            List<String> cnts = cols.get(1).getStringVal().getValues();
            assertTrue(names.contains("alice"), "alice should appear: " + names);
            assertEquals("2", cnts.get(names.indexOf("alice")), "alice cnt should be 2");

            TCloseOperationResp close = client.CloseOperation(new TCloseOperationReq(op));
            assertEquals(TStatusCode.SUCCESS_STATUS, close.getStatus().getStatusCode());

            TCloseSessionResp closeSess = client.CloseSession(
                    new TCloseSessionReq(open.getSessionHandle()));
            assertEquals(TStatusCode.SUCCESS_STATUS, closeSess.getStatus().getStatusCode());
        }
    }

    @Test
    void metadataGetTablesAndColumns() throws Exception {
        try (TTransport transport = new TSocket("localhost", port)) {
            transport.open();
            TCLIService.Client client = new TCLIService.Client(
                    new TBinaryProtocol(transport), new TBinaryProtocol(transport));

            TOpenSessionResp open = client.OpenSession(
                    new TOpenSessionReq(TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11));
            assertEquals(TStatusCode.SUCCESS_STATUS, open.getStatus().getStatusCode());

            TGetTablesResp tables = client.GetTables(new TGetTablesReq(open.getSessionHandle()));
            assertEquals(TStatusCode.SUCCESS_STATUS, tables.getStatus().getStatusCode());
            TOperationHandle tOp = tables.getOperationHandle();
            TFetchResultsResp tRes = client.FetchResults(
                    new TFetchResultsReq(tOp, TFetchOrientation.FETCH_FIRST, 1000));
            List<TColumn> tCols = tRes.getResults().getColumns();
            assertFalse(tCols.isEmpty(), "tables result should have columns");
            List<String> tNames = tCols.get(2).getStringVal().getValues();
            assertTrue(tNames.contains("users"), "users table should be listed: " + tNames);
            assertTrue(tNames.contains("orders"), "orders table should be listed: " + tNames);

            TGetColumnsResp columns = client.GetColumns(new TGetColumnsReq(open.getSessionHandle()));
            assertEquals(TStatusCode.SUCCESS_STATUS, columns.getStatus().getStatusCode());
            TFetchResultsResp cRes = client.FetchResults(
                    new TFetchResultsReq(columns.getOperationHandle(), TFetchOrientation.FETCH_FIRST, 1000));
            List<TColumn> cCols = cRes.getResults().getColumns();
            assertFalse(cCols.isEmpty(), "columns result should have columns");
            List<String> colNames = cCols.get(3).getStringVal().getValues();
            assertTrue(colNames.contains("id"), "id column should be listed: " + colNames);

            client.CloseSession(new TCloseSessionReq(open.getSessionHandle()));
        }
    }

    @Test
    void whereOrderByAndLimit() throws Exception {
        var result = server.executeSparkSql(
                "SELECT name, age FROM users WHERE age >= 30 ORDER BY age DESC LIMIT 1");

        assertEquals(List.of("name", "age"), result.columns());
        assertEquals(List.of(List.of("carol", 40)), result.rows());
    }

    @Test
    void aggregateHaving() throws Exception {
        var result = server.executeSparkSql(
                "SELECT user_id, count(*) AS cnt FROM orders "
                        + "GROUP BY user_id HAVING count(*) > 1 ORDER BY user_id");

        assertEquals(List.of("user_id", "cnt"), result.columns());
        assertEquals(List.of(List.of(1L, 2L)), result.rows());
    }

    @Test
    void aliasedSubquery() throws Exception {
        var result = server.executeSparkSql(
                "SELECT s.name FROM (SELECT name FROM users WHERE age >= 30) s ORDER BY s.name");

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("alice"), List.of("carol")), result.rows());
    }
}
