package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import org.apache.hive.service.rpc.thrift.*;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSaslClientTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.sasl.Sasl;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the SASL PLAIN handshake used by standard Hive JDBC clients
 * (DataGrip/IntelliJ) over the same server that serves NOSASL clients.
 */
class ThriftSaslTest {

    private ThriftServer server;
    private int port = 10096;
    private DuckDbExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        CatalogService catalog = new CatalogService(store);
        executor.registerTable("users",
                List.of(new DuckDbExecutor.ColDef("id", "BIGINT"),
                        new DuckDbExecutor.ColDef("name", "VARCHAR")),
                List.of(List.of(1L, "alice")));
        catalog.registerTable("default", "users", "TABLE",
                List.of(new CatalogService.ColumnDef("id",
                                io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, false, null),
                        new CatalogService.ColumnDef("name",
                                new io.github.joegxx.localsql.ir.type.StringType(), true, null)),
                null, "duckdb", null, java.util.Map.of(), null);
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
    void saslPlainHandshakeAndQuery() throws Exception {
        try (TTransport sock = new TSocket("localhost", port)) {
            sock.open();
            HashMap<String, String> props = new HashMap<>();
            props.put(Sasl.QOP, "auth");
            TTransport sasl = new TSaslClientTransport("PLAIN", "tester", "anonymous", "default",
                    props, new Cb(), sock);
            sasl.open();

            TCLIService.Client client = new TCLIService.Client(
                    new TBinaryProtocol(sasl), new TBinaryProtocol(sasl));
            TOpenSessionResp open = client.OpenSession(
                    new TOpenSessionReq(TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11));
            assertEquals(TStatusCode.SUCCESS_STATUS, open.getStatus().getStatusCode());
            assertEquals(TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11, open.getServerProtocolVersion());

            TExecuteStatementResp exec = client.ExecuteStatement(
                    new TExecuteStatementReq(open.getSessionHandle(), "SELECT name FROM users ORDER BY name"));
            assertEquals(TStatusCode.SUCCESS_STATUS, exec.getStatus().getStatusCode());

            TFetchResultsResp res = client.FetchResults(
                    new TFetchResultsReq(exec.getOperationHandle(), TFetchOrientation.FETCH_FIRST, 100));
            List<String> names = res.getResults().getColumns().get(0).getStringVal().getValues();
            assertTrue(names.contains("alice"), "alice should be fetched over SASL: " + names);
            sasl.close();
        }
    }

    private static final class Cb implements CallbackHandler {
        @Override
        public void handle(Callback[] callbacks) {
            for (Callback cb : callbacks) {
                if (cb instanceof NameCallback) ((NameCallback) cb).setName("tester");
                if (cb instanceof PasswordCallback) ((PasswordCallback) cb).setPassword("x".toCharArray());
            }
        }
    }
}
