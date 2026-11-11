package io.github.joegxx.localsql.starrocks;

import com.starrocks.sql.parser.StarRocksParser;
import io.github.joegxx.localsql.ir.type.*;

import java.util.ArrayList;
import java.util.List;

/**
 * StarRocks 类型 -> IR 类型。方言差异在这里归一：DATETIME -> TIMESTAMP、
 * LARGEINT -> HUGEINT、TEXT/CHAR/VARCHAR -> STRING。
 * StarRocks 专属聚合类型（BITMAP/HLL/PERCENTILE）显式拒绝。
 */
final class StarRocksTypeBuilder {

    static DataType build(StarRocksParser.TypeContext ctx) {
        if (ctx == null) return UnknownType.INSTANCE;
        if (ctx.baseType() != null) return base(ctx.baseType());
        if (ctx.decimalType() != null) return decimal(ctx.decimalType());
        if (ctx.arrayType() != null) return new ArrayType(build(ctx.arrayType().type()), true);
        if (ctx.mapType() != null) {
            return new MapType(build(ctx.mapType().type(0)), build(ctx.mapType().type(1)), true);
        }
        if (ctx.structType() != null) return struct(ctx.structType());
        return UnknownType.INSTANCE;
    }

    private static DataType base(StarRocksParser.BaseTypeContext ctx) {
        int t = ctx.getStart().getType();
        switch (t) {
            case StarRocksParser.BOOLEAN: return new BooleanType();
            case StarRocksParser.TINYINT: return IntegralType.TINYINT;
            case StarRocksParser.SMALLINT: return IntegralType.SMALLINT;
            case StarRocksParser.INT:
            case StarRocksParser.INTEGER:
            case StarRocksParser.SIGNED: return IntegralType.INT;
            // UNSIGNED INT widens to BIGINT: IR has no UINTEGER
            case StarRocksParser.UNSIGNED: return IntegralType.BIGINT;
            case StarRocksParser.BIGINT: return IntegralType.BIGINT;
            case StarRocksParser.LARGEINT: return new IntegralType(128, true);
            case StarRocksParser.FLOAT: return FractionalType.FLOAT;
            case StarRocksParser.DOUBLE: return FractionalType.DOUBLE;
            case StarRocksParser.DATE: return new DateType();
            case StarRocksParser.DATETIME: return new TimestampType();
            // TIME has no DuckDB-side counterpart in the current pipeline
            case StarRocksParser.TIME: return UnknownType.INSTANCE;
            case StarRocksParser.CHAR:
            case StarRocksParser.VARCHAR:
            case StarRocksParser.STRING:
            case StarRocksParser.TEXT: return new StringType();
            // JSON values ride as text for now; native JSON cast is a later batch
            case StarRocksParser.JSON: return new StringType();
            case StarRocksParser.VARBINARY:
            case StarRocksParser.BINARY: return new BinaryType();
            case StarRocksParser.BITMAP:
            case StarRocksParser.HLL:
            case StarRocksParser.PERCENTILE:
                throw new UnsupportedOperationException(
                        "StarRocks-specific type " + ctx.getText() + " is not supported");
            default: return UnknownType.INSTANCE;
        }
    }

    private static DataType decimal(StarRocksParser.DecimalTypeContext ctx) {
        int precision = ctx.precision != null ? Integer.parseInt(ctx.precision.getText()) : 10;
        int scale = ctx.scale != null ? Integer.parseInt(ctx.scale.getText()) : 0;
        return new DecimalType(precision, scale);
    }

    private static DataType struct(StarRocksParser.StructTypeContext ctx) {
        List<StructField> fields = new ArrayList<>();
        for (StarRocksParser.SubfieldDescContext sf : ctx.subfieldDescs().subfieldDesc()) {
            if (sf.identifier() == null) {
                throw new UnsupportedOperationException(
                        "Nested struct field paths are not supported: " + sf.getText());
            }
            fields.add(new StructField(unquote(sf.identifier().getText()), build(sf.type()), true));
        }
        return new StructType(fields);
    }

    static String unquote(String text) {
        if (text == null) return null;
        if (text.length() >= 2 && text.startsWith("`") && text.endsWith("`")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
