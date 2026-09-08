package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.Catalog;
import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles the admin/introspection statements database IDEs (DataGrip, DBeaver)
 * issue over HiveServer2 that are not SELECT queries: SHOW DATABASES/SCHEMAS,
 * SHOW TABLES, SHOW COLUMNS, DESCRIBE and USE. The ANTLR pipeline is DQL-only,
 * so these are dispatched textually against the catalog before parsing.
 */
final class AdminStatementHandler {

    record Outcome(DuckDbExecutor.QueryResult result, String newCurrentDatabase) {}

    private static final Pattern SHOW_DATABASES =
            Pattern.compile("(?i)^SHOW\\s+(DATABASES|SCHEMAS)(\\s+LIKE\\s+'([^']*)')?\\s*$");
    private static final Pattern SHOW_TABLES =
            Pattern.compile("(?i)^SHOW\\s+TABLES(\\s+(IN|FROM)\\s+([A-Za-z_][\\w]*))?((\\s+LIKE\\s+'([^']*)'))?\\s*$");
    private static final Pattern SHOW_COLUMNS =
            Pattern.compile("(?i)^SHOW\\s+COLUMNS\\s+(IN|FROM)\\s+([A-Za-z_][\\w]*(\\.[A-Za-z_][\\w]*)*)\\s*$");
    private static final Pattern DESCRIBE =
            Pattern.compile("(?i)^(DESCRIBEFORMATTED|DESCRIBE|DESC)(\\s+(EXTENDED|FORMATTED))?(\\s+TABLE)?\\s+((?:[A-Za-z_][\\w]*|`[^`]+`|\"[^\"]+\")(?:\\.(?:[A-Za-z_][\\w]*|`[^`]+`|\"[^\"]+\"))*)\\s*$");
    private static final String IDENT = "(?:[A-Za-z_][\\w]*|`(?:[^`]|``)+`|\"(?:[^\"]|\"\")+\"|'[^']*')";
    private static final String QUALIFIED = IDENT + "(?:\\." + IDENT + ")*";
    private static final Pattern SHOW_CREATE =
            Pattern.compile("(?i)^SHOW\\s+CREATE\\s+(TABLE|VIEW|DATABASE|SCHEMA)\\s+(" + QUALIFIED + ")(?:\\s+AS\\s+SEMI)?\\s*$");
    private static final Pattern SHOW_INDEXES =
            Pattern.compile("(?i)^SHOW\\s+(?:FORMATTED\\s+)?(?:EXTENDED\\s+)?INDEX(?:ES)?(\\s+(?:IN|FROM|ON)\\s+" + QUALIFIED + ")?\\s*$");
    private static final Pattern USE =
            Pattern.compile("(?i)^USE\\s+(?:(?:DATABASE)\\s+)?((?:[A-Za-z_][\\w]*|`(?:[^`]|``)+`|\"(?:[^\"]|\"\")+\")(?:\\.(?:[A-Za-z_][\\w]*|`(?:[^`]|``)+`|\"(?:[^\"]|\"\")+\"))*)\\s*$");
    private static final Pattern EMPTY_USE =
            Pattern.compile("(?i)^USE\\s+(?:``|\"\"|'')\\s*$");
    private static final Pattern CURRENT_DATABASE =
            Pattern.compile("(?i)^SELECT\\s+current_database\\s*\\(\\s*\\)\\s*(AS\\s+[A-Za-z_][\\w]*)?\\s*$");
    private static final Pattern CURRENT_SCHEMA =
            Pattern.compile("(?i)^SELECT\\s+current_schema\\s*\\(\\s*\\)\\s*(AS\\s+[A-Za-z_][\\w]*)?\\s*$");

    private AdminStatementHandler() {}

    /** Returns empty when the statement is not an admin statement (caller falls through to the SQL pipeline). */
    static Optional<Outcome> tryHandle(CatalogService catalog, String sql, String currentDatabase) {
        String s = strip(sql);
        if (s.isEmpty()) return Optional.empty();

        // Spark 3.1.x JDBC probes metadata with an empty quoted database.
        // Treat it as a no-op so the probe can continue to GetSchemas.
        if (EMPTY_USE.matcher(s).matches()) {
            return Optional.of(new Outcome(
                    new DuckDbExecutor.QueryResult(List.of("result"), new ArrayList<>()), null));
        }

        Matcher m = SHOW_DATABASES.matcher(s);
        if (m.matches()) {
            String like = m.group(3);
            List<List<Object>> rows = new ArrayList<>();
            for (Catalog.Database db : catalog.catalog().listDatabases()) {
                if (like == null || matches(db.name(), like)) rows.add(List.of(db.name()));
            }
            return Optional.of(new Outcome(new DuckDbExecutor.QueryResult(List.of("database_name"), rows), null));
        }

        m = SHOW_TABLES.matcher(s);
        if (m.matches()) {
            String db = m.group(3) != null ? m.group(3).toLowerCase(Locale.ROOT) : currentDatabase;
            String like = m.group(6);
            List<List<Object>> rows = new ArrayList<>();
            for (Catalog.Table t : catalog.catalog().listTables(db)) {
                String name = t.tableName();
                if (like == null || matches(name, like)) rows.add(List.of(name));
            }
            return Optional.of(new Outcome(new DuckDbExecutor.QueryResult(List.of("tableName"), rows), null));
        }

        m = SHOW_COLUMNS.matcher(s);
        if (m.matches()) {
            return Optional.of(describe(catalog, m.group(2), currentDatabase));
        }

        m = DESCRIBE.matcher(s);
        if (m.matches()) {
            boolean formatted = "DESCRIBEFORMATTED".equalsIgnoreCase(m.group(1))
                    || m.group(3) != null;
            return Optional.of(describe(catalog, m.group(5), currentDatabase, formatted));
        }

        m = SHOW_CREATE.matcher(s);
        if (m.matches()) {
            String kind = m.group(1).toUpperCase(Locale.ROOT);
            String target = unquoteQualified(m.group(2));
            if ("DATABASE".equals(kind) || "SCHEMA".equals(kind)) {
                return Optional.of(showCreateDatabase(catalog, target));
            }
            return Optional.of(showCreateTable(catalog, target, currentDatabase));
        }

        // LocalSQL/DuckDB have no index concept; return an empty result like
        // Hive does for an indexless table instead of failing IDE probes.
        if (SHOW_INDEXES.matcher(s).matches()) {
            return Optional.of(new Outcome(new DuckDbExecutor.QueryResult(
                    List.of("index_name", "tab_name", "idx_original_col_name", "idx_col_name"),
                    new ArrayList<>()), null));
        }

        m = USE.matcher(s);
        if (m.matches()) {
            String target = unquoteQualified(m.group(1));
            // 'USE spark_catalog' (the catalog name alone) is a no-op switch to
            // default; 'USE catalog.db' / 'USE db' switch to the database part.
            String db;
            if (target.equalsIgnoreCase("spark_catalog")) {
                db = "default";
            } else {
                int lastDot = target.lastIndexOf('.');
                db = target.substring(lastDot + 1).toLowerCase(Locale.ROOT);
            }
            if (catalog.catalog().getDatabase(db).isEmpty()) {
                throw new IllegalArgumentException("Database does not exist: " + db);
            }
            return Optional.of(new Outcome(
                    new DuckDbExecutor.QueryResult(List.of("result"), new ArrayList<>()), db));
        }

        // IDEs probe the current database/schema on connect. Passing these to
        // DuckDB would return 'memory'/'main', which clients then use as a
        // schema filter and see zero tables. Spark semantics: session database.
        if (CURRENT_DATABASE.matcher(s).matches() || CURRENT_SCHEMA.matcher(s).matches()) {
            return Optional.of(new Outcome(new DuckDbExecutor.QueryResult(
                    List.of(currentDatabaseAlias(s)), List.of(List.of(currentDatabase))), null));
        }

        return Optional.empty();
    }

    private static String currentDatabaseAlias(String sql) {
        java.util.regex.Matcher am = java.util.regex.Pattern.compile(
                "(?i)\\bAS\\s+([A-Za-z_][\\w]*)").matcher(sql);
        return am.find() ? am.group(1) : "current_database()";
    }

    private static String unquoteQualified(String target) {
        StringBuilder out = new StringBuilder(target.length());
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (!quoted && (c == '`' || c == '"')) {
                quoted = true;
                quote = c;
            } else if (quoted && c == quote) {
                if (i + 1 < target.length() && target.charAt(i + 1) == quote) {
                    out.append(c);
                    i++;
                } else {
                    quoted = false;
                }
            } else if (!quoted && c == '.') {
                out.append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Outcome showCreateDatabase(CatalogService catalog, String name) {
        Catalog.Database db = catalog.catalog().getDatabase(name)
                .orElseThrow(() -> new IllegalArgumentException("Database does not exist: " + name));
        StringBuilder sb = new StringBuilder("CREATE DATABASE ").append(quoteSql(db.name()));
        if (db.description() != null) {
            sb.append(" COMMENT '").append(db.description().replace("'", "''")).append("'");
        }
        if (db.location() != null) {
            sb.append(" LOCATION '").append(db.location().replace("'", "''")).append("'");
        }
        if (!db.properties().isEmpty()) {
            sb.append(" WITH DBPROPERTIES (");
            boolean first = true;
            for (var e : db.properties().entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append("'").append(String.valueOf(e.getKey()).replace("'", "''")).append("'")
                  .append("='").append(String.valueOf(e.getValue()).replace("'", "''")).append("'");
            }
            sb.append(")");
        }
        return new Outcome(new DuckDbExecutor.QueryResult(
                List.of("database_name", "create_statement"), List.of(List.of(db.name(), sb.toString()))), null);
    }

    private static Outcome showCreateTable(CatalogService catalog, String qualified, String currentDatabase) {
        String[] parts = qualified.split("\\.");
        String db;
        String table;
        if (parts.length >= 2) {
            db = parts[parts.length - 2].toLowerCase(Locale.ROOT);
            table = parts[parts.length - 1];
        } else {
            db = currentDatabase;
            table = parts[0];
        }
        Catalog.Table t = catalog.catalog().getTable(db, table)
                .orElseThrow(() -> new IllegalArgumentException("Table or view not found: " + db + "." + table));
        if ("VIEW".equalsIgnoreCase(t.tableType())) {
            return new Outcome(new DuckDbExecutor.QueryResult(
                    List.of("view_name", "create_statement"),
                    List.of(List.of(t.tableName(), createViewSql(catalog, t)))), null);
        }
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(quoteSql(db)).append('.').append(quoteSql(t.tableName()));
        sb.append(" (");
        for (int i = 0; i < t.columns().size(); i++) {
            Catalog.Column c = t.columns().get(i);
            if (i > 0) sb.append(", ");
            sb.append(quoteSql(c.name())).append(' ').append(createType(c.type()));
            if (!c.nullable()) sb.append(" NOT NULL");
            if (c.comment() != null) {
                sb.append(" COMMENT '").append(c.comment().replace("'", "''")).append("'");
            }
        }
        sb.append(")");
        if (t.comment() != null) {
            sb.append(" COMMENT '").append(t.comment().replace("'", "''")).append("'");
        }
        if (t.location() != null && !t.location().isEmpty()) {
            sb.append(" LOCATION '").append(t.location().replace("'", "''")).append("'");
        }
        if (!t.properties().isEmpty()) {
            sb.append(" TBLPROPERTIES (");
            boolean first = true;
            for (var e : t.properties().entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append("'").append(String.valueOf(e.getKey()).replace("'", "''")).append("'")
                  .append("='").append(String.valueOf(e.getValue()).replace("'", "''")).append("'");
            }
            sb.append(")");
        }
        return new Outcome(new DuckDbExecutor.QueryResult(
                List.of("table_name", "create_statement"),
                List.of(List.of(t.tableName(), sb.toString()))), null);
    }

    /** Views replay from the runtime store DDL; fall back to a synthesized form. */
    private static String createViewSql(CatalogService catalog, Catalog.Table t) {
        if (catalog.store() != null && t.tableId() > 0) {
            try {
                var rt = catalog.store().loadRuntimeInfo(t.tableId());
                if (rt.isPresent() && rt.get().createSql() != null) {
                    return rt.get().createSql();
                }
            } catch (Exception ignored) {
            }
        }
        return "CREATE VIEW " + quoteSql(t.database()) + '.' + quoteSql(t.tableName());
    }

    private static String quoteSql(String ident) {
        return "`" + ident.replace("`", "``") + "`";
    }

    private static Outcome describe(CatalogService catalog, String qualified, String currentDatabase) {
        return describe(catalog, qualified, currentDatabase, false);
    }

    private static Outcome describe(CatalogService catalog, String qualified, String currentDatabase,
                                    boolean formatted) {
        String[] parts = qualified.split("\\.");
        String db;
        String table;
        if (parts.length >= 2) {
            db = parts[parts.length - 2].toLowerCase(Locale.ROOT);
            table = parts[parts.length - 1];
        } else {
            db = currentDatabase;
            table = parts[0];
        }
        var found = catalog.catalog().getTable(db, table);
        List<List<Object>> rows = new ArrayList<>();
        if (found.isPresent()) {
            Catalog.Table tableInfo = found.get();
            for (Catalog.Column c : tableInfo.columns()) {
                List<Object> row = new ArrayList<>(3);
                row.add(c.name());
                row.add(displayType(c.type()));
                row.add(c.comment() == null ? "" : c.comment());
                rows.add(row);
            }
            if (formatted) {
                rows.add(List.of("", "", ""));
                rows.add(List.of("# Detailed Table Information", "", ""));
                rows.add(List.of("Database", db, ""));
                rows.add(List.of("Table", tableInfo.tableName(), ""));
                rows.add(List.of("Type", tableInfo.tableType(), ""));
                rows.add(List.of("Provider", tableInfo.format() == null ? "duckdb" : tableInfo.format(), ""));
                rows.add(List.of("Location", tableInfo.location() == null ? "" : tableInfo.location(), ""));
                if (tableInfo.comment() != null) rows.add(List.of("Comment", tableInfo.comment(), ""));
                if (!tableInfo.properties().isEmpty()) {
                    rows.add(List.of("Properties", tableInfo.properties().toString(), ""));
                }
            }
        }
        return new Outcome(new DuckDbExecutor.QueryResult(
                List.of("col_name", "data_type", "comment"), rows), null);
    }

    /** SHOW CREATE TABLE displays types the Spark way: upper case. */
    private static String createType(io.github.joegxx.localsql.ir.type.DataType t) {
        return displayType(t).toUpperCase(Locale.ROOT);
    }

    /** Spark-style type display; decimal keeps its precision/scale. */
    private static String displayType(io.github.joegxx.localsql.ir.type.DataType t) {
        if (t == null) return "string";
        if (t instanceof io.github.joegxx.localsql.ir.type.DecimalType d) {
            return "decimal(" + d.precision() + "," + d.scale() + ")";
        }
        return t.typeName().toLowerCase(Locale.ROOT);
    }

    /** JDBC-style pattern match: '%' any sequence, '_' single char, '\' escapes. */
    private static boolean matches(String value, String pattern) {
        return SqlPatterns.matches(value, pattern, true);
    }

    private static String strip(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }
}
