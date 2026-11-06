package io.github.joegxx.localsql.catalog;

import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.ir.type.StringType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Logical catalog facade: databases, tables, columns and properties.
 * All mutations funnel through this class so callers cannot bypass the
 * store (when present) or mutate internal state through leaked references.
 * Physical runtime bindings live in {@link RuntimeStore}, executed and
 * written by the orchestration layer — never here.
 */
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

    /**
     * A frozen snapshot of the current committed state. DDL orchestration
     * snapshots before mutating, restores on failure and keeps the live
     * catalog committed-only.
     */
    public Catalog snapshot() {
        return catalog.copy();
    }

    /** Atomically replace the live catalog with the given snapshot (commit). */
    public void commitSnapshot(Catalog snapshot) {
        catalog.restoreFrom(snapshot);
    }

    /** Discard in-memory changes and reload the last state persisted in the store. */
    public void reloadFromStore() throws Exception {
        if (store == null) {
            catalog.restoreFrom(new Catalog());
            return;
        }
        catalog.restoreFrom(new Catalog());
        loadFromStore();
    }

    private void loadFromStore() throws Exception {
        if (store == null) return;
        List<Catalog.Database> databases = store.loadDatabases();
        for (Catalog.Database db : databases) {
            catalog.createDatabase(db);
        }
        if (catalog.getDatabase("default").isEmpty()) {
            catalog.createDatabase(new Catalog.Database("default", null, "default database"));
        }
        List<Catalog.Table> tables = store.loadTables(null);
        for (Catalog.Table t : tables) {
            String database = t.database();
            if (catalog.getDatabase(database).isEmpty()) {
                catalog.createDatabase(new Catalog.Database(database, null, database + " database"));
            }
        }
        for (Catalog.Table t : tables) {
            catalog.createTable(t);
        }
    }

    public Catalog.Table registerTable(String database, String tableName, String tableType,
                                       List<ColumnDef> columnDefs, String location, String format,
                                       String comment, Map<String, String> properties,
                                       String metadataJson) throws Exception {
        return registerTable(database, tableName, tableType, columnDefs, location, format,
                comment, properties, metadataJson, "main");
    }

    /**
     * @param physicalSchema DuckDB schema holding the physical object: main
     *                      for the default database / legacy layout, the
     *                      database name for per-database schemas.
     */
    public Catalog.Table registerTable(String database, String tableName, String tableType,
                                        List<ColumnDef> columnDefs, String location, String format,
                                        String comment, Map<String, String> properties,
                                        String metadataJson, String physicalSchema) throws Exception {
        List<Catalog.Column> cols = new ArrayList<>();
        int ordinal = 0;
        for (ColumnDef cd : columnDefs) {
            cols.add(new Catalog.Column(cd.name(), cd.type(), cd.nullable(), cd.comment(),
                    ordinal++, cd.defaultValue(), cd.expression()));
        }
        if (catalog.getTable(database, tableName).isPresent()) {
            throw new IllegalStateException("Table already registered: " + database + "." + tableName
                    + "; use updateTable to replace an existing definition");
        }
        String schema = physicalSchema == null || physicalSchema.isBlank() ? "main" : physicalSchema;
        Catalog.Table table = new Catalog.Table(0, List.of(database, tableName), tableType,
                cols, location, format, comment,
                properties == null ? new LinkedHashMap<>() : properties,
                metadataJson, schema);
        if (store != null) {
            long id = store.saveTable(table);
            table = new Catalog.Table(id, table.name(), table.tableType(), table.columns(),
                    table.location(), table.format(), table.comment(),
                    table.properties(), table.metadataJson(), table.physicalSchema());
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

    /**
     * Update a table's logical metadata in memory and in the store.
     * Callers build the whole replacement record; nothing is patched here.
     */
    public void updateTable(Catalog.Table table) throws Exception {
        catalog.updateTable(table);
        if (store != null) store.saveTable(table);
    }

    /** Drop a table: remove from memory and delete from the persistent store. */
    public void dropTable(String database, String tableName) throws Exception {
        Catalog.Table removed = catalog.removeTable(database, tableName);
        if (removed != null && store != null) {
            store.deleteTable(removed.tableId());
        }
    }

    public void createDatabase(String name, String comment, String location) {
        createDatabase(name, comment, location, Map.of());
    }

    public void createDatabase(String name, String comment, String location, Map<String, String> properties) {
        Catalog.Database db = new Catalog.Database(name, location, comment,
                properties == null ? Map.of() : properties);
        catalog.createDatabase(db);
        if (store != null) {
            try {
                store.saveDatabase(db);
            } catch (Exception e) {
                catalog.removeDatabase(name);
                throw new IllegalStateException("Failed to persist database " + name + ": " + e.getMessage(), e);
            }
        }
    }

    public void alterDatabase(String name, Map<String, String> properties, String location) {
        Catalog.Database old = catalog.getDatabase(name).orElseThrow(() -> new IllegalArgumentException("Database does not exist: " + name));
        Map<String, String> merged = new LinkedHashMap<>(old.properties());
        if (properties != null) merged.putAll(properties);
        String newLocation = location != null ? location : old.location();
        Catalog.Database updated = new Catalog.Database(old.name(), newLocation, old.description(), merged);
        catalog.createDatabase(updated);
        if (store != null) {
            try {
                store.saveDatabase(updated);
            } catch (Exception e) {
                catalog.createDatabase(old);
                throw new IllegalStateException("Failed to persist database " + name + " change: " + e.getMessage(), e);
            }
        }
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
        if (store != null) store.deleteDatabase(name);
        return true;
    }

    /**
     * Logical rename only: memory + store records move to the new name. The
     * caller (orchestration layer) owns the physical RENAME and the runtime
     * definition update — this class never edits SQL strings.
     */
    public void renameTable(String fromDb, String fromTable, String toDb, String toTable) throws Exception {
        Catalog.Table old = catalog.getTable(fromDb, fromTable).orElse(null);
        if (old == null) throw new IllegalArgumentException("Table does not exist: " + fromDb + "." + fromTable);
        Catalog.Table existing = catalog.getTable(toDb, toTable).orElse(null);
        if (existing != null) throw new IllegalArgumentException("Target table already exists: " + toDb + "." + toTable);
        Catalog.Table renamed = new Catalog.Table(old.tableId(), List.of(toDb, toTable), old.tableType(), old.columns(),
                old.location(), old.format(), old.comment(), old.properties(), old.metadataJson(),
                old.physicalSchema());
        catalog.renameTable(fromDb, fromTable, toDb, toTable);
        if (store != null) {
            try {
                store.saveTable(renamed);
            } catch (Exception e) {
                catalog.renameTable(toDb, toTable, fromDb, fromTable);
                throw new IllegalStateException("Failed to persist rename of " + fromDb + "." + fromTable
                        + ": " + e.getMessage(), e);
            }
        }
    }

    public void refreshTable(String database, String tableName, List<ColumnDef> newCols) throws Exception {
        Catalog.Table old = catalog.getTable(database, tableName)
                .orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + database + "." + tableName));
        Catalog.Table updated = new Catalog.Table(old.tableId(), old.name(), old.tableType(), toCatalogColumns(newCols),
                old.location(), old.format(), old.comment(), old.properties(), old.metadataJson(),
                old.physicalSchema());
        catalog.updateTable(updated);
        if (store != null) {
            try {
                store.saveTable(updated);
            } catch (Exception e) {
                catalog.updateTable(old);
                throw new IllegalStateException("Failed to persist column refresh for " + database + "."
                        + tableName + ": " + e.getMessage(), e);
            }
        }
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
