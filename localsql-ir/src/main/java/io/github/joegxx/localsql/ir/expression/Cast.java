package io.github.joegxx.localsql.ir.expression;

import io.github.joegxx.localsql.ir.type.DataType;

public final class Cast extends Expression {
    private final Expression child;
    private final DataType target;
    private final boolean tryCast;

    public Cast(Expression child, DataType target) {
        this(child, target, false);
    }

    /**
     * @param tryCast emits DuckDB's {@code TRY_CAST}: errors become NULL,
     *                used to express Spark's try_* overflow semantics.
     */
    public Cast(Expression child, DataType target, boolean tryCast) {
        this.child = child;
        this.target = target;
        this.tryCast = tryCast;
    }

    public Expression child() { return child; }
    public DataType target() { return target; }
    public boolean tryCast() { return tryCast; }

    @Override
    public String toString() {
        return (tryCast ? "TRY_CAST(" : "CAST(") + child + " AS " + target.typeName() + ")";
    }
}
