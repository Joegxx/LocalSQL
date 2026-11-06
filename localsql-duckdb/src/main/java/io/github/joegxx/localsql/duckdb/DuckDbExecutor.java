package io.github.joegxx.localsql.duckdb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DuckDbExecutor implements AutoCloseable {

    private final Connection connection;

    public DuckDbExecutor() throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:duckdb:");
        DuckDbBuiltinUdfRegistrar.register(connection);
    }

    public DuckDbExecutor(String path) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:duckdb:" + (path == null ? "" : path));
        DuckDbBuiltinUdfRegistrar.register(connection);
    }

    public QueryResult execute(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery(sql);
            ResultSetMetaData meta = rs.getMetaData();
            int count = meta.getColumnCount();
            List<ColMeta> cols = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                cols.add(new ColMeta(meta.getColumnLabel(i), meta.getColumnType(i), meta.getColumnTypeName(i),
                        meta.isNullable(i) != ResultSetMetaData.columnNoNulls, meta.getPrecision(i), meta.getScale(i)));
            }
            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(count);
                for (int i = 1; i <= count; i++) row.add(rs.getObject(i));
                rows.add(row);
            }
            return new QueryResult(cols, rows);
        }
    }

    /**
     * Column description of a query result: the JDBC sqlType plus the native
     * DuckDB type name, nullability, precision and scale, so metadata
     * consumers (HiveServer2 GetResultSetMetadata) report real types instead
     * of flattening everything to STRING. Values in the same column are
     * serialized through the matching Thrift channel.
     */
    public record ColMeta(String name, int sqlType, String typeName,
                          boolean nullable, int precision, int scale) {

        /**
         * All-STRING column for hand-built results (admin statements, legacy
         * shape). LONGVARCHAR keeps the HiveServer2 metadata STRING_TYPE and
         * the string fetch channel.
         */
        public static ColMeta string(String name) {
            return new ColMeta(name, java.sql.Types.LONGVARCHAR, "STRING", true, 0, 0);
        }
    }

    /**
     * Query result with per-column metadata. The Collection constructor keeps
     * the legacy all-STRING shape for hand-built results (admin statements,
     * DDL counts, fixtures): plain name strings become STRING columns,
     * prebuilt ColMeta entries pass through.
     */
    public record QueryResult(List<ColMeta> colMetas, List<List<Object>> rows) {

        @SuppressWarnings("unchecked")
        public QueryResult(java.util.Collection<?> columnsOrMetas, List<List<Object>> rows) {
            this(columnsOrMetas.stream()
                            .map(c -> c instanceof ColMeta m ? m : ColMeta.string((String) c))
                            .toList(),
                    rows);
        }

        public List<String> columns() {
            return colMetas.stream().map(ColMeta::name).toList();
        }
    }

    public int executeUpdate(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            return stmt.executeUpdate(sql);
        }
    }

    /** Execute any statement (DDL, INSTALL/LOAD extension, table-producing CALLs). */
    public void run(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    public void registerSampleTable(String name, List<String> columns, List<List<Object>> rows) throws SQLException {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(quoteIdent(name)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteIdent(columns.get(i))).append(" VARCHAR");
        }
        sb.append(")");
        executeUpdate(sb.toString());
        for (List<Object> row : rows) insertRow(name, row);
    }

    /**
     * Register a typed table and insert rows. Column types must be DuckDB-native
     * type names (INT, BIGINT, VARCHAR, DOUBLE, etc.). Values are coerced to
     * string literals except null and Number.
     */
    public void registerTable(String name, List<ColDef> colDefs, List<List<Object>> rows) throws SQLException {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(quoteIdent(name)).append(" (");
        for (int i = 0; i < colDefs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteIdent(colDefs.get(i).name())).append(' ').append(colDefs.get(i).type());
        }
        sb.append(")");
        executeUpdate(sb.toString());
        for (List<Object> row : rows) insertRow(name, row);
    }

    public record ColDef(String name, String type) {}

    private void insertRow(String table, List<Object> row) throws SQLException {
        StringBuilder ins = new StringBuilder("INSERT INTO ").append(quoteIdent(table)).append(" VALUES (");
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) ins.append(", ");
            Object v = row.get(i);
            if (v == null) ins.append("NULL");
            else if (v instanceof Number) ins.append(v);
            else ins.append("'").append(v.toString().replace("'", "''")).append("'");
        }
        ins.append(")");
        executeUpdate(ins.toString());
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
