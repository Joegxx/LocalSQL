package io.github.joegxx.localsql.rewrite;

/**
 * Dialect translation helpers used by ExpressionLowering: Spark schema
 * strings for from_json, Java SimpleDateFormat patterns for date_format,
 * and Spark day names for next_day. These run during rewrite; the
 * generator only serializes the resulting IR.
 */
final class SparkDialectFormats {

    private SparkDialectFormats() {}

    static String sparkSchemaToDuckJson(String schema) {
        SchemaParser p = new SchemaParser(schema);
        return p.parseSchema();
    }

    private static final class SchemaParser {
        private final String s;
        private int i;

        SchemaParser(String s) { this.s = s; }

        String parseSchema() {
            skipWs();
            // STRUCT<...> or ARRAY<...> or DECIMAL(...): parse as a single type;
            // otherwise treat the whole string as a bare field list
            if (s.regionMatches(true, i, "STRUCT<", 0, 7)
                    || s.regionMatches(true, i, "ARRAY<", 0, 6)) {
                String t = parseType();
                skipWs();
                if (i != s.length()) {
                    throw new UnsupportedOperationException("from_json trailing input after schema: " + s.substring(i));
                }
                return t;
            }
            String t = parseStructBody();
            skipWs();
            if (i != s.length()) {
                throw new UnsupportedOperationException("from_json trailing input after schema: " + s.substring(i));
            }
            return t;
        }

        private String parseType() {
            skipWs();
            String word = readWord();
            switch (word.toUpperCase(java.util.Locale.ROOT)) {
                case "STRUCT" -> {
                    expect('<');
                    return parseStructBody();
                }
                case "ARRAY" -> {
                    expect('<');
                    String element = parseType();
                    skipWs();
                    expect('>');
                    return "[" + element + "]";
                }
                case "MAP" ->
                        throw new UnsupportedOperationException("from_json MAP types are not supported yet: " + s);
                case "DECIMAL", "NUMERIC" -> {
                    skipWs();
                    expect('(');
                    String precision = readDigits();
                    expect(',');
                    String scale = readDigits();
                    expect(')');
                    return "\"DECIMAL(" + precision + "," + scale + ")\"";
                }
                default -> {
                    String duck = primitive(word);
                    if (duck == null) {
                        throw new UnsupportedOperationException("from_json unsupported type '" + word + "' in " + s);
                    }
                    return '"' + duck + '"';
                }
            }
        }

        /** Parses the body of a struct: name TYPE, name TYPE, ... up to '>' or EOF. */
        private String parseStructBody() {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            while (true) {
                skipWs();
                if (peek() == '>') { i++; break; }
                if (i >= s.length()) break;
                if (!first) {
                    expect(',');
                    skipWs();
                }
                String name = readWord();
                if (name.isEmpty()) throw new IllegalArgumentException("missing field name in " + s);
                skipWs();
                String type = parseType();
                skipComment();
                if (!first) out.append(", ");
                out.append('"').append(name.replace("\"", "\\\"")).append("\": ").append(type);
                first = false;
            }
            out.append('}');
            return out.toString();
        }

        /** Skips a trailing COMMENT '...' token after a field type. */
        private void skipComment() {
            skipWs();
            if (s.regionMatches(true, i, "COMMENT", 0, 7)) {
                i += 7;
                skipWs();
                if (i < s.length() && s.charAt(i) == '\'') {
                    int close = s.indexOf('\'', i + 1);
                    i = close < 0 ? s.length() : close + 1;
                }
            }
        }

        private String primitive(String word) {
            return switch (word.toUpperCase(java.util.Locale.ROOT)) {
                case "INT", "INTEGER" -> "INTEGER";
                case "BIGINT", "LONG" -> "BIGINT";
                case "SMALLINT" -> "SMALLINT";
                case "TINYINT", "BYTE" -> "TINYINT";
                case "STRING", "VARCHAR" -> "VARCHAR";
                case "DOUBLE" -> "DOUBLE";
                case "FLOAT", "REAL" -> "FLOAT";
                case "BOOLEAN", "BOOL" -> "BOOLEAN";
                case "DATE" -> "DATE";
                case "TIMESTAMP" -> "TIMESTAMP";
                default -> null;
            };
        }

        private void expect(char c) {
            skipWs();
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + i + " in " + s);
            }
            i++;
        }

        private char peek() { return i < s.length() ? s.charAt(i) : 0; }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        /** Reads an identifier/type keyword, stopping at non-word chars (< > ( , ). */
        private String readWord() {
            skipWs();
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
            return s.substring(start, i);
        }

        private String readDigits() {
            skipWs();
            int start = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            return s.substring(start, i);
        }
    }

    /**
     * Translates a Java SimpleDateFormat pattern (Spark's date_format) into a
     * DuckDB strftime pattern for the common specifiers. Unrecognized tokens
     * fail loudly rather than emitting a silently wrong pattern. Literal
     * single-quoted blocks pass through; %% is an escaped percent.
     */
    static String javaFormatToStrftime(String fmt) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < fmt.length()) {
            char c = fmt.charAt(i);
            if (c == '\'') {
                // quoted literal block
                int close = fmt.indexOf('\'', i + 1);
                if (close < 0) throw new IllegalArgumentException("unterminated quote in date_format: " + fmt);
                for (char q : fmt.substring(i + 1, close).toCharArray()) {
                    if (q == '%') out.append("%%");
                    else out.append(q);
                }
                i = close + 1;
                continue;
            }
            if (c == '%') {
                out.append("%%");
                i++;
                continue;
            }
            if (c == 'y' || c == 'M' || c == 'd' || c == 'H' || c == 'm' || c == 's'
                    || c == 'E' || c == 'a' || c == 'D' || c == 'w' || c == 'u') {
                int run = 0;
                while (i + run < fmt.length() && fmt.charAt(i + run) == c) run++;
                String spec = switch (c) {
                    case 'y' -> run == 2 ? "%y" : "%Y";
                    case 'M' -> run >= 3 ? "%b" : "%m";
                    case 'd' -> run == 2 ? "%d" : "%e";
                    case 'H' -> run == 2 ? "%H" : "%H";
                    case 'm' -> run == 2 ? "%M" : "%M";
                    case 's' -> run == 2 ? "%S" : "%S";
                    case 'E' -> run >= 4 ? "%A" : "%a";
                    case 'a' -> "%p";
                    case 'D' -> "%j";
                    case 'w' -> run == 1 ? "%u" : "%W";
                    case 'u' -> "%u";
                    default -> "%Y";
                };
                out.append(spec);
                i += run;
                continue;
            }
            if (c == 'S' || c == 'z' || c == 'Z' || c == 'G' || c == 'k' || c == 'K' || c == 'F' || c == 'W') {
                throw new IllegalArgumentException("date_format specifier not supported: '" + c
                        + "' in " + fmt + " (supported: y/M/d/H/m/s/E/a/D/w/u + quoted literals)");
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Spark next_day accepts full names and abbreviations; 0=Monday..6=Sunday. */
    static int dayIndex(String name) {
        return switch (name.toLowerCase()) {
            case "mon", "monday" -> 0;
            case "tue", "tues", "tuesday" -> 1;
            case "wed", "wednesday" -> 2;
            case "thu", "thurs", "thursday" -> 3;
            case "fri", "friday" -> 4;
            case "sat", "saturday" -> 5;
            case "sun", "sunday" -> 6;
            default -> -1;
        };
    }
}
