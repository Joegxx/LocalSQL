package io.github.joegxx.localsql.analyzer;

import java.util.List;
import java.util.Locale;

/**
 * Read-only metadata about a function the analyzer can bind. Names are
 * lowercase; {@code aliases} lists alternative source names that resolve to
 * the same identity. Arity bounds are inclusive; use the same value for
 * fixed-arity functions and {@code Integer.MAX_VALUE} for variadic ones.
 *
 * <p>This is a metadata contract only: it carries no execution handle, no
 * classloader and no rewrite target. Downstream conversion rules live in
 * Rewrite, keyed by the canonical {@link #name()}.
 */
public record FunctionInfo(String name, List<String> aliases, int minArgs, int maxArgs, FunctionKind kind) {
    public FunctionInfo {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Function name is required");
        name = name.toLowerCase(Locale.ROOT);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        if (minArgs < 0 || maxArgs < minArgs) {
            throw new IllegalArgumentException("Invalid arity range: " + minArgs + ".." + maxArgs);
        }
        if (kind == null) throw new IllegalArgumentException("Function kind is required");
    }

    public boolean acceptsArity(int argCount) {
        return argCount >= minArgs && argCount <= maxArgs;
    }
}
