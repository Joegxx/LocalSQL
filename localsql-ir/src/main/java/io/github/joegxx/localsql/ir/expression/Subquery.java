package io.github.joegxx.localsql.ir.expression;

import io.github.joegxx.localsql.ir.relation.Relation;

public final class Subquery extends Expression {
    private final Relation query;

    public Subquery(Relation query) { this.query = query; }
    public Relation query() { return query; }

    @Override
    public String toString() { return "(" + query + ")"; }
}
