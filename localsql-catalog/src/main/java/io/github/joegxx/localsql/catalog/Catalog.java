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

    public record Column(String name, DataType type, boolean nullable, String comment) {}

    public record Table(List<String> name, List<Column> columns, String location, String format) {
        public String qualifiedName() { return String.join(".", name); }
    }

    public record Database(String name, String location, String description) {}

    private final Map<String, Database> databases = new LinkedHashMap<>();
    private final Map<String, Table> tables = new LinkedHashMap<>();

    public Catalog() {
        createDatabase(new Database("default", null, "default database"));
    }

    public void createDatabase(Database db) { databases.put(db.name().toLowerCase(), db); }

    public List<Database> listDatabases() { return new ArrayList<>(databases.values()); }

    public Optional<Database> getDatabase(String name) {
        return Optional.ofNullable(databases.get(name.toLowerCase()));
    }

    public void createTable(Table table) { tables.put(table.qualifiedName().toLowerCase(), table); }

    public List<Table> listTables(String database) {
        String prefix = (database == null ? "" : database.toLowerCase() + ".");
        return tables.values().stream().filter(t -> t.qualifiedName().toLowerCase().startsWith(prefix)).toList();
    }

    public Optional<Table> getTable(List<String> name) {
        return Optional.ofNullable(tables.get(String.join(".", name).toLowerCase()));
    }

    public Column column(Table table, String colName) {
        for (Column c : table.columns()) if (c.name().equalsIgnoreCase(colName)) return c;
        return new Column(colName, UnknownType.INSTANCE, true, null);
    }
}
