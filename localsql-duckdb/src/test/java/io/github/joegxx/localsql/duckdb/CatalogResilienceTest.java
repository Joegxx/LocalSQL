package io.github.joegxx.localsql.duckdb;

import io.github.joegxx.localsql.catalog.Catalog;
import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.ir.type.IntegralType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1 acceptance: catalog cache survives store failures, callers cannot
 * mutate internal metadata through references, and the snapshot protocol
 * publishes atomically.
 */
class CatalogResilienceTest {

    /** Delegating store whose saveTable can be made to fail on demand. */
    private static final class FailingStore implements CatalogStore {
        private final CatalogStore delegate;
        final AtomicBoolean failSaves = new AtomicBoolean(false);
        int saveCalls = 0;

        FailingStore(CatalogStore delegate) { this.delegate = delegate; }

        @Override public void init() throws Exception { delegate.init(); }
        @Override public long saveTable(Catalog.Table table) throws Exception {
            saveCalls++;
            if (failSaves.get()) throw new IllegalStateException("injected store failure");
            return delegate.saveTable(table);
        }
        @Override public void deleteTable(long tableId) throws Exception { delegate.deleteTable(tableId); }
        @Override public Optional<Catalog.Table> loadTable(long tableId) throws Exception { return delegate.loadTable(tableId); }
        @Override public Optional<Catalog.Table> loadTable(String db, String t) throws Exception { return delegate.loadTable(db, t); }
        @Override public List<Catalog.Table> loadTables(String db) throws Exception { return delegate.loadTables(db); }
        @Override public void saveDatabase(Catalog.Database d) throws Exception {
            if (failSaves.get()) throw new IllegalStateException("injected store failure");
            delegate.saveDatabase(d);
        }
        @Override public void deleteDatabase(String name) throws Exception { delegate.deleteDatabase(name); }
        @Override public List<Catalog.Database> loadDatabases() throws Exception { return delegate.loadDatabases(); }
        @Override public void saveRuntimeInfo(long id, String n, String s) throws Exception { delegate.saveRuntimeInfo(id, n, s); }
        @Override public Optional<RuntimeInfo> loadRuntimeInfo(long id) throws Exception { return delegate.loadRuntimeInfo(id); }
        @Override public List<RuntimeInfo> loadAllRuntimeInfo() throws Exception { return delegate.loadAllRuntimeInfo(); }
    }

    private static CatalogService.ColumnDef idCol() {
        return new CatalogService.ColumnDef("id", IntegralType.INT, true, null);
    }

    @Test
    void registerTableFailureLeavesCacheUnchanged() throws Exception {
        try (DuckDbExecutor exec = new DuckDbExecutor()) {
            FailingStore store = new FailingStore(new DuckDbCatalogStore(exec));
            CatalogService svc = new CatalogService(store);

            store.failSaves.set(true);
            assertThrows(Exception.class, () -> svc.registerTable("default", "t", "TABLE",
                    List.of(idCol()), null, null, null, null, null));
            // cache must not contain the failed table
            assertTrue(svc.getTable("default", "t").isEmpty(),
                    "a failed registration must not pollute the in-memory cache");
            store.failSaves.set(false);
            svc.registerTable("default", "t", "TABLE", List.of(idCol()), null, null, null, null, null);
            assertTrue(svc.getTable("default", "t").isPresent());
        }
    }

    @Test
    void refreshTableFailureKeepsOldColumns() throws Exception {
        try (DuckDbExecutor exec = new DuckDbExecutor()) {
            FailingStore store = new FailingStore(new DuckDbCatalogStore(exec));
            CatalogService svc = new CatalogService(store);
            svc.registerTable("default", "t", "TABLE", List.of(idCol()), null, null, null, null, null);

            store.failSaves.set(true);
            assertThrows(Exception.class, () -> svc.refreshTable("default", "t",
                    List.of(new CatalogService.ColumnDef("id", IntegralType.BIGINT, true, null),
                            new CatalogService.ColumnDef("extra", IntegralType.INT, true, null))));
            // old single-column definition must survive in cache
            assertEquals(1, svc.getTable("default", "t").orElseThrow().columns().size());
        }
    }

    @Test
    void snapshotProtocolIsolatesWorkingChanges() throws Exception {
        CatalogService svc = new CatalogService();
        svc.registerTable("default", "t", "TABLE", List.of(idCol()), null, null, null, null, null);

        Catalog working = svc.snapshot();
        working.createTable(new Catalog.Table(0, List.of("default", "staging"), "TABLE",
                List.of(new Catalog.Column("id", IntegralType.INT, true, null, 0, null, null)),
                null, null, null, null, null));
        // the working snapshot is isolated from the committed catalog
        assertTrue(svc.getTable("default", "staging").isEmpty(),
                "parallel readers must not observe working-snapshot tables");
        // publish makes it visible
        svc.commitSnapshot(working);
        assertTrue(svc.getTable("default", "staging").isPresent());
    }

    @Test
    void externalMutationCannotCorruptInternalState() throws Exception {
        CatalogService svc = new CatalogService();
        var table = svc.registerTable("default", "t", "TABLE",
                List.of(new CatalogService.ColumnDef("id", IntegralType.INT, true, "comment"),
                        new CatalogService.ColumnDef("name", new io.github.joegxx.localsql.ir.type.StringType(), true, null)),
                null, null, null, null, null);
        // mutate everything the caller can reach
        assertThrows(UnsupportedOperationException.class,
                () -> table.columns().clear(), "columns list must be immutable");
        assertThrows(UnsupportedOperationException.class,
                () -> table.name().add("x"), "name list must be immutable");
        assertThrows(UnsupportedOperationException.class,
                () -> table.properties().put("k", "v"), "properties map must be immutable");
        // and the catalog still reports the original column set
        assertEquals(2, svc.getTable("default", "t").orElseThrow().columns().size());

        // listTables returns a copy: clearing it must not affect the catalog
        svc.catalog().listTables("default").clear();
        assertEquals(1, svc.catalog().listTables("default").size());
        // same for listDatabases
        svc.catalog().listDatabases().clear();
        assertTrue(svc.catalog().getDatabase("default").isPresent());
    }

    @Test
    void createDatabaseRollsBackMemoryOnStoreFailure() throws Exception {
        try (DuckDbExecutor exec = new DuckDbExecutor()) {
            FailingStore store = new FailingStore(new DuckDbCatalogStore(exec));
            CatalogService svc = new CatalogService(store);
            store.failSaves.set(true);
            assertThrows(Exception.class, () -> svc.createDatabase("doomed", null, null));
            assertTrue(svc.catalog().getDatabase("doomed").isEmpty(),
                    "failed database persistence must not stay in the cache");
        }
    }
}
