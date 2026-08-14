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
    }

    public DuckDbExecutor(String path) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:duckdb:" + (path == null ? "" : path));
    }

    public QueryResult execute(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery(sql);
            ResultSetMetaData meta = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(meta.getColumnLabel(i));
            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) row.add(rs.getObject(i));
                rows.add(row);
            }
            return new QueryResult(columns, rows);
        }
    }

    public int executeUpdate(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            return stmt.executeUpdate(sql);
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

    public record QueryResult(List<String> columns, List<List<Object>> rows) {}
}
