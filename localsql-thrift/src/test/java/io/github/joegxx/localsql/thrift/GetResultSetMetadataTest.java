package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import org.apache.hive.service.rpc.thrift.TColumn;
import org.apache.hive.service.rpc.thrift.TColumnDesc;
import org.apache.hive.service.rpc.thrift.TExecuteStatementReq;
import org.apache.hive.service.rpc.thrift.TExecuteStatementResp;
import org.apache.hive.service.rpc.thrift.TFetchResultsReq;
import org.apache.hive.service.rpc.thrift.TFetchResultsResp;
import org.apache.hive.service.rpc.thrift.TGetResultSetMetadataReq;
import org.apache.hive.service.rpc.thrift.TGetResultSetMetadataResp;
import org.apache.hive.service.rpc.thrift.TOpenSessionReq;
import org.apache.hive.service.rpc.thrift.TOpenSessionResp;
import org.apache.hive.service.rpc.thrift.TOperationHandle;
import org.apache.hive.service.rpc.thrift.TSessionHandle;
import org.apache.hive.service.rpc.thrift.TTypeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-16 F5 fix: GetResultSetMetadata reports the real result types
 * (per-column JDBC metadata from DuckDB), and FetchResults serializes each
 * column through the Thrift channel matching its declared type — a mismatch
 * between the two silently reads NULL in HiveServer2 clients. Hand-built
 * results (admin statements, DDL counts' legacy shape) keep STRING, DDL count
 * reports BIGINT.
 */
class GetResultSetMetadataTest {

    private LocalSqlThriftService service;
    private DuckDbExecutor executor;
    private TSessionHandle session;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogService catalog = new CatalogService(new DuckDbCatalogStore(executor));
        executor.registerTable("typed",
                List.of(new DuckDbExecutor.ColDef("id", "BIGINT"),
                        new DuckDbExecutor.ColDef("label", "VARCHAR")),
                List.of());
        catalog.registerTable("default", "typed", "TABLE",
                List.of(new CatalogService.ColumnDef("id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("label", new io.github.joegxx.localsql.ir.type.StringType(), true, null)),
                null, "duckdb", null, null, null);
        service = new LocalSqlThriftService(catalog, new SqlPipeline(catalog, executor));
        TOpenSessionResp open = service.OpenSession(new TOpenSessionReq());
        session = open.getSessionHandle();
    }

    @AfterEach
    void tearDown() throws Exception { executor.close(); }

    private TOperationHandle exec(String sql) {
        TExecuteStatementReq req = new TExecuteStatementReq();
        req.setSessionHandle(session);
        req.setStatement(sql);
        TExecuteStatementResp resp = service.ExecuteStatement(req);
        assertEquals(org.apache.hive.service.rpc.thrift.TStatusCode.SUCCESS_STATUS, resp.getStatus().getStatusCode(),
                () -> "execute failed: " + resp.getStatus().getErrorMessage());
        return resp.getOperationHandle();
    }

    private List<TColumnDesc> schema(TOperationHandle op) {
        TGetResultSetMetadataReq req = new TGetResultSetMetadataReq();
        req.setOperationHandle(op);
        TGetResultSetMetadataResp resp = service.GetResultSetMetadata(req);
        return resp.getSchema().getColumns();
    }

    private static TTypeId typeOf(TColumnDesc col) {
        return col.getTypeDesc().getTypes().get(0).getPrimitiveEntry().getType();
    }

    @Test
    void scalarQueryReportsRealTypes() {
        TOperationHandle op = exec(
                "SELECT 1 AS n, 'a' AS s, cast(1.5 AS DOUBLE) AS d, cast(1.23 AS decimal(10,2)) AS dec, "
                        + "true AS b, date '2024-01-01' AS dt, timestamp '2024-01-01 10:00:00' AS ts");
        List<TColumnDesc> cols = schema(op);
        assertEquals(7, cols.size());
        assertEquals(TTypeId.INT_TYPE, typeOf(cols.get(0)));
        assertEquals(TTypeId.VARCHAR_TYPE, typeOf(cols.get(1)));
        assertEquals(TTypeId.DOUBLE_TYPE, typeOf(cols.get(2)));
        assertEquals(TTypeId.DECIMAL_TYPE, typeOf(cols.get(3)));
        assertEquals(TTypeId.BOOLEAN_TYPE, typeOf(cols.get(4)));
        assertEquals(TTypeId.DATE_TYPE, typeOf(cols.get(5)));
        assertEquals(TTypeId.TIMESTAMP_TYPE, typeOf(cols.get(6)));
        assertEquals("n", cols.get(0).getColumnName());
        // decimal qualifiers carry precision/scale for the client driver
        Map<String, org.apache.hive.service.rpc.thrift.TTypeQualifierValue> quals =
                cols.get(3).getTypeDesc().getTypes().get(0).getPrimitiveEntry().getTypeQualifiers().getQualifiers();
        assertEquals("10", quals.get("precision").getStringValue());
        assertEquals("2", quals.get("scale").getStringValue());
    }

    @Test
    void tableColumnsReportRealTypes() {
        TOperationHandle op = exec("SELECT id, label FROM typed");
        List<TColumnDesc> cols = schema(op);
        assertEquals(TTypeId.BIGINT_TYPE, typeOf(cols.get(0)));
        assertEquals(TTypeId.VARCHAR_TYPE, typeOf(cols.get(1)));
    }

    @Test
    void emptyResultKeepsTypes() {
        TOperationHandle op = exec("SELECT id AS x, label AS s FROM typed WHERE id < 0");
        List<TColumnDesc> cols = schema(op);
        assertEquals(TTypeId.BIGINT_TYPE, typeOf(cols.get(0)));
        TFetchResultsResp fetch = service.FetchResults(fetchReq(op, 100));
        assertTrue(fetch.getResults().getColumns().get(0).isSetI64Val());
        assertEquals(0, fetch.getResults().getColumns().get(0).getI64Val().getValuesSize());
    }

    @Test
    void fetchUsesTheChannelMatchingTheMetadata() {
        exec("INSERT INTO typed VALUES (7, 'seven')");
        TOperationHandle op = exec("SELECT id, label, cast(id AS INTEGER) AS small FROM typed");
        TFetchResultsResp fetch = service.FetchResults(fetchReq(op, 100));
        List<TColumn> cols = fetch.getResults().getColumns();
        // BIGINT column -> i64 channel with the value
        assertTrue(cols.get(0).isSetI64Val(), "id column must use the i64 channel");
        assertEquals(7L, cols.get(0).getI64Val().getValues().get(0));
        // VARCHAR column -> string channel
        assertTrue(cols.get(1).isSetStringVal(), "label column must use the string channel");
        assertEquals("seven", cols.get(1).getStringVal().getValues().get(0));
        // INT column -> i32 channel (a mismatch would silently read NULL)
        assertTrue(cols.get(2).isSetI32Val(), "small column must use the i32 channel");
        assertEquals(7, cols.get(2).getI32Val().getValues().get(0));
    }

    @Test
    void nullValuesUseTheNullBitmap() {
        exec("INSERT INTO typed VALUES (NULL, NULL)");
        TOperationHandle op = exec("SELECT id, label FROM typed");
        TFetchResultsResp fetch = service.FetchResults(fetchReq(op, 100));
        List<TColumn> cols = fetch.getResults().getColumns();
        assertTrue(cols.get(0).isSetI64Val());
        assertEquals(0L, cols.get(0).getI64Val().getValues().get(0));
        assertTrue(cols.get(0).getI64Val().getNulls()[0] != 0, "id null bit must be set");
        assertTrue(cols.get(1).isSetStringVal());
        assertEquals("", cols.get(1).getStringVal().getValues().get(0));
        assertTrue(cols.get(1).getStringVal().getNulls()[0] != 0, "label null bit must be set");
    }

    @Test
    void adminStatementStaysStringTyped() {
        TOperationHandle op = exec("SHOW TABLES");
        List<TColumnDesc> cols = schema(op);
        assertFalse(cols.isEmpty());
        for (TColumnDesc c : cols) {
            assertEquals(TTypeId.STRING_TYPE, typeOf(c));
        }
    }

    @Test
    void insertCountReportsBigint() {
        TOperationHandle op = exec("INSERT INTO typed VALUES (1, 'x')");
        List<TColumnDesc> cols = schema(op);
        assertEquals(1, cols.size());
        assertEquals("count", cols.get(0).getColumnName());
        assertEquals(TTypeId.BIGINT_TYPE, typeOf(cols.get(0)));
        TFetchResultsResp fetch = service.FetchResults(fetchReq(op, 100));
        assertTrue(fetch.getResults().getColumns().get(0).isSetI64Val());
        assertEquals(1L, fetch.getResults().getColumns().get(0).getI64Val().getValues().get(0));
    }

    private TFetchResultsReq fetchReq(TOperationHandle op, int maxRows) {
        TFetchResultsReq req = new TFetchResultsReq();
        req.setOperationHandle(op);
        req.setMaxRows(maxRows);
        return req;
    }
}
