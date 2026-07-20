package io.github.joegxx.localsql.ir.expression;

import java.util.List;
import java.util.Objects;

public final class CaseWhen extends Expression {
    private final List<WhenBranch> branches;
    private final Expression elseValue;

    public CaseWhen(List<WhenBranch> branches, Expression elseValue) {
        this.branches = List.copyOf(branches);
        this.elseValue = elseValue;
    }

    public List<WhenBranch> branches() { return branches; }
    public Expression elseValue() { return elseValue; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CASE");
        for (WhenBranch b : branches) sb.append(" WHEN ").append(b.condition()).append(" THEN ").append(b.value());
        if (elseValue != null) sb.append(" ELSE ").append(elseValue);
        return sb.append(" END").toString();
    }

    public record WhenBranch(Expression condition, Expression value) {}
}
