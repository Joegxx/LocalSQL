package io.github.joegxx.localsql.rewrite;

import io.github.joegxx.localsql.ir.relation.Relation;

import java.util.Set;

public final class RewriteEngine {

    private final FunctionNameRewriter functionNames;
    private final ExpressionLowering expressionLowering = new ExpressionLowering();

    public RewriteEngine() { this(Set.of()); }

    /**
     * @param reservedFunctionNames source names that must not be renamed by
     *                              the rewrite pass: custom UDF mappings and
     *                              custom function-map templates keep their
     *                              generator-owned conversion (A1.3: only
     *                              one conversion per function applies).
     */
    public RewriteEngine(Set<String> reservedFunctionNames) {
        this.functionNames = new FunctionNameRewriter(reservedFunctionNames);
    }

    /** Returns the rewritten tree; callers must use the return value. */
    public Relation rewrite(Relation rel) {
        functionNames.rewrite(rel);
        return expressionLowering.rewrite(rel);
    }
}
