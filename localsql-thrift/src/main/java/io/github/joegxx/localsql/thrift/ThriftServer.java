package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.analyzer.SemanticAnalyzer;
import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.duckdb.DuckDbSqlGenerator;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.parser.SparkSqlParser;
import io.github.joegxx.localsql.rewrite.RewriteEngine;
import io.github.joegxx.localsql.spark.SparkAstBuilder;
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
    private final DuckDbExecutor executor;
    private final DuckDbSqlGenerator generator = new DuckDbSqlGenerator();
    private final SemanticAnalyzer analyzer;
    private final RewriteEngine rewriter = new RewriteEngine();
    private TServer server;
    private LocalSqlThriftService service;

    public ThriftServer(CatalogService catalogService, DuckDbExecutor executor) {
        this.catalogService = catalogService;
        this.executor = executor;
        this.analyzer = new SemanticAnalyzer(catalogService.catalog());
    }

    public void start(int port) {
        service = new LocalSqlThriftService(catalogService, executor);
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
        SparkSqlParser parser = new SparkSqlParser();
        var ctx = parser.parseStatement(sql);
        SparkAstBuilder builder = new SparkAstBuilder();
        Relation rel = builder.buildStatement(sql, s -> parser.parseStatement(s));
        analyzer.analyze(rel);
        rewriter.rewrite(rel);
        String duckSql = generator.generate(rel);
        LOG.info("Translated to DuckDB SQL: {}", duckSql);
        return executor.execute(duckSql);
    }
}
