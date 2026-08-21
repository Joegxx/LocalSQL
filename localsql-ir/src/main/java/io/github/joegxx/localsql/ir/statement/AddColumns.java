package io.github.joegxx.localsql.ir.statement;

import java.util.List;

/** ALTER TABLE tbl ADD COLUMNS (colDef, ...). Supports qualifiedColTypeWithPosition. */
public final class AddColumns extends Statement {
    private final List<String> tableName;
    private final List<ColDef> columns;

    public AddColumns(List<String> tableName, List<ColDef> columns) {
        this.tableName = List.copyOf(tableName);
        this.columns = List.copyOf(columns);
    }

    public List<String> tableName() { return tableName; }
    public List<ColDef> columns() { return columns; }

    public record ColDef(String name, io.github.joegxx.localsql.ir.type.DataType type,
                         boolean nullable, String comment, String position) {}

    @Override
    public String toString() { return "AddColumns " + String.join(".", tableName); }
}
