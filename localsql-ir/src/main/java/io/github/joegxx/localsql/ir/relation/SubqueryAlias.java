package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;

import java.util.List;

public final class SubqueryAlias extends Relation {
    private final Relation child;
    private final String alias;
    private final List<AttributeReference> output;

    public SubqueryAlias(Relation child, String alias) {
        this(child, alias, child == null ? List.of() : child.output());
    }

    /** @param output explicitly supplied output (e.g. analyzer re-qualified columns) */
    public SubqueryAlias(Relation child, String alias, List<AttributeReference> output) {
        this.child = child;
        this.alias = alias;
        this.output = output == null ? List.of() : List.copyOf(output);
    }

    public Relation child() { return child; }
    public String alias() { return alias; }

    @Override
    public List<AttributeReference> output() { return output; }

    @Override
    public String toString() { return "SubqueryAlias[" + alias + "](" + child + ")"; }
}
