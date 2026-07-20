package io.github.joegxx.localsql.ir.expression;

import java.util.List;

public final class Star extends Expression {
    private final List<String> qualifier;

    public Star(List<String> qualifier) {
        this.qualifier = qualifier == null ? List.of() : List.copyOf(qualifier);
    }

    public List<String> qualifier() { return qualifier; }

    @Override
    public String toString() {
        return qualifier.isEmpty() ? "*" : String.join(".", qualifier) + ".*";
    }
}
