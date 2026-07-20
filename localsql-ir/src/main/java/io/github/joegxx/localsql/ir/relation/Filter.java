package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

public final class Filter extends Relation {
    private final Relation child;
    private final Expression condition;

    public Filter(Relation child, Expression condition) {
        this.child = child;
        this.condition = condition;
    }

    public Relation child() { return child; }
    public Expression condition() { return condition; }

    @Override
    public List<AttributeReference> output() { return child.output(); }

    @Override
    public String toString() { return "Filter[" + condition + "](" + child + ")"; }
}
