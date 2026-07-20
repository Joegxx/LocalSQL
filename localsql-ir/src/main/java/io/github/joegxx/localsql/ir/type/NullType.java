package io.github.joegxx.localsql.ir.type;

public record NullType() implements DataType {
    @Override public String typeName() { return "NULL"; }
}
