package io.github.joegxx.localsql.ir.statement;

import java.util.Map;

/** ALTER DATABASE db SET DBPROPERTIES (...) or SET LOCATION '...'. */
public final class AlterDatabase extends Statement {
    private final String name;
    private final Map<String, String> properties;
    private final String location;

    public AlterDatabase(String name, Map<String, String> properties, String location) {
        this.name = name;
        this.properties = properties == null ? Map.of() : Map.copyOf(properties);
        this.location = location;
    }

    public String name() { return name; }
    public Map<String, String> properties() { return properties; }
    public String location() { return location; }

    @Override
    public String toString() { return "AlterDatabase " + name; }
}
