package io.github.joegxx.localsql.ir.statement;

import io.github.joegxx.localsql.ir.relation.Relation;

import java.util.List;

/** A plain query (SELECT ...). Wraps the Relation tree the existing pipeline consumes. */
public final class QueryStatement extends Statement {
    private final Relation query;
    private final boolean explain;

    public QueryStatement(Relation query) { this(query, false); }

    /** EXPLAIN <query>: the pipeline translates and reports the DuckDB SQL instead of executing. */
    public QueryStatement(Relation query, boolean explain) {
        this.query = query;
        this.explain = explain;
    }

    public Relation query() { return query; }
    public boolean explain() { return explain; }

    @Override
    public String toString() { return (explain ? "Explain(" : "QueryStatement(") + query + ")"; }
}
