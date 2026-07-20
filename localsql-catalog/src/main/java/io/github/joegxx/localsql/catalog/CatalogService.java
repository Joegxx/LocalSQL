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

    public record ColumnDef(String name, DataType type, boolean nullable, String comment,
                            String defaultValue, String expression) {
        public ColumnDef(String name, DataType type, boolean nullable, String comment) {
            this(name, type, nullable, comment, null, null);
        }
    }
}
