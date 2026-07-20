package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;

import java.util.List;

public final class With extends Relation {
    private final List<CTERelation> ctes;
    private final Relation body;

    public With(List<CTERelation> ctes, Relation body) {
        this.ctes = List.copyOf(ctes);
        this.body = body;
    }

    public List<CTERelation> ctes() { return ctes; }
    public Relation body() { return body; }

    @Override
    public List<AttributeReference> output() { return body.output(); }

    @Override
    public String toString() { return "With" + ctes + "(" + body + ")"; }
}
