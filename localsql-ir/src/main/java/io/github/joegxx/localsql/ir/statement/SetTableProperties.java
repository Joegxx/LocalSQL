package io.github.joegxx.localsql.ir.statement;

import java.util.List;
import java.util.Map;

/** ALTER TABLE/VIEW tbl SET TBLPROPERTIES (...). */
public final class SetTableProperties extends Statement {
    private final List<String> tableName;
    private final Map<String, String> properties;

    public SetTableProperties(List<String> tableName, Map<String, String> properties) {
        this.tableName = List.copyOf(tableName);
        this.properties = Map.copyOf(properties);
    }

    public List<String> tableName() { return tableName; }
    public Map<String, String> properties() { return properties; }

    @Override
    public String toString() { return "SetTableProperties " + String.join(".", tableName); }
}
