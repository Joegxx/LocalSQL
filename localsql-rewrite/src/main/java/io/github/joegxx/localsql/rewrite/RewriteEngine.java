package io.github.joegxx.localsql.rewrite;

import io.github.joegxx.localsql.ir.relation.Relation;

import java.util.Set;

public final class RewriteEngine {

    private final FunctionNameRewriter functionNames;
    private final ExpressionLowering expressionLowering;

    public RewriteEngine() { this(Set.of(), Set.of()); }

    /**
     * @param reservedFunctionNames source names that must not be renamed by
     *                              the rewrite pass: custom UDF mappings and
     *                              custom function-map templates keep their
     *                              generator-owned conversion (A1.3: only
     *                              one conversion per function applies).
     */
    public RewriteEngine(Set<String> reservedFunctionNames) {
        this(reservedFunctionNames, Set.of());
    }

    /**
     * @param udtfNames registered UDTF source names; LATERAL VIEW generators
     *                  matching one pass through lowering untouched for
     *                  UdtfGenerateSplitter (which runs after this rewrite).
     *                  Anything else unknown fails loudly at lowering.
     */
    public RewriteEngine(Set<String> reservedFunctionNames, Set<String> udtfNames) {
        this.functionNames = new FunctionNameRewriter(reservedFunctionNames);
        this.expressionLowering = new ExpressionLowering(udtfNames);
    }

    /** Returns the rewritten tree; callers must use the return value. */
    public Relation rewrite(Relation rel) {
        functionNames.rewrite(rel);
        return expressionLowering.rewrite(rel);
    }
}
