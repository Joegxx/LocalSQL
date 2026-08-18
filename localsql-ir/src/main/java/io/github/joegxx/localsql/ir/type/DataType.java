package io.github.joegxx.localsql.ir.type;

public sealed interface DataType
    permits BooleanType, IntegralType, FractionalType, DecimalType, StringType, BinaryType,
            DateType, TimestampType, ArrayType, MapType, StructType, NullType, UnknownType {

    String typeName();
}
