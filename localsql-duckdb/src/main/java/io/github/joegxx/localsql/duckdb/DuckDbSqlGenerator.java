package io.github.joegxx.localsql.duckdb;

import io.github.joegxx.localsql.ir.expression.*;
import io.github.joegxx.localsql.ir.relation.*;
import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.ir.type.DecimalType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DuckDbSqlGenerator {

    public String generate(Relation rel) {
        StringBuilder sb = new StringBuilder();
        emit(rel, sb);
        return sb.toString();
    }

    public String generateInsert(io.github.joegxx.localsql.ir.statement.Insert i) {
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(bareName(i.tableName()));
        if (i.columns() != null) {
            sb.append(" (");
            for (int c = 0; c < i.columns().size(); c++) {
                if (c > 0) sb.append(", ");
                sb.append(quote(i.columns().get(c)));
            }
            sb.append(")");
        }
        sb.append(" ");
        emit(i.query(), sb);
        return sb.toString();
    }

    public String generateCreateTable(io.github.joegxx.localsql.ir.statement.CreateTable t) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        sb.append(bareName(t.tableName()));
        sb.append(" (");
        List<io.github.joegxx.localsql.ir.statement.CreateTable.ColumnDef> cols = t.columns();
        for (int c = 0; c < cols.size(); c++) {
            if (c > 0) sb.append(", ");
            var col = cols.get(c);
            sb.append(quote(col.name())).append(" ").append(duckType(col.type()));
            if (!col.nullable()) sb.append(" NOT NULL");
        }
        sb.append(")");
        return sb.toString();
    }

    public String generateCreateTableAs(io.github.joegxx.localsql.ir.statement.CreateTable t) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        sb.append(bareName(t.tableName()));
        sb.append(" AS ");
        emit(t.query(), sb);
        return sb.toString();
    }

    public String generateCreateView(io.github.joegxx.localsql.ir.statement.CreateView v) {
        StringBuilder sb = new StringBuilder("CREATE ");
        if (v.orReplace()) sb.append("OR REPLACE ");
        sb.append("VIEW ");
        sb.append(bareName(v.tableName()));
        sb.append(" AS ");
        emit(v.query(), sb);
        return sb.toString();
    }

    public String generateRenameTable(io.github.joegxx.localsql.ir.statement.RenameTable rt, boolean isView) {
        String keyword = isView ? "VIEW " : "TABLE ";
        return "ALTER " + keyword + bareName(rt.from()) + " RENAME TO " + bareName(rt.to());
    }

    public String generateAddColumn(List<String> tableName, io.github.joegxx.localsql.ir.statement.AddColumns.ColDef col) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ");
        sb.append(bareName(tableName)).append(" ADD COLUMN ").append(quote(col.name()))
                .append(" ").append(duckType(col.type()));
        if (!col.nullable()) sb.append(" NOT NULL");
        // position ignored for DuckDB (always appends); comment ignored as hint
        return sb.toString();
    }

    public String generateDropColumn(List<String> tableName, String col) {
        return "ALTER TABLE " + bareName(tableName) + " DROP COLUMN " + quote(col);
    }

    public String generateRenameColumn(List<String> tableName, String from, String to) {
        return "ALTER TABLE " + bareName(tableName) + " RENAME COLUMN " + quote(from) + " TO " + quote(to);
    }

    public String generateAlterColumnType(List<String> tableName, String col, DataType newType) {
        return "ALTER TABLE " + bareName(tableName) + " ALTER " + quote(col) + " TYPE " + duckType(newType);
    }

    public String generateAlterColumnSetNotNull(List<String> tableName, String col, boolean setNotNull) {
        // DuckDB 1.5 supports ALTER COLUMN ... SET/DROP NOT NULL
        return "ALTER TABLE " + bareName(tableName) + " ALTER " + quote(col) + (setNotNull ? " SET NOT NULL" : " DROP NOT NULL");
    }

    /** Physical tables all live in DuckDB's main schema: emit only the last part. */
    private String bareName(List<String> parts) {
        return quote(parts.get(parts.size() - 1));
    }

    private void emit(Relation rel, StringBuilder sb) {
        if (rel instanceof With w) emitWith(w, sb);
        else if (rel instanceof Project p) emitProject(p, sb);
        else if (rel instanceof Filter f) emitFilter(f, sb);
        else if (rel instanceof Join j) emitJoin(j, sb);
        else if (rel instanceof Aggregate a) emitAggregate(a, sb);
        else if (rel instanceof Sort s) emitSort(s, sb);
        else if (rel instanceof Limit l) emitLimit(l, sb);
        else if (rel instanceof Union u) emitUnion(u, sb);
        else if (rel instanceof TableScan t) emitTableScan(t, sb);
        else if (rel instanceof Values v) emitValues(v, sb);
        else if (rel instanceof Generate g) emitGenerate(g, sb);
        else if (rel instanceof SubqueryAlias a) emitSubqueryAlias(a, sb);
        else throw new UnsupportedOperationException("Unsupported relation: " + rel.getClass().getSimpleName());
    }

    private void emitWith(With w, StringBuilder sb) {
        sb.append("WITH ");
        for (int i = 0; i < w.ctes().size(); i++) {
            CTERelation cte = w.ctes().get(i);
            if (i > 0) sb.append(", ");
            sb.append(quote(cte.name())).append(" AS (");
            emit(cte.definition(), sb);
            sb.append(")");
        }
        sb.append(" ");
        emit(w.body(), sb);
    }

    private void emitProject(Project p, StringBuilder sb) {
        sb.append("SELECT ");
        if (p.distinct()) sb.append("DISTINCT ");
        if (p.projectList().size() == 1 && p.projectList().get(0) instanceof Star) {
            sb.append("*");
        } else {
            for (int i = 0; i < p.projectList().size(); i++) {
                if (i > 0) sb.append(", ");
                emitExpr(p.projectList().get(i), sb);
            }
        }
        if (p.child() instanceof Filter f && !(f.child() instanceof Values)) {
            // Inline the filter instead of wrapping it in a subquery: wrapping would
            // drop alias qualifiers (FROM t AS x inside a derived table makes x.x
            // invisible to the outer select list).
            sb.append(" FROM ");
            emitChildSource(f.child(), sb);
            sb.append(" WHERE ");
            emitExpr(f.condition(), sb);
        } else if (!(p.child() instanceof Values)) {
            sb.append(" FROM ");
            emitChildSource(p.child(), sb);
        }
    }

    private void emitFilter(Filter f, StringBuilder sb) {
        sb.append("SELECT * FROM ");
        emitChildSource(f.child(), sb);
        sb.append(" WHERE ");
        emitExpr(f.condition(), sb);
    }

    private void emitJoin(Join j, StringBuilder sb) {
        emitChildSource(j.left(), sb);
        sb.append(" ").append(joinKeyword(j.joinType())).append(" ");
        emitChildSource(j.right(), sb);
        if (j.isUsing()) {
            sb.append(" USING (");
            for (int i = 0; i < j.usingColumns().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(quote(j.usingColumns().get(i)));
            }
            sb.append(")");
        } else if (j.condition() != null) {
            sb.append(" ON ");
            emitExpr(j.condition(), sb);
        }
    }

    private void emitChildSource(Relation child, StringBuilder sb) {
        if (isQuery(child)) {
            sb.append("(");
            emit(child, sb);
            sb.append(")");
        } else {
            emit(child, sb);
        }
    }

    private String joinKeyword(Join.JoinType t) {
        return switch (t) {
            case INNER -> "JOIN";
            case LEFT -> "LEFT JOIN";
            case RIGHT -> "RIGHT JOIN";
            case FULL -> "FULL OUTER JOIN";
            case CROSS -> "CROSS JOIN";
            case LEFT_SEMI -> "SEMI JOIN";
            case LEFT_ANTI -> "ANTI JOIN";
        };
    }

    private void emitAggregate(Aggregate a, StringBuilder sb) {
        sb.append("SELECT ");
        List<Expression> out = new ArrayList<>();
        if (a.aggregateExpressions() != null && !a.aggregateExpressions().isEmpty()) {
            out.addAll(a.aggregateExpressions());
        } else {
            out.addAll(a.groupingExpressions());
        }
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append(", ");
            emitExpr(out.get(i), sb);
        }
        Relation src = a.child();
        Expression inlinedFilter = null;
        if (src instanceof Filter f && !(f.child() instanceof Values)) {
            // Inline the filter to keep alias qualifiers visible (see emitProject).
            inlinedFilter = f.condition();
            src = f.child();
        }
        sb.append(" FROM ");
        emitChildSource(src, sb);
        if (inlinedFilter != null) {
            sb.append(" WHERE ");
            emitExpr(inlinedFilter, sb);
        }
        boolean hasGroupBy = !a.groupingExpressions().isEmpty() || !a.groupingAnalytics().isEmpty();
        if (hasGroupBy) {
            sb.append(" GROUP BY ");
            boolean first = true;
            for (Expression g : a.groupingExpressions()) {
                if (!first) sb.append(", ");
                emitExpr(g, sb);
                first = false;
            }
            for (Aggregate.GroupingAnalytics ga : a.groupingAnalytics()) {
                if (!first) sb.append(", ");
                emitGroupingAnalytics(ga, sb);
                first = false;
            }
        }
        if (a.havingCondition() != null) {
            sb.append(" HAVING ");
            emitExpr(a.havingCondition(), sb);
        }
    }

    private void emitGroupingAnalytics(Aggregate.GroupingAnalytics ga, StringBuilder sb) {
        switch (ga.kind()) {
            case ROLLUP -> {
                sb.append("ROLLUP(");
                appendExprList(ga.sets().get(0), sb);
                sb.append(")");
            }
            case CUBE -> {
                sb.append("CUBE(");
                appendExprList(ga.sets().get(0), sb);
                sb.append(")");
            }
            case GROUPING_SETS -> {
                sb.append("GROUPING SETS(");
                for (int i = 0; i < ga.sets().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("(");
                    appendExprList(ga.sets().get(i), sb);
                    sb.append(")");
                }
                sb.append(")");
            }
        }
    }

    private void appendExprList(List<Expression> exprs, StringBuilder sb) {
        for (int i = 0; i < exprs.size(); i++) {
            if (i > 0) sb.append(", ");
            emitExpr(exprs.get(i), sb);
        }
    }

    private void emitSort(Sort s, StringBuilder sb) {
        if (isQuery(s.child())) {
            emit(s.child(), sb);
        } else {
            sb.append("SELECT * FROM ");
            emitChildSource(s.child(), sb);
        }
        sb.append(" ORDER BY ");
        for (int i = 0; i < s.order().size(); i++) {
            if (i > 0) sb.append(", ");
            Sort.SortOrder o = s.order().get(i);
            emitExpr(o.expr(), sb);
            sb.append(o.ascending() ? " ASC" : " DESC");
            sb.append(o.nullsLast() ? " NULLS LAST" : " NULLS FIRST");
        }
    }

    private void emitLimit(Limit l, StringBuilder sb) {
        sb.append("SELECT * FROM ");
        emitChildSource(l.child(), sb);
        if (l.offset() > 0) sb.append(" OFFSET ").append(l.offset());
        sb.append(" LIMIT ").append(l.limit());
    }

    private boolean isQuery(Relation relation) {
        return relation instanceof Project || relation instanceof Filter
                || relation instanceof Aggregate || relation instanceof Sort
                || relation instanceof Limit || relation instanceof Union
                || relation instanceof With || relation instanceof Generate;
    }

    private void emitChild(Relation child, StringBuilder sb) {
        emit(child, sb);
    }

    private void emitUnion(Union u, StringBuilder sb) {
        String op = switch (u.kind()) {
            case UNION -> "UNION";
            case INTERSECT -> "INTERSECT";
            case EXCEPT -> "EXCEPT";
        };
        for (int i = 0; i < u.children().size(); i++) {
            if (i > 0) sb.append(" ").append(op).append(u.distinct() ? " " : " ALL ").append(" ");
            sb.append("(");
            emit(u.children().get(i), sb);
            sb.append(")");
        }
    }

    private void emitTableScan(TableScan t, StringBuilder sb) {
        // Logical name may be [catalog,] database, table (e.g. default.orders
        // from IDEs or qualified SQL). Physical tables all live in DuckDB's
        // main schema, so emit the bare table name.
        String table = t.tableName().get(t.tableName().size() - 1);
        sb.append(quote(table));
        if (t.alias() != null) sb.append(" AS ").append(quote(t.alias()));
    }

    private void emitValues(Values v, StringBuilder sb) {
        sb.append("VALUES ");
        for (int i = 0; i < v.rows().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("(");
            List<Expression> row = v.rows().get(i);
            for (int j = 0; j < row.size(); j++) {
                if (j > 0) sb.append(", ");
                emitExpr(row.get(j), sb);
            }
            sb.append(")");
        }
    }

    private void emitSubqueryAlias(SubqueryAlias a, StringBuilder sb) {
        sb.append("(");
        emit(a.child(), sb);
        sb.append(") AS ").append(quote(a.alias()));
    }

    private void emitGenerate(Generate g, StringBuilder sb) {
        sb.append("SELECT * FROM ");
        emit(g.child(), sb);
        sb.append(", UNNEST(");
        emitExpr(g.generator(), sb);
        sb.append(") AS ").append(quote(g.generatorOutputNames().isEmpty() ? "col" : g.generatorOutputNames().get(0)));
    }

    private void emitExpr(Expression e, StringBuilder sb) {
        if (e instanceof Literal l) emitLiteral(l, sb);
        else if (e instanceof IntervalLiteral iv) {
            sb.append("INTERVAL '").append(iv.value()).append("' ").append(iv.unit().toUpperCase());
        }
        else if (e instanceof Identifier id) sb.append(quote(id.name()));
        else if (e instanceof Lambda lam) emitLambda(lam, sb);
        else if (e instanceof AttributeReference a) {
            if (a.qualifier().isEmpty()) sb.append(quote(a.name()));
            else sb.append(String.join(".", a.qualifier().stream().map(DuckDbSqlGenerator::quote).toList())).append(".").append(quote(a.name()));
        }
        else if (e instanceof Alias al) {
            emitExpr(al.child(), sb);
            sb.append(" AS ").append(quote(al.name()));
        }
        else if (e instanceof Star s) {
            if (s.qualifier().isEmpty()) sb.append("*");
            else sb.append(String.join(".", s.qualifier().stream().map(DuckDbSqlGenerator::quote).toList())).append(".*");
        }
        else if (e instanceof BinaryExpression b) {
            if (b.op() == BinaryExpression.Op.BIT_XOR) {
                // DuckDB's ^ is power-of; bit xor needs the function form
                sb.append("xor(");
                emitExpr(b.left(), sb);
                sb.append(", ");
                emitExpr(b.right(), sb);
                sb.append(")");
            } else if (b.op() == BinaryExpression.Op.EQ_NULL_SAFE) {
                sb.append("(");
                emitExpr(b.left(), sb);
                sb.append(" IS NOT DISTINCT FROM ");
                emitExpr(b.right(), sb);
                sb.append(")");
            } else {
                sb.append("(");
                emitExpr(b.left(), sb);
                sb.append(" ").append(b.op().symbol()).append(" ");
                emitExpr(b.right(), sb);
                sb.append(")");
            }
        }
        else if (e instanceof UnaryExpression u) {
            switch (u.op()) {
                case NEG -> { sb.append("(-"); emitExpr(u.child(), sb); sb.append(")"); }
                case NOT -> { sb.append("(NOT "); emitExpr(u.child(), sb); sb.append(")"); }
                case IS_NULL -> { emitExpr(u.child(), sb); sb.append(" IS NULL"); }
                case IS_NOT_NULL -> { emitExpr(u.child(), sb); sb.append(" IS NOT NULL"); }
            }
        }
        else if (e instanceof FunctionCall f) emitFunctionCall(f, sb);
        else if (e instanceof Cast c) {
            sb.append("CAST(");
            emitExpr(c.child(), sb);
            sb.append(" AS ").append(duckType(c.target())).append(")");
        }
        else if (e instanceof CaseWhen cw) emitCaseWhen(cw, sb);
        else if (e instanceof InList in) {
            emitExpr(in.value(), sb);
            sb.append(in.negated() ? " NOT IN (" : " IN (");
            for (int i = 0; i < in.list().size(); i++) {
                if (i > 0) sb.append(", ");
                emitExpr(in.list().get(i), sb);
            }
            sb.append(")");
        }
        else if (e instanceof ArrayExpr a) {
            sb.append("[");
            for (int i = 0; i < a.elements().size(); i++) {
                if (i > 0) sb.append(", ");
                emitExpr(a.elements().get(i), sb);
            }
            sb.append("]");
        }
        else if (e instanceof StructExpr s) {
            sb.append("struct_pack(");
            for (int i = 0; i < s.fields().size(); i++) {
                if (i > 0) sb.append(", ");
                emitExpr(s.fields().get(i), sb);
            }
            sb.append(")");
        }
        else if (e instanceof Subquery sq) {
            sb.append("(");
            emit(sq.query(), sb);
            sb.append(")");
        }
        else if (e instanceof ScalarSubquery sq) {
            sb.append("(");
            emit(sq.query(), sb);
            sb.append(")");
        }
        else throw new UnsupportedOperationException("Unsupported expr: " + e.getClass().getSimpleName());
    }

    private void emitLiteral(Literal l, StringBuilder sb) {
        switch (l.kind()) {
            case NULL -> sb.append("NULL");
            case BOOLEAN, INTEGER, DECIMAL, DOUBLE -> sb.append(l.value());
            case STRING -> sb.append("'").append(((String) l.value()).replace("'", "''")).append("'");
            case DATE -> sb.append("DATE '").append(l.value()).append("'");
            case TIMESTAMP -> sb.append("TIMESTAMP '").append(l.value()).append("'");
        }
    }

    /**
     * Direct name mappings: Spark/Hive function name -> DuckDB function.
     * Semantics must match Spark (verified in SparkFunctionMappingTest);
     * functions needing argument restructuring live in emitMappedFunction.
     */
    private static final Map<String, String> FN_MAP = Map.ofEntries(
            // strings
            Map.entry("size", "array_length"),
            Map.entry("explode", "unnest"),
            Map.entry("posexplode", "unnest"),
            Map.entry("length", "length"),
            Map.entry("substr", "substring"),
            Map.entry("substring", "substring"),
            Map.entry("concat", "concat"),
            Map.entry("concat_ws", "concat_ws"),
            Map.entry("coalesce", "coalesce"),
            Map.entry("cast", "cast"),
            Map.entry("count", "count"),
            Map.entry("sum", "sum"),
            Map.entry("avg", "avg"),
            Map.entry("min", "min"),
            Map.entry("max", "max"),
            Map.entry("upper", "upper"),
            Map.entry("lower", "lower"),
            Map.entry("reverse", "reverse"),
            Map.entry("ascii", "ascii"),
            Map.entry("chr", "chr"),
            Map.entry("lpad", "lpad"),
            Map.entry("rpad", "rpad"),
            Map.entry("trim", "trim"),
            Map.entry("ltrim", "ltrim"),
            Map.entry("rtrim", "rtrim"),
            Map.entry("btrim", "btrim"),
            Map.entry("levenshtein", "levenshtein"),
            Map.entry("soundex", "soundex"),
            Map.entry("contains", "contains"),
            Map.entry("startswith", "starts_with"),
            Map.entry("endswith", "ends_with"),
            Map.entry("repeat", "repeat"),
            Map.entry("replace", "replace"),
            Map.entry("instr", "strpos"),
            Map.entry("split", "regexp_split_to_array"),
            Map.entry("regexp_replace", "regexp_replace"),
            Map.entry("regexp_extract", "regexp_extract"),
            Map.entry("regexp_extract_all", "regexp_extract_all"),
            Map.entry("regexp_like", "regexp_matches"),
            // numbers
            Map.entry("abs", "abs"),
            Map.entry("ceil", "ceil"),
            Map.entry("ceiling", "ceiling"),
            Map.entry("floor", "floor"),
            Map.entry("sqrt", "sqrt"),
            Map.entry("cbrt", "cbrt"),
            Map.entry("exp", "exp"),
            Map.entry("ln", "ln"),
            Map.entry("log2", "log2"),
            Map.entry("log10", "log10"),
            Map.entry("pow", "pow"),
            Map.entry("power", "pow"),
            Map.entry("sign", "sign"),
            Map.entry("signum", "sign"),
            Map.entry("isnan", "isnan"),
            Map.entry("isinf", "isinf"),
            Map.entry("pi", "pi"),
            Map.entry("acos", "acos"),
            Map.entry("acosh", "acosh"),
            Map.entry("asin", "asin"),
            Map.entry("asinh", "asinh"),
            Map.entry("atan", "atan"),
            Map.entry("atan2", "atan2"),
            Map.entry("atanh", "atanh"),
            Map.entry("cos", "cos"),
            Map.entry("cosh", "cosh"),
            Map.entry("cot", "cot"),
            Map.entry("sin", "sin"),
            Map.entry("sinh", "sinh"),
            Map.entry("tan", "tan"),
            Map.entry("tanh", "tanh"),
            Map.entry("degrees", "degrees"),
            Map.entry("radians", "radians"),
            Map.entry("round", "round"),
            Map.entry("factorial", "factorial"),
            Map.entry("bin", "bin"),
            // strings (DuckDB-native names)
            Map.entry("lcase", "lcase"),
            Map.entry("ucase", "ucase"),
            Map.entry("char_length", "char_length"),
            Map.entry("character_length", "character_length"),
            Map.entry("octet_length", "octet_length"),
            Map.entry("left", "left"),
            Map.entry("right", "right"),
            Map.entry("translate", "translate"),
            Map.entry("printf", "printf"),
            Map.entry("char", "chr"),
            Map.entry("sha", "sha1"),
            Map.entry("mean", "avg"),
            Map.entry("std", "stddev_samp"),
            // encoding / hash
            Map.entry("hex", "hex"),
            Map.entry("unhex", "unhex"),
            Map.entry("base64", "to_base64"),
            Map.entry("unbase64", "from_base64"),
            Map.entry("md5", "md5"),
            Map.entry("sha1", "sha1"),
            // encoding (charset support: utf-8 only, like Spark's default)
            Map.entry("encode", "encode"),
            Map.entry("decode", "decode"),
            // dates / misc (DuckDB-native names)
            Map.entry("now", "now"),
            Map.entry("random", "random"),
            Map.entry("date_part", "date_part"),
            Map.entry("make_date", "make_date"),
            Map.entry("map_concat", "map_concat"),
            Map.entry("map_entries", "map_entries"),
            Map.entry("map_from_entries", "map_from_entries"),
            Map.entry("map_from_arrays", "map"),
            Map.entry("to_json", "to_json"),
            Map.entry("typeof", "typeof"),
            Map.entry("uuid", "uuid"),
            // dates
            Map.entry("current_date", "current_date"),
            Map.entry("current_timestamp", "current_timestamp"),
            Map.entry("year", "year"),
            Map.entry("month", "month"),
            Map.entry("day", "day"),
            Map.entry("dayofmonth", "day"),
            Map.entry("quarter", "quarter"),
            Map.entry("hour", "hour"),
            Map.entry("minute", "minute"),
            Map.entry("second", "second"),
            Map.entry("dayofyear", "dayofyear"),
            Map.entry("weekofyear", "week"),
            Map.entry("last_day", "last_day"),
            Map.entry("get_json_object", "json_extract_string"),
            Map.entry("json_extract", "json_extract"),
            Map.entry("json_array_length", "json_array_length"),
            // arrays / maps
            Map.entry("array", "list_value"),
            Map.entry("array_contains", "list_contains"),
            Map.entry("array_position", "list_position"),
            Map.entry("array_distinct", "list_distinct"),
            Map.entry("array_intersect", "list_intersect"),
            Map.entry("array_join", "array_to_string"),
            Map.entry("array_max", "list_max"),
            Map.entry("array_min", "list_min"),
            Map.entry("element_at", "list_element"),
            Map.entry("flatten", "flatten"),
            Map.entry("cardinality", "len"),
            Map.entry("map_keys", "map_keys"),
            Map.entry("map_values", "map_values"),
            // aggregates
            Map.entry("nvl", "coalesce"),
            Map.entry("ifnull", "coalesce"),
            Map.entry("any_value", "any_value"),
            Map.entry("first", "first"),
            Map.entry("median", "median"),
            Map.entry("mode", "mode"),
            Map.entry("corr", "corr"),
            Map.entry("covar_pop", "covar_pop"),
            Map.entry("covar_samp", "covar_samp"),
            Map.entry("stddev", "stddev_samp"),
            Map.entry("stddev_pop", "stddev_pop"),
            Map.entry("stddev_samp", "stddev_samp"),
            Map.entry("variance", "var_samp"),
            Map.entry("var_pop", "var_pop"),
            Map.entry("var_samp", "var_samp"),
            Map.entry("approx_count_distinct", "approx_count_distinct"),
            Map.entry("collect_list", "list"),
            Map.entry("bool_and", "bool_and"),
            Map.entry("bool_or", "bool_or"),
            Map.entry("every", "bool_and"),
            Map.entry("some", "bool_or"),
            Map.entry("any", "bool_or"),
            // higher-order functions (lambda-capable in DuckDB)
            Map.entry("transform", "list_transform"),
            Map.entry("filter", "list_filter"),
            Map.entry("reduce", "list_reduce"),
            // window
            Map.entry("row_number", "row_number"),
            Map.entry("rank", "rank"),
            Map.entry("dense_rank", "dense_rank"),
            Map.entry("percent_rank", "percent_rank"),
            Map.entry("cume_dist", "cume_dist"),
            Map.entry("ntile", "ntile"),
            Map.entry("lag", "lag"),
            Map.entry("lead", "lead"),
            Map.entry("first_value", "first_value"),
            Map.entry("last_value", "last_value"),
            Map.entry("nth_value", "nth_value"),
            Map.entry("max_by", "arg_max"),
            Map.entry("min_by", "arg_min")
    );

    /** Custom rename overlay (from --function-map); overrides built-ins. */
    private final Map<String, String> customRenames;
    /** Custom templates with {N} positional argument substitution. */
    private final Map<String, String> customTemplates;

    public DuckDbSqlGenerator() {
        this(Map.of(), Map.of());
    }

    public DuckDbSqlGenerator(Map<String, String> customRenames, Map<String, String> customTemplates) {
        this.customRenames = Map.copyOf(customRenames == null ? Map.of() : customRenames);
        this.customTemplates = Map.copyOf(customTemplates == null ? Map.of() : customTemplates);
    }

    /**
     * Mappings that need argument restructuring (arg order swaps, arithmetic
     * instead of functions, CASE rewrites, literal dispatch). Returns false
     * when the shape does not apply and generic emission should proceed.
     */
    /** Renders a user-provided template: {N} is replaced by the Nth argument. */
    private boolean emitCustomTemplate(String name, FunctionCall f, StringBuilder sb) {
        String template = customTemplates.get(name);
        if (template == null) return false;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int close = template.indexOf('}', i);
                if (close > i) {
                    int argIndex;
                    try {
                        argIndex = Integer.parseInt(template.substring(i + 1, close).trim());
                    } catch (NumberFormatException e) {
                        argIndex = -1;
                    }
                    if (argIndex >= 0 && argIndex < f.arguments().size()) {
                        emitExpr(f.arguments().get(argIndex), sb);
                        i = close + 1;
                        continue;
                    }
                    throw new IllegalArgumentException("Function template '" + name + "' references argument {"
                            + template.substring(i + 1, close) + "} but was called with "
                            + f.arguments().size() + " arguments");
                }
            }
            sb.append(c);
            i++;
        }
        return true;
    }

    private boolean emitCustomRename(String name, FunctionCall f, StringBuilder sb) {
        String target = customRenames.get(name);
        if (target == null) return false;
        sb.append(target).append("(");
        if (f.distinct()) sb.append("DISTINCT ");
        for (int i = 0; i < f.arguments().size(); i++) {
            if (i > 0) sb.append(", ");
            emitExpr(f.arguments().get(i), sb);
        }
        sb.append(")");
        if (f.windowSpec() != null) emitWindowSpec(f.windowSpec(), sb);
        return true;
    }

    private boolean emitMappedFunction(String name, FunctionCall f, StringBuilder sb) {
        List<io.github.joegxx.localsql.ir.expression.Expression> args = f.arguments();
        switch (name) {
            case "if" -> {
                if (args.size() != 3) return false;
                sb.append("(CASE WHEN ");
                emitExpr(args.get(0), sb);
                sb.append(" THEN ");
                emitExpr(args.get(1), sb);
                sb.append(" ELSE ");
                emitExpr(args.get(2), sb);
                sb.append(" END)");
                return true;
            }
            case "nvl2" -> {
                if (args.size() != 3) return false;
                sb.append("(CASE WHEN ");
                emitExpr(args.get(0), sb);
                sb.append(" IS NOT NULL THEN ");
                emitExpr(args.get(1), sb);
                sb.append(" ELSE ");
                emitExpr(args.get(2), sb);
                sb.append(" END)");
                return true;
            }
            case "equal_null" -> {
                if (args.size() != 2) return false;
                sb.append("(");
                emitExpr(args.get(0), sb);
                sb.append(" IS NOT DISTINCT FROM ");
                emitExpr(args.get(1), sb);
                sb.append(")");
                return true;
            }
            case "mod", "pmod" -> {
                if (args.size() != 2) return false;
                // Spark returns NULL on zero divisor; pmod keeps a - floor(a/b)*b
                sb.append("(CASE WHEN (");
                emitExpr(args.get(1), sb);
                sb.append(") = 0 THEN NULL ELSE ");
                if (name.equals("mod")) {
                    sb.append("(");
                    emitExpr(args.get(0), sb);
                    sb.append(" % ");
                    emitExpr(args.get(1), sb);
                    sb.append(")");
                } else {
                    sb.append("((");
                    emitExpr(args.get(0), sb);
                    sb.append(" % ");
                    emitExpr(args.get(1), sb);
                    sb.append(") + ");
                    emitExpr(args.get(1), sb);
                    sb.append(") % ");
                    emitExpr(args.get(1), sb);
                }
                sb.append(" END)");
                return true;
            }
            case "date_add", "date_sub" -> {
                if (args.size() != 2) return false;
                sb.append("(CAST(");
                emitExpr(args.get(0), sb);
                sb.append(" AS DATE) ").append(name.equals("date_add") ? "+" : "-").append(" (");
                emitExpr(args.get(1), sb);
                sb.append("))");
                return true;
            }
            case "datediff" -> {
                if (args.size() != 2) return false;
                // Spark datediff(end, start); DuckDB date_diff(part, start, end)
                sb.append("date_diff('day', CAST(");
                emitExpr(args.get(1), sb);
                sb.append(" AS DATE), CAST(");
                emitExpr(args.get(0), sb);
                sb.append(" AS DATE))");
                return true;
            }
            case "add_months" -> {
                if (args.size() != 2) return false;
                sb.append("(CAST(CAST(");
                emitExpr(args.get(0), sb);
                sb.append(" AS DATE) + to_months(");
                emitExpr(args.get(1), sb);
                sb.append(") AS DATE))");
                return true;
            }
            case "unix_timestamp" -> {
                if (args.size() == 1) {
                    sb.append("CAST(epoch(CAST(");
                    emitExpr(args.get(0), sb);
                    sb.append(" AS TIMESTAMP)) AS BIGINT)");
                    return true;
                }
                if (args.isEmpty()) {
                    sb.append("CAST(epoch(CAST(CURRENT_TIMESTAMP AS TIMESTAMP)) AS BIGINT)");
                    return true;
                }
                return false;
            }
            case "from_unixtime" -> {
                if (args.size() != 1) return false;
                sb.append("strftime(to_timestamp(");
                emitExpr(args.get(0), sb);
                sb.append("), '%Y-%m-%d %H:%M:%S')");
                return true;
            }
            case "weekday" -> {
                if (args.size() != 1) return false;
                // Spark weekday: Monday=0..Sunday=6; DuckDB weekday is 1-based Monday
                sb.append("((weekday(");
                emitExpr(args.get(0), sb);
                sb.append(")) - 1)");
                return true;
            }
            case "dayofweek" -> {
                if (args.size() != 1) return false;
                // Spark dayofweek: Sunday=1..Saturday=7; DuckDB weekday: Monday=1..Sunday=7
                sb.append("((weekday(");
                emitExpr(args.get(0), sb);
                sb.append(") % 7) + 1)");
                return true;
            }
            case "regexp_replace" -> {
                if (args.size() < 2 || args.size() > 3) return false;
                // Spark replaces all occurrences; DuckDB needs the 'g' flag
                sb.append("regexp_replace(");
                emitExpr(args.get(0), sb);
                sb.append(", ");
                emitExpr(args.get(1), sb);
                sb.append(", ");
                if (args.size() == 3) emitExpr(args.get(2), sb);
                else sb.append("''");
                sb.append(", 'g')");
                return true;
            }
            case "regexp_count" -> {
                if (args.size() < 2 || args.size() > 3) return false;
                sb.append("len(regexp_extract_all(");
                emitExpr(args.get(0), sb);
                sb.append(", ");
                emitExpr(args.get(1), sb);
                sb.append("))");
                return true;
            }
            case "array_union" -> {
                if (args.size() != 2) return false;
                sb.append("list_distinct(list_concat(");
                emitExpr(args.get(0), sb);
                sb.append(", ");
                emitExpr(args.get(1), sb);
                sb.append("))");
                return true;
            }
            case "array_except" -> {
                if (args.size() != 2) return false;
                sb.append("list_distinct(list_filter(");
                emitExpr(args.get(0), sb);
                sb.append(", x -> NOT list_contains(");
                emitExpr(args.get(1), sb);
                sb.append(", x)))");
                return true;
            }
            case "array_join" -> {
                if (args.size() == 2) return false; // DuckDB array_to_string(list, delim) is a direct match
                if (args.size() != 3) return false;
                sb.append("array_to_string(list_transform(");
                emitExpr(args.get(0), sb);
                sb.append(", x -> COALESCE(CAST(x AS VARCHAR), ");
                emitExpr(args.get(2), sb);
                sb.append(")), ");
                emitExpr(args.get(1), sb);
                sb.append(")");
                return true;
            }
            case "to_date" -> {
                if (args.size() == 1) {
                    sb.append("CAST(");
                    emitExpr(args.get(0), sb);
                    sb.append(" AS DATE)");
                    return true;
                }
                if (args.size() == 2) {
                    sb.append("CAST(strptime(");
                    emitExpr(args.get(0), sb);
                    sb.append(", ");
                    emitExpr(args.get(1), sb);
                    sb.append(") AS DATE)");
                    return true;
                }
                return false;
            }
            case "to_timestamp" -> {
                if (args.size() != 1) return false;
                sb.append("CAST(");
                emitExpr(args.get(0), sb);
                sb.append(" AS TIMESTAMP)");
                return true;
            }
            case "log" -> {
                if (args.size() == 1) {
                    sb.append("ln(");
                    emitExpr(args.get(0), sb);
                    sb.append(")");
                    return true;
                }
                if (args.size() == 2) {
                    // Spark log(base, x); DuckDB log(x) is base 10
                    sb.append("(ln(");
                    emitExpr(args.get(1), sb);
                    sb.append(") / ln(");
                    emitExpr(args.get(0), sb);
                    sb.append("))");
                    return true;
                }
                return false;
            }
            case "locate", "position" -> {
                if (args.size() == 2) {
                    // Spark locate(substr, str); DuckDB strpos(str, substr)
                    sb.append("strpos(");
                    emitExpr(args.get(1), sb);
                    sb.append(", ");
                    emitExpr(args.get(0), sb);
                    sb.append(")");
                    return true;
                }
                if (args.size() == 3) {
                    sb.append("(CASE WHEN strpos(substr(");
                    emitExpr(args.get(1), sb);
                    sb.append(", ");
                    emitExpr(args.get(2), sb);
                    sb.append("), ");
                    emitExpr(args.get(0), sb);
                    sb.append(") = 0 THEN 0 ELSE strpos(substr(");
                    emitExpr(args.get(1), sb);
                    sb.append(", ");
                    emitExpr(args.get(2), sb);
                    sb.append("), ");
                    emitExpr(args.get(0), sb);
                    sb.append(") + ");
                    emitExpr(args.get(2), sb);
                    sb.append(" - 1 END)");
                    return true;
                }
                return false;
            }
            case "sha2" -> {
                if (args.size() != 2) return false;
                Object bits = args.get(1) instanceof io.github.joegxx.localsql.ir.expression.Literal l
                        && l.kind() == io.github.joegxx.localsql.ir.expression.Literal.Kind.INTEGER
                        ? l.value() : null;
                if (bits == null || !(bits instanceof Number n)
                        || (n.longValue() != 0 && n.longValue() != 256)) {
                    throw new UnsupportedOperationException(
                            "sha2 supports 0/256 bits via DuckDB sha256; requested: " + args.get(1));
                }
                sb.append("sha256(");
                emitExpr(args.get(0), sb);
                sb.append(")");
                return true;
            }
            case "map" -> {
                if (args.isEmpty() || args.size() % 2 != 0) return false;
                sb.append("map(list_value(");
                for (int i = 0; i < args.size(); i += 2) {
                    if (i > 0) sb.append(", ");
                    emitExpr(args.get(i), sb);
                }
                sb.append("), list_value(");
                for (int i = 1; i < args.size(); i += 2) {
                    if (i > 1) sb.append(", ");
                    emitExpr(args.get(i), sb);
                }
                sb.append("))");
                return true;
            }
            case "string", "tinyint", "smallint", "int", "bigint", "float", "double",
                 "boolean", "date", "timestamp", "binary", "decimal" -> {
                if (args.isEmpty() || args.size() > 3) return false;
                String sqlType = switch (name) {
                    case "string" -> "VARCHAR";
                    case "date" -> "DATE";
                    case "timestamp" -> "TIMESTAMP";
                    case "binary" -> "BLOB";
                    default -> name.toUpperCase();
                };
                sb.append("CAST(");
                emitExpr(args.get(0), sb);
                if (name.equals("decimal") && args.size() >= 2) {
                    Object p = args.get(1) instanceof io.github.joegxx.localsql.ir.expression.Literal l
                            && l.kind() == io.github.joegxx.localsql.ir.expression.Literal.Kind.INTEGER ? l.value() : null;
                    if (p == null) throw new UnsupportedOperationException("decimal(x, p[, s]) requires literal precision");
                    Object s = args.size() == 3 && args.get(2) instanceof io.github.joegxx.localsql.ir.expression.Literal l2
                            && l2.kind() == io.github.joegxx.localsql.ir.expression.Literal.Kind.INTEGER ? l2.value() : 0;
                    sqlType = "DECIMAL(" + p + "," + s + ")";
                } else if (name.equals("decimal")) {
                    sqlType = "DECIMAL(10,0)";
                }
                sb.append(" AS ").append(sqlType).append(")");
                return true;
            }
            case "isnull" -> {
                if (args.size() != 1) return false;
                sb.append("(");
                emitExpr(args.get(0), sb);
                sb.append(" IS NULL)");
                return true;
            }
            case "isnotnull" -> {
                if (args.size() != 1) return false;
                sb.append("(");
                emitExpr(args.get(0), sb);
                sb.append(" IS NOT NULL)");
                return true;
            }
            case "positive" -> {
                if (args.size() != 1) return false;
                sb.append("(");
                emitExpr(args.get(0), sb);
                sb.append(")");
                return true;
            }
            case "negative" -> {
                if (args.size() != 1) return false;
                sb.append("(-(");
                emitExpr(args.get(0), sb);
                sb.append("))");
                return true;
            }
            case "octet_length" -> {
                // DuckDB's octet_length only binds for BLOB; strings need an explicit cast
                if (args.size() != 1) return false;
                sb.append("octet_length(CAST(");
                emitExpr(args.get(0), sb);
                sb.append(" AS BLOB))");
                return true;
            }
            case "e" -> {
                sb.append("exp(1)");
                return true;
            }
            case "expm1" -> {
                if (args.size() != 1) return false;
                sb.append("(exp(");
                emitExpr(args.get(0), sb);
                sb.append(") - 1)");
                return true;
            }
            case "log1p" -> {
                if (args.size() != 1) return false;
                sb.append("ln((");
                emitExpr(args.get(0), sb);
                sb.append(") + 1)");
                return true;
            }
            case "hypot" -> {
                if (args.size() != 2) return false;
                sb.append("sqrt((");
                emitExpr(args.get(0), sb);
                sb.append(" * ");
                emitExpr(args.get(0), sb);
                sb.append(") + (");
                emitExpr(args.get(1), sb);
                sb.append(" * ");
                emitExpr(args.get(1), sb);
                sb.append("))");
                return true;
            }
            case "nanvl" -> {
                if (args.size() != 2) return false;
                sb.append("(CASE WHEN isnan(");
                emitExpr(args.get(0), sb);
                sb.append(") THEN ");
                emitExpr(args.get(1), sb);
                sb.append(" ELSE ");
                emitExpr(args.get(0), sb);
                sb.append(" END)");
                return true;
            }
            case "elt" -> {
                if (args.size() < 2) return false;
                sb.append("list_element(list_value(");
                for (int i = 1; i < args.size(); i++) {
                    if (i > 1) sb.append(", ");
                    emitExpr(args.get(i), sb);
                }
                sb.append("), ");
                emitExpr(args.get(0), sb);
                sb.append(")");
                return true;
            }
            case "space" -> {
                if (args.size() != 1) return false;
                sb.append("repeat(' ', ");
                emitExpr(args.get(0), sb);
                sb.append(")");
                return true;
            }
            case "find_in_set" -> {
                if (args.size() != 2) return false;
                sb.append("coalesce(list_position(string_split(CAST(");
                emitExpr(args.get(1), sb);
                sb.append(" AS VARCHAR), ','), CAST(");
                emitExpr(args.get(0), sb);
                sb.append(" AS VARCHAR)), 0)");
                return true;
            }
            case "substring_index" -> {
                if (args.size() != 3) return false;
                Object count = args.get(2) instanceof io.github.joegxx.localsql.ir.expression.Literal l
                        && l.kind() == io.github.joegxx.localsql.ir.expression.Literal.Kind.INTEGER ? l.value() : null;
                if (count == null) {
                    throw new UnsupportedOperationException("substring_index requires a literal count");
                }
                long c = ((Number) count).longValue();
                sb.append("CASE WHEN ");
                emitExpr(args.get(2), sb);
                sb.append(" = 0 THEN '' ELSE array_to_string(list_slice(string_split(CAST(");
                emitExpr(args.get(0), sb);
                sb.append(" AS VARCHAR), CAST(");
                emitExpr(args.get(1), sb);
                sb.append(" AS VARCHAR)), ");
                sb.append(c > 0 ? "1, " + c : c + ", -1");
                sb.append("), CAST(");
                emitExpr(args.get(1), sb);
                sb.append(" AS VARCHAR)) END");
                return true;
            }
            case "format_number" -> {
                if (args.size() != 2) return false;
                Object d = args.get(1) instanceof io.github.joegxx.localsql.ir.expression.Literal l
                        && l.kind() == io.github.joegxx.localsql.ir.expression.Literal.Kind.INTEGER ? l.value() : null;
                if (d == null) {
                    throw new UnsupportedOperationException("format_number requires a literal scale");
                }
                sb.append("printf('%,.").append(((Number) d).intValue()).append("f', ");
                emitExpr(args.get(0), sb);
                sb.append(")");
                return true;
            }
            case "slice" -> {
                if (args.size() != 3) return false;
                sb.append("list_slice(");
                emitExpr(args.get(0), sb);
                sb.append(", ");
                emitExpr(args.get(1), sb);
                sb.append(", ((");
                emitExpr(args.get(1), sb);
                sb.append(") + (");
                emitExpr(args.get(2), sb);
                sb.append(")) - 1)");
                return true;
            }
            case "array_remove" -> {
                if (args.size() != 2) return false;
                sb.append("list_filter(");
                emitExpr(args.get(0), sb);
                sb.append(", x -> x IS DISTINCT FROM ");
                emitExpr(args.get(1), sb);
                sb.append(")");
                return true;
            }
            case "current_catalog" -> {
                sb.append("'spark_catalog'");
                return true;
            }
            case "current_timezone" -> {
                sb.append("current_setting('TimeZone')");
                return true;
            }
            case "date_from_unix_date" -> {
                if (args.size() != 1) return false;
                sb.append("(DATE '1970-01-01' + CAST((");
                emitExpr(args.get(0), sb);
                sb.append(") AS INTEGER))");
                return true;
            }
            case "unix_date" -> {
                if (args.size() != 1) return false;
                sb.append("date_diff('day', DATE '1970-01-01', CAST(");
                emitExpr(args.get(0), sb);
                sb.append(" AS DATE))");
                return true;
            }
            case "unix_seconds", "unix_millis", "unix_micros" -> {
                if (args.size() != 1) return false;
                String fn = switch (name) {
                    case "unix_seconds" -> "epoch";
                    case "unix_millis" -> "epoch_ms";
                    default -> "epoch_us";
                };
                sb.append("CAST(").append(fn).append("(CAST(");
                emitExpr(args.get(0), sb);
                sb.append(" AS TIMESTAMP)) AS BIGINT)");
                return true;
            }
            case "to_unix_timestamp" -> {
                if (args.size() != 1) return false;
                sb.append("CAST(epoch(CAST(");
                emitExpr(args.get(0), sb);
                sb.append(" AS TIMESTAMP)) AS BIGINT)");
                return true;
            }
            case "count_if" -> {
                if (args.size() != 1) return false;
                sb.append("count(CASE WHEN ");
                emitExpr(args.get(0), sb);
                sb.append(" THEN 1 END)");
                return true;
            }
            case "collect_set" -> {
                if (args.size() != 1) return false;
                sb.append("list(DISTINCT ");
                emitExpr(args.get(0), sb);
                sb.append(")");
                return true;
            }
            case "try_divide" -> {
                if (args.size() != 2) return false;
                sb.append("(");
                emitExpr(args.get(0), sb);
                sb.append(" / NULLIF(");
                emitExpr(args.get(1), sb);
                sb.append(", 0))");
                return true;
            }
            case "months_between" -> {
                if (args.size() != 2) return false;
                // Spark: (y1*12+m1) - (y2*12+m2) + ((day1 + timefrac1) - (day2 + timefrac2)) / 31
                String template = "((EXTRACT(year FROM \u0001) * 12 + EXTRACT(month FROM \u0001))"
                        + " - (EXTRACT(year FROM \u0002) * 12 + EXTRACT(month FROM \u0002))"
                        + " + ((EXTRACT(day FROM \u0001) + (EXTRACT(hour FROM \u0001) * 3600"
                        + " + EXTRACT(minute FROM \u0001) * 60 + EXTRACT(second FROM \u0001)) / 86400.0)"
                        + " - (EXTRACT(day FROM \u0002) + (EXTRACT(hour FROM \u0002) * 3600"
                        + " + EXTRACT(minute FROM \u0002) * 60 + EXTRACT(second FROM \u0002)) / 86400.0)) / 31.0)";
                for (char c : template.toCharArray()) {
                    if (c == '\u0001') {
                        sb.append("CAST(");
                        emitExpr(args.get(0), sb);
                        sb.append(" AS TIMESTAMP)");
                    } else if (c == '\u0002') {
                        sb.append("CAST(");
                        emitExpr(args.get(1), sb);
                        sb.append(" AS TIMESTAMP)");
                    } else {
                        sb.append(c);
                    }
                }
                return true;
            }
            case "next_day" -> {
                if (args.size() != 2) return false;
                Object dayName = args.get(1) instanceof io.github.joegxx.localsql.ir.expression.Literal l
                        && l.kind() == io.github.joegxx.localsql.ir.expression.Literal.Kind.STRING ? l.value() : null;
                if (dayName == null) {
                    throw new UnsupportedOperationException("next_day requires a literal day name");
                }
                int target = dayIndex(String.valueOf(dayName));
                if (target < 0) {
                    throw new UnsupportedOperationException("next_day: unknown day name " + dayName);
                }
                sb.append("(CAST(");
                emitExpr(args.get(0), sb);
                sb.append(" AS DATE) + CAST(CASE WHEN ((").append(target).append(" - (weekday(");
                emitExpr(args.get(0), sb);
                sb.append(") - 1)) % 7 + 7) % 7 = 0 THEN 7 ELSE (((").append(target).append(" - (weekday(");
                emitExpr(args.get(0), sb);
                sb.append(") - 1)) % 7 + 7) % 7) END AS INTEGER))");
                return true;
            }
            case "date_trunc" -> {
                if (args.size() != 2) return false;
                Object unit = args.get(0) instanceof io.github.joegxx.localsql.ir.expression.Literal l
                        && l.kind() == io.github.joegxx.localsql.ir.expression.Literal.Kind.STRING ? l.value() : null;
                if (unit == null) {
                    throw new UnsupportedOperationException("date_trunc requires a literal unit");
                }
                String translated = switch (String.valueOf(unit).toLowerCase()) {
                    case "year", "yyyy", "yy" -> "year";
                    case "quarter" -> "quarter";
                    case "mon", "month", "mm" -> "month";
                    case "week" -> "week";
                    case "day", "dd" -> "day";
                    case "hour" -> "hour";
                    case "minute" -> "minute";
                    case "second" -> "second";
                    default -> null;
                };
                if (translated == null) {
                    throw new UnsupportedOperationException("date_trunc unit not supported: " + unit);
                }
                sb.append("date_trunc('").append(translated).append("', ");
                emitExpr(args.get(1), sb);
                sb.append(")");
                return true;
            }
            case "exists" -> {
                if (args.size() != 2) return false;
                sb.append("(len(list_filter(");
                emitExpr(args.get(0), sb);
                sb.append(", ");
                emitExpr(args.get(1), sb);
                sb.append(")) > 0)");
                return true;
            }
            case "forall" -> {
                if (args.size() != 2) return false;
                sb.append("(len(list_filter(");
                emitExpr(args.get(0), sb);
                sb.append(", ");
                emitExpr(args.get(1), sb);
                sb.append(")) = len(");
                emitExpr(args.get(0), sb);
                sb.append("))");
                return true;
            }
            case "aggregate" -> {
                // aggregate(arr, zero, merge): prepend zero so DuckDB's reduce
                // uses it as the accumulator seed. A finish lambda cannot be
                // applied (DuckDB does not support invoking lambdas).
                if (args.size() != 3) {
                    throw new UnsupportedOperationException(
                            "aggregate with a finish lambda is not supported yet; use aggregate(arr, zero, merge)");
                }
                sb.append("list_reduce(list_concat(list_value(");
                emitExpr(args.get(1), sb);
                sb.append("), ");
                emitExpr(args.get(0), sb);
                sb.append("), ");
                emitExpr(args.get(2), sb);
                sb.append(")");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void emitLambda(Lambda lam, StringBuilder sb) {
        if (lam.variables().size() == 1) {
            sb.append(quote(lam.variables().get(0))).append(" -> ");
        } else {
            sb.append("(");
            for (int i = 0; i < lam.variables().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(quote(lam.variables().get(i)));
            }
            sb.append(") -> ");
        }
        emitExpr(lam.body(), sb);
    }

    private void emitFunctionCall(FunctionCall f, StringBuilder sb) {
        String name = f.name().toLowerCase();
        if (emitCustomTemplate(name, f, sb)) return;
        if (emitCustomRename(name, f, sb)) return;
        if (name.equals("between") && f.arguments().size() == 3) {
            sb.append("(");
            emitExpr(f.arguments().get(0), sb);
            sb.append(" BETWEEN ");
            emitExpr(f.arguments().get(1), sb);
            sb.append(" AND ");
            emitExpr(f.arguments().get(2), sb);
            sb.append(")");
            return;
        }
        if ((name.equals("like") || name.equals("rlike")) && f.arguments().size() == 2 && f.windowSpec() == null) {
            sb.append("(");
            emitExpr(f.arguments().get(0), sb);
            sb.append(name.equals("like") ? " LIKE " : " REGEXP ");
            emitExpr(f.arguments().get(1), sb);
            sb.append(")");
            return;
        }
        if (emitMappedFunction(name, f, sb)) return;
        String mapped = FN_MAP.getOrDefault(name, name);
        sb.append(mapped).append("(");
        if (f.distinct()) sb.append("DISTINCT ");
        for (int i = 0; i < f.arguments().size(); i++) {
            if (i > 0) sb.append(", ");
            emitExpr(f.arguments().get(i), sb);
        }
        sb.append(")");
        if (f.windowSpec() != null) emitWindowSpec(f.windowSpec(), sb);
    }

    /** Spark next_day accepts full names and abbreviations; 0=Monday..6=Sunday. */
    private static int dayIndex(String name) {
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

    private void emitWindowSpec(WindowSpec w, StringBuilder sb) {
        sb.append(" OVER (");
        boolean first = true;
        if (!w.partitionBy().isEmpty()) {
            sb.append("PARTITION BY ");
            for (int i = 0; i < w.partitionBy().size(); i++) {
                if (i > 0) sb.append(", ");
                emitExpr(w.partitionBy().get(i), sb);
            }
            first = false;
        }
        if (!w.orderBy().isEmpty()) {
            if (!first) sb.append(" ");
            sb.append("ORDER BY ");
            for (int i = 0; i < w.orderBy().size(); i++) {
                if (i > 0) sb.append(", ");
                Sort.SortOrder o = w.orderBy().get(i);
                emitExpr(o.expr(), sb);
                sb.append(o.ascending() ? " ASC" : " DESC");
                sb.append(o.nullsLast() ? " NULLS LAST" : " NULLS FIRST");
            }
            first = false;
        }
        if (w.frame() != null) {
            if (!first) sb.append(" ");
            sb.append(w.frame().rows() ? "ROWS" : "RANGE").append(" BETWEEN ");
            emitFrameBound(w.frame().start(), sb);
            sb.append(" AND ");
            emitFrameBound(w.frame().end() != null ? w.frame().end() : WindowSpec.Bound.currentRow(), sb);
        }
        sb.append(")");
    }

    private void emitFrameBound(WindowSpec.Bound b, StringBuilder sb) {
        switch (b.type()) {
            case UNBOUNDED_PRECEDING -> sb.append("UNBOUNDED PRECEDING");
            case UNBOUNDED_FOLLOWING -> sb.append("UNBOUNDED FOLLOWING");
            case CURRENT_ROW -> sb.append("CURRENT ROW");
            case PRECEDING -> { emitExpr(b.offset(), sb); sb.append(" PRECEDING"); }
            case FOLLOWING -> { emitExpr(b.offset(), sb); sb.append(" FOLLOWING"); }
        }
    }

    private void emitCaseWhen(CaseWhen cw, StringBuilder sb) {
        sb.append("CASE");
        for (CaseWhen.WhenBranch b : cw.branches()) {
            sb.append(" WHEN ");
            emitExpr(b.condition(), sb);
            sb.append(" THEN ");
            emitExpr(b.value(), sb);
        }
        if (cw.elseValue() != null) {
            sb.append(" ELSE ");
            emitExpr(cw.elseValue(), sb);
        }
        sb.append(" END");
    }

    private String duckType(DataType t) {
        if (t == null) return "VARCHAR";
        if (t instanceof DecimalType d) return "DECIMAL(" + d.precision() + "," + d.scale() + ")";
        if (t instanceof io.github.joegxx.localsql.ir.type.ArrayType a) {
            return duckType(a.elementType()) + "[]";
        }
        if (t instanceof io.github.joegxx.localsql.ir.type.MapType m) {
            return "MAP(" + duckType(m.keyType()) + ", " + duckType(m.valueType()) + ")";
        }
        if (t instanceof io.github.joegxx.localsql.ir.type.StructType s) {
            StringBuilder sb = new StringBuilder("STRUCT(");
            for (int i = 0; i < s.fields().size(); i++) {
                if (i > 0) sb.append(", ");
                var f = s.fields().get(i);
                sb.append(quote(f.name())).append(" ").append(duckType(f.dataType()));
            }
            return sb.append(")").toString();
        }
        return switch (t.typeName()) {
            case "BOOLEAN" -> "BOOLEAN";
            case "TINYINT" -> "TINYINT";
            case "SMALLINT" -> "SMALLINT";
            case "INT" -> "INTEGER";
            case "BIGINT" -> "BIGINT";
            case "FLOAT" -> "FLOAT";
            case "DOUBLE" -> "DOUBLE";
            case "STRING" -> "VARCHAR";
            case "BINARY" -> "BLOB";
            case "DATE" -> "DATE";
            case "TIMESTAMP" -> "TIMESTAMP";
            default -> "VARCHAR";
        };
    }

    /** Quote an identifier for DuckDB (public so the pipeline can share the exact rules). */
    public static String quote(String id) {
        if (id == null || id.isEmpty()) return id;
        if (id.matches("[a-z_][a-z0-9_]*") && !DUCKDB_RESERVED.contains(id)) return id;
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    /** DuckDB reserved words that would break unquoted SQL if used as identifiers. */
    public static final java.util.Set<String> DUCKDB_RESERVED = java.util.Set.of(
            "all", "analyse", "analyze", "and", "anti", "any", "array", "as", "asc",
            "asymmetric", "at", "authorization", "between", "bigint", "binary", "bit",
            "boolean", "both", "by", "case", "cast", "char", "character", "check",
            "collate", "column", "concat", "constraint", "create", "cross", "cube",
            "current", "current_catalog", "current_date", "current_path",
            "current_role", "current_schema", "current_time", "current_timestamp",
            "current_user", "database", "date", "decimal", "default", "deferrable",
            "deferred", "delete", "desc", "describe", "distinct", "do", "double",
            "drop", "else", "end", "except", "exists", "explain", "extract", "false",
            "fetch", "filter", "first", "float", "following", "for", "foreign",
            "from", "full", "function", "group", "grouping", "groups", "having",
            "if", "ilike", "in", "initially", "inner", "inout", "insert", "int",
            "integer", "intersect", "interval", "into", "is", "join", "json",
            "lateral", "leading", "left", "like", "limit", "list", "local",
            "localtime", "localtimestamp", "map", "match", "mediumint", "mod", "natural",
            "new", "no", "none", "not", "null", "nulls", "numeric", "of", "offset",
            "old", "on", "only", "or", "order", "out", "outer", "over", "overlaps",
            "partition", "position", "preceding", "precision", "primary", "qualify",
            "range", "read", "real", "recursive", "references", "regexp", "release",
            "rename", "replace", "returning", "returns", "revoke", "right", "rollback",
            "rollup", "row", "rows", "sample", "schema", "select", "semi", "set",
            "similar", "smallint", "some", "struct", "table", "tablesample", "text",
            "then", "time", "timestamp", "tinyint", "to", "trailing", "transaction",
            "trigger", "true", "union", "unique", "unknown", "update", "user",
            "using", "uuid", "values", "varchar", "view", "when", "where", "window",
            "with", "xor", "year");
}
