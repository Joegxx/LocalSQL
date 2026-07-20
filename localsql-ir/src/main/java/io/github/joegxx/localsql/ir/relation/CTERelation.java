package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;

import java.util.List;

public final class CTERelation extends Relation {
    private final String name;
    private final Relation definition;
    private final List<AttributeReference> output;

    public CTERelation(String name, Relation definition, List<AttributeReference> output) {
        this.name = name;
        this.definition = definition;
        this.output = List.copyOf(output);
    }

    public String name() { return name; }
    public Relation definition() { return definition; }

    @Override
    public List<AttributeReference> output() { return output; }

    @Override
    public String toString() { return "CTE[" + name + "]"; }
}
