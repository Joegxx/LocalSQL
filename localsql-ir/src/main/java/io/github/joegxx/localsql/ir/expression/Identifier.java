package io.github.joegxx.localsql.ir.expression;

import java.util.List;

public final class Identifier extends Expression {
    private final String name;
    private final boolean quoted;

    public Identifier(String name, boolean quoted) {
        this.name = name;
        this.quoted = quoted;
    }

    public String name() { return name; }
    public boolean quoted() { return quoted; }

    @Override
    public String toString() { return quoted ? "`" + name + "`" : name; }
}
