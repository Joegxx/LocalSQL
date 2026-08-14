package io.github.joegxx.localsql.duckdb;

import io.github.joegxx.localsql.catalog.Catalog;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.ir.type.DataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * CatalogStore backed by 4 DuckDB tables:
 *   catalog_table   (table_id, catalog_name, database_name, schema_name, table_name,
 *                    table_type, comment, properties, metadata_json)
 *   catalog_column  (table_id, ordinal, column_name, data_type, nullable,
 *                    comment, default_value, expression)
 *   catalog_property(table_id, property_key, property_value)
 *   runtime_table   (table_id, duck_table_name, create_sql, last_refresh)
 *
 * DuckDB is the physical persistence for logical metadata. This class only
 * serializes/deserializes Catalog records; it does not execute user SQL or
 * store runtime execution state.
 */
public final class DuckDbCatalogStore implements CatalogStore {

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
    }

    @Override
    public long saveTable(Catalog.Table table) throws Exception {
        long tableId = table.tableId() > 0 ? table.tableId() : nextTableId();
        String catalogName = table.name().size() > 2 ? table.name().get(0) : "local";
        String databaseName = table.name().size() > 1 ? table.name().get(0) : "default";
        String schemaName = table.name().size() > 2 ? table.name().get(1) : databaseName;
        String tableName = table.tableName();
        String tableType = table.tableType() == null ? "TABLE" : table.tableType();
        String comment = sqlEscape(table.comment());
        String properties = serializeProperties(table.properties());
        String metadataJson = sqlEscape(table.metadataJson());

        executeUpdate("DELETE FROM catalog_table WHERE table_id = " + tableId);
        executeUpdate("DELETE FROM catalog_column WHERE table_id = " + tableId);
        executeUpdate("DELETE FROM catalog_property WHERE table_id = " + tableId);

        executeUpdate(String.format(
                "INSERT INTO catalog_table VALUES (%d, '%s', '%s', '%s', '%s', '%s', %s, '%s', %s)",
                tableId, sqlEscape(catalogName), sqlEscape(databaseName), sqlEscape(schemaName),
                sqlEscape(tableName), sqlEscape(tableType),
                comment == null ? "NULL" : "'" + comment + "'",
                sqlEscape(properties),
                metadataJson == null ? "NULL" : "'" + metadataJson + "'"));

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
                        + "t.table_type, t.comment, t.properties, t.metadata_json FROM catalog_table t "
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
            String propertiesJson = asString(row.get(7));
            String metadataJson = asString(row.get(8));

            List<String> name = schemaName != null && !schemaName.equals(databaseName)
                    ? List.of(catalogName, databaseName, schemaName, tableName)
                    : List.of(databaseName, tableName);

            List<Catalog.Column> cols = loadColumns(id);
            java.util.Map<String, String> props = deserializeProperties(propertiesJson);

            out.add(new Catalog.Table(id, name, tableType == null ? "TABLE" : tableType,
                    cols, null, "duckdb", comment, props, metadataJson));
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

    private static String serializeProperties(java.util.Map<String, String> props) {
        if (props == null || props.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var e : props.entrySet()) {
            if (!first) sb.append("\n");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private static java.util.Map<String, String> deserializeProperties(String json) {
        java.util.Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        for (String line : json.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return out;
    }
}
