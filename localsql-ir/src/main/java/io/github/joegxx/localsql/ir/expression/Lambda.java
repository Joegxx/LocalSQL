package io.github.joegxx.localsql.ir.expression;

import java.util.List;

/** Higher-order lambda: variables -> body (e.g. transform(arr, x -> x + 1)). */
public final class Lambda extends Expression {
    private final List<String> variables;
    private final Expression body;

    public Lambda(List<String> variables, Expression body) {
        this.variables = List.copyOf(variables);
        this.body = body;
    }

    public List<String> variables() { return variables; }
    public Expression body() { return body; }

    @Override
    public String toString() {
        if (variables.size() == 1) return variables.get(0) + " -> " + body;
        return "(" + String.join(", ", variables) + ") -> " + body;
    }
}
