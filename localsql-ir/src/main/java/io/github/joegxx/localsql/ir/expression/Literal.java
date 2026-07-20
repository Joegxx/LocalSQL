package io.github.joegxx.localsql.ir.expression;

import java.util.Objects;

public final class Literal extends Expression {
    public enum Kind { NULL, BOOLEAN, INTEGER, DECIMAL, DOUBLE, STRING, DATE, TIMESTAMP }

    private final Object value;
    private final Kind kind;

    public Literal(Object value, Kind kind) {
        this.value = value;
        this.kind = kind;
    }

    public static Literal ofNull() { return new Literal(null, Kind.NULL); }
    public static Literal ofBool(boolean v) { return new Literal(v, Kind.BOOLEAN); }
    public static Literal ofInt(long v) { return new Literal(v, Kind.INTEGER); }
    public static Literal ofDecimal(String text) { return new Literal(text, Kind.DECIMAL); }
    public static Literal ofDouble(double v) { return new Literal(v, Kind.DOUBLE); }
    public static Literal ofString(String v) { return new Literal(v, Kind.STRING); }
    public static Literal ofDate(String v) { return new Literal(v, Kind.DATE); }
    public static Literal ofTimestamp(String v) { return new Literal(v, Kind.TIMESTAMP); }

    public Object value() { return value; }
    public Kind kind() { return kind; }
    public boolean isNull() { return kind == Kind.NULL; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Literal literal)) return false;
        return Objects.equals(value, literal.value) && kind == literal.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, kind);
    }

    @Override
    public String toString() {
        return "Literal{" + kind + ", " + value + '}';
    }
}
