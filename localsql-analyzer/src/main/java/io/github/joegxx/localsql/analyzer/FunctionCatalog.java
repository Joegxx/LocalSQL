package io.github.joegxx.localsql.analyzer;

import java.util.Optional;

/**
 * Read-only function metadata the analyzer consults while binding
 * {@code FunctionCall} nodes. Implemented by the built-in Spark catalog and
 * composed with UDF metadata by the orchestration layer; implementations
 * must not expose execution state (gateways, classloaders, Hive classes).
 *
 * <p>Lookup is by source name, including aliases. The catalog does not
 * resolve overloads by argument type in this milestone; arity checking
 * happens in the analyzer via {@link FunctionInfo#acceptsArity(int)}.
 */
public interface FunctionCatalog {

    /** @return metadata for the canonical name or one of its aliases. */
    Optional<FunctionInfo> lookup(String name);

    default boolean isKnown(String name) {
        return lookup(name).isPresent();
    }
}
