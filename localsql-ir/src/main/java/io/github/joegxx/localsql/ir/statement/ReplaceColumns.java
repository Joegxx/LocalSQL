package io.github.joegxx.localsql.ir.statement;

import java.util.List;

/** ALTER TABLE tbl REPLACE COLUMNS (colDef, ...). */
public final class ReplaceColumns extends Statement {
    private final List<String> tableName;
    private final List<AddColumns.ColDef> columns;

    public ReplaceColumns(List<String> tableName, List<AddColumns.ColDef> columns) {
        this.tableName = List.copyOf(tableName);
        this.columns = List.copyOf(columns);
    }

    public List<String> tableName() { return tableName; }
    public List<AddColumns.ColDef> columns() { return columns; }

    @Override
    public String toString() { return "ReplaceColumns " + String.join(".", tableName); }
}
