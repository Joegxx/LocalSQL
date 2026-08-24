package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.analyzer.SemanticAnalyzer;
import io.github.joegxx.localsql.catalog.Catalog;
import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.duckdb.DuckDbSqlGenerator;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.statement.CreateTable;
import io.github.joegxx.localsql.ir.statement.CreateView;
import io.github.joegxx.localsql.ir.statement.DropTable;
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

    /**
     * Serializes statement execution: INSERT OVERWRITE runs a BEGIN/DELETE/
     * INSERT/COMMIT sequence across several Statements on the shared DuckDB
     * connection, and interleaved requests from the multi-threaded Thrift
     * server would corrupt each other's transactions. A local single-user
     * runtime trades concurrency for correctness here.
     */
    synchronized DuckDbExecutor.QueryResult execute(String sql) throws SQLException {
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
        if (stmt instanceof DropTable dr) return executeDrop(dr);
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.CreateDatabase cd) return executeCreateDatabase(cd);
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.DropDatabase dd) return executeDropDatabase(dd);
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.AlterDatabase ad) return executeAlterDatabase(ad);
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.RenameTable rt) return executeRenameTable(rt);
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.SetTableProperties stp) return executeSetTableProperties(stp);
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.UnsetTableProperties utp) return executeUnsetTableProperties(utp);
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.AddColumns ac) return executeAddColumns(ac);
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.DropColumns dc) return executeDropColumns(dc);
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.RenameColumn rc) return executeRenameColumn(rc);
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.AlterColumn alc) return executeAlterColumn(alc);
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.ReplaceColumns rpc) return executeReplaceColumns(rpc);
        throw new UnsupportedOperationException(
                "Unsupported statement: " + stmt.getClass().getSimpleName());
    }

    private DuckDbExecutor.QueryResult executeDrop(DropTable dr)
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
        executor.run((dr.view() ? "DROP VIEW IF EXISTS " : "DROP TABLE IF EXISTS ") + DuckDbSqlGenerator.quote(name));
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
            executor.run("DROP VIEW IF EXISTS " + DuckDbSqlGenerator.quote(name));
        }
        // view does not exist: IF NOT EXISTS only matters in the branch above;
        // always create it here
        rewriter.rewrite(cv.query());
        String createSql = generator.generateCreateView(cv);
        executor.run(createSql);
        registerPhysical(db, name, "VIEW", createSql);
        return empty();
    }

    private DuckDbExecutor.QueryResult executeCreateDatabase(io.github.joegxx.localsql.ir.statement.CreateDatabase cd) throws SQLException {
        analyzer.analyze(cd);
        String name = cd.name();
        if (catalogService.catalog().getDatabase(name).isPresent()) {
            if (cd.ifNotExists()) return empty();
            throw new IllegalArgumentException("Database already exists: " + name);
        }
        catalogService.createDatabase(name, cd.comment(), cd.location(), cd.properties());
        return empty();
    }

    private DuckDbExecutor.QueryResult executeAlterDatabase(io.github.joegxx.localsql.ir.statement.AlterDatabase ad) throws SQLException {
        analyzer.analyze(ad);
        try {
            catalogService.alterDatabase(ad.name(), ad.properties(), ad.location());
        } catch (Exception e) {
            throw new SQLException("Failed to alter database " + ad.name() + ": " + e.getMessage(), e);
        }
        return empty();
    }

    private DuckDbExecutor.QueryResult executeDropDatabase(io.github.joegxx.localsql.ir.statement.DropDatabase dd) throws SQLException {
        analyzer.analyze(dd);
        String name = dd.name();
        var existing = catalogService.catalog().getDatabase(name);
        if (existing.isEmpty()) {
            if (dd.ifExists()) return empty();
            throw new IllegalArgumentException("Database does not exist: " + name);
        }
        List<Catalog.Table> tables = new ArrayList<>(catalogService.catalog().listTables(name));
        if (!tables.isEmpty() && !dd.cascade()) {
            throw new IllegalArgumentException("Database " + name + " is not empty. Use CASCADE to drop.");
        }
        for (Catalog.Table t : tables) {
            String drop = "VIEW".equalsIgnoreCase(t.tableType()) ? "DROP VIEW IF EXISTS " : "DROP TABLE IF EXISTS ";
            try { executor.run(drop + DuckDbSqlGenerator.quote(t.tableName())); } catch (Exception ignored) {}
        }
        try {
            catalogService.dropDatabase(name, dd.cascade());
        } catch (Exception e) {
            throw new SQLException("Failed to drop database " + name + ": " + e.getMessage(), e);
        }
        return empty();
    }

    private DuckDbExecutor.QueryResult executeRenameTable(io.github.joegxx.localsql.ir.statement.RenameTable rt) throws SQLException {
        analyzer.analyze(rt);
        String fromDb = databaseOf(rt.from());
        String fromTable = rt.from().get(rt.from().size() - 1);
        String toDb = databaseOf(rt.to());
        String toTable = rt.to().get(rt.to().size() - 1);
        Catalog.Table existing = catalogService.getTable(fromDb, fromTable).orElseThrow();
        boolean isView = "VIEW".equalsIgnoreCase(existing.tableType());
        if (!fromDb.equalsIgnoreCase(toDb)) {
            throw new IllegalArgumentException("RENAME across databases not supported: " + fromDb + " -> " + toDb);
        }
        String sql = generator.generateRenameTable(rt, isView);
        LOG.info("Translated to DuckDB SQL: {}", sql);
        executor.run(sql);
        try {
            catalogService.renameTable(fromDb, fromTable, toDb, toTable);
        } catch (Exception e) {
            throw new SQLException("Failed to rename catalog entry: " + e.getMessage(), e);
        }
        return empty();
    }

    private DuckDbExecutor.QueryResult executeSetTableProperties(io.github.joegxx.localsql.ir.statement.SetTableProperties stp) throws SQLException {
        analyzer.analyze(stp);
        String db = databaseOf(stp.tableName());
        String table = stp.tableName().get(stp.tableName().size() - 1);
        Catalog.Table t = catalogService.getTable(db, table).orElseThrow();
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>(t.properties());
        merged.putAll(stp.properties());
        Catalog.Table updated = new Catalog.Table(t.tableId(), t.name(), t.tableType(), t.columns(), t.location(), t.format(), t.comment(), merged, t.metadataJson());
        catalogService.catalog().updateTable(updated);
        try { if (catalogService.store() != null) catalogService.store().saveTable(updated); } catch (Exception e) { throw new SQLException(e.getMessage(), e); }
        return empty();
    }

    private DuckDbExecutor.QueryResult executeUnsetTableProperties(io.github.joegxx.localsql.ir.statement.UnsetTableProperties utp) throws SQLException {
        analyzer.analyze(utp);
        String db = databaseOf(utp.tableName());
        String table = utp.tableName().get(utp.tableName().size() - 1);
        Catalog.Table t = catalogService.getTable(db, table).orElseThrow();
        java.util.Map<String, String> props = new java.util.LinkedHashMap<>(t.properties());
        for (String key : utp.properties().keySet()) {
            if (!props.containsKey(key) && !utp.ifExists()) {
                throw new IllegalArgumentException("Property does not exist: " + key);
            }
            props.remove(key);
        }
        Catalog.Table updated = new Catalog.Table(t.tableId(), t.name(), t.tableType(), t.columns(), t.location(), t.format(), t.comment(), props, t.metadataJson());
        catalogService.catalog().updateTable(updated);
        try { if (catalogService.store() != null) catalogService.store().saveTable(updated); } catch (Exception e) { throw new SQLException(e.getMessage(), e); }
        return empty();
    }

    private DuckDbExecutor.QueryResult executeAddColumns(io.github.joegxx.localsql.ir.statement.AddColumns ac) throws SQLException {
        analyzer.analyze(ac);
        String db = databaseOf(ac.tableName());
        String table = ac.tableName().get(ac.tableName().size() - 1);
        for (var col : ac.columns()) {
            String sql = generator.generateAddColumn(ac.tableName(), col);
            LOG.info("Translated to DuckDB SQL: {}", sql);
            executor.run(sql);
        }
        refreshFromDescribe(db, table);
        return empty();
    }

    private DuckDbExecutor.QueryResult executeDropColumns(io.github.joegxx.localsql.ir.statement.DropColumns dc) throws SQLException {
        analyzer.analyze(dc);
        String db = databaseOf(dc.tableName());
        String table = dc.tableName().get(dc.tableName().size() - 1);
        for (String col : dc.columns()) {
            String sql = generator.generateDropColumn(dc.tableName(), col);
            LOG.info("Translated to DuckDB SQL: {}", sql);
            executor.run(sql);
        }
        refreshFromDescribe(db, table);
        return empty();
    }

    private DuckDbExecutor.QueryResult executeRenameColumn(io.github.joegxx.localsql.ir.statement.RenameColumn rc) throws SQLException {
        analyzer.analyze(rc);
        String db = databaseOf(rc.tableName());
        String table = rc.tableName().get(rc.tableName().size() - 1);
        String sql = generator.generateRenameColumn(rc.tableName(), rc.from(), rc.to());
        LOG.info("Translated to DuckDB SQL: {}", sql);
        executor.run(sql);
        refreshFromDescribe(db, table);
        return empty();
    }

    private DuckDbExecutor.QueryResult executeAlterColumn(io.github.joegxx.localsql.ir.statement.AlterColumn alc) throws SQLException {
        analyzer.analyze(alc);
        String db = databaseOf(alc.tableName());
        String table = alc.tableName().get(alc.tableName().size() - 1);
        String targetCol = alc.column();
        if (alc.newName() != null && !alc.newName().equalsIgnoreCase(alc.column())) {
            String sqlRename = generator.generateRenameColumn(alc.tableName(), alc.column(), alc.newName());
            LOG.info("Translated to DuckDB SQL: {}", sqlRename);
            executor.run(sqlRename);
            targetCol = alc.newName();
            // refresh after rename so subsequent alters see new name
            refreshFromDescribe(db, table);
        }
        if (alc.newType() != null) {
            String sql = generator.generateAlterColumnType(alc.tableName(), targetCol, alc.newType());
            LOG.info("Translated to DuckDB SQL: {}", sql);
            executor.run(sql);
        }
        if (alc.setNotNull() != null) {
            String sql = generator.generateAlterColumnSetNotNull(alc.tableName(), targetCol, alc.setNotNull());
            LOG.info("Translated to DuckDB SQL: {}", sql);
            executor.run(sql);
        }
        if (alc.newComment() != null) {
            // DuckDB COMMENT ON COLUMN not critical for local; store in catalog comment
            // We will update catalog column comment via refresh, but refresh loses comment from DESCRIBE
            // So manually patch catalog entry
            Catalog.Table t = catalogService.getTable(db, table).orElseThrow();
            List<CatalogService.ColumnDef> defs = new ArrayList<>();
            for (Catalog.Column c : t.columns()) {
                if (c.name().equalsIgnoreCase(targetCol)) {
                    defs.add(new CatalogService.ColumnDef(c.name(), c.type(), c.nullable(), alc.newComment(), c.defaultValue(), c.expression()));
                } else {
                    defs.add(new CatalogService.ColumnDef(c.name(), c.type(), c.nullable(), c.comment(), c.defaultValue(), c.expression()));
                }
            }
            try { catalogService.refreshTable(db, table, defs); } catch (Exception e) { throw new SQLException(e.getMessage(), e); }
        }
        if (alc.newType() != null || alc.setNotNull() != null) {
            refreshFromDescribe(db, table);
            // if comment was also changed, our refresh will overwrite it; re-apply
            if (alc.newComment() != null) {
                Catalog.Table t = catalogService.getTable(db, table).orElseThrow();
                List<CatalogService.ColumnDef> defs = new ArrayList<>();
                for (Catalog.Column c : t.columns()) {
                    if (c.name().equalsIgnoreCase(targetCol)) {
                        defs.add(new CatalogService.ColumnDef(c.name(), c.type(), c.nullable(), alc.newComment(), c.defaultValue(), c.expression()));
                    } else {
                        defs.add(new CatalogService.ColumnDef(c.name(), c.type(), c.nullable(), c.comment(), c.defaultValue(), c.expression()));
                    }
                }
                try { catalogService.refreshTable(db, table, defs); } catch (Exception e) { throw new SQLException(e.getMessage(), e); }
            }
        }
        return empty();
    }

    private DuckDbExecutor.QueryResult executeReplaceColumns(io.github.joegxx.localsql.ir.statement.ReplaceColumns rpc) throws SQLException {
        analyzer.analyze(rpc);
        String db = databaseOf(rpc.tableName());
        String table = rpc.tableName().get(rpc.tableName().size() - 1);
        Catalog.Table old = catalogService.getTable(db, table).orElseThrow();
        // For overlapping columns, try to keep data: ADD missing, DROP extra, ALTER type for changed
        java.util.Set<String> newNames = new java.util.HashSet<>();
        java.util.Map<String, io.github.joegxx.localsql.ir.type.DataType> newTypes = new java.util.HashMap<>();
        for (var c : rpc.columns()) { newNames.add(c.name().toLowerCase()); newTypes.put(c.name().toLowerCase(), c.type()); }
        // Drop columns not in new schema
        for (Catalog.Column c : List.copyOf(old.columns())) {
            if (!newNames.contains(c.name().toLowerCase())) {
                executor.run(generator.generateDropColumn(rpc.tableName(), c.name()));
            }
        }
        // Add columns not in old schema
        java.util.Set<String> oldNames = new java.util.HashSet<>();
        for (Catalog.Column c : old.columns()) oldNames.add(c.name().toLowerCase());
        for (var c : rpc.columns()) {
            if (!oldNames.contains(c.name().toLowerCase())) {
                executor.run(generator.generateAddColumn(rpc.tableName(), c));
            } else {
                Catalog.Column oc = old.columns().stream().filter(col -> col.name().equalsIgnoreCase(c.name())).findFirst().orElse(null);
                // Full type equality (record equals): DECIMAL(10,2) vs DECIMAL(12,3)
                // or TINYINT vs INT must trigger a physical ALTER even though the
                // type *names* are related.
                if (oc != null && !oc.type().equals(c.type())) {
                    executor.run(generator.generateAlterColumnType(rpc.tableName(), c.name(), c.type()));
                }
                if (oc != null && oc.nullable() != c.nullable()) {
                    executor.run(generator.generateAlterColumnSetNotNull(rpc.tableName(), c.name(), !c.nullable()));
                }
            }
        }
        refreshFromDescribe(db, table);
        // patch comments/nullability from new definition
        Catalog.Table refreshed = catalogService.getTable(db, table).orElseThrow();
        List<CatalogService.ColumnDef> patched = new ArrayList<>();
        for (Catalog.Column c : refreshed.columns()) {
            var nc = rpc.columns().stream().filter(col -> col.name().equalsIgnoreCase(c.name())).findFirst().orElse(null);
            if (nc != null) {
                patched.add(new CatalogService.ColumnDef(c.name(), c.type(), nc.nullable(), nc.comment(), c.defaultValue(), c.expression()));
            } else {
                patched.add(new CatalogService.ColumnDef(c.name(), c.type(), c.nullable(), c.comment(), c.defaultValue(), c.expression()));
            }
        }
        try { catalogService.refreshTable(db, table, patched); } catch (Exception e) { throw new SQLException(e.getMessage(), e); }
        return empty();
    }

    private void refreshFromDescribe(String db, String table) throws SQLException {
        try {
            DuckDbExecutor.QueryResult desc = executor.execute("DESCRIBE " + DuckDbSqlGenerator.quote(table));
            Catalog.Table old = catalogService.getTable(db, table).orElse(null);
            java.util.Map<String, String> oldComments = new java.util.HashMap<>();
            if (old != null) {
                for (Catalog.Column c : old.columns()) {
                    if (c.comment() != null) oldComments.put(c.name().toLowerCase(), c.comment());
                }
            }
            List<CatalogService.ColumnDef> cols = new ArrayList<>();
            for (List<Object> row : desc.rows()) {
                boolean nullable = row.size() <= 2 || !"NO".equalsIgnoreCase(String.valueOf(row.get(2)));
                String colName = String.valueOf(row.get(0));
                String comment = oldComments.get(colName.toLowerCase());
                cols.add(new CatalogService.ColumnDef(
                        colName,
                        Catalog.parseDataType(String.valueOf(row.get(1))),
                        nullable, comment));
            }
            catalogService.refreshTable(db, table, cols);
        } catch (Exception e) {
            throw new SQLException("Failed to refresh metadata for " + table + ": " + e.getMessage(), e);
        }
    }

    private void registerPhysical(String db, String table, String type, String createSql) throws SQLException {
        try {
            DuckDbExecutor.QueryResult desc = executor.execute("DESCRIBE " + DuckDbSqlGenerator.quote(table));
            List<CatalogService.ColumnDef> cols = new ArrayList<>();
            for (List<Object> row : desc.rows()) {
                // DESCRIBE columns: 0=name 1=type 2=null(YES/NO) 3=key 4=default 5=extra
                boolean nullable = row.size() <= 2 || !"NO".equalsIgnoreCase(String.valueOf(row.get(2)));
                cols.add(new CatalogService.ColumnDef(
                        String.valueOf(row.get(0)),
                        Catalog.parseDataType(String.valueOf(row.get(1))),
                        nullable, null));
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
        return DuckDbSqlGenerator.quote(parts.get(parts.size() - 1));
    }

    private static String databaseOf(List<String> parts) {
        return parts.size() >= 2 ? parts.get(parts.size() - 2) : "default";
    }
}
