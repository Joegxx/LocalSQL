package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

public final class Join extends Relation {
    public enum JoinType { INNER, LEFT, RIGHT, FULL, CROSS, LEFT_SEMI, LEFT_ANTI }

    private final Relation left;
    private final Relation right;
    private final JoinType joinType;
    private final Expression condition;
    private final boolean using;

    public Join(Relation left, Relation right, JoinType joinType, Expression condition, boolean using) {
        this.left = left;
        this.right = right;
        this.joinType = joinType;
        this.condition = condition;
        this.using = using;
    }

    public Relation left() { return left; }
    public Relation right() { return right; }
    public JoinType joinType() { return joinType; }
    public Expression condition() { return condition; }
    public boolean using() { return using; }

    @Override
    public List<AttributeReference> output() {
        java.util.List<AttributeReference> out = new java.util.ArrayList<>(left.output());
        out.addAll(right.output());
        return out;
    }

    @Override
    public String toString() {
        return "Join[" + joinType + ", " + condition + "](" + left + ", " + right + ")";
    }
}
