package io.github.joegxx.localsql.ir.expression;

import java.util.List;

public final class UnaryExpression extends Expression {
    public enum Op { NEG, NOT, IS_NULL, IS_NOT_NULL }

    private final Op op;
    private final Expression child;

    public UnaryExpression(Op op, Expression child) {
        this.op = op;
        this.child = child;
    }

    public Op op() { return op; }
    public Expression child() { return child; }

    @Override
    public String toString() {
        return switch (op) {
            case NEG -> "-(" + child + ")";
            case NOT -> "NOT(" + child + ")";
            case IS_NULL -> "(" + child + " IS NULL)";
            case IS_NOT_NULL -> "(" + child + " IS NOT NULL)";
        };
    }
}
