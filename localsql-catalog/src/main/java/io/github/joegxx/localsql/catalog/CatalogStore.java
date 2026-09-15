package io.github.joegxx.localsql.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for logical catalog metadata: databases, tables, columns and
 * properties. Runtime execution state (physical table names, replay DDL) is
 * a separate concern in {@link RuntimeStore}; a CatalogStore implementation
 * does not have to persist it.
 */
public interface CatalogStore extends RuntimeStore {

    void init() throws Exception;

    long saveTable(Catalog.Table table) throws Exception;

    void deleteTable(long tableId) throws Exception;

    Optional<Catalog.Table> loadTable(long tableId) throws Exception;

    Optional<Catalog.Table> loadTable(String database, String tableName) throws Exception;

    List<Catalog.Table> loadTables(String database) throws Exception;

    // ---- database-level metadata ----

    void saveDatabase(Catalog.Database database) throws Exception;

    void deleteDatabase(String name) throws Exception;

    List<Catalog.Database> loadDatabases() throws Exception;
}
