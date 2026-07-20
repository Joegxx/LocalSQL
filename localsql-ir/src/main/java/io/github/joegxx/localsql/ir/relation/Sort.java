package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

public final class Sort extends Relation {
    public record SortOrder(Expression expr, boolean ascending, boolean nullsLast) {}

    private final Relation child;
    private final List<SortOrder> order;

    public Sort(Relation child, List<SortOrder> order) {
        this.child = child;
        this.order = List.copyOf(order);
    }

    public Relation child() { return child; }
    public List<SortOrder> order() { return order; }

    @Override
    public List<AttributeReference> output() { return child.output(); }

    @Override
    public String toString() { return "Sort[" + order + "](" + child + ")"; }
}
