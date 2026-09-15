package io.github.joegxx.localsql.analyzer;

/**
 * Semantic category of a function known to the analyzer. The kind decides
 * where the call may appear (SCALAR in projections/filters, AGGREGATE in
 * GROUP BY queries, WINDOW in OVER clauses, TABLE in LATERAL VIEW) and how
 * rewrite rules may transform it.
 */
public enum FunctionKind {
    SCALAR,
    AGGREGATE,
    WINDOW,
    TABLE
}
