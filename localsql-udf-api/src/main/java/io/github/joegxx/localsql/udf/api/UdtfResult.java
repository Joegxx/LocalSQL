package io.github.joegxx.localsql.udf.api;

import java.util.List;

/**
 * Result of a table-generating UDF batch: for every produced row,
 * {@code sourceRows} records the index of the input row it came from.
 * Column names and DuckDB type names come from the UDTF's initialize().
 */
public record UdtfResult(
        List<String> columnNames,
        List<String> columnTypes,
        List<List<Object>> rows,
        List<Integer> sourceRows) {

    public UdtfResult {
        columnNames = List.copyOf(columnNames == null ? List.of() : columnNames);
        columnTypes = List.copyOf(columnTypes == null ? List.of() : columnTypes);
        rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
        sourceRows = List.copyOf(sourceRows == null ? List.of() : sourceRows);
    }
}
