package io.github.joegxx.localsql.ir.type;

public record DecimalType(int precision, int scale) implements DataType {
    @Override public String typeName() { return "DECIMAL"; }
}
