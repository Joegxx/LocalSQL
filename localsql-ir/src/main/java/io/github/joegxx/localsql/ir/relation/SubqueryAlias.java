package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;

import java.util.List;

public final class SubqueryAlias extends Relation {
    private final Relation child;
    private final String alias;

    public SubqueryAlias(Relation child, String alias) {
        this.child = child;
        this.alias = alias;
    }

    public Relation child() { return child; }
    public String alias() { return alias; }

    @Override
    public List<AttributeReference> output() { return child.output(); }

    @Override
    public String toString() { return "SubqueryAlias[" + alias + "](" + child + ")"; }
}
