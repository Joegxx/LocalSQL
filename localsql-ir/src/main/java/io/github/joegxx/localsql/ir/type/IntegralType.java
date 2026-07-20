package io.github.joegxx.localsql.ir.type;

public record IntegralType(int bits, boolean signed) implements DataType {
    public static final IntegralType TINYINT = new IntegralType(8, true);
    public static final IntegralType SMALLINT = new IntegralType(16, true);
    public static final IntegralType INT = new IntegralType(32, true);
    public static final IntegralType BIGINT = new IntegralType(64, true);
    @Override public String typeName() { return bits == 64 ? "BIGINT" : "INT"; }
}
