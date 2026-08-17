package io.github.joegxx.localsql.ir.expression;

public final class IntervalLiteral extends Expression {
    private final String value;
    private final String unit;

    public IntervalLiteral(String value, String unit) {
        this.value = value;
        this.unit = unit;
    }

    public String value() { return value; }
    public String unit() { return unit; }

    @Override
    public String toString() {
        return "IntervalLiteral{" + value + " " + unit + '}';
    }
}