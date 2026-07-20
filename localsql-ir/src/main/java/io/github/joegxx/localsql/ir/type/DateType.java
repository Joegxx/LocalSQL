package io.github.joegxx.localsql.ir.type;

public record DateType() implements DataType {
    @Override public String typeName() { return "DATE"; }
}
