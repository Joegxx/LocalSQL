package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import org.apache.hive.service.rpc.thrift.*;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared harness for tests that exercise SQL through the real Thrift endpoint:
 * OpenSession -> ExecuteStatement -> GetResultSetMetadata -> FetchResults -> CloseOperation.
 * Results are materialized as rows of strings (the Thrift layer is string-typed).
 */
final class ThriftQueryRunner implements AutoCloseable {

    private final ThriftServer server;
    private final DuckDbExecutor executor;
    private final CatalogService catalogService;
    private final TTransport transport;
    private final TCLIService.Client client;
    private final TSessionHandle session;

    private ThriftQueryRunner(ThriftServer server, DuckDbExecutor executor, CatalogService catalogService,
                              TTransport transport, TCLIService.Client client, TSessionHandle session) {
        this.server = server;
        this.executor = executor;
        this.catalogService = catalogService;
        this.transport = transport;
        this.client = client;
        this.session = session;
    }

    /** Boot a fresh in-process server (catalog + DuckDB) and connect a Thrift client to it. */
    static ThriftQueryRunner start(int port) throws Exception {
        DuckDbExecutor executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        CatalogService catalog = new CatalogService(store);
        ThriftServer server = new ThriftServer(catalog, executor);
        server.start(port);
        Thread.sleep(800);
        return connect(server, executor, catalog, port);
    }

    static ThriftQueryRunner connect(ThriftServer server, DuckDbExecutor executor,
                                     CatalogService catalog, int port) throws Exception {
        TTransport transport = new TSocket("localhost", port);
        transport.open();
        TCLIService.Client client = new TCLIService.Client(
                new TBinaryProtocol(transport), new TBinaryProtocol(transport));
        TOpenSessionResp open = client.OpenSession(
                new TOpenSessionReq(TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11));
        if (open.getStatus().getStatusCode() != TStatusCode.SUCCESS_STATUS) {
            throw new IllegalStateException("OpenSession failed: " + open.getStatus());
        }
        return new ThriftQueryRunner(server, executor, catalog, transport, client, open.getSessionHandle());
    }

    CatalogService catalog() { return catalogService; }
    DuckDbExecutor executor() { return executor; }

    /** Execute Spark SQL through Thrift and return rows as strings (nulls kept as null). */
    List<List<String>> query(String sql) throws Exception {
        TExecuteStatementResp exec = client.ExecuteStatement(new TExecuteStatementReq(session, sql));
        if (exec.getStatus().getStatusCode() != TStatusCode.SUCCESS_STATUS) {
            throw new IllegalStateException("ExecuteStatement failed: " + exec.getStatus().getErrorMessage());
        }
        TOperationHandle op = exec.getOperationHandle();
        try {
            TFetchResultsResp res = client.FetchResults(
                    new TFetchResultsReq(op, TFetchOrientation.FETCH_FIRST, 100_000));
            if (res.getStatus().getStatusCode() != TStatusCode.SUCCESS_STATUS) {
                throw new IllegalStateException("FetchResults failed: " + res.getStatus().getErrorMessage());
            }
            List<TColumn> cols = res.getResults().getColumns();
            List<List<String>> rows = new ArrayList<>();
            if (cols == null || cols.isEmpty()) return rows;
            int n = valueCount(cols.get(0));
            for (int r = 0; r < n; r++) {
                List<String> row = new ArrayList<>(cols.size());
                for (TColumn c : cols) {
                    row.add(cellValue(c, r));
                }
                rows.add(row);
            }
            return rows;
        } finally {
            client.CloseOperation(new TCloseOperationReq(op));
        }
    }

    private static int valueCount(TColumn c) {
        if (c.isSetStringVal()) return c.getStringVal().getValuesSize();
        if (c.isSetI64Val()) return c.getI64Val().getValuesSize();
        if (c.isSetI32Val()) return c.getI32Val().getValuesSize();
        if (c.isSetDoubleVal()) return c.getDoubleVal().getValuesSize();
        if (c.isSetBoolVal()) return c.getBoolVal().getValuesSize();
        if (c.isSetByteVal()) return c.getByteVal().getValuesSize();
        if (c.isSetI16Val()) return c.getI16Val().getValuesSize();
        return 0;
    }

    /** Cell as text with the null bitmap applied (channels match the metadata type). */
    private static String cellValue(TColumn c, int r) {
        if (c.isSetStringVal()) {
            return nullBit(c.getStringVal().getNulls(), r) ? null : c.getStringVal().getValues().get(r);
        }
        if (c.isSetI64Val()) {
            return nullBit(c.getI64Val().getNulls(), r) ? null : String.valueOf(c.getI64Val().getValues().get(r));
        }
        if (c.isSetI32Val()) {
            return nullBit(c.getI32Val().getNulls(), r) ? null : String.valueOf(c.getI32Val().getValues().get(r));
        }
        if (c.isSetDoubleVal()) {
            return nullBit(c.getDoubleVal().getNulls(), r) ? null : String.valueOf(c.getDoubleVal().getValues().get(r));
        }
        if (c.isSetBoolVal()) {
            return nullBit(c.getBoolVal().getNulls(), r) ? null : String.valueOf(c.getBoolVal().getValues().get(r));
        }
        if (c.isSetByteVal()) {
            return nullBit(c.getByteVal().getNulls(), r) ? null : String.valueOf(c.getByteVal().getValues().get(r));
        }
        if (c.isSetI16Val()) {
            return nullBit(c.getI16Val().getNulls(), r) ? null : String.valueOf(c.getI16Val().getValues().get(r));
        }
        return null;
    }

    private static boolean nullBit(byte[] nulls, int r) {
        return nulls != null && nulls.length > r / 8 && (nulls[r / 8] & (1 << (r % 8))) != 0;
    }

    @Override
    public void close() throws Exception {
        client.CloseSession(new TCloseSessionReq(session));
        transport.close();
        server.stop();
        executor.close();
    }
}
