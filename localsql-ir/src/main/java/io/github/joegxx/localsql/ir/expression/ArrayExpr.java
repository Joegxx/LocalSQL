package io.github.joegxx.localsql.ir.expression;

import java.util.List;

public final class ArrayExpr extends Expression {
    private final List<Expression> elements;

    public ArrayExpr(List<Expression> elements) { this.elements = List.copyOf(elements); }
    public List<Expression> elements() { return elements; }

    @Override
    public String toString() { return "array(" + String.join(", ", elements.stream().map(Object::toString).toList()) + ")"; }
}
