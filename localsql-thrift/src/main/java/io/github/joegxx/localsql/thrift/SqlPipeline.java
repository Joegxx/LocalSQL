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
import io.github.joegxx.localsql.rewrite.UdfProjectionSplitter;
import io.github.joegxx.localsql.rewrite.UdtfGenerateSplitter;
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
    private final DuckDbSqlGenerator generator;
    private final SparkSqlParser parser = new SparkSqlParser();
    private final SparkAstBuilder astBuilder = new SparkAstBuilder();
    private final SemanticAnalyzer analyzer;
    private final RewriteEngine rewriter = new RewriteEngine();
    private final UdfSupport udfSupport;
    private final UdfProjectionSplitter udfSplitter;
    private final UdtfGenerateSplitter udtfSplitter;
    private final java.util.concurrent.atomic.AtomicLong udtfTempTableCounter =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Session database for resolving unqualified table names (USE db; CREATE
     * TABLE t must register db.t). Set at the start of execute() — safe because
     * execute is synchronized and all statement handlers run on that call stack.
     */
    private String sessionDatabase = "default";

    SqlPipeline(CatalogService catalogService, DuckDbExecutor executor) {
        this(catalogService, executor, null, null);
    }

    SqlPipeline(CatalogService catalogService, DuckDbExecutor executor, UdfSupport udfSupport) {
        this(catalogService, executor, udfSupport, null);
    }

    SqlPipeline(CatalogService catalogService, DuckDbExecutor executor, UdfSupport udfSupport,
                DuckDbSqlGenerator generator) {
        this.catalogService = catalogService;
        this.executor = executor;
        this.analyzer = new SemanticAnalyzer(catalogService.catalog());
        this.udfSupport = udfSupport;
        this.udfSplitter = udfSupport == null ? null : new UdfProjectionSplitter(udfSupport.registry());
        this.udtfSplitter = udfSupport == null ? null : new UdtfGenerateSplitter(udfSupport.registry());
        this.generator = generator == null ? new DuckDbSqlGenerator() : generator;
    }

    /**
     * Serializes statement execution: INSERT OVERWRITE runs a BEGIN/DELETE/
     * INSERT/COMMIT sequence across several Statements on the shared DuckDB
     * connection, and interleaved requests from the multi-threaded Thrift
     * server would corrupt each other's transactions. A local single-user
     * runtime trades concurrency for correctness here.
     */
    synchronized DuckDbExecutor.QueryResult execute(String sql) throws SQLException {
        return execute(sql, "default");
    }

    /** @param currentDatabase session database for unqualified table names. */
    synchronized DuckDbExecutor.QueryResult execute(String sql, String currentDatabase) throws SQLException {
        this.sessionDatabase = currentDatabase == null || currentDatabase.isEmpty()
                ? "default" : currentDatabase;
        Statement stmt = astBuilder.buildStatement(sql, s -> parser.parseStatement(s));
        if (stmt instanceof QueryStatement q) {
            Relation rel = q.query();
            analyzer.analyze(rel, sessionDatabase);
            rewriter.rewrite(rel);
            if (udtfSplitter != null) {
                UdtfGenerateSplitter.UdtfPlan udtfPlan = udtfSplitter.trySplit(rel);
                if (udtfPlan != null) return executeWithUdtf(udtfPlan);
            }
            if (udfSplitter != null) {
                UdfProjectionSplitter.SplitPlan plan = udfSplitter.trySplit(rel);
                if (plan != null) return executeWithUdfSplit(plan);
            }
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

    @FunctionalInterface
    private interface SqlWork {
        void run() throws Exception;
    }

    /**
     * Executes a UDTF (LATERAL VIEW) plan: stage 1 materializes source
     * columns plus UDTF argument expressions, the gateway expands rows,
     * everything lands in a DuckDB temp table and the original query is
     * rebuilt around a scan of it, so outer filters/ORDER BY/LIMIT keep
     * their normal semantics.
     */
    private DuckDbExecutor.QueryResult executeWithUdtf(UdtfGenerateSplitter.UdtfPlan plan) throws SQLException {
        String stage1Sql = generator.generate(plan.stage1());
        LOG.info("UDTF stage-1 SQL: {}", stage1Sql);
        DuckDbExecutor.QueryResult stage1 = executor.execute(stage1Sql);
        try {
            io.github.joegxx.localsql.udf.api.UdfBatch input = sliceRows(stage1, plan.argIndexes());
            io.github.joegxx.localsql.udf.api.UdtfResult out =
                    udfSupport.gateway().executeUdtf(plan.definition(), input, udfSupport.timeoutMillis());
            LOG.info("UDTF executed: {} input rows -> {} expanded rows",
                    input.rowCount(), out.rows().size());

            List<String> udtfNames = plan.outputNames().isEmpty() ? out.columnNames() : plan.outputNames();
            if (udtfNames.size() != out.columnNames().size()) {
                throw new SQLException("UDTF " + plan.definition().id() + " produced " + out.columnNames().size()
                        + " columns but LATERAL VIEW declared " + udtfNames.size());
            }
            List<String> tempCols = new ArrayList<>(plan.sourceNames());
            for (String n : udtfNames) {
                if (!tempCols.add(n)) {
                    throw new SQLException("Duplicate column name in LATERAL VIEW result: " + n);
                }
            }
            String tempName = "__udtf_tmp_" + udtfTempTableCounter.incrementAndGet();
            createUdtfTempTable(tempName, tempCols, stage1, stage1Sql, out);
            insertUdtfRows(tempName, plan.sourceNames().size(), stage1, out);

            Relation rebuilt = udtfSplitter.rebuild(
                    udtfSplitter.expandedScan(tempName, tempCols), plan);
            String finalSql = generator.generate(rebuilt);
            LOG.info("UDTF final SQL: {}", finalSql);
            return executor.execute(finalSql);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("UDTF execution failed: " + e.getMessage(), e);
        }
    }

    private void createUdtfTempTable(String tempName, List<String> columns,
                                     DuckDbExecutor.QueryResult stage1, String stage1Sql,
                                     io.github.joegxx.localsql.udf.api.UdtfResult out) throws SQLException {
        List<String> types = new ArrayList<>();
        DuckDbExecutor.QueryResult described = executor.execute("DESCRIBE " + stage1Sql);
        int sourceCount = columns.size() - out.columnNames().size();
        for (int i = 0; i < sourceCount; i++) {
            types.add(i < described.rows().size() ? String.valueOf(described.rows().get(i).get(1)) : "VARCHAR");
        }
        types.addAll(out.columnTypes());
        StringBuilder ddl = new StringBuilder("CREATE OR REPLACE TEMP TABLE ").append(DuckDbSqlGenerator.quote(tempName)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) ddl.append(", ");
            ddl.append(DuckDbSqlGenerator.quote(columns.get(i))).append(' ').append(types.get(i));
        }
        ddl.append(")");
        executor.run(ddl.toString());
    }

    private void insertUdtfRows(String tempName, int sourceCount,
                                DuckDbExecutor.QueryResult stage1,
                                io.github.joegxx.localsql.udf.api.UdtfResult out) throws SQLException {
        final int chunk = 400;
        List<List<Object>> expanded = new ArrayList<>(out.rows().size());
        for (int j = 0; j < out.rows().size(); j++) {
            List<Object> row = new ArrayList<>(sourceCount + out.columnNames().size());
            List<Object> src = stage1.rows().get(out.sourceRows().get(j));
            for (int c = 0; c < sourceCount; c++) row.add(c < src.size() ? src.get(c) : null);
            row.addAll(out.rows().get(j));
            expanded.add(row);
        }
        for (int from = 0; from < expanded.size(); from += chunk) {
            int to = Math.min(from + chunk, expanded.size());
            StringBuilder ins = new StringBuilder("INSERT INTO ")
                    .append(DuckDbSqlGenerator.quote(tempName)).append(" VALUES ");
            for (int r = from; r < to; r++) {
                if (r > from) ins.append(", ");
                ins.append("(");
                List<Object> row = expanded.get(r);
                for (int c = 0; c < row.size(); c++) {
                    if (c > 0) ins.append(", ");
                    ins.append(sqlLiteral(row.get(c)));
                }
                ins.append(")");
            }
            executor.run(ins.toString());
        }
    }

    private static String sqlLiteral(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Boolean b) return b ? "TRUE" : "FALSE";
        if (value instanceof Number) return value.toString();
        String s = value.toString().replace("'", "''");
        return "'" + s + "'";
    }

    /**
     * Executes a UDF-split plan: run stage 1 (UDF calls replaced by their
     * argument expressions), compute each registered UDF through the gateway
     * in batches, then assemble the final result set in Java. Row order is
     * preserved, so the UDF columns align with stage-1 rows by index.
     */
    private DuckDbExecutor.QueryResult executeWithUdfSplit(UdfProjectionSplitter.SplitPlan plan) throws SQLException {
        String stage1Sql = generator.generate(plan.stage1());
        LOG.info("UDF stage-1 SQL: {}", stage1Sql);
        DuckDbExecutor.QueryResult stage1 = executor.execute(stage1Sql);
        try {
            List<String> outputNames = new ArrayList<>();
            List<List<Object>> outputColumns = new ArrayList<>();
            for (UdfProjectionSplitter.OutputSpec spec : plan.outputs()) {
                if (spec instanceof UdfProjectionSplitter.OutputSpec.Column c) {
                    outputNames.add(stage1.columns().get(c.stage1Index()));
                    outputColumns.add(columnAt(stage1, c.stage1Index()));
                } else if (spec instanceof UdfProjectionSplitter.OutputSpec.Udf u) {
                    io.github.joegxx.localsql.udf.api.UdfBatch input =
                            sliceRows(stage1, u.argIndexes());
                    io.github.joegxx.localsql.udf.api.UdfBatch out =
                            udfSupport.gateway().execute(u.definition(), input, udfSupport.timeoutMillis());
                    outputNames.add(u.name());
                    outputColumns.add(firstColumn(out));
                    LOG.info("UDF executed: {} rows={} -> {} result rows",
                            u.definition().id(), input.rowCount(), out.rowCount());
                }
            }
            List<List<Object>> rows = new ArrayList<>(stage1.rows().size());
            for (int r = 0; r < stage1.rows().size(); r++) {
                List<Object> row = new ArrayList<>(outputColumns.size());
                for (List<Object> col : outputColumns) row.add(r < col.size() ? col.get(r) : null);
                rows.add(row);
            }
            return new DuckDbExecutor.QueryResult(outputNames, rows);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("UDF execution failed: " + e.getMessage(), e);
        }
    }

    private static List<Object> columnAt(DuckDbExecutor.QueryResult result, int index) {
        List<Object> out = new ArrayList<>(result.rows().size());
        for (List<Object> row : result.rows()) out.add(index < row.size() ? row.get(index) : null);
        return out;
    }

    private static io.github.joegxx.localsql.udf.api.UdfBatch sliceRows(
            DuckDbExecutor.QueryResult result, List<Integer> indexes) {
        List<List<Object>> rows = new ArrayList<>(result.rows().size());
        for (List<Object> row : result.rows()) {
            List<Object> slice = new ArrayList<>(indexes.size());
            for (int i : indexes) slice.add(i < row.size() ? row.get(i) : null);
            rows.add(slice);
        }
        return new io.github.joegxx.localsql.udf.api.UdfBatch(
                indexes.stream().map(i -> "__udf_arg_" + i).toList(), rows);
    }

    private static List<Object> firstColumn(io.github.joegxx.localsql.udf.api.UdfBatch batch) {
        List<Object> out = new ArrayList<>(batch.rowCount());
        for (List<Object> row : batch.rows()) out.add(row.isEmpty() ? null : row.get(0));
        return out;
    }

    /**
     * Run physical DuckDB statements together with catalog-store writes inside
     * one DuckDB transaction: a mid-sequence failure rolls back both the
     * physical objects and the persisted metadata, so they cannot drift apart
     * (previously a failed multi-column ALTER or a store failure after a
     * physical RENAME left the two layers inconsistent). In-memory catalog
     * mutations are not rolled back — they heal on the next store reload; the
     * pipeline is synchronized, so no other statement observes the intermediate
     * state.
     */
    private void inTransaction(SqlWork work) throws SQLException {
        executor.run("BEGIN");
        try {
            work.run();
            executor.run("COMMIT");
        } catch (Exception e) {
            try { executor.run("ROLLBACK"); } catch (Exception ignored) {}
            if (e instanceof SQLException se) throw se;
            if (e instanceof RuntimeException re) throw re;
            throw new SQLException(e.getMessage(), e);
        }
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
            inTransaction(() -> {
                catalogService.dropTable(db, name);
                executor.run((dr.view() ? "DROP VIEW IF EXISTS " : "DROP TABLE IF EXISTS ")
                        + DuckDbSqlGenerator.quote(name));
            });
        } catch (SQLException e) {
            throw new SQLException("Failed to drop catalog entry for " + name + ": " + e.getMessage(), e);
        }
        return empty();
    }

    private DuckDbExecutor.QueryResult executeInsert(Insert i) throws SQLException {
        analyzer.analyze(i, sessionDatabase);
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
        analyzer.analyze(ct, sessionDatabase);
        String db = databaseOf(ct.tableName());
        // Unquoted logical name: this feeds catalog metadata, not SQL text.
        String table = ct.tableName().get(ct.tableName().size() - 1);
        Catalog.Table existing = catalogService.getTable(db, table).orElse(null);
        if (existing != null) {
            if (ct.ifNotExists()) return empty();
            throw new IllegalArgumentException(
                    "Table already exists: " + String.join(".", ct.tableName()));
        }
        // All physical tables live in DuckDB's single main schema: a bare name
        // cannot be shared by two logical tables even across databases.
        for (Catalog.Table other : catalogService.catalog().listTables(null)) {
            if (other.tableName().equalsIgnoreCase(table)) {
                throw new IllegalArgumentException(
                        "A table named " + table + " already exists in database "
                                + other.database() + " (tables from all databases share one physical namespace)");
            }
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
            inTransaction(() -> {
                executor.run(createSql);
                registerPhysical(db, table, "TABLE", createSql);
                patchColumnComments(db, table, commentMap(cols));
            });
            return empty();
        }
        if (ct.query() == null) {
            createSql = generator.generateCreateTable(ct);
            inTransaction(() -> {
                executor.run(createSql);
                registerPhysical(db, table, "TABLE", createSql);
                patchColumnComments(db, table, commentMap(ct.columns()));
            });
            return empty();
        }
        rewriter.rewrite(ct.query());
        if (ct.columns().isEmpty()) {
            createSql = generator.generateCreateTableAs(ct);
            inTransaction(() -> {
                executor.run(createSql);
                registerPhysical(db, table, "TABLE", createSql);
            });
            return empty();
        }
        // Explicit column list + AS query: create the (empty) table with the
        // declared schema, then populate it through the INSERT path.
        createSql = generator.generateCreateTable(ct);
        DuckDbExecutor.QueryResult[] inserted = new DuckDbExecutor.QueryResult[1];
        inTransaction(() -> {
            executor.run(createSql);
            registerPhysical(db, table, "TABLE", createSql);
            patchColumnComments(db, table, commentMap(ct.columns()));
            inserted[0] = executeInsert(new Insert(ct.tableName(), false, null, ct.query()));
        });
        return inserted[0];
    }

    private DuckDbExecutor.QueryResult executeCreateView(CreateView cv) throws SQLException {
        analyzer.analyze(cv, sessionDatabase);
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
        }
        // view does not exist: IF NOT EXISTS only matters in the branch above;
        // always create it here
        rewriter.rewrite(cv.query());
        String createSql = generator.generateCreateView(cv);
        final boolean replacing = existing != null;
        inTransaction(() -> {
            if (replacing) {
                try {
                    catalogService.dropTable(db, name);
                } catch (Exception e) {
                    throw new SQLException("Failed to drop view " + name + ": " + e.getMessage(), e);
                }
                executor.run("DROP VIEW IF EXISTS " + DuckDbSqlGenerator.quote(name));
            }
            executor.run(createSql);
            registerPhysical(db, name, "VIEW", createSql);
        });
        return empty();
    }

    private DuckDbExecutor.QueryResult executeCreateDatabase(io.github.joegxx.localsql.ir.statement.CreateDatabase cd) throws SQLException {
        analyzer.analyze(cd, sessionDatabase);
        String name = cd.name();
        if (catalogService.catalog().getDatabase(name).isPresent()) {
            if (cd.ifNotExists()) return empty();
            throw new IllegalArgumentException("Database already exists: " + name);
        }
        catalogService.createDatabase(name, cd.comment(), cd.location(), cd.properties());
        return empty();
    }

    private DuckDbExecutor.QueryResult executeAlterDatabase(io.github.joegxx.localsql.ir.statement.AlterDatabase ad) throws SQLException {
        analyzer.analyze(ad, sessionDatabase);
        try {
            catalogService.alterDatabase(ad.name(), ad.properties(), ad.location());
        } catch (Exception e) {
            throw new SQLException("Failed to alter database " + ad.name() + ": " + e.getMessage(), e);
        }
        return empty();
    }

    private DuckDbExecutor.QueryResult executeDropDatabase(io.github.joegxx.localsql.ir.statement.DropDatabase dd) throws SQLException {
        analyzer.analyze(dd, sessionDatabase);
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
        try {
            inTransaction(() -> {
                for (Catalog.Table t : tables) {
                    String drop = "VIEW".equalsIgnoreCase(t.tableType()) ? "DROP VIEW IF EXISTS " : "DROP TABLE IF EXISTS ";
                    try { executor.run(drop + DuckDbSqlGenerator.quote(t.tableName())); } catch (Exception ignored) {}
                }
                catalogService.dropDatabase(name, dd.cascade());
            });
        } catch (SQLException e) {
            throw new SQLException("Failed to drop database " + name + ": " + e.getMessage(), e);
        }
        return empty();
    }

    private DuckDbExecutor.QueryResult executeRenameTable(io.github.joegxx.localsql.ir.statement.RenameTable rt) throws SQLException {
        analyzer.analyze(rt, sessionDatabase);
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
        try {
            inTransaction(() -> {
                executor.run(sql);
                catalogService.renameTable(fromDb, fromTable, toDb, toTable);
            });
        } catch (SQLException e) {
            throw new SQLException("Failed to rename catalog entry: " + e.getMessage(), e);
        }
        return empty();
    }

    private DuckDbExecutor.QueryResult executeSetTableProperties(io.github.joegxx.localsql.ir.statement.SetTableProperties stp) throws SQLException {
        analyzer.analyze(stp, sessionDatabase);
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
        analyzer.analyze(utp, sessionDatabase);
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
        analyzer.analyze(ac, sessionDatabase);
        String db = databaseOf(ac.tableName());
        String table = ac.tableName().get(ac.tableName().size() - 1);
        inTransaction(() -> {
            for (var col : ac.columns()) {
                String sql = generator.generateAddColumn(ac.tableName(), col);
                LOG.info("Translated to DuckDB SQL: {}", sql);
                executor.run(sql);
            }
            refreshFromDescribe(db, table);
            patchColumnComments(db, table, colDefComments(ac.columns()));
        });
        return empty();
    }

    private DuckDbExecutor.QueryResult executeDropColumns(io.github.joegxx.localsql.ir.statement.DropColumns dc) throws SQLException {
        analyzer.analyze(dc, sessionDatabase);
        String db = databaseOf(dc.tableName());
        String table = dc.tableName().get(dc.tableName().size() - 1);
        inTransaction(() -> {
            for (String col : dc.columns()) {
                String sql = generator.generateDropColumn(dc.tableName(), col);
                LOG.info("Translated to DuckDB SQL: {}", sql);
                executor.run(sql);
            }
            refreshFromDescribe(db, table);
        });
        return empty();
    }

    private DuckDbExecutor.QueryResult executeRenameColumn(io.github.joegxx.localsql.ir.statement.RenameColumn rc) throws SQLException {
        analyzer.analyze(rc, sessionDatabase);
        String db = databaseOf(rc.tableName());
        String table = rc.tableName().get(rc.tableName().size() - 1);
        String sql = generator.generateRenameColumn(rc.tableName(), rc.from(), rc.to());
        LOG.info("Translated to DuckDB SQL: {}", sql);
        inTransaction(() -> {
            executor.run(sql);
            refreshFromDescribe(db, table);
        });
        return empty();
    }

    private DuckDbExecutor.QueryResult executeAlterColumn(io.github.joegxx.localsql.ir.statement.AlterColumn alc) throws SQLException {
        analyzer.analyze(alc, sessionDatabase);
        String db = databaseOf(alc.tableName());
        String table = alc.tableName().get(alc.tableName().size() - 1);
        String targetCol = alc.column();
        // Comments are logical-only (DuckDB DESCRIBE cannot report them), so the
        // desired value must survive the refresh; on rename also carry over the
        // old comment, which refreshFromDescribe would lose (it matches by name).
        String desiredComment = alc.newComment();
        final boolean renamed = alc.newName() != null && !alc.newName().equalsIgnoreCase(alc.column());
        if (renamed) {
            if (desiredComment == null) {
                desiredComment = catalogService.getTable(db, table).stream()
                        .flatMap(t -> t.columns().stream())
                        .filter(c -> c.name().equalsIgnoreCase(alc.column()) && c.comment() != null)
                        .map(Catalog.Column::comment).findFirst().orElse(null);
            }
            targetCol = alc.newName();
        }
        final String col = targetCol;
        final String comment = desiredComment;
        inTransaction(() -> {
            if (renamed) {
                String sqlRename = generator.generateRenameColumn(alc.tableName(), alc.column(), alc.newName());
                LOG.info("Translated to DuckDB SQL: {}", sqlRename);
                executor.run(sqlRename);
            }
            if (alc.newType() != null) {
                String sql = generator.generateAlterColumnType(alc.tableName(), col, alc.newType());
                LOG.info("Translated to DuckDB SQL: {}", sql);
                executor.run(sql);
            }
            if (alc.setNotNull() != null) {
                String sql = generator.generateAlterColumnSetNotNull(alc.tableName(), col, alc.setNotNull());
                LOG.info("Translated to DuckDB SQL: {}", sql);
                executor.run(sql);
            }
            if (renamed || alc.newType() != null || alc.setNotNull() != null) {
                refreshFromDescribe(db, table);
            }
            if (comment != null) {
                patchColumnComments(db, table, java.util.Map.of(col.toLowerCase(), comment));
            }
        });
        return empty();
    }

    private DuckDbExecutor.QueryResult executeReplaceColumns(io.github.joegxx.localsql.ir.statement.ReplaceColumns rpc) throws SQLException {
        analyzer.analyze(rpc, sessionDatabase);
        String db = databaseOf(rpc.tableName());
        String table = rpc.tableName().get(rpc.tableName().size() - 1);
        Catalog.Table old = catalogService.getTable(db, table).orElseThrow();
        // For overlapping columns, try to keep data: ADD missing, DROP extra, ALTER type for changed
        java.util.Set<String> newNames = new java.util.HashSet<>();
        for (var c : rpc.columns()) newNames.add(c.name().toLowerCase());
        java.util.Set<String> oldNames = new java.util.HashSet<>();
        for (Catalog.Column c : old.columns()) oldNames.add(c.name().toLowerCase());
        // Nullability is physical (SET/DROP NOT NULL, seen by DESCRIBE).
        // REPLACE defines a complete new schema: every column's comment comes
        // solely from the new definition — stale old comments must be cleared
        // (refreshFromDescribe preserved them), so pass nulls explicitly.
        java.util.Map<String, String> comments = new java.util.HashMap<>();
        for (var c : rpc.columns()) comments.put(c.name().toLowerCase(), c.comment());
        inTransaction(() -> {
            // Drop columns not in new schema
            for (Catalog.Column c : List.copyOf(old.columns())) {
                if (!newNames.contains(c.name().toLowerCase())) {
                    executor.run(generator.generateDropColumn(rpc.tableName(), c.name()));
                }
            }
            // Add columns not in old schema; alter overlapping columns whose
            // type (full record equality: DECIMAL(10,2) vs DECIMAL(12,3),
            // TINYINT vs INT) or nullability changed.
            for (var c : rpc.columns()) {
                if (!oldNames.contains(c.name().toLowerCase())) {
                    executor.run(generator.generateAddColumn(rpc.tableName(), c));
                } else {
                    Catalog.Column oc = old.columns().stream().filter(col -> col.name().equalsIgnoreCase(c.name())).findFirst().orElse(null);
                    if (oc != null && !oc.type().equals(c.type())) {
                        executor.run(generator.generateAlterColumnType(rpc.tableName(), c.name(), c.type()));
                    }
                    if (oc != null && oc.nullable() != c.nullable()) {
                        executor.run(generator.generateAlterColumnSetNotNull(rpc.tableName(), c.name(), !c.nullable()));
                    }
                }
            }
            refreshFromDescribe(db, table);
            patchColumnComments(db, table, comments);
        });
        return empty();
    }

    /** Comments by lowercase column name for a CREATE TABLE column list (nulls skipped). */
    private static java.util.Map<String, String> commentMap(List<CreateTable.ColumnDef> defs) {
        java.util.Map<String, String> comments = new java.util.HashMap<>();
        for (CreateTable.ColumnDef d : defs) {
            if (d.comment() != null) comments.put(d.name().toLowerCase(), d.comment());
        }
        return comments;
    }

    /** Comments by lowercase column name for an ADD/REPLACE COLUMNS definition list. */
    private static java.util.Map<String, String> colDefComments(List<io.github.joegxx.localsql.ir.statement.AddColumns.ColDef> defs) {
        java.util.Map<String, String> comments = new java.util.HashMap<>();
        for (var d : defs) {
            if (d.comment() != null) comments.put(d.name().toLowerCase(), d.comment());
        }
        return comments;
    }

    /**
     * Re-apply logical-only column comments on top of the current catalog entry.
     * Physical sources (DESCRIBE) cannot report comments, so after any
     * refreshFromDescribe the desired comments must be written back explicitly.
     * Map entries override (a null value clears the comment); columns absent
     * from the map keep their current comment.
     */
    private void patchColumnComments(String db, String table, java.util.Map<String, String> comments) throws SQLException {
        if (comments.isEmpty()) return;
        Catalog.Table t = catalogService.getTable(db, table).orElseThrow();
        List<CatalogService.ColumnDef> defs = new ArrayList<>();
        for (Catalog.Column c : t.columns()) {
            String key = c.name().toLowerCase();
            String comment = comments.containsKey(key) ? comments.get(key) : c.comment();
            defs.add(new CatalogService.ColumnDef(c.name(), c.type(), c.nullable(),
                    comment, c.defaultValue(), c.expression()));
        }
        try {
            catalogService.refreshTable(db, table, defs);
        } catch (Exception e) {
            throw new SQLException("Failed to update comments for " + table + ": " + e.getMessage(), e);
        }
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

    /** Unqualified single-part names resolve against the session database. */
    private String databaseOf(List<String> parts) {
        return parts.size() == 1 ? sessionDatabase : parts.get(parts.size() - 2);
    }
}
