package io.github.joegxx.localsql.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for physical runtime bindings: which DuckDB table name backs a
 * logical table and the DDL that (re)creates it. Kept separate from
 * {@link CatalogStore} so the logical catalog never depends on runtime
 * execution details.
 */
public interface RuntimeStore {

    void saveRuntimeInfo(long tableId, String duckTableName, String createSql) throws Exception;

    Optional<RuntimeInfo> loadRuntimeInfo(long tableId) throws Exception;

    List<RuntimeInfo> loadAllRuntimeInfo() throws Exception;

    record RuntimeInfo(long tableId, String duckTableName, String createSql, String lastRefresh) {}
}
