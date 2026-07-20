package io.github.joegxx.localsql.ir.expression;

import java.util.List;
import java.util.Objects;

public final class FunctionCall extends Expression {
    private String name;
    private final List<Expression> arguments;
    private final boolean distinct;

    public FunctionCall(String name, List<Expression> arguments) {
        this(name, arguments, false);
    }

    public FunctionCall(String name, List<Expression> arguments, boolean distinct) {
        this.name = name;
        this.arguments = List.copyOf(arguments);
        this.distinct = distinct;
    }

    public String name() { return name; }
    public void rename(String newName) { this.name = newName; }
    public List<Expression> arguments() { return arguments; }
    public boolean distinct() { return distinct; }

    @Override
    public String toString() {
        return name + "(" + (distinct ? "DISTINCT " : "") + String.join(", ", arguments.stream().map(Object::toString).toList()) + ")";
    }
}
