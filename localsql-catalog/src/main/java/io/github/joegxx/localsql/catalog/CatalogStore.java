package io.github.joegxx.localsql.catalog;

import java.util.List;
import java.util.Optional;

public interface CatalogStore {

    void init() throws Exception;

    long saveTable(Catalog.Table table) throws Exception;

    void deleteTable(long tableId) throws Exception;

    Optional<Catalog.Table> loadTable(long tableId) throws Exception;

    Optional<Catalog.Table> loadTable(String database, String tableName) throws Exception;

    List<Catalog.Table> loadTables(String database) throws Exception;

    void saveRuntimeInfo(long tableId, String duckTableName, String createSql) throws Exception;

    Optional<RuntimeInfo> loadRuntimeInfo(long tableId) throws Exception;

    List<RuntimeInfo> loadAllRuntimeInfo() throws Exception;

    record RuntimeInfo(long tableId, String duckTableName, String createSql, String lastRefresh) {}
}
