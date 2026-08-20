package io.github.joegxx.localsql.ir.statement;

import java.util.List;

/** DROP TABLE [IF EXISTS] t [PURGE] / DROP VIEW [IF EXISTS] v. */
public final class DropTable extends Statement {
    private final List<String> tableName;
    private final boolean view;
    private final boolean ifExists;

    public DropTable(List<String> tableName, boolean view, boolean ifExists) {
        this.tableName = List.copyOf(tableName);
        this.view = view;
        this.ifExists = ifExists;
    }

    public List<String> tableName() { return tableName; }
    public boolean view() { return view; }
    public boolean ifExists() { return ifExists; }

    @Override
    public String toString() {
        return (view ? "DropView " : "DropTable ") + String.join(".", tableName);
    }
}
