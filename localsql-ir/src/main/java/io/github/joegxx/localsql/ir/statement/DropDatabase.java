package io.github.joegxx.localsql.ir.statement;

/** DROP DATABASE / SCHEMA / NAMESPACE. */
public final class DropDatabase extends Statement {
    private final String name;
    private final boolean ifExists;
    private final boolean cascade;

    public DropDatabase(String name, boolean ifExists, boolean cascade) {
        this.name = name;
        this.ifExists = ifExists;
        this.cascade = cascade;
    }

    public String name() { return name; }
    public boolean ifExists() { return ifExists; }
    public boolean cascade() { return cascade; }

    @Override
    public String toString() { return "DropDatabase " + name; }
}
