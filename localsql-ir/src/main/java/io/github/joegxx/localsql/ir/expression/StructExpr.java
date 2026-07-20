package io.github.joegxx.localsql.ir.expression;

import java.util.List;

public final class StructExpr extends Expression {
    private final List<Expression> fields;

    public StructExpr(List<Expression> fields) { this.fields = List.copyOf(fields); }
    public List<Expression> fields() { return fields; }

    @Override
    public String toString() { return "struct(" + String.join(", ", fields.stream().map(Object::toString).toList()) + ")"; }
}
