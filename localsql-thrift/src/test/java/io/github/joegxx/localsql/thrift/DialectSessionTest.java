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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0 验收：连接期方言注入。方言经 OpenSession configuration 传入（多 key 形态），
 * 未知值告警回落 SPARK，未接入的方言（STARROCKS，M1 接线前）大声失败而非静默降级。
 */
class DialectSessionTest {

    private ThriftServer server;
    private final int port = 10193;
    private DuckDbExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        CatalogService catalog = new CatalogService(store);
        executor.executeUpdate("CREATE TABLE t1 (id BIGINT)");
        executor.executeUpdate("INSERT INTO t1 VALUES (1)");
        server = new ThriftServer(catalog, executor);
        server.start(port);
        Thread.sleep(600);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
        if (executor != null) executor.close();
    }

    private static final class Client implements AutoCloseable {
        final TCLIService.Client client;
        final TTransport transport;
        final TSessionHandle session;

        Client(int port, Map<String, String> conf) throws Exception {
            transport = new TSocket("localhost", port);
            transport.open();
            client = new TCLIService.Client(new TBinaryProtocol(transport), new TBinaryProtocol(transport));
            TOpenSessionReq req = new TOpenSessionReq(TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11);
            if (conf != null) req.setConfiguration(new HashMap<>(conf));
            TOpenSessionResp open = client.OpenSession(req);
            assertEquals(TStatusCode.SUCCESS_STATUS, open.getStatus().getStatusCode());
            session = open.getSessionHandle();
        }

        TExecuteStatementResp execute(String sql) throws Exception {
            return client.ExecuteStatement(new TExecuteStatementReq(session, sql));
        }

        @Override
        public void close() throws Exception {
            client.CloseSession(new TCloseSessionReq(session));
            transport.close();
        }
    }

    @Test
    void defaultSessionUsesSpark() throws Exception {
        try (Client c = new Client(port, null)) {
            assertEquals(TStatusCode.SUCCESS_STATUS, c.execute("SELECT 1").getStatus().getStatusCode());
        }
    }

    @Test
    void explicitSparkDialectWorks() throws Exception {
        try (Client c = new Client(port, Map.of("sql_dialect", "spark"))) {
            assertEquals(TStatusCode.SUCCESS_STATUS, c.execute("SELECT 1").getStatus().getStatusCode());
        }
    }

    @Test
    void unknownDialectValueFallsBackToSpark() throws Exception {
        // 打错的方言值绝不静默当作别的方言，也不让会话失效：告警后按 SPARK 执行
        try (Client c = new Client(port, Map.of("sql_dialect", "starock"))) {
            assertEquals(TStatusCode.SUCCESS_STATUS, c.execute("SELECT 1").getStatus().getStatusCode());
        }
    }

    @Test
    void starrocksDialectExecutesThroughItsOwnFrontend() throws Exception {
        // M1 接线后：starrocks 会话经 StarRocksAstBuilder 解析执行
        try (Client c = new Client(port, Map.of("sql_dialect", "starrocks"))) {
            TExecuteStatementResp resp = c.execute("SELECT 1");
            assertEquals(TStatusCode.SUCCESS_STATUS, resp.getStatus().getStatusCode(),
                    "starrocks dialect should execute: " + resp.getStatus().getErrorMessage());
        }
    }

    @Test
    void hiveConfKeyFormAccepted() throws Exception {
        // hive-jdbc 的 session var 可能以 set:hiveconf: 前缀送达
        try (Client c = new Client(port, Map.of("set:hiveconf:sql_dialect", "starrocks"))) {
            assertEquals(TStatusCode.SUCCESS_STATUS, c.execute("SELECT 1").getStatus().getStatusCode());
        }
    }

    @Test
    void dialectParsingCoversKeyForms() {
        assertEquals(Optional.empty(), Dialect.fromSessionValue(null));
        assertEquals(Optional.empty(), Dialect.fromSessionValue("  "));
        assertEquals(Optional.empty(), Dialect.fromSessionValue("bogus"));
        assertEquals(Optional.of(Dialect.SPARK), Dialect.fromSessionValue("Spark"));
        assertEquals(Optional.of(Dialect.STARROCKS), Dialect.fromSessionValue(" STARROCKS "));
    }
}
