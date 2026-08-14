package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

public final class Aggregate extends Relation {
    private final Relation child;
    private final List<Expression> groupingExpressions;
    private final List<Expression> aggregateExpressions;
    private final Expression havingCondition;

    public Aggregate(Relation child, List<Expression> groupingExpressions, List<Expression> aggregateExpressions) {
        this(child, groupingExpressions, aggregateExpressions, null);
    }

    public Aggregate(Relation child, List<Expression> groupingExpressions,
                     List<Expression> aggregateExpressions, Expression havingCondition) {
        this.child = child;
        this.groupingExpressions = List.copyOf(groupingExpressions);
        this.aggregateExpressions = List.copyOf(aggregateExpressions);
        this.havingCondition = havingCondition;
    }

    public Relation child() { return child; }
    public List<Expression> groupingExpressions() { return groupingExpressions; }
    public List<Expression> aggregateExpressions() { return aggregateExpressions; }
    public Expression havingCondition() { return havingCondition; }

    @Override
    public List<AttributeReference> output() { return child.output(); }

    @Override
    public String toString() {
        return "Aggregate[grp=" + groupingExpressions + ", agg=" + aggregateExpressions + "](" + child + ")";
    }
}
