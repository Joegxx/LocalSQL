package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import org.apache.hive.service.rpc.thrift.TCLIService;
import org.apache.hive.service.rpc.thrift.TColumn;
import org.apache.hive.service.rpc.thrift.TFetchResultsResp;
import org.apache.hive.service.rpc.thrift.TOperationHandle;
import org.apache.hive.service.rpc.thrift.TExecuteStatementReq;
import org.apache.hive.service.rpc.thrift.TExecuteStatementResp;
import org.apache.hive.service.rpc.thrift.TOpenSessionReq;
import org.apache.hive.service.rpc.thrift.TOpenSessionResp;
import org.apache.hive.service.rpc.thrift.TFetchResultsReq;
import org.apache.hive.service.rpc.thrift.TFetchOrientation;
import org.apache.hive.service.rpc.thrift.TProtocolVersion;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;

public final class ThriftServer {

    private static final Logger LOG = LoggerFactory.getLogger(ThriftServer.class);

    private final CatalogService catalogService;
    private final SqlPipeline pipeline;
    private TServer server;
    private LocalSqlThriftService service;

    public ThriftServer(CatalogService catalogService, DuckDbExecutor executor) {
        this.catalogService = catalogService;
        this.pipeline = new SqlPipeline(catalogService, executor);
    }

    public void start(int port) {
        service = new LocalSqlThriftService(catalogService, pipeline);
        TCLIService.Processor<LocalSqlThriftService> processor = new TCLIService.Processor<>(service);
        Thread serverThread = new Thread(() -> {
            try {
                TServerSocket transport = new TServerSocket(port);
                TThreadPoolServer.Args args = new TThreadPoolServer.Args(transport)
                        .processor(processor)
                        .transportFactory(new HiveServerTTransportFactory())
                        .minWorkerThreads(5)
                        .maxWorkerThreads(50);
                server = new TThreadPoolServer(args);
                LOG.info("HiveServer2 Thrift server listening on port {} (jdbc:hive2://localhost:{})", port, port);
                server.serve();
            } catch (TTransportException e) {
                // A silent bind failure is the worst outcome: the JVM keeps
                // running while clients get connection refused. Fail loudly.
                LOG.error("Thrift server failed to bind port {}. Is another instance running? " +
                        "Hint: lsof -iTCP:{} -sTCP:LISTEN", port, port, e);
                System.exit(1);
            }
        }, "localsql-thrift-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public void stop() {
        if (server != null) {
            server.stop();
            LOG.info("ThriftServer stopped");
        }
    }

    public DuckDbExecutor.QueryResult executeSparkSql(String sql) throws SQLException {
        LOG.info("Executing Spark SQL: {}", sql);
        var admin = AdminStatementHandler.tryHandle(catalogService, sql, "default");
        if (admin.isPresent()) return admin.get().result();
        return pipeline.execute(sql);
    }
}
