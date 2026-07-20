package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;

import java.util.List;

public final class Union extends Relation {
    private final List<Relation> children;
    private final boolean distinct;

    public Union(List<Relation> children, boolean distinct) {
        this.children = List.copyOf(children);
        this.distinct = distinct;
    }

    public List<Relation> children() { return children; }
    public boolean distinct() { return distinct; }

    @Override
    public List<AttributeReference> output() { return children.isEmpty() ? List.of() : children.get(0).output(); }

    @Override
    public String toString() { return "Union[distinct=" + distinct + "](" + children + ")"; }
}
