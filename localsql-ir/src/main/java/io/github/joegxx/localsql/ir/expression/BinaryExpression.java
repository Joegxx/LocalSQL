package io.github.joegxx.localsql.ir.expression;

import java.util.List;
import java.util.Objects;

public final class BinaryExpression extends Expression {
    public enum Op {
        ADD("+"), SUB("-"), MUL("*"), DIV("/"), INT_DIV("//"), MOD("%"),
        EQ("="), NEQ("<>"), LT("<"), LTE("<="), GT(">"), GTE(">="),
        AND("AND"), OR("OR"),
        STRING_CONCAT("||"),
        BIT_AND("&"), BIT_OR("|"), BIT_XOR("xor"),
        // Spark null-safe equality <=>, SQL standard IS NOT DISTINCT FROM
        EQ_NULL_SAFE("IS NOT DISTINCT FROM");

        private final String symbol;
        Op(String s) { this.symbol = s; }
        public String symbol() { return symbol; }
    }

    private final Expression left;
    private final Op op;
    private final Expression right;

    public BinaryExpression(Expression left, Op op, Expression right) {
        this.left = left;
        this.op = op;
        this.right = right;
    }

    public Expression left() { return left; }
    public Op op() { return op; }
    public Expression right() { return right; }

    @Override
    public String toString() { return "(" + left + " " + op.symbol() + " " + right + ")"; }
}