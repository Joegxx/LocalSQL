package io.github.joegxx.localsql.ir.type;

public record BooleanType() implements DataType {
    @Override public String typeName() { return "BOOLEAN"; }
}
