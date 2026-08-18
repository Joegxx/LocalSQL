package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

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
    private final List<Expression> aggregateExpressions;
    private final Expression havingCondition;
    private final List<GroupingAnalytics> groupingAnalytics;

    public Aggregate(Relation child, List<Expression> groupingExpressions, List<Expression> aggregateExpressions) {
        this(child, groupingExpressions, aggregateExpressions, null, List.of());
    }

    public Aggregate(Relation child, List<Expression> groupingExpressions,
                     List<Expression> aggregateExpressions, Expression havingCondition) {
        this(child, groupingExpressions, aggregateExpressions, havingCondition, List.of());
    }

    public Aggregate(Relation child, List<Expression> groupingExpressions,
                     List<Expression> aggregateExpressions, Expression havingCondition,
                     List<GroupingAnalytics> groupingAnalytics) {
        this.child = child;
        this.groupingExpressions = List.copyOf(groupingExpressions);
        this.aggregateExpressions = List.copyOf(aggregateExpressions);
        this.havingCondition = havingCondition;
        this.groupingAnalytics = List.copyOf(groupingAnalytics);
    }

    public Relation child() { return child; }
    public List<Expression> groupingExpressions() { return groupingExpressions; }
    public List<Expression> aggregateExpressions() { return aggregateExpressions; }
    public Expression havingCondition() { return havingCondition; }
    public List<GroupingAnalytics> groupingAnalytics() { return groupingAnalytics; }

    @Override
    public List<AttributeReference> output() { return child.output(); }

    @Override
    public String toString() {
        return "Aggregate[grp=" + groupingExpressions + ", analytics=" + groupingAnalytics
                + ", agg=" + aggregateExpressions + "](" + child + ")";
    }
}