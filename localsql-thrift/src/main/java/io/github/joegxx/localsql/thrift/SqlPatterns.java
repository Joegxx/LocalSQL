package io.github.joegxx.localsql.thrift;

import java.util.regex.Pattern;

/**
 * JDBC search-pattern translation (DatabaseMetaData semantics), shared by the
 * Thrift metadata RPCs (GetSchemas/GetTables/GetColumns) and SHOW ... LIKE.
 * Clients escape wildcards with '\' (we advertise it via CLI_SEARCH_PATTERN_ESCAPE),
 * so 'ods\_fuaisv' means a literal underscore and must not be treated as the
 * single-char wildcard.
 */
final class SqlPatterns {

    private SqlPatterns() {}

    static boolean matches(String value, String pattern) {
        return matches(value, pattern, false);
    }

    static boolean matches(String value, String pattern, boolean caseInsensitive) {
        if (pattern == null || pattern.isEmpty()) return true;
        if (value == null) return false;
        String regex = toRegex(pattern);
        return caseInsensitive
                ? value.matches("(?i)" + regex)
                : value.matches(regex);
    }

    private static String toRegex(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                // escaped char: always literal, even for % and _
                sb.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (c == '%') {
                sb.append(".*");
            } else if (c == '_') {
                sb.append('.');
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.toString();
    }
}
