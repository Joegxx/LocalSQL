package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;

import java.util.List;

public final class Union extends Relation {

    public enum Kind { UNION, INTERSECT, EXCEPT }

    private final List<Relation> children;
    private final boolean distinct;
    private final Kind kind;

    public Union(List<Relation> children, boolean distinct) {
        this(children, distinct, Kind.UNION);
    }

    public Union(List<Relation> children, boolean distinct, Kind kind) {
        this.children = List.copyOf(children);
        this.distinct = distinct;
        this.kind = kind;
    }

    public List<Relation> children() { return children; }
    public boolean distinct() { return distinct; }
    public Kind kind() { return kind; }

    @Override
    public List<AttributeReference> output() { return children.isEmpty() ? List.of() : children.get(0).output(); }

    @Override
    public String toString() {
        return kind + "[distinct=" + distinct + "](" + children + ")";
    }
}