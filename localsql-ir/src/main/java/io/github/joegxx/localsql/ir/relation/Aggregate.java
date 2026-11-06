package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

/**
 * Aggregation with the Spark result-expression contract:
 * <ul>
 *   <li>{@link #groupingExpressions()} — the GROUP BY keys (plus translated
 *       ordinals; the analyzer resolves {@code GROUP BY 2} to the second
 *       result expression).</li>
 *   <li>{@link #resultExpressions()} — the full SELECT output list in output
 *       order. It mixes plain grouping references and aggregate calls, like
 *       Catalyst's result expressions. It is NOT an aggregate-only list;
 *       consumers that need to tell them apart must look up function kinds
 *       (the analyzer validates that non-aggregate items are determined by
 *       the grouping keys).</li>
 *   <li>{@link #havingCondition()} — HAVING, resolved against the result
 *       aliases first, then the raw FROM columns.</li>
 *   <li>{@link #groupingAnalytics()} — ROLLUP / CUBE / GROUPING SETS.</li>
 * </ul>
 */
public final class Aggregate extends Relation {

    /** Grouping analytics in the GROUP BY clause: ROLLUP / CUBE / GROUPING SETS. */
    public record GroupingAnalytics(Kind kind, List<List<Expression>> sets) {
        public enum Kind { ROLLUP, CUBE, GROUPING_SETS }

        public static GroupingAnalytics rollup(List<Expression> exprs) {
            return new GroupingAnalytics(Kind.ROLLUP, List.of(exprs));
        }

        public static GroupingAnalytics cube(List<Expression> exprs) {
            return new GroupingAnalytics(Kind.CUBE, List.of(exprs));
        }

        public static GroupingAnalytics groupingSets(List<List<Expression>> sets) {
            return new GroupingAnalytics(Kind.GROUPING_SETS, sets);
        }
    }

    private final Relation child;
    private final List<Expression> groupingExpressions;
    private final List<Expression> resultExpressions;
    private final Expression havingCondition;
    private final List<GroupingAnalytics> groupingAnalytics;
    private List<AttributeReference> output;

    public Aggregate(Relation child, List<Expression> groupingExpressions, List<Expression> resultExpressions) {
        this(child, groupingExpressions, resultExpressions, null, List.of());
    }

    public Aggregate(Relation child, List<Expression> groupingExpressions,
                     List<Expression> resultExpressions, Expression havingCondition) {
        this(child, groupingExpressions, resultExpressions, havingCondition, List.of());
    }

    public Aggregate(Relation child, List<Expression> groupingExpressions,
                     List<Expression> resultExpressions, Expression havingCondition,
                     List<GroupingAnalytics> groupingAnalytics) {
        this.child = child;
        this.groupingExpressions = List.copyOf(groupingExpressions);
        this.resultExpressions = List.copyOf(resultExpressions);
        this.havingCondition = havingCondition;
        this.groupingAnalytics = List.copyOf(groupingAnalytics);
    }

    public Relation child() { return child; }
    public List<Expression> groupingExpressions() { return groupingExpressions; }

    /** The full SELECT output list in output order (grouping refs and aggregates mixed). */
    public List<Expression> resultExpressions() { return resultExpressions; }

    public Expression havingCondition() { return havingCondition; }
    public List<GroupingAnalytics> groupingAnalytics() { return groupingAnalytics; }

    @Override
    public List<AttributeReference> output() {
        return output != null ? output : child.output();
    }

    /** Set by the analyzer: one attribute per result expression. */
    public void setOutput(List<AttributeReference> output) { this.output = List.copyOf(output); }

    @Override
    public String toString() {
        return "Aggregate[grp=" + groupingExpressions + ", analytics=" + groupingAnalytics
                + ", out=" + resultExpressions + "](" + child + ")";
    }
}
