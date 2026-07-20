package io.github.joegxx.localsql.ir.type;

public record StringType() implements DataType {
    @Override public String typeName() { return "STRING"; }
}
