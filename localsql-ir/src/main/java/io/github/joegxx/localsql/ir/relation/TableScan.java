package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;

import java.util.List;

public final class TableScan extends Relation {
    private final List<String> tableName;
    private final List<AttributeReference> output;
    private final String alias;

    public TableScan(List<String> tableName, List<AttributeReference> output) {
        this(tableName, output, null);
    }

    public TableScan(List<String> tableName, List<AttributeReference> output, String alias) {
        this.tableName = List.copyOf(tableName);
        this.output = List.copyOf(output);
        this.alias = alias;
    }

    public List<String> tableName() { return tableName; }
    public String alias() { return alias; }

    @Override
    public List<AttributeReference> output() { return output; }

    @Override
    public String toString() {
        return "UnresolvedRelation " + String.join(".", tableName);
    }
}
