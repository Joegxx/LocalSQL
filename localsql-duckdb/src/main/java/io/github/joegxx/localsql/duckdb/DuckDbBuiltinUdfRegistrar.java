package io.github.joegxx.localsql.duckdb;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;

import org.duckdb.DuckDBFunctions;

/**
 * Registers Spark-compatible built-in scalar functions that DuckDB lacks as
 * Java UDFs on the DuckDB connection. The SQL generator passes these function
 * names through directly.
 */
public final class DuckDbBuiltinUdfRegistrar {

    private DuckDbBuiltinUdfRegistrar() {
    }

    public static void register(Connection connection) throws SQLException {
        registerInitcap(connection);
        registerConv(connection);
        registerBround(connection);
    }

    private static void registerInitcap(Connection connection) throws SQLException {
        DuckDBFunctions.scalarFunction()
                .withName("initcap")
                .withParameter(String.class)
                .withReturnType(String.class)
                .withFunction((String input) -> input == null ? null : initcap(input))
                .withNullInNullOut()
                .register(connection);
    }

    private static void registerConv(Connection connection) throws SQLException {
        DuckDBFunctions.scalarFunction()
                .withName("conv")
                .withParameter(org.duckdb.DuckDBColumnType.VARCHAR)
                .withParameter(org.duckdb.DuckDBColumnType.VARCHAR)
                .withParameter(org.duckdb.DuckDBColumnType.VARCHAR)
                .withReturnType(org.duckdb.DuckDBColumnType.VARCHAR)
                .withVectorizedFunction((chunk, out) -> {
                    org.duckdb.DuckDBReadableVector num = chunk.vector(0);
                    org.duckdb.DuckDBReadableVector from = chunk.vector(1);
                    org.duckdb.DuckDBReadableVector to = chunk.vector(2);
                    for (long row = 0; row < chunk.rowCount(); row++) {
                        if (num.isNull(row) || from.isNull(row) || to.isNull(row)) {
                            out.setNull(row);
                            continue;
                        }
                        String converted = conv(num.getString(row), from.getString(row), to.getString(row));
                        if (converted == null) out.setNull(row);
                        else out.setString(row, converted);
                    }
                })
                .register(connection);
    }

    private static void registerBround(Connection connection) throws SQLException {
        DuckDBFunctions.scalarFunction()
                .withName("bround")
                .withParameter(org.duckdb.DuckDBColumnType.DOUBLE)
                .withReturnType(org.duckdb.DuckDBColumnType.DOUBLE)
                .withFunction((java.util.function.Function<Double, Double>) x -> bround(x, null))
                .withNullInNullOut()
                .register(connection);
        DuckDBFunctions.scalarFunction()
                .withName("bround")
                .withParameter(org.duckdb.DuckDBColumnType.DOUBLE)
                .withParameter(org.duckdb.DuckDBColumnType.DOUBLE)
                .withReturnType(org.duckdb.DuckDBColumnType.DOUBLE)
                .withFunction((java.util.function.BiFunction<Double, Double, Double>)
                        (x, scale) -> bround(x, scale))
                .withNullInNullOut()
                .register(connection);
    }

    /** Spark initcap: first letter of each word upper, rest lower; word = alphanumeric run. */
    static String initcap(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean newWord = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(newWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
                newWord = false;
            } else {
                sb.append(c);
                newWord = true;
            }
        }
        return sb.toString();
    }

    private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final BigInteger U64_MODULUS = BigInteger.ONE.shiftLeft(64);

    /**
     * Spark conv: converts num (string) from one base to another. Negative num
     * is the 64-bit two's complement when to_base is positive, signed when
     * to_base is negative; values beyond 64 bits saturate to FFFFFFFFFFFFFFFF.
     */
    static String conv(String numStr, String fromStr, String toStr) {
        try {
            int fromBase = Integer.parseInt(fromStr.trim());
            int toBase = Integer.parseInt(toStr.trim());
            if (fromBase < 2 || fromBase > 36 || Math.abs(toBase) < 2 || Math.abs(toBase) > 36) {
                return null;
            }
            String num = numStr.trim();
            boolean negative = num.startsWith("-");
            if (negative) num = num.substring(1);
            BigInteger value = BigInteger.ZERO;
            BigInteger base = BigInteger.valueOf(fromBase);
            for (int i = 0; i < num.length(); i++) {
                int digit = Character.digit(num.charAt(i), fromBase);
                if (digit < 0) break;
                value = value.multiply(base).add(BigInteger.valueOf(digit));
            }
            if (negative) value = value.negate();
            if (value.bitLength() > 64) {
                return U64_MAX.toString(Math.abs(toBase)).toUpperCase();
            }
            if (value.signum() < 0 && toBase > 0) {
                return U64_MODULUS.add(value).toString(toBase).toUpperCase();
            }
            return value.abs().toString(Math.abs(toBase)).toUpperCase().replaceFirst("^", value.signum() < 0 ? "-" : "");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Spark bround: HALF_EVEN rounding to d decimal places; d = 0 when omitted (null). */
    static Double bround(Double x, Double scale) {
        if (x == null) return null;
        int s = scale == null ? 0 : (int) Math.rint(scale);
        BigDecimal value = BigDecimal.valueOf(x);
        return value.setScale(s, RoundingMode.HALF_EVEN).doubleValue();
    }
}
