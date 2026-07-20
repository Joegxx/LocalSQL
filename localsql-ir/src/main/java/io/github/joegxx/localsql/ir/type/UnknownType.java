package io.github.joegxx.localsql.ir.type;

public record UnknownType() implements DataType {
    public static final UnknownType INSTANCE = new UnknownType();
    @Override public String typeName() { return "UNKNOWN"; }
}
