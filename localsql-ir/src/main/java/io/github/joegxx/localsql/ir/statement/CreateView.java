package io.github.joegxx.localsql.ir.statement;

import io.github.joegxx.localsql.ir.relation.Relation;

import java.util.List;

/** CREATE [OR REPLACE] VIEW v AS query. */
public final class CreateView extends Statement {
    private final List<String> tableName;
    private final boolean orReplace;
    private final boolean ifNotExists;
    private final Relation query;

    public CreateView(List<String> tableName, boolean orReplace, boolean ifNotExists, Relation query) {
        this.tableName = List.copyOf(tableName);
        this.orReplace = orReplace;
        this.ifNotExists = ifNotExists;
        this.query = query;
    }

    public List<String> tableName() { return tableName; }
    public boolean orReplace() { return orReplace; }
    public boolean ifNotExists() { return ifNotExists; }
    public Relation query() { return query; }

    @Override
    public String toString() {
        return "CreateView " + String.join(".", tableName);
    }
}
