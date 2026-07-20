package io.github.joegxx.localsql.ir.expression;

import java.util.List;
import java.util.Objects;

public final class AttributeReference extends Expression {
    private final String name;
    private final List<String> qualifier;

    public AttributeReference(String name, List<String> qualifier) {
        this.name = name;
        this.qualifier = qualifier == null ? List.of() : List.copyOf(qualifier);
    }

    public String name() { return name; }
    public List<String> qualifier() { return qualifier; }

    @Override
    public String toString() {
        if (qualifier.isEmpty()) return name;
        return String.join(".", qualifier) + "." + name;
    }
}
