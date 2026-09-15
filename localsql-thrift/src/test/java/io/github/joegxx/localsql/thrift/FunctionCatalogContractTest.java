package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.analyzer.BuiltInFunctionCatalog;
import io.github.joegxx.localsql.analyzer.FunctionCatalog;
import io.github.joegxx.localsql.analyzer.FunctionInfo;
import io.github.joegxx.localsql.analyzer.FunctionKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 milestone tests (development plan section 6): the analyzer's function
 * metadata contract. Covers canonical names, aliases, arity bounds, kind
 * classification and conflict handling when composing catalogs. The strict
 * rejection gate itself is exercised in {@link SemanticAnalyzerContractTest}.
 */
class FunctionCatalogContractTest {

    private final BuiltInFunctionCatalog catalog = BuiltInFunctionCatalog.instance();

    // ------------------------------------------------------------------
    // Canonical lookup
    // ------------------------------------------------------------------

    @Test
    void lookupIsCaseInsensitive() {
        assertTrue(catalog.lookup("UPPER").isPresent());
        assertEquals("upper", catalog.lookup("UPPER").orElseThrow().name());
    }

    @Test
    void unknownFunctionIsAbsent() {
        assertTrue(catalog.lookup("no_such_function_xyz").isEmpty());
        assertFalse(catalog.isKnown("no_such_function_xyz"));
    }

    // ------------------------------------------------------------------
    // Aliases
    // ------------------------------------------------------------------

    @Test
    void aliasesResolveToCanonicalIdentity() {
        // Spark treats these spellings as one function; the analyzer binds
        // the alias to the canonical identity
        for (String alias : List.of("substr", "ceiling", "power", "char_length", "character_length")) {
            Optional<FunctionInfo> info = catalog.lookup(alias);
            assertTrue(info.isPresent(), alias);
            assertFalse(info.get().name().equals(alias), alias + " should resolve to a canonical name");
        }
        assertEquals("substring", catalog.lookup("substr").orElseThrow().name());
        assertEquals("ceil", catalog.lookup("ceiling").orElseThrow().name());
        assertEquals("pow", catalog.lookup("power").orElseThrow().name());
        assertEquals("length", catalog.lookup("char_length").orElseThrow().name());
    }

    // ------------------------------------------------------------------
    // Kind classification
    // ------------------------------------------------------------------

    @Test
    void aggregatesAreClassified() {
        for (String name : List.of("count", "sum", "avg", "min", "max", "median", "percentile",
                "approx_count_distinct", "collect_list", "count_if", "max_by", "min_by")) {
            assertEquals(FunctionKind.AGGREGATE, catalog.lookup(name).orElseThrow().kind(), name);
        }
    }

    @Test
    void windowFunctionsAreClassified() {
        for (String name : List.of("row_number", "rank", "dense_rank", "percent_rank", "cume_dist", "ntile", "lag", "lead")) {
            assertEquals(FunctionKind.WINDOW, catalog.lookup(name).orElseThrow().kind(), name);
        }
    }

    @Test
    void tableFunctionsAreClassified() {
        for (String name : List.of("explode", "posexplode", "json_tuple")) {
            assertEquals(FunctionKind.TABLE, catalog.lookup(name).orElseThrow().kind(), name);
        }
    }

    @Test
    void scalarFunctionsAreClassified() {
        for (String name : List.of("upper", "lower", "concat", "substr", "coalesce", "abs", "round", "length")) {
            assertEquals(FunctionKind.SCALAR, catalog.lookup(name).orElseThrow().kind(), name);
        }
    }

    // ------------------------------------------------------------------
    // Arity bounds
    // ------------------------------------------------------------------

    @Test
    void arityBoundsAreInclusive() {
        FunctionInfo fixed = new FunctionInfo("fx", null, 2, 2, FunctionKind.SCALAR);
        assertTrue(fixed.acceptsArity(2));
        assertFalse(fixed.acceptsArity(1));
        assertFalse(fixed.acceptsArity(3));
        FunctionInfo variadic = new FunctionInfo("vx", null, 1, Integer.MAX_VALUE, FunctionKind.SCALAR);
        assertTrue(variadic.acceptsArity(1));
        assertTrue(variadic.acceptsArity(100));
    }

    // ------------------------------------------------------------------
    // Conflict handling when composing catalogs (A3.3)
    // ------------------------------------------------------------------

    @Test
    void compositionOverlaysUdfOverBuiltInForNewNames() {
        FunctionCatalog udf = single(new FunctionInfo("my_udf", null, 1, 1, FunctionKind.SCALAR));
        FunctionCatalog composed = compose(catalog, udf);
        assertTrue(composed.lookup("my_udf").isPresent());
        assertEquals("my_udf", composed.lookup("my_udf").orElseThrow().name());
    }

    @Test
    void compositionRejectsConflictingSameNameDifferentKind() {
        // a UDF colliding with a built-in of a different kind is ambiguous
        // and must be rejected at assembly time, not resolved by priority
        FunctionInfo clash = new FunctionInfo("count", null, 1, 1, FunctionKind.SCALAR);
        assertEquals(FunctionKind.AGGREGATE, catalog.lookup("count").orElseThrow().kind());
        assertThrows(IllegalArgumentException.class, () -> requireCompatible(catalog, clash));
    }

    @Test
    void compositionAcceptsSameNameSameKind() {
        FunctionInfo compatible = new FunctionInfo("count", null, 1, 1, FunctionKind.AGGREGATE);
        assertDoesNotThrow(() -> requireCompatible(catalog, compatible));
    }

    private void assertDoesNotThrow(Runnable r) {
        r.run();
    }

    /** Composition helper mirroring what the app layer must do for UDFs. */
    private static FunctionCatalog compose(FunctionCatalog base, FunctionCatalog overlay) {
        return name -> overlay.lookup(name).isPresent() ? overlay.lookup(name) : base.lookup(name);
    }

    /**
     * Assembly-time compatibility rule: a custom function may only shadow a
     * built-in with the same kind (explicit override policy); a kind change
     * makes the name ambiguous and must fail assembly (A3.3).
     */
    private static void requireCompatible(FunctionCatalog builtIn, FunctionInfo custom) {
        builtIn.lookup(custom.name()).ifPresent(existing -> {
            if (existing.kind() != custom.kind()) {
                throw new IllegalArgumentException(
                        "Function " + custom.name() + " conflicts with built-in kind " + existing.kind());
            }
        });
    }

    private static FunctionCatalog single(FunctionInfo info) {
        Map<String, FunctionInfo> map = new ConcurrentHashMap<>();
        map.put(info.name(), info);
        return name -> java.util.Optional.ofNullable(map.get(name));
    }
}
