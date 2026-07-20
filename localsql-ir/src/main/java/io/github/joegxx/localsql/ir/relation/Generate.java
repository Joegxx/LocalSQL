package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

public final class Generate extends Relation {
    private final Expression generator;
    private final List<String> generatorOutputNames;
    private final Relation child;

    public Generate(Expression generator, List<String> generatorOutputNames, Relation child) {
        this.generator = generator;
        this.generatorOutputNames = List.copyOf(generatorOutputNames);
        this.child = child;
    }

    public Expression generator() { return generator; }
    public List<String> generatorOutputNames() { return generatorOutputNames; }
    public Relation child() { return child; }

    @Override
    public List<AttributeReference> output() { return child.output(); }

    @Override
    public String toString() { return "Generate[" + generator + "](" + child + ")"; }
}
