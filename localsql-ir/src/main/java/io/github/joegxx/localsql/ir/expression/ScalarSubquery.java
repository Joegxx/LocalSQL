package io.github.joegxx.localsql.ir.expression;

import io.github.joegxx.localsql.ir.relation.Relation;

public final class ScalarSubquery extends Expression {
    private final Relation query;

    public ScalarSubquery(Relation query) { this.query = query; }
    public Relation query() { return query; }

    @Override
    public String toString() { return "scalar(" + query + ")"; }
}
