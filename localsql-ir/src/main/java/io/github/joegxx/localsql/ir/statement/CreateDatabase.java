package io.github.joegxx.localsql.ir.statement;

import java.util.Map;

/** CREATE DATABASE / SCHEMA / NAMESPACE. */
public final class CreateDatabase extends Statement {
    private final String name;
    private final boolean ifNotExists;
    private final String comment;
    private final String location;
    private final Map<String, String> properties;

    public CreateDatabase(String name, boolean ifNotExists, String comment,
                          String location, Map<String, String> properties) {
        this.name = name;
        this.ifNotExists = ifNotExists;
        this.comment = comment;
        this.location = location;
        this.properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public String name() { return name; }
    public boolean ifNotExists() { return ifNotExists; }
    public String comment() { return comment; }
    public String location() { return location; }
    public Map<String, String> properties() { return properties; }

    @Override
    public String toString() { return "CreateDatabase " + name; }
}
