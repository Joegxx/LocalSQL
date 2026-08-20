package io.github.joegxx.localsql.ir.statement;

import io.github.joegxx.localsql.ir.relation.Relation;

import java.util.List;

/** A plain query (SELECT ...). Wraps the Relation tree the existing pipeline consumes. */
public final class QueryStatement extends Statement {
    private final Relation query;

    public QueryStatement(Relation query) { this.query = query; }

    public Relation query() { return query; }

    @Override
    public String toString() { return "QueryStatement(" + query + ")"; }
}
