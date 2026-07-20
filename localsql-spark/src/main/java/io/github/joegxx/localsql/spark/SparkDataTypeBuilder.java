package io.github.joegxx.localsql.spark;

import io.github.joegxx.localsql.ir.type.*;
import org.antlr.v4.runtime.RuleContext;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser.ComplexColTypeListContext;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser.DataTypeContext;

import java.util.ArrayList;
import java.util.List;

final class SparkDataTypeBuilder {

    static DataType build(DataTypeContext ctx) {
        if (ctx == null) return UnknownType.INSTANCE;
        if (ctx instanceof SqlBaseParser.PrimitiveDataTypeContext p) return primitive(p);
        if (ctx instanceof SqlBaseParser.ComplexDataTypeContext c) return complex(c);
        return UnknownType.INSTANCE;
    }

    private static DataType complex(SqlBaseParser.ComplexDataTypeContext c) {
        int kw = c.complex.getType();
        switch (kw) {
            case SqlBaseParser.ARRAY -> { return new ArrayType(build(c.dataType(0)), true); }
            case SqlBaseParser.MAP -> {
                return new MapType(build(c.dataType(0)), build(c.dataType(1)), true);
            }
            case SqlBaseParser.STRUCT -> {
                ComplexColTypeListContext listCtx = c.complexColTypeList();
                if (listCtx == null) return new StructType(List.of());
                List<StructField> fields = new ArrayList<>();
                for (var col : listCtx.complexColType()) {
                    String name = col.identifier().getText();
                    DataType t = build(col.dataType());
                    fields.add(new StructField(name, t, true));
                }
                return new StructType(fields);
            }
        }
        return UnknownType.INSTANCE;
    }

    private static DataType primitive(SqlBaseParser.PrimitiveDataTypeContext p) {
        String name = p.identifier().getText().toLowerCase();
        List<Integer> args = new ArrayList<>();
        if (p.INTEGER_VALUE() != null) {
            for (var v : p.INTEGER_VALUE()) args.add(Integer.parseInt(v.getText()));
        }
        return switch (name) {
            case "boolean" -> new BooleanType();
            case "tinyint" -> IntegralType.TINYINT;
            case "smallint" -> IntegralType.SMALLINT;
            case "int", "integer" -> IntegralType.INT;
            case "bigint" -> IntegralType.BIGINT;
            case "float" -> FractionalType.FLOAT;
            case "double" -> FractionalType.DOUBLE;
            case "string", "varchar", "char" -> new StringType();
            case "binary" -> new BinaryType();
            case "date" -> new DateType();
            case "timestamp" -> new TimestampType();
            case "null" -> new NullType();
            default -> UnknownType.INSTANCE;
        };
    }

    static String unquoted(RuleContext ctx) {
        String t = ctx.getText();
        if (t.length() >= 2 && t.startsWith("`") && t.endsWith("`")) return t.substring(1, t.length() - 1);
        return t;
    }
}
