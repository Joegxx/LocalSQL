package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

/**
 * LATERAL VIEW generator. The generator expression is the source-form call
 * (explode / posexplode / explode_outer); the rewrite pass owns converting
 * it to the pure argument expression plus the {@link #ordinalOutput()} flag,
 * and the generator emits the DuckDB table-function form
 * {@code UNNEST(<argument>) [ WITH ORDINALITY ] AS <viewAlias>(<cols>)}.
 *
 * <p>Modeling contract:
 * <ul>
 *   <li>{@link #generator()} — after lowering, the pure list expression the
 *       UNNEST expands (for posexplode: the values; the ordinal column is
 *       supplied by the {@link #ordinalOutput()} flag).</li>
 *   <li>{@link #ordinalOutput()} — posexplode semantics: DuckDB's
 *       {@code WITH ORDINALITY} appends a 1-based position column after the
 *       value column, so for the user-facing (pos, col) order the
 *       generatorOutputNames are stored in physical order (col first, pos
 *       last) and the view stays addressable by name.</li>
 *   <li>{@link #generatorOutputNames()} — the output column names; empty
 *       means the generator uses its default names.</li>
 * </ul>
 */
public final class Generate extends Relation {
    private final Expression generator;
    private final List<String> generatorOutputNames;
    private final String viewAlias;
    private final Relation child;
    private final boolean ordinalOutput;

    public Generate(Expression generator, List<String> generatorOutputNames, Relation child) {
        this(generator, generatorOutputNames, null, false, child);
    }

    public Generate(Expression generator, List<String> generatorOutputNames,
                    String viewAlias, Relation child) {
        this(generator, generatorOutputNames, viewAlias, false, child);
    }

    public Generate(Expression generator, List<String> generatorOutputNames,
                    String viewAlias, boolean ordinalOutput, Relation child) {
        this.generator = generator;
        this.generatorOutputNames = List.copyOf(generatorOutputNames);
        this.viewAlias = viewAlias;
        this.ordinalOutput = ordinalOutput;
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

    /** Posexplode semantics: emit a 1-based position column after the value. */
    public boolean ordinalOutput() { return ordinalOutput; }

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
    public String toString() {
        return "Generate[" + generator + (ordinalOutput ? ",ordinal" : "") + "](" + child + ")";
    }
}
