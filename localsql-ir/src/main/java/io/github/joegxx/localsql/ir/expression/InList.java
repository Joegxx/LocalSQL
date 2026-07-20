package io.github.joegxx.localsql.ir.expression;

import java.util.List;

public final class InList extends Expression {
    private final Expression value;
    private final List<Expression> list;
    private final boolean negated;

    public InList(Expression value, List<Expression> list, boolean negated) {
        this.value = value;
        this.list = List.copyOf(list);
        this.negated = negated;
    }

    public Expression value() { return value; }
    public List<Expression> list() { return list; }
    public boolean negated() { return negated; }

    @Override
    public String toString() {
        return value + (negated ? " NOT IN (" : " IN (") + String.join(", ", list.stream().map(Object::toString).toList()) + ")";
    }
}
