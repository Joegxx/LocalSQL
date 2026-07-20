package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.duckdb.DuckDbSqlGenerator;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.spark.SparkAstBuilder;
import io.github.joegxx.localsql.parser.SparkSqlParser;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser.SingleStatementContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;

public final class ThriftServer {

    private static final Logger LOG = LoggerFactory.getLogger(ThriftServer.class);

    private final CatalogService catalogService;
    private final DuckDbExecutor executor;
    private final DuckDbSqlGenerator generator = new DuckDbSqlGenerator();

    public ThriftServer(CatalogService catalogService, DuckDbExecutor executor) {
        this.catalogService = catalogService;
        this.executor = executor;
    }

    public DuckDbExecutor.QueryResult executeSparkSql(String sql) throws SQLException {
        LOG.info("Executing Spark SQL: {}", sql);
        SparkSqlParser parser = new SparkSqlParser();
        SingleStatementContext ctx = parser.parseStatement(sql);
        SparkAstBuilder builder = new SparkAstBuilder();
        Relation rel = builder.buildStatement(sql, s -> parser.parseStatement(s));
        String duckSql = generator.generate(rel);
        LOG.info("Translated to DuckDB SQL: {}", duckSql);
        return executor.execute(duckSql);
    }

    public void start(int port) {
        LOG.info("ThriftServer (HiveServer2 compatible) starting on port {} - MVP placeholder", port);
        LOG.info("Connect via: jdbc:hive2://localhost:{}", port);
    }

    public void stop() {
        LOG.info("ThriftServer stopped");
    }
}
