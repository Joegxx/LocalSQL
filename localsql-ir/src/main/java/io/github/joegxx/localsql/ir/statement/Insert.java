package io.github.joegxx.localsql.ir.statement;

import io.github.joegxx.localsql.ir.relation.Relation;

import java.util.List;

/**
 * INSERT INTO / INSERT OVERWRITE.
 * tableName keeps the raw identifier parts (["t"], ["db","t"], ["catalog","db","t"]);
 * name resolution is the analyzer's job. columns is null when no column list
 * was given, meaning positional insert into all columns.
 */
public final class Insert extends Statement {
    private final List<String> tableName;
    private final boolean overwrite;
    private final List<String> columns;
    private final Relation query;

    public Insert(List<String> tableName, boolean overwrite, List<String> columns, Relation query) {
        this.tableName = List.copyOf(tableName);
        this.overwrite = overwrite;
        this.columns = columns == null ? null : List.copyOf(columns);
        this.query = query;
    }

    public List<String> tableName() { return tableName; }
    public boolean overwrite() { return overwrite; }
    public List<String> columns() { return columns; }
    public Relation query() { return query; }

    @Override
    public String toString() {
        return (overwrite ? "InsertOverwrite " : "InsertInto ") + String.join(".", tableName);
    }
}
