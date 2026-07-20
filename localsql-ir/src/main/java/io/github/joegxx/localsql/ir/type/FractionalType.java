package io.github.joegxx.localsql.ir.type;

public record FractionalType(int bits) implements DataType {
    public static final FractionalType FLOAT = new FractionalType(32);
    public static final FractionalType DOUBLE = new FractionalType(64);
    @Override public String typeName() { return bits == 32 ? "FLOAT" : "DOUBLE"; }
}
