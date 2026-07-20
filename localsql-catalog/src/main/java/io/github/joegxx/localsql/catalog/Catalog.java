package io.github.joegxx.localsql.catalog;

import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.ir.type.StringType;
import io.github.joegxx.localsql.ir.type.UnknownType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Catalog {

    public record Column(String name, DataType type, boolean nullable, String comment,
                         int ordinal, String defaultValue, String expression) {
        public Column(String name, DataType type, boolean nullable, String comment) {
            this(name, type, nullable, comment, -1, null, null);
        }
    }

    public record Table(long tableId, List<String> name, String tableType, List<Column> columns,
                        String location, String format, String comment,
                        Map<String, String> properties, String metadataJson) {
        public Table(List<String> name, List<Column> columns, String location, String format) {
            this(0, name, "TABLE", columns, location, format, null, new LinkedHashMap<>(), null);
        }
        public String qualifiedName() { return String.join(".", name); }
        public String database() { return name.size() > 1 ? name.get(0) : "default"; }
        public String tableName() { return name.get(name.size() - 1); }
    }

    public record Database(String name, String location, String description) {}

    private final Map<String, Database> databases = new LinkedHashMap<>();
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private long nextTableId = 1;

    public Catalog() {
        createDatabase(new Database("default", null, "default database"));
    }

    public void createDatabase(Database db) { databases.put(db.name().toLowerCase(), db); }

    public List<Database> listDatabases() { return new ArrayList<>(databases.values()); }

    public Optional<Database> getDatabase(String name) {
        return Optional.ofNullable(databases.get(name.toLowerCase()));
    }

    public void createTable(Table table) {
        long id = table.tableId() > 0 ? table.tableId() : nextTableId++;
        if (table.tableId() <= 0) {
            table = new Table(id, table.name(), table.tableType(), table.columns(),
                    table.location(), table.format(), table.comment(),
                    table.properties(), table.metadataJson());
        } else {
            nextTableId = Math.max(nextTableId, id + 1);
        }
        tables.put(table.qualifiedName().toLowerCase(), table);
    }

    public List<Table> listTables(String database) {
        if (database == null || database.isEmpty()) return new ArrayList<>(tables.values());
        String prefix = database.toLowerCase() + ".";
        return tables.values().stream()
                .filter(t -> t.qualifiedName().toLowerCase().startsWith(prefix))
                .toList();
    }

    public Optional<Table> getTable(List<String> name) {
        return Optional.ofNullable(tables.get(String.join(".", name).toLowerCase()));
    }

    public Optional<Table> getTable(String database, String tableName) {
        return getTable(List.of(database, tableName));
    }

    public Column column(Table table, String colName) {
        for (Column c : table.columns()) if (c.name().equalsIgnoreCase(colName)) return c;
        return new Column(colName, UnknownType.INSTANCE, true, null);
    }

    public static DataType parseDataType(String typeName) {
        if (typeName == null) return new StringType();
        String t = typeName.trim().toUpperCase();
        return switch (t) {
            case "BOOLEAN" -> new io.github.joegxx.localsql.ir.type.BooleanType();
            case "TINYINT" -> io.github.joegxx.localsql.ir.type.IntegralType.TINYINT;
            case "SMALLINT" -> io.github.joegxx.localsql.ir.type.IntegralType.SMALLINT;
            case "INT", "INTEGER" -> io.github.joegxx.localsql.ir.type.IntegralType.INT;
            case "BIGINT", "LONG" -> io.github.joegxx.localsql.ir.type.IntegralType.BIGINT;
            case "FLOAT" -> io.github.joegxx.localsql.ir.type.FractionalType.FLOAT;
            case "DOUBLE" -> io.github.joegxx.localsql.ir.type.FractionalType.DOUBLE;
            case "STRING", "VARCHAR" -> new StringType();
            case "DATE" -> new io.github.joegxx.localsql.ir.type.DateType();
            case "TIMESTAMP" -> new io.github.joegxx.localsql.ir.type.TimestampType();
            default -> new StringType();
        };
    }

    public static String toStorageType(DataType type) {
        if (type == null) return "STRING";
        return switch (type.typeName()) {
            case "BOOLEAN" -> "BOOLEAN";
            case "INT" -> "INT";
            case "BIGINT" -> "BIGINT";
            case "FLOAT" -> "FLOAT";
            case "DOUBLE" -> "DOUBLE";
            case "STRING" -> "STRING";
            case "BINARY" -> "BINARY";
            case "DATE" -> "DATE";
            case "TIMESTAMP" -> "TIMESTAMP";
            default -> "STRING";
        };
    }
}
