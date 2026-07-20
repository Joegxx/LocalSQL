package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

public final class Limit extends Relation {
    private final Relation child;
    private final long limit;
    private final long offset;

    public Limit(Relation child, long limit, long offset) {
        this.child = child;
        this.limit = limit;
        this.offset = offset;
    }

    public Relation child() { return child; }
    public long limit() { return limit; }
    public long offset() { return offset; }

    @Override
    public List<AttributeReference> output() { return child.output(); }

    @Override
    public String toString() { return "Limit[" + limit + "," + offset + "](" + child + ")"; }
}
