package io.github.joegxx.localsql.ir.type;

public record BinaryType() implements DataType {
    @Override public String typeName() { return "BINARY"; }
}
