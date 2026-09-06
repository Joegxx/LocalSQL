package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.ir.type.BinaryType;
import io.github.joegxx.localsql.ir.type.BooleanType;
import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.ir.type.DateType;
import io.github.joegxx.localsql.ir.type.DecimalType;
import io.github.joegxx.localsql.ir.type.FractionalType;
import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.type.StringType;
import io.github.joegxx.localsql.ir.type.TimestampType;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses UDF config type names (STRING, BIGINT, DECIMAL(10,2)...) into IR DataTypes. */
final class UdfTypeParser {

    private static final Pattern DECIMAL = Pattern.compile("(?i)(?:DECIMAL|NUMERIC)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+))?\\s*\\)");

    private UdfTypeParser() {}

    static DataType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("UDF type must not be blank");
        }
        String t = raw.trim();
        String upper = t.toUpperCase(Locale.ROOT);
        Matcher decimal = DECIMAL.matcher(t);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1));
            int scale = decimal.group(2) == null ? 0 : Integer.parseInt(decimal.group(2));
            return new DecimalType(precision, scale);
        }
        return switch (upper) {
            case "STRING", "VARCHAR", "CHAR", "TEXT" -> new StringType();
            case "BOOLEAN", "BOOL" -> new BooleanType();
            case "TINYINT", "BYTE" -> IntegralType.TINYINT;
            case "SMALLINT", "SHORT" -> IntegralType.SMALLINT;
            case "INT", "INTEGER" -> IntegralType.INT;
            case "BIGINT", "LONG" -> IntegralType.BIGINT;
            case "FLOAT", "REAL" -> FractionalType.FLOAT;
            case "DOUBLE" -> FractionalType.DOUBLE;
            case "DATE" -> new DateType();
            case "TIMESTAMP" -> new TimestampType();
            case "BINARY" -> new BinaryType();
            default -> throw new IllegalArgumentException("Unsupported UDF type: " + raw
                    + " (supported: STRING, BOOLEAN, TINYINT, SMALLINT, INT, BIGINT, FLOAT, DOUBLE,"
                    + " DECIMAL(p,s), DATE, TIMESTAMP, BINARY; quote values containing commas"
                    + " in YAML, e.g. \"DECIMAL(10,2)\")");
        };
    }
}
