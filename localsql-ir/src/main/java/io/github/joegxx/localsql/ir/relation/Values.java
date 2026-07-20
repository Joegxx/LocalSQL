package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

public final class Values extends Relation {
    private final List<List<Expression>> rows;

    public Values(List<List<Expression>> rows) { this.rows = List.copyOf(rows); }
    public List<List<Expression>> rows() { return rows; }

    @Override
    public List<AttributeReference> output() { return List.of(); }

    @Override
    public String toString() { return "Values" + rows; }
}
