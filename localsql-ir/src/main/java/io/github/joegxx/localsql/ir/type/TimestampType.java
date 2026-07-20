package io.github.joegxx.localsql.ir.type;

public record TimestampType() implements DataType {
    @Override public String typeName() { return "TIMESTAMP"; }
}
