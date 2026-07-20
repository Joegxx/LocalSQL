package io.github.joegxx.localsql.ir.expression;

import io.github.joegxx.localsql.ir.type.DataType;

public final class Cast extends Expression {
    private final Expression child;
    private final DataType target;

    public Cast(Expression child, DataType target) {
        this.child = child;
        this.target = target;
    }

    public Expression child() { return child; }
    public DataType target() { return target; }

    @Override
    public String toString() { return "CAST(" + child + " AS " + target.typeName() + ")"; }
}
