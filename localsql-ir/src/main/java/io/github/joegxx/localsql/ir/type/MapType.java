package io.github.joegxx.localsql.ir.type;

public record MapType(DataType keyType, DataType valueType, boolean valueContainsNull) implements DataType {
    @Override public String typeName() {
        return "MAP<" + keyType.typeName() + "," + valueType.typeName() + ">";
    }
}
