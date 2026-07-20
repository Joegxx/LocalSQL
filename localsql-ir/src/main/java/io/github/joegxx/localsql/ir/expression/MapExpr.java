package io.github.joegxx.localsql.ir.expression;

import java.util.List;
import java.util.Map;

public final class MapExpr extends Expression {
    private final List<Map.Entry<Expression, Expression>> entries;

    public MapExpr(List<Map.Entry<Expression, Expression>> entries) { this.entries = List.copyOf(entries); }
    public List<Map.Entry<Expression, Expression>> entries() { return entries; }

    @Override
    public String toString() {
        return "map(" + entries.stream().map(e -> e.getKey() + ", " + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("") + ")";
    }
}
