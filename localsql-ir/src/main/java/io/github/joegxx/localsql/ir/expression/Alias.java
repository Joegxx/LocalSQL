package io.github.joegxx.localsql.ir.expression;

import java.util.List;
import java.util.Objects;

public final class Alias extends Expression {
    private final Expression child;
    private final String name;
    private final List<String> qualifier;

    public Alias(Expression child, String name, List<String> qualifier) {
        this.child = child;
        this.name = name;
        this.qualifier = qualifier == null ? List.of() : List.copyOf(qualifier);
    }

    public Expression child() { return child; }
    public String name() { return name; }
    public List<String> qualifier() { return qualifier; }

    @Override
    public String toString() { return child + " AS " + name; }
}
