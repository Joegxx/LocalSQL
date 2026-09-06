package io.github.joegxx.localsql.udf.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Wire-neutral batch contract; transport implementations may encode values with Arrow. */
public record UdfBatch(List<String> columnNames, List<List<Object>> rows) {
    public UdfBatch {
        columnNames = columnNames == null
                ? List.of() : Collections.unmodifiableList(new ArrayList<>(columnNames));
        rows = rows == null
                ? List.of()
                : rows.stream().map(row -> (List<Object>) Collections.unmodifiableList(new ArrayList<>(row))).toList();
    }

    public int rowCount() {
        return rows.size();
    }
}
