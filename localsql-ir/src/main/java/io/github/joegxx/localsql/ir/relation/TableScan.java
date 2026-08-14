package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;

import java.util.List;

public final class TableScan extends Relation {
    private final List<String> tableName;
    private List<AttributeReference> output;
    private final String alias;

    public TableScan(List<String> tableName, List<AttributeReference> output) {
        this(tableName, output, null);
    }

    public TableScan(List<String> tableName, List<AttributeReference> output, String alias) {
        this.tableName = List.copyOf(tableName);
        this.output = output == null ? List.of() : new java.util.ArrayList<>(output);
        this.alias = alias;
    }

    public List<String> tableName() { return tableName; }
    public String alias() { return alias; }

    @Override
    public List<AttributeReference> output() { return output; }

    public void setOutput(List<AttributeReference> output) { this.output = output; }

    @Override
    public String toString() {
        return "UnresolvedRelation " + String.join(".", tableName);
    }
}
