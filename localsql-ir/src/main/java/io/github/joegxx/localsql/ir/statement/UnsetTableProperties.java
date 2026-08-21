package io.github.joegxx.localsql.ir.statement;

import java.util.List;
import java.util.Map;

/** ALTER TABLE/VIEW tbl UNSET TBLPROPERTIES [IF EXISTS] (...). */
public final class UnsetTableProperties extends Statement {
    private final List<String> tableName;
    private final Map<String, String> properties;
    private final boolean ifExists;

    public UnsetTableProperties(List<String> tableName, Map<String, String> properties, boolean ifExists) {
        this.tableName = List.copyOf(tableName);
        this.properties = Map.copyOf(properties);
        this.ifExists = ifExists;
    }

    public List<String> tableName() { return tableName; }
    public Map<String, String> properties() { return properties; }
    public boolean ifExists() { return ifExists; }

    @Override
    public String toString() { return "UnsetTableProperties " + String.join(".", tableName); }
}
