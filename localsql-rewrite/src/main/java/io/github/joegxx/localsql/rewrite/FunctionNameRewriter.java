package io.github.joegxx.localsql.rewrite;

import io.github.joegxx.localsql.ir.expression.Expression;
import io.github.joegxx.localsql.ir.expression.FunctionCall;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.visitor.IrVisitor;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A1 step: source-name to target-name rewriting for functions whose DuckDB
 * call form is a plain rename (no argument restructuring). Owns the
 * translation for these functions exclusively; the generator no longer
 * maps them. Functions reserved by custom UDF configuration or still
 * handled by generator-specific branches are skipped via
 * {@code reservedNames} so only one conversion ever applies (A1.3).
 */
public final class FunctionNameRewriter {

    /**
     * Spark 3.2 source name -> DuckDB function name, 1:1 argument-preserving
     * renames only. Argument-restructuring families (dates, JSON, lambdas,
     * casts) remain generator-owned until migrated per function family.
     */
    static final Map<String, String> RENAMES = Map.ofEntries(
            Map.entry("any", "bool_or"),
            Map.entry("approx_percentile", "quantile_cont"),
            Map.entry("array", "list_value"),
            Map.entry("array_contains", "list_contains"),
            Map.entry("array_distinct", "list_distinct"),
            Map.entry("array_intersect", "list_intersect"),
            Map.entry("array_max", "list_max"),
            Map.entry("array_min", "list_min"),
            Map.entry("array_position", "list_position"),
            Map.entry("array_sort", "list_sort"),
            Map.entry("arrays_overlap", "list_has_any"),
            Map.entry("base64", "to_base64"),
            Map.entry("cardinality", "len"),
            Map.entry("char", "chr"),
            Map.entry("collect_list", "list"),
            Map.entry("dayofmonth", "day"),
            Map.entry("element_at", "list_element"),
            Map.entry("endswith", "ends_with"),
            Map.entry("every", "bool_and"),
            Map.entry("explode", "unnest"),
            Map.entry("filter", "list_filter"),
            Map.entry("format_string", "printf"),
            Map.entry("get_field", "struct_extract"),
            Map.entry("get_json_object", "json_extract_string"),
            Map.entry("ifnull", "coalesce"),
            Map.entry("instr", "strpos"),
            Map.entry("java.lang.Math.abs", "abs"),
            Map.entry("java.lang.Math.ceil", "ceil"),
            Map.entry("java.lang.Math.exp", "exp"),
            Map.entry("java.lang.Math.floor", "floor"),
            Map.entry("java.lang.Math.log", "ln"),
            Map.entry("java.lang.Math.log10", "log10"),
            Map.entry("java.lang.Math.max", "greatest"),
            Map.entry("java.lang.Math.min", "least"),
            Map.entry("java.lang.Math.pow", "pow"),
            Map.entry("java.lang.Math.round", "round"),
            Map.entry("java.lang.Math.sqrt", "sqrt"),
            Map.entry("json_object_keys", "json_keys"),
            Map.entry("max_by", "arg_max"),
            Map.entry("mean", "avg"),
            Map.entry("min_by", "arg_min"),
            Map.entry("nvl", "coalesce"),
            Map.entry("percentile", "quantile_cont"),
            Map.entry("percentile_approx", "quantile_cont"),
            Map.entry("posexplode", "unnest"),
            Map.entry("power", "pow"),
            Map.entry("reduce", "list_reduce"),
            Map.entry("regexp_like", "regexp_matches"),
            Map.entry("rint", "round"),
            Map.entry("sha", "sha1"),
            Map.entry("signum", "sign"),
            Map.entry("size", "array_length"),
            Map.entry("some", "bool_or"),
            Map.entry("split", "regexp_split_to_array"),
            Map.entry("startswith", "starts_with"),
            Map.entry("std", "stddev_samp"),
            Map.entry("stddev", "stddev_samp"),
            Map.entry("substr", "substring"),
            Map.entry("transform", "list_transform"),
            Map.entry("unbase64", "from_base64"),
            Map.entry("variance", "var_samp"),
            Map.entry("weekofyear", "week")
    );

    private final Set<String> reservedNames;

    public FunctionNameRewriter() { this(Set.of()); }

    public FunctionNameRewriter(Set<String> reservedNames) {
        this.reservedNames = reservedNames == null ? Set.of() : reservedNames;
    }

    public static boolean isRenamable(String name) {
        return RENAMES.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public void rewrite(Relation rel) {
        new Visitor().visitRelation(rel, null);
    }

    private final class Visitor extends IrVisitor<Void, Void> {
        @Override
        public Void visitFunctionCall(FunctionCall e, Void ctx) {
            String name = e.name().toLowerCase(Locale.ROOT);
            if (RENAMES.containsKey(name) && !reservedNames.contains(name)) {
                e.rename(RENAMES.get(name));
            }
            return super.visitFunctionCall(e, ctx);
        }
    }
}
