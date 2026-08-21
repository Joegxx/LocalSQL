package io.github.joegxx.localsql.ir.statement;

import java.util.List;

/** ALTER TABLE tbl DROP COLUMNS (col, ...). */
public final class DropColumns extends Statement {
    private final List<String> tableName;
    private final List<String> columns;

    public DropColumns(List<String> tableName, List<String> columns) {
        this.tableName = List.copyOf(tableName);
        this.columns = List.copyOf(columns);
    }

    public List<String> tableName() { return tableName; }
    public List<String> columns() { return columns; }

    @Override
    public String toString() { return "DropColumns " + String.join(".", tableName); }
}
