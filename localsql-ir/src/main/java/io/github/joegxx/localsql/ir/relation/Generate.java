package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

public final class Generate extends Relation {
    private final Expression generator;
    private final List<String> generatorOutputNames;
    private final String viewAlias;
    private final Relation child;

    public Generate(Expression generator, List<String> generatorOutputNames, Relation child) {
        this(generator, generatorOutputNames, null, child);
    }

    public Generate(Expression generator, List<String> generatorOutputNames,
                    String viewAlias, Relation child) {
        this.generator = generator;
        this.generatorOutputNames = List.copyOf(generatorOutputNames);
        this.viewAlias = viewAlias;
        this.child = child;
    }

    public Expression generator() { return generator; }
    public List<String> generatorOutputNames() { return generatorOutputNames; }

    /**
     * The LATERAL VIEW alias ({@code LATERAL VIEW f(x) v AS c}), under which
     * the generator output columns are addressable; null when the view was
     * written without a name.
     */
    public String viewAlias() { return viewAlias; }

    public Relation child() { return child; }

    /**
     * The generator's output columns appended to the child output (Spark
     * LATERAL VIEW semantics: the expanded columns sit beside the source).
     */
    @Override
    public List<AttributeReference> output() {
        List<AttributeReference> out = new java.util.ArrayList<>(child.output());
        for (String name : generatorOutputNames) {
            out.add(new AttributeReference(name, List.of()));
        }
        return List.copyOf(out);
    }

    @Override
    public String toString() { return "Generate[" + generator + "](" + child + ")"; }
}
