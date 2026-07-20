package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

public final class Window extends Relation {
    public record WindowSpec(List<Expression> partitionBy, List<Sort.SortOrder> orderBy) {}

    private final Relation child;
    private final List<Expression> windowExpressions;
    private final WindowSpec spec;

    public Window(Relation child, List<Expression> windowExpressions, WindowSpec spec) {
        this.child = child;
        this.windowExpressions = List.copyOf(windowExpressions);
        this.spec = spec;
    }

    public Relation child() { return child; }
    public List<Expression> windowExpressions() { return windowExpressions; }
    public WindowSpec spec() { return spec; }

    @Override
    public List<AttributeReference> output() { return child.output(); }

    @Override
    public String toString() { return "Window[" + windowExpressions + "](" + child + ")"; }
}
