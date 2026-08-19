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
            Pattern.compile("(?i)^(DESCRIBE|DESC)(\\s+(EXTENDED|FORMATTED))?(\\s+TABLE)?\\s+([A-Za-z_][\\w]*(\\.[A-Za-z_][\\w]*)*)\\s*$");
    private static final Pattern USE =
            Pattern.compile("(?i)^USE\\s+([A-Za-z_][\\w]*)\\s*$");
    private static final Pattern CURRENT_DATABASE =
            Pattern.compile("(?i)^SELECT\\s+current_database\\s*\\(\\s*\\)\\s*(AS\\s+[A-Za-z_][\\w]*)?\\s*$");
    private static final Pattern CURRENT_SCHEMA =
            Pattern.compile("(?i)^SELECT\\s+current_schema\\s*\\(\\s*\\)\\s*(AS\\s+[A-Za-z_][\\w]*)?\\s*$");

    private AdminStatementHandler() {}

    /** Returns empty when the statement is not an admin statement (caller falls through to the SQL pipeline). */
    static Optional<Outcome> tryHandle(CatalogService catalog, String sql, String currentDatabase) {
        String s = strip(sql);
        if (s.isEmpty()) return Optional.empty();

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
            return Optional.of(describe(catalog, m.group(5), currentDatabase));
        }

        m = USE.matcher(s);
        if (m.matches()) {
            String db = m.group(1).toLowerCase(Locale.ROOT);
            // IDEs issue 'USE spark_catalog' (the catalog name, not a database).
            // Treat the catalog name as a no-op switch to the default database.
            if (db.equals("spark_catalog")) {
                return Optional.of(new Outcome(
                        new DuckDbExecutor.QueryResult(List.of("result"), new ArrayList<>()), "default"));
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

    private static Outcome describe(CatalogService catalog, String qualified, String currentDatabase) {
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
            for (Catalog.Column c : found.get().columns()) {
                List<Object> row = new ArrayList<>(3);
                row.add(c.name());
                row.add(c.type() == null ? "string" : c.type().typeName().toLowerCase(Locale.ROOT));
                row.add(c.comment() == null ? "" : c.comment());
                rows.add(row);
            }
        }
        return new Outcome(new DuckDbExecutor.QueryResult(
                List.of("col_name", "data_type", "comment"), rows), null);
    }

    /** JDBC-style pattern match: '%' any sequence, '_' single char. */
    private static boolean matches(String value, String pattern) {
        String regex = Pattern.quote(pattern)
                .replace("*", "\\E.*\\Q")
                .replace("%", "\\E.*\\Q")
                .replace("_", "\\E.\\Q");
        return value.matches("(?i)" + regex);
    }

    private static String strip(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }
}