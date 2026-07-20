package io.github.joegxx.localsql.ir.type;

public record ArrayType(DataType elementType, boolean containsNull) implements DataType {
    @Override public String typeName() { return "ARRAY<" + elementType.typeName() + ">"; }
}
