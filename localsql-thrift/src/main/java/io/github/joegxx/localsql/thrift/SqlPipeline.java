package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.analyzer.SemanticAnalyzer;
import io.github.joegxx.localsql.catalog.Catalog;
import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.duckdb.DuckDbSqlGenerator;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.statement.CreateTable;
import io.github.joegxx.localsql.ir.statement.CreateView;
import io.github.joegxx.localsql.ir.statement.Insert;
import io.github.joegxx.localsql.ir.statement.QueryStatement;
import io.github.joegxx.localsql.ir.statement.Statement;
import io.github.joegxx.localsql.parser.SparkSqlParser;
import io.github.joegxx.localsql.rewrite.RewriteEngine;
import io.github.joegxx.localsql.spark.SparkAstBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * End-to-end pipeline for one Spark SQL statement: parse -> IR -> analyze ->
 * rewrite -> dispatch to DuckDB. Both ThriftServer.executeSparkSql and
 * LocalSqlThriftService.ExecuteStatement funnel through here.
 */
final class SqlPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(SqlPipeline.class);

    private final CatalogService catalogService;
    private final DuckDbExecutor executor;
    private final DuckDbSqlGenerator generator = new DuckDbSqlGenerator();
    private final SparkSqlParser parser = new SparkSqlParser();
    private final SparkAstBuilder astBuilder = new SparkAstBuilder();
    private final SemanticAnalyzer analyzer;
    private final RewriteEngine rewriter = new RewriteEngine();

    SqlPipeline(CatalogService catalogService, DuckDbExecutor executor) {
        this.catalogService = catalogService;
        this.executor = executor;
        this.analyzer = new SemanticAnalyzer(catalogService.catalog());
    }

    DuckDbExecutor.QueryResult execute(String sql) throws SQLException {
        Statement stmt = astBuilder.buildStatement(sql, s -> parser.parseStatement(s));
        if (stmt instanceof QueryStatement q) {
            Relation rel = q.query();
            analyzer.analyze(rel);
            rewriter.rewrite(rel);
            String duckSql = generator.generate(rel);
            LOG.info("Translated to DuckDB SQL: {}", duckSql);
            return executor.execute(duckSql);
        }
        if (stmt instanceof Insert i) return executeInsert(i);
        if (stmt instanceof CreateTable ct) return executeCreateTable(ct);
        if (stmt instanceof CreateView cv) return executeCreateView(cv);
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.DropTable dr) return executeDrop(dr);
        throw new UnsupportedOperationException(
                "Unsupported statement: " + stmt.getClass().getSimpleName());
    }

    private DuckDbExecutor.QueryResult executeDrop(io.github.joegxx.localsql.ir.statement.DropTable dr)
            throws SQLException {
        String db = databaseOf(dr.tableName());
        String name = dr.tableName().get(dr.tableName().size() - 1);
        Catalog.Table existing = catalogService.getTable(db, name).orElse(null);
        if (existing == null) {
            if (dr.ifExists()) return empty();
            throw new IllegalArgumentException(
                    (dr.view() ? "View" : "Table") + " does not exist: " + String.join(".", dr.tableName()));
        }
        if (dr.view() && !"VIEW".equalsIgnoreCase(existing.tableType())) {
            throw new IllegalArgumentException(
                    "DROP VIEW target is not a view: " + String.join(".", dr.tableName()));
        }
        if (!dr.view() && "VIEW".equalsIgnoreCase(existing.tableType())) {
            throw new IllegalArgumentException(
                    "DROP TABLE target is a view (use DROP VIEW): " + String.join(".", dr.tableName()));
        }
        try {
            catalogService.dropTable(db, name);
        } catch (Exception e) {
            throw new SQLException("Failed to drop catalog entry for " + name + ": " + e.getMessage(), e);
        }
        executor.run((dr.view() ? "DROP VIEW IF EXISTS " : "DROP TABLE IF EXISTS ") + quote(name));
        return empty();
    }

    private DuckDbExecutor.QueryResult executeInsert(Insert i) throws SQLException {
        analyzer.analyze(i);
        rewriter.rewrite(i.query());
        String table = bare(i.tableName());
        boolean tx = i.overwrite();
        if (tx) executor.run("BEGIN");
        try {
            if (tx) executor.run("DELETE FROM " + table);
            String sql = generator.generateInsert(i);
            LOG.info("Translated to DuckDB SQL: {}", sql);
            int n = executor.executeUpdate(sql);
            if (tx) executor.run("COMMIT");
            return new DuckDbExecutor.QueryResult(List.of("count"), List.of(List.of((long) n)));
        } catch (Exception e) {
            if (tx) {
                try { executor.run("ROLLBACK"); } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    private DuckDbExecutor.QueryResult executeCreateTable(CreateTable ct) throws SQLException {
        analyzer.analyze(ct);
        String db = databaseOf(ct.tableName());
        String table = bare(ct.tableName());
        Catalog.Table existing = catalogService.getTable(db, ct.tableName().get(ct.tableName().size() - 1)).orElse(null);
        if (existing != null) {
            if (ct.ifNotExists()) return empty();
            throw new IllegalArgumentException(
                    "Table already exists: " + String.join(".", ct.tableName()));
        }
        String createSql;
        if (ct.likeSource() != null) {
            Catalog.Table src = catalogService.catalog().resolveTable(ct.likeSource())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Source table does not exist: " + String.join(".", ct.likeSource())));
            List<CreateTable.ColumnDef> cols = new ArrayList<>();
            for (Catalog.Column c : src.columns()) {
                cols.add(new CreateTable.ColumnDef(c.name(), c.type(), c.nullable(), c.comment()));
            }
            CreateTable resolved = new CreateTable(ct.tableName(), false, cols, null, null);
            createSql = generator.generateCreateTable(resolved);
            executor.run(createSql);
            registerPhysical(db, table, "TABLE", createSql);
            return empty();
        }
        if (ct.query() == null) {
            createSql = generator.generateCreateTable(ct);
            executor.run(createSql);
            registerPhysical(db, table, "TABLE", createSql);
            return empty();
        }
        rewriter.rewrite(ct.query());
        if (ct.columns().isEmpty()) {
            createSql = generator.generateCreateTableAs(ct);
            executor.run(createSql);
            registerPhysical(db, table, "TABLE", createSql);
            return empty();
        }
        // Explicit column list + AS query: create the (empty) table with the
        // declared schema, then populate it through the INSERT path.
        createSql = generator.generateCreateTable(ct);
        executor.run(createSql);
        registerPhysical(db, table, "TABLE", createSql);
        return executeInsert(new Insert(ct.tableName(), false, null, ct.query()));
    }

    private DuckDbExecutor.QueryResult executeCreateView(CreateView cv) throws SQLException {
        analyzer.analyze(cv);
        String db = databaseOf(cv.tableName());
        String name = cv.tableName().get(cv.tableName().size() - 1);
        Catalog.Table existing = catalogService.getTable(db, name).orElse(null);
        if (existing != null) {
            if ("TABLE".equalsIgnoreCase(existing.tableType())) {
                throw new IllegalArgumentException(
                        "Cannot create view: a table with that name already exists: " + name);
            }
            if (!cv.orReplace()) {
                if (cv.ifNotExists()) return empty();
                throw new IllegalArgumentException("View already exists: " + name);
            }
            try {
                catalogService.dropTable(db, name);
            } catch (Exception e) {
                throw new SQLException("Failed to drop view " + name + ": " + e.getMessage(), e);
            }
            executor.run("DROP VIEW IF EXISTS " + quote(name));
        } else if (cv.ifNotExists() && cv.orReplace()) {
            return empty();
        }
        rewriter.rewrite(cv.query());
        String createSql = generator.generateCreateView(cv);
        executor.run(createSql);
        registerPhysical(db, name, "VIEW", createSql);
        return empty();
    }

    private void registerPhysical(String db, String table, String type, String createSql) throws SQLException {
        try {
            DuckDbExecutor.QueryResult desc = executor.execute("DESCRIBE " + quote(table));
            List<CatalogService.ColumnDef> cols = new ArrayList<>();
            for (List<Object> row : desc.rows()) {
                cols.add(new CatalogService.ColumnDef(
                        String.valueOf(row.get(0)),
                        Catalog.parseDataType(String.valueOf(row.get(1))),
                        true, null));
            }
            Catalog.Table t = catalogService.registerTable(
                    db, table, type, cols, null, null, null, null, null);
            if (catalogService.store() != null) {
                catalogService.store().saveRuntimeInfo(t.tableId(), table, createSql);
            }
        } catch (Exception e) {
            throw new SQLException("Failed to register metadata for " + table + ": " + e.getMessage(), e);
        }
    }

    private static DuckDbExecutor.QueryResult empty() {
        return new DuckDbExecutor.QueryResult(List.of("result"), List.of());
    }

    private static String bare(List<String> parts) {
        return quote(parts.get(parts.size() - 1));
    }

    private static String quote(String id) {
        if (id.matches("[a-z_][a-z0-9_]*") && !DuckDbSqlGenerator.DUCKDB_RESERVED.contains(id)) return id;
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    private static String databaseOf(List<String> parts) {
        return parts.size() >= 2 ? parts.get(parts.size() - 2) : "default";
    }
}
