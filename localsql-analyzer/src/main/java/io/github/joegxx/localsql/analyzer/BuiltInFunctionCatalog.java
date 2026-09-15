package io.github.joegxx.localsql.analyzer;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in function metadata for Spark 3.2 built-ins available through the
 * pipeline. Names and aliases are the Spark-side source names; this catalog
 * carries no DuckDB mapping (conversion belongs to Rewrite). The list is a
 * superset union of the function families exercised by the mapping
 * regression tests (strings, numbers, dates, hash/encoding, JSON, arrays,
 * aggregates, window and table functions).
 *
 * <p>Arity bounds are currently permissive ({@code 0..MAX}) because overload
 * resolution by argument type is not part of the binding contract yet; the
 * catalog exists so the analyzer has a single authoritative lookup point.
 */
public final class BuiltInFunctionCatalog implements FunctionCatalog {

    private static final List<String> NO_ALIASES = List.of();

    private static final int MAX = Integer.MAX_VALUE;

    private static final Map<String, FunctionInfo> REGISTRY = new HashMap<>();

    static {
        f("any", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("approx_count_distinct", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("approx_percentile", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("avg", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("bool_and", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("bool_or", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("collect_list", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("collect_set", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("corr", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("count", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("count_if", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("covar_pop", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("covar_samp", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("every", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("first", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("first_value", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("histogram_numeric", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("kurtosis", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("last", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("last_value", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("max", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("max_by", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("median", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("min", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("min_by", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("mode", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("percentile", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("percentile_approx", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("skewness", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("stddev", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("stddev_pop", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("stddev_samp", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("sum", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("var_pop", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("var_samp", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("variance", NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE);
        f("cume_dist", NO_ALIASES, 0, MAX, FunctionKind.WINDOW);
        f("dense_rank", NO_ALIASES, 0, MAX, FunctionKind.WINDOW);
        f("lag", NO_ALIASES, 0, MAX, FunctionKind.WINDOW);
        f("lead", NO_ALIASES, 0, MAX, FunctionKind.WINDOW);
        f("ntile", NO_ALIASES, 0, MAX, FunctionKind.WINDOW);
        f("percent_rank", NO_ALIASES, 0, MAX, FunctionKind.WINDOW);
        f("rank", NO_ALIASES, 0, MAX, FunctionKind.WINDOW);
        f("row_number", NO_ALIASES, 0, MAX, FunctionKind.WINDOW);
        f("explode", NO_ALIASES, 0, MAX, FunctionKind.TABLE);
        f("json_tuple", NO_ALIASES, 0, MAX, FunctionKind.TABLE);
        f("posexplode", NO_ALIASES, 0, MAX, FunctionKind.TABLE);
        f("stack", NO_ALIASES, 0, MAX, FunctionKind.TABLE);
        f("abs", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("acos", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("acosh", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("any_value", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("array", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("array_contains", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("array_distinct", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("array_intersect", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("array_join", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("array_max", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("array_min", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("array_position", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("array_sort", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("arrays_overlap", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("ascii", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("asin", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("asinh", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("atan", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("atan2", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("atanh", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("base64", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("bin", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("bit_count", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("bit_length", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("btrim", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("cardinality", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("cast", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("cbrt", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("ceil", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("ceiling", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("char", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("char_length", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("character_length", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("chr", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("coalesce", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("concat", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("concat_ws", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("contains", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("cos", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("cosh", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("cot", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("current_date", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("current_timestamp", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("date_part", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("day", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("dayofmonth", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("dayofyear", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("decode", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("degrees", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("element_at", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("encode", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("endswith", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("exp", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("factorial", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("filter", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("flatten", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("floor", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("format_string", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("get_field", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("get_json_object", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("greatest", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("hex", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("hour", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("ifnull", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("instr", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("isinf", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("isnan", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("java.lang.Math.abs", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("java.lang.Math.ceil", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("java.lang.Math.exp", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("java.lang.Math.floor", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("java.lang.Math.log", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("java.lang.Math.log10", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("java.lang.Math.max", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("java.lang.Math.min", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("java.lang.Math.pow", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("java.lang.Math.round", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("java.lang.Math.sqrt", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("json_array_length", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("json_extract", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("json_object_keys", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("last_day", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("lcase", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("least", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("left", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("length", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("levenshtein", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("ln", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("log10", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("log2", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("lower", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("lpad", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("ltrim", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("make_date", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("map_concat", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("map_entries", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("map_from_arrays", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("map_from_entries", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("map_keys", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("map_values", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("md5", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("mean", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("minute", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("month", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("now", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("nth_value", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("nullif", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("nvl", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("octet_length", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("pi", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("pow", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("power", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("printf", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("quarter", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("radians", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("random", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("reduce", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("regexp_extract", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("regexp_extract_all", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("regexp_like", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("regexp_replace", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("repeat", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("replace", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("reverse", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("right", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("rint", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("round", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("rpad", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("rtrim", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("second", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("sha", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("sha1", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("sign", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("signum", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("sin", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("sinh", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("size", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("some", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("soundex", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("split", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("sqrt", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("startswith", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("std", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("substr", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("substring", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("tan", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("tanh", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("to_json", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("transform", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("translate", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("trim", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("typeof", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("ucase", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("unbase64", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("unhex", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("upper", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("uuid", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("weekofyear", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        f("year", NO_ALIASES, 0, MAX, FunctionKind.SCALAR);
        // aliases point at canonical entries
        registerAlias("substr", "substring");
        registerAlias("ceiling", "ceil");
        registerAlias("power", "pow");
        registerAlias("character_length", "length");
        registerAlias("char_length", "length");
    }

    private static void f(String name, List<String> aliases, int minArgs, int maxArgs, FunctionKind kind) {
        FunctionInfo info = new FunctionInfo(name, aliases, minArgs, maxArgs, kind);
        REGISTRY.put(info.name(), info);
        for (String a : aliases) REGISTRY.putIfAbsent(a, info);
    }

    /**
     * Points {@code alias} at the canonical entry, overriding a same-named
     * entry: Spark treats these spellings as one function, so the analyzer
     * binds them to a single identity even when both are translated
     * separately (translation ownership stays with Rewrite).
     */
    private static void registerAlias(String alias, String canonical) {
        FunctionInfo info = REGISTRY.get(canonical);
        if (info != null) REGISTRY.put(alias, info);
    }

    @Override
    public Optional<FunctionInfo> lookup(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(REGISTRY.get(name.toLowerCase(Locale.ROOT)));
    }

    public static BuiltInFunctionCatalog instance() { return new BuiltInFunctionCatalog(); }
}
