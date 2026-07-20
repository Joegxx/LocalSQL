package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
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

class ThriftServerSmokeTest {

    private ThriftServer server;
    private int port = 10099;
    private CatalogService catalog;
    private DuckDbExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new CatalogService();
        catalog.registerSampleTable("users", "id", "name", "age");
        catalog.registerSampleTable("orders", "id", "user_id", "amount");
        executor = new DuckDbExecutor();
        executor.registerSampleTable("users", List.of("id", "name", "age"), List.of(
                List.of("1", "alice", "30"),
                List.of("2", "bob", "25"),
                List.of("3", "carol", "40")
        ));
        executor.registerSampleTable("orders", List.of("id", "user_id", "amount"), List.of(
                List.of("100", "1", "99.5"),
                List.of("101", "1", "20.0"),
                List.of("102", "2", "150.0")
        ));
        server = new ThriftServer(catalog, executor);
        server.start(port);
        Thread.sleep(800);
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
}
