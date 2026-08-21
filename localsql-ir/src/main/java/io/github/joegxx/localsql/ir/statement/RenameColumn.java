package io.github.joegxx.localsql.ir.statement;

import java.util.List;

/** ALTER TABLE tbl RENAME COLUMN from TO to. */
public final class RenameColumn extends Statement {
    private final List<String> tableName;
    private final String from;
    private final String to;

    public RenameColumn(List<String> tableName, String from, String to) {
        this.tableName = List.copyOf(tableName);
        this.from = from;
        this.to = to;
    }

    public List<String> tableName() { return tableName; }
    public String from() { return from; }
    public String to() { return to; }

    @Override
    public String toString() { return "RenameColumn " + String.join(".", tableName) + "." + from + " -> " + to; }
}
