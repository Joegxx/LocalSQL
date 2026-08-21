package io.github.joegxx.localsql.catalog;

import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.type.StringType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CatalogService {

    private final Catalog catalog = new Catalog();
    private final CatalogStore store;

    public CatalogService() {
        this.store = null;
    }

    public CatalogService(CatalogStore store) throws Exception {
        this.store = store;
        this.store.init();
        loadFromStore();
    }

    public Catalog catalog() { return catalog; }

    public CatalogStore store() { return store; }

    private void loadFromStore() throws Exception {
        if (store == null) return;
        for (Catalog.Table t : store.loadTables(null)) {
            catalog.createTable(t);
        }
    }

    public Catalog.Table registerTable(String database, String tableName, String tableType,
                                       List<ColumnDef> columnDefs, String location, String format,
                                       String comment, Map<String, String> properties,
                                       String metadataJson) throws Exception {
        List<Catalog.Column> cols = new ArrayList<>();
        int ordinal = 0;
        for (ColumnDef cd : columnDefs) {
            cols.add(new Catalog.Column(cd.name(), cd.type(), cd.nullable(), cd.comment(),
                    ordinal++, cd.defaultValue(), cd.expression()));
        }
        Catalog.Table table = new Catalog.Table(0, List.of(database, tableName), tableType,
                cols, location, format, comment,
                properties == null ? new LinkedHashMap<>() : properties,
                metadataJson);
        if (store != null) {
            long id = store.saveTable(table);
            table = new Catalog.Table(id, table.name(), table.tableType(), table.columns(),
                    table.location(), table.format(), table.comment(),
                    table.properties(), table.metadataJson());
        }
        catalog.createTable(table);
        return table;
    }

    public void registerSampleTable(String name, String... columns) {
        var colList = new ArrayList<Catalog.Column>();
        int ordinal = 0;
        for (String c : columns) colList.add(new Catalog.Column(c, new StringType(), true, null, ordinal++, null, null));
        catalog.createTable(new Catalog.Table(List.of("default", name), colList, null, "parquet"));
    }

    public Optional<Catalog.Table> getTable(String database, String tableName) {
        return catalog.getTable(database, tableName);
    }

    /** Drop a table: remove from memory and delete from the persistent store. */
    public void dropTable(String database, String tableName) throws Exception {
        Catalog.Table removed = catalog.removeTable(database, tableName);
        if (removed != null && store != null) {
            store.deleteTable(removed.tableId());
        }
    }

    public void createDatabase(String name, String comment, String location) {
        catalog.createDatabase(new Catalog.Database(name, location, comment));
    }

    public void createDatabase(String name, String comment, String location, Map<String, String> properties) {
        catalog.createDatabase(new Catalog.Database(name, location, comment, properties == null ? Map.of() : Map.copyOf(properties)));
    }

    public void alterDatabase(String name, Map<String, String> properties, String location) {
        Catalog.Database old = catalog.getDatabase(name).orElseThrow(() -> new IllegalArgumentException("Database does not exist: " + name));
        Map<String, String> merged = new LinkedHashMap<>(old.properties());
        if (properties != null) merged.putAll(properties);
        String newLocation = location != null ? location : old.location();
        // keep description
        Catalog.Database updated = new Catalog.Database(old.name(), newLocation, old.description(), Map.copyOf(merged));
        catalog.createDatabase(updated);
    }

    public boolean dropDatabase(String name, boolean cascade) throws Exception {
        if ("default".equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("Cannot drop default database");
        }
        Catalog.Database db = catalog.getDatabase(name).orElse(null);
        if (db == null) return false;
        List<Catalog.Table> tables = catalog.listTables(name);
        if (!tables.isEmpty() && !cascade) {
            throw new IllegalArgumentException("Database " + name + " is not empty. Use CASCADE to drop.");
        }
        for (Catalog.Table t : List.copyOf(tables)) {
            dropTable(t.database(), t.tableName());
        }
        catalog.removeDatabase(name);
        return true;
    }

    public void renameTable(String fromDb, String fromTable, String toDb, String toTable) throws Exception {
        Catalog.Table old = catalog.getTable(fromDb, fromTable).orElse(null);
        if (old == null) throw new IllegalArgumentException("Table does not exist: " + fromDb + "." + fromTable);
        Catalog.Table existing = catalog.getTable(toDb, toTable).orElse(null);
        if (existing != null) throw new IllegalArgumentException("Target table already exists: " + toDb + "." + toTable);
        Catalog.Table renamed = new Catalog.Table(old.tableId(), List.of(toDb, toTable), old.tableType(), old.columns(),
                old.location(), old.format(), old.comment(), old.properties(), old.metadataJson());
        catalog.renameTable(fromDb, fromTable, toDb, toTable);
        if (store != null) {
            store.saveTable(renamed);
            var rt = store.loadRuntimeInfo(old.tableId());
            if (rt.isPresent()) {
                String oldSql = rt.get().createSql();
                String patched = oldSql;
                if (oldSql != null) {
                    // Patch CREATE TABLE/VIEW prefix so replay recreates the new name.
                    // Handles both quoted and unquoted forms.
                    String quotedFrom = "\"" + fromTable + "\"";
                    String quotedTo = "\"" + toTable + "\"";
                    if (oldSql.contains(quotedFrom)) {
                        patched = oldSql.replaceFirst(java.util.regex.Pattern.quote(quotedFrom), java.util.regex.Matcher.quoteReplacement(quotedTo));
                    } else {
                        patched = oldSql.replaceFirst("(?i)(CREATE\\s+(?:TABLE|VIEW)\\s+)" + java.util.regex.Pattern.quote(fromTable), "$1" + java.util.regex.Matcher.quoteReplacement(toTable));
                        if (patched.equals(oldSql)) {
                            patched = oldSql.replaceFirst("\\b" + java.util.regex.Pattern.quote(fromTable) + "\\b", java.util.regex.Matcher.quoteReplacement(toTable));
                        }
                    }
                }
                store.saveRuntimeInfo(old.tableId(), toTable, patched);
            }
        }
    }

    public void refreshTable(String database, String tableName, List<ColumnDef> newCols) throws Exception {
        Catalog.Table old = catalog.getTable(database, tableName)
                .orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + database + "." + tableName));
        Catalog.Table updated = new Catalog.Table(old.tableId(), old.name(), old.tableType(), toCatalogColumns(newCols),
                old.location(), old.format(), old.comment(), old.properties(), old.metadataJson());
        catalog.updateTable(updated);
        if (store != null) store.saveTable(updated);
    }

    private static List<Catalog.Column> toCatalogColumns(List<ColumnDef> defs) {
        List<Catalog.Column> out = new ArrayList<>();
        int ord = 0;
        for (ColumnDef d : defs) {
            out.add(new Catalog.Column(d.name(), d.type(), d.nullable(), d.comment(), ord++, d.defaultValue(), d.expression()));
        }
        return out;
    }

    public record ColumnDef(String name, DataType type, boolean nullable, String comment,
                            String defaultValue, String expression) {
        public ColumnDef(String name, DataType type, boolean nullable, String comment) {
            this(name, type, nullable, comment, null, null);
        }
    }
}
