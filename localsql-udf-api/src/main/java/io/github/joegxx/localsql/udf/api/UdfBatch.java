package io.github.joegxx.localsql.udf.api;

import java.util.List;

/** Wire-neutral batch contract; transport implementations may encode values with Arrow. */
public record UdfBatch(List<String> columnNames, List<List<Object>> rows) {
    public UdfBatch {
        columnNames = List.copyOf(columnNames == null ? List.of() : columnNames);
        rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
    }

    public int rowCount() {
        return rows.size();
    }
}
