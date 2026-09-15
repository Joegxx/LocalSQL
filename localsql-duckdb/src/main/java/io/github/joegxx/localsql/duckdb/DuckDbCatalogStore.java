package io.github.joegxx.localsql.duckdb;

import io.github.joegxx.localsql.catalog.Catalog;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.ir.type.DataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * CatalogStore backed by DuckDB tables:
 *   catalog_table   (table_id, catalog_name, database_name, schema_name, table_name,
 *                    table_type, comment, properties, metadata_json)
 *   catalog_column  (table_id, ordinal, column_name, data_type, nullable,
 *                    comment, default_value, expression)
 *   catalog_property(table_id, property_key, property_value)
 *   runtime_table   (table_id, duck_table_name, create_sql, last_refresh)
 *   catalog_database(name, display_name, description, location)
 *   catalog_property_kv(name, property_key, property_value)  -- database properties
 *   catalog_schema_version(version)
 *
 * DuckDB is the physical persistence for logical metadata. This class only
 * serializes/deserializes Catalog records; it does not execute user SQL or
 * store runtime execution state.
 *
 * Migration: a pre-database-table store has no catalog_database rows. On
 * init, databases are derived from catalog_table's distinct database_name
 * and persisted (as bare names, without the lost description/location).
 */
public final class DuckDbCatalogStore implements CatalogStore {

    /** Bump when the on-disk schema changes in a way old binaries cannot read. */
    static final int SCHEMA_VERSION = 1;

    private final DuckDbExecutor executor;

    public DuckDbCatalogStore(DuckDbExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void init() throws Exception {
        executeUpdate("""
                CREATE TABLE IF NOT EXISTS catalog_table (
                    table_id BIGINT PRIMARY KEY,
                    catalog_name VARCHAR,
                    database_name VARCHAR,
                    schema_name VARCHAR,
                    table_name VARCHAR,
                    table_type VARCHAR,
                    comment VARCHAR,
                    properties VARCHAR,
                    metadata_json VARCHAR
                )""");
        executeUpdate("""
                CREATE TABLE IF NOT EXISTS catalog_column (
                    table_id BIGINT,
                    ordinal INTEGER,
                    column_name VARCHAR,
                    data_type VARCHAR,
                    nullable BOOLEAN,
                    comment VARCHAR,
                    default_value VARCHAR,
                    expression VARCHAR
                )""");
        executeUpdate("""
                CREATE TABLE IF NOT EXISTS catalog_property (
                    table_id BIGINT,
                    property_key VARCHAR,
                    property_value VARCHAR
                )""");
        executeUpdate("""
                CREATE TABLE IF NOT EXISTS runtime_table (
                    table_id BIGINT PRIMARY KEY,
                    duck_table_name VARCHAR,
                    create_sql VARCHAR,
                    last_refresh VARCHAR
                )""");
        executeUpdate("""
                CREATE TABLE IF NOT EXISTS catalog_database (
                    database_name VARCHAR PRIMARY KEY,
                    display_name VARCHAR,
                    description VARCHAR,
                    location VARCHAR
                )""");
        executeUpdate("""
                CREATE TABLE IF NOT EXISTS catalog_db_property (
                    database_name VARCHAR,
                    property_key VARCHAR,
                    property_value VARCHAR
                )""");
        executeUpdate("CREATE TABLE IF NOT EXISTS catalog_schema_version (version INTEGER)");
        initOrMigrateSchemaVersion();
    }

    /**
     * Old stores have an empty version table; they predate catalog_database,
     * so databases are derived from catalog_table and written back. Future
     * versions must reject stores written by newer binaries.
     */
    private void initOrMigrateSchemaVersion() throws Exception {
        DuckDbExecutor.QueryResult rs = executor.execute(
                "SELECT COALESCE(MAX(version), 0) FROM catalog_schema_version");
        int version = rs.rows().isEmpty() ? 0 : ((Number) rs.rows().get(0).get(0)).intValue();
        if (version > SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "Catalog store schema version " + version + " is newer than supported version "
                            + SCHEMA_VERSION + "; refusing to open (a downgrade needs a backup restore)");
        }
        if (version == 0) {
            if (catalogDatabaseTableIsEmpty()) {
                deriveDatabasesFromTables();
            }
            executeUpdate("INSERT INTO catalog_schema_version VALUES (" + SCHEMA_VERSION + ")");
        }
    }

    private boolean catalogDatabaseTableIsEmpty() throws Exception {
        DuckDbExecutor.QueryResult rs = executor.execute("SELECT count(*) FROM catalog_database");
        return rs.rows().isEmpty() || ((Number) rs.rows().get(0).get(0)).longValue() == 0;
    }

    private void deriveDatabasesFromTables() throws Exception {
        DuckDbExecutor.QueryResult rs = executor.execute(
                "SELECT DISTINCT database_name FROM catalog_table");
        for (List<Object> row : rs.rows()) {
            String name = asString(row.get(0));
            if (name == null || name.isBlank()) continue;
            saveDatabase(new Catalog.Database(name, null, name + " database"));
        }
        if (catalogDatabaseTableIsEmpty()) {
            saveDatabase(new Catalog.Database("default", null, "default database"));
        }
    }

    @Override
    public long saveTable(Catalog.Table table) throws Exception {
        long tableId = table.tableId() > 0 ? table.tableId() : nextTableId();
        List<String> n = table.name();
        // [t] / [db,t] / [catalog,db,t] / [catalog,db,schema,t]
        String catalogName;
        String databaseName;
        String schemaName;
        switch (n.size()) {
            case 1 -> { catalogName = "local"; databaseName = "default"; schemaName = databaseName; }
            case 2 -> { catalogName = "local"; databaseName = n.get(0); schemaName = databaseName; }
            case 3 -> { catalogName = n.get(0); databaseName = n.get(1); schemaName = databaseName; }
            default -> { catalogName = n.get(0); databaseName = n.get(1); schemaName = n.get(2); }
        }
        String tableName = table.tableName();
        String tableType = table.tableType() == null ? "TABLE" : table.tableType();
        // Properties live exclusively in catalog_property rows; the legacy
        // catalog_table.properties text column is no longer written (reading
        // it back also cannot represent '=' or newlines inside values).
        executeUpdate("DELETE FROM catalog_table WHERE table_id = " + tableId);
        executeUpdate("DELETE FROM catalog_column WHERE table_id = " + tableId);
        executeUpdate("DELETE FROM catalog_property WHERE table_id = " + tableId);

        executeUpdate(String.format(
                "INSERT INTO catalog_table VALUES (%d, '%s', '%s', '%s', '%s', '%s', %s, NULL, %s)",
                tableId, sqlEscape(catalogName), sqlEscape(databaseName), sqlEscape(schemaName),
                sqlEscape(tableName), sqlEscape(tableType),
                table.comment() == null ? "NULL" : "'" + sqlEscape(table.comment()) + "'",
                table.metadataJson() == null ? "NULL" : "'" + sqlEscape(table.metadataJson()) + "'"));

        for (Catalog.Column c : table.columns()) {
            executeUpdate(String.format(
                    "INSERT INTO catalog_column VALUES (%d, %d, '%s', '%s', %b, %s, %s, %s)",
                    tableId, c.ordinal(), sqlEscape(c.name()),
                    sqlEscape(Catalog.toStorageType(c.type())),
                    c.nullable(),
                    c.comment() == null ? "NULL" : "'" + sqlEscape(c.comment()) + "'",
                    c.defaultValue() == null ? "NULL" : "'" + sqlEscape(c.defaultValue()) + "'",
                    c.expression() == null ? "NULL" : "'" + sqlEscape(c.expression()) + "'"));
        }

        if (table.properties() != null) {
            for (var entry : table.properties().entrySet()) {
                executeUpdate(String.format(
                        "INSERT INTO catalog_property VALUES (%d, '%s', '%s')",
                        tableId, sqlEscape(entry.getKey()), sqlEscape(entry.getValue())));
            }
        }

        return tableId;
    }

    @Override
    public void deleteTable(long tableId) throws Exception {
        executeUpdate("DELETE FROM catalog_table WHERE table_id = " + tableId);
        executeUpdate("DELETE FROM catalog_column WHERE table_id = " + tableId);
        executeUpdate("DELETE FROM catalog_property WHERE table_id = " + tableId);
        executeUpdate("DELETE FROM runtime_table WHERE table_id = " + tableId);
    }

    @Override
    public Optional<Catalog.Table> loadTable(long tableId) throws Exception {
        List<Catalog.Table> tables = loadTablesByIds("WHERE t.table_id = " + tableId);
        return tables.isEmpty() ? Optional.empty() : Optional.of(tables.get(0));
    }

    @Override
    public Optional<Catalog.Table> loadTable(String database, String tableName) throws Exception {
        String where = database == null
                ? "WHERE t.table_name = '" + sqlEscape(tableName) + "'"
                : "WHERE t.database_name = '" + sqlEscape(database)
                        + "' AND t.table_name = '" + sqlEscape(tableName) + "'";
        List<Catalog.Table> tables = loadTablesByIds(where);
        return tables.isEmpty() ? Optional.empty() : Optional.of(tables.get(0));
    }

    @Override
    public List<Catalog.Table> loadTables(String database) throws Exception {
        String where = database == null || database.isEmpty()
                ? ""
                : "WHERE t.database_name = '" + sqlEscape(database) + "'";
        return loadTablesByIds(where);
    }

    // ---- database-level metadata ----

    @Override
    public void saveDatabase(Catalog.Database database) throws Exception {
        String name = database.name();
        executeUpdate("DELETE FROM catalog_database WHERE database_name = '" + sqlEscape(name) + "'");
        executeUpdate(String.format(
                "INSERT INTO catalog_database VALUES ('%s', '%s', %s, %s)",
                sqlEscape(name),
                sqlEscape(name),
                database.description() == null ? "NULL" : "'" + sqlEscape(database.description()) + "'",
                database.location() == null ? "NULL" : "'" + sqlEscape(database.location()) + "'"));
        executeUpdate("DELETE FROM catalog_db_property WHERE database_name = '" + sqlEscape(name) + "'");
        for (var entry : database.properties().entrySet()) {
            executeUpdate(String.format(
                    "INSERT INTO catalog_db_property VALUES ('%s', '%s', '%s')",
                    sqlEscape(name), sqlEscape(entry.getKey()), sqlEscape(entry.getValue())));
        }
    }

    @Override
    public void deleteDatabase(String name) throws Exception {
        executeUpdate("DELETE FROM catalog_database WHERE database_name = '" + sqlEscape(name) + "'");
        executeUpdate("DELETE FROM catalog_db_property WHERE database_name = '" + sqlEscape(name) + "'");
    }

    @Override
    public List<Catalog.Database> loadDatabases() throws Exception {
        DuckDbExecutor.QueryResult rs = executor.execute(
                "SELECT database_name, description, location FROM catalog_database");
        List<Catalog.Database> out = new ArrayList<>();
        for (List<Object> row : rs.rows()) {
            String name = asString(row.get(0));
            if (name == null) continue;
            out.add(new Catalog.Database(name, asString(row.get(2)), asString(row.get(1)),
                    loadDatabaseProperties(name)));
        }
        return out;
    }

    private java.util.Map<String, String> loadDatabaseProperties(String name) throws Exception {
        DuckDbExecutor.QueryResult rs = executor.execute(
                "SELECT property_key, property_value FROM catalog_db_property WHERE database_name = '"
                        + sqlEscape(name) + "'");
        java.util.Map<String, String> out = new LinkedHashMap<>();
        for (List<Object> row : rs.rows()) {
            out.put(asString(row.get(0)), asString(row.get(1)));
        }
        return out;
    }

    @Override
    public void saveRuntimeInfo(long tableId, String duckTableName, String createSql) throws Exception {
        executeUpdate("DELETE FROM runtime_table WHERE table_id = " + tableId);
        executeUpdate(String.format(
                "INSERT INTO runtime_table VALUES (%d, '%s', '%s', CURRENT_TIMESTAMP)",
                tableId, sqlEscape(duckTableName), sqlEscape(createSql)));
    }

    @Override
    public Optional<RuntimeInfo> loadRuntimeInfo(long tableId) throws Exception {
        DuckDbExecutor.QueryResult rs = executor.execute(
                "SELECT duck_table_name, create_sql, CAST(last_refresh AS VARCHAR) FROM runtime_table WHERE table_id = "
                        + tableId);
        if (rs.rows().isEmpty()) return Optional.empty();
        List<Object> row = rs.rows().get(0);
        return Optional.of(new RuntimeInfo(tableId,
                asString(row.get(0)), asString(row.get(1)), asString(row.get(2))));
    }

    @Override
    public List<RuntimeInfo> loadAllRuntimeInfo() throws Exception {
        DuckDbExecutor.QueryResult rs = executor.execute(
                "SELECT table_id, duck_table_name, create_sql, CAST(last_refresh AS VARCHAR) FROM runtime_table");
        List<RuntimeInfo> out = new ArrayList<>();
        for (List<Object> row : rs.rows()) {
            out.add(new RuntimeInfo(
                    ((Number) row.get(0)).longValue(),
                    asString(row.get(1)), asString(row.get(2)), asString(row.get(3))));
        }
        return out;
    }

    private List<Catalog.Table> loadTablesByIds(String whereClause) throws Exception {
        DuckDbExecutor.QueryResult rs = executor.execute(
                "SELECT t.table_id, t.catalog_name, t.database_name, t.schema_name, t.table_name, "
                        + "t.table_type, t.comment, t.metadata_json FROM catalog_table t "
                        + whereClause + " ORDER BY t.table_id");
        List<Catalog.Table> out = new ArrayList<>();
        for (List<Object> row : rs.rows()) {
            long id = ((Number) row.get(0)).longValue();
            String catalogName = asString(row.get(1));
            String databaseName = asString(row.get(2));
            String schemaName = asString(row.get(3));
            String tableName = asString(row.get(4));
            String tableType = asString(row.get(5));
            String comment = asString(row.get(6));
            String metadataJson = asString(row.get(7));

            List<String> name = schemaName != null && !schemaName.equals(databaseName)
                    ? List.of(catalogName, databaseName, schemaName, tableName)
                    : List.of(databaseName, tableName);

            List<Catalog.Column> cols = loadColumns(id);
            java.util.Map<String, String> props = loadProperties(id);

            out.add(new Catalog.Table(id, name, tableType == null ? "TABLE" : tableType,
                    cols, null, "duckdb", comment, props, metadataJson));
        }
        return out;
    }

    private java.util.Map<String, String> loadProperties(long tableId) throws Exception {
        DuckDbExecutor.QueryResult rs = executor.execute(
                "SELECT property_key, property_value FROM catalog_property WHERE table_id = " + tableId);
        java.util.Map<String, String> out = new LinkedHashMap<>();
        for (List<Object> row : rs.rows()) {
            out.put(asString(row.get(0)), asString(row.get(1)));
        }
        return out;
    }

    private List<Catalog.Column> loadColumns(long tableId) throws Exception {
        DuckDbExecutor.QueryResult rs = executor.execute(
                "SELECT ordinal, column_name, data_type, nullable, comment, default_value, expression "
                        + "FROM catalog_column WHERE table_id = " + tableId + " ORDER BY ordinal");
        List<Catalog.Column> out = new ArrayList<>();
        for (List<Object> row : rs.rows()) {
            int ordinal = ((Number) row.get(0)).intValue();
            String colName = asString(row.get(1));
            DataType type = Catalog.parseDataType(asString(row.get(2)));
            boolean nullable = row.get(3) instanceof Boolean b ? b : true;
            String comment = asString(row.get(4));
            String defaultValue = asString(row.get(5));
            String expression = asString(row.get(6));
            out.add(new Catalog.Column(colName, type, nullable, comment, ordinal, defaultValue, expression));
        }
        return out;
    }

    private long nextTableId() throws Exception {
        DuckDbExecutor.QueryResult rs = executor.execute(
                "SELECT COALESCE(MAX(table_id), 0) + 1 FROM catalog_table");
        if (rs.rows().isEmpty()) return 1;
        Object v = rs.rows().get(0).get(0);
        return v instanceof Number n ? n.longValue() : 1L;
    }

    private void executeUpdate(String sql) throws Exception {
        executor.executeUpdate(sql);
    }

    private static String sqlEscape(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
