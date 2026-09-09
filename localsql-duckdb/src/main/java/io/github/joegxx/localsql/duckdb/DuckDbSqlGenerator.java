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
            Map.entry("get_field", "struct_extract"),
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
            Map.entry("json_object_keys", "json_keys"),
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
            Map.entry("percentile", "quantile_cont"),
            Map.entry("percentile_approx", "quantile_cont"),
            Map.entry("approx_percentile", "quantile_cont"),
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
            Map.entry("min_by", "arg_min"),
            // Spark 3.5 registry backfill: DuckDB-native renames
            Map.entry("greatest", "greatest"),
            Map.entry("least", "least"),
            Map.entry("nullif", "nullif"),
            Map.entry("last", "last"),
            Map.entry("skewness", "skewness"),
            Map.entry("kurtosis", "kurtosis"),
            Map.entry("format_string", "printf"),
            Map.entry("array_sort", "list_sort"),
            Map.entry("arrays_overlap", "list_has_any"),
            Map.entry("bit_length", "bit_length"),
            Map.entry("bit_count", "bit_count"),
            Map.entry("rint", "round")
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
            case "initcap" -> {
                if (args.size() != 1) return false;
                // registered as a Java UDF on the DuckDB connection (DuckDB has no initcap)
                emitBuiltinUdfCall("initcap", args, List.of("VARCHAR"), sb);
                return true;
            }
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
            case "sort_array" -> {
                // sort_array(arr[, asc]) -> list_sort(arr, 'ASC'|'DESC'); the
                // second arg must be a boolean literal (comparator lambdas unsupported)
                if (args.size() < 1 || args.size() > 2) return false;
                boolean desc = false;
                if (args.size() == 2) {
                    if (args.get(1) instanceof io.github.joegxx.localsql.ir.expression.Literal l
                            && l.kind() == io.github.joegxx.localsql.ir.expression.Literal.Kind.BOOLEAN) {
                        desc = !((Boolean) l.value());
                    } else {
                        throw new UnsupportedOperationException(
                                "sort_array's second argument must be a boolean literal; comparator lambdas are not supported");
                    }
                }
                sb.append("list_sort(");
                emitExpr(args.get(0), sb);
                sb.append(", '").append(desc ? "DESC" : "ASC").append("')");
                return true;
            }
            case "sequence" -> {
                // sequence(start, stop[, step]) -> range(); DuckDB range is
                // upper-exclusive and does not auto-reverse, so handle both
                if (args.size() < 2 || args.size() > 3) return false;
                sb.append("CASE WHEN ");
                emitExpr(args.get(1), sb);
                sb.append(" >= ");
                emitExpr(args.get(0), sb);
                sb.append(" THEN range(");
                emitExpr(args.get(0), sb);
                sb.append(", ");
                emitExpr(args.get(1), sb);
                sb.append(" + 1");
                if (args.size() == 3) { sb.append(", "); emitExpr(args.get(2), sb); }
                sb.append(") ELSE range(");
                emitExpr(args.get(0), sb);
                sb.append(", ");
                emitExpr(args.get(1), sb);
                sb.append(" - 1, -1) END");
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
            case "transform_values", "transform_keys", "map_filter" -> {
                if (args.size() != 2 || !(args.get(1) instanceof Lambda lam)
                        || lam.variables().size() != 2) {
                    throw new UnsupportedOperationException(name
                            + " requires a two-variable lambda, got: " + (args.size() >= 2 ? args.get(1) : ""));
                }
                // lower the two-variable Spark lambda onto a single-variable
                // DuckDB lambda over map_entries structs: k -> "e"."key", v -> "e"."value"
                String k = lam.variables().get(0);
                String v = lam.variables().get(1);
                java.util.Map<String, Expression> subst = java.util.Map.of(
                        k, new AttributeReference("key", List.of("e")),
                        v, new AttributeReference("value", List.of("e")));
                Expression body = io.github.joegxx.localsql.ir.transform.IdentifierSubstituter
                        .rewrite(lam.body(), subst);
                sb.append("map_from_entries(");
                if (name.equals("map_filter")) {
                    sb.append("list_filter(map_entries(");
                    emitExpr(args.get(0), sb);
                    sb.append("), e -> ");
                    emitExpr(body, sb);
                    sb.append(")");
                } else {
                    sb.append("list_transform(map_entries(");
                    emitExpr(args.get(0), sb);
                    sb.append("), e -> {'key': ");
                    if (name.equals("transform_keys")) {
                        emitExpr(body, sb);
                        sb.append(", 'value': e.value");
                    } else {
                        sb.append("e.key, 'value': ");
                        emitExpr(body, sb);
                    }
                    sb.append("})");
                }
                sb.append(")");
                return true;
            }
            case "date_format" -> {
                if (args.size() != 2) return false;
                Object fmt = args.get(1) instanceof io.github.joegxx.localsql.ir.expression.Literal l
                        && l.kind() == io.github.joegxx.localsql.ir.expression.Literal.Kind.STRING ? l.value() : null;
                if (fmt == null) {
                    throw new UnsupportedOperationException(
                            "date_format requires a literal format string (Java -> strftime translation)");
                }
                String pattern = String.valueOf(fmt);
                int sss = pattern.indexOf("SSS");
                if (sss >= 0) {
                    if (pattern.indexOf("SSS", sss + 3) >= 0) {
                        throw new UnsupportedOperationException("date_format: multiple SSS segments are not supported: " + pattern);
                    }
                    // SSS (millis) cannot live inside strftime (only %f micros); emit a concat
                    String before = javaFormatToStrftime(pattern.substring(0, sss));
                    String after = javaFormatToStrftime(pattern.substring(sss + 3));
                    sb.append("concat(");
                    boolean first = true;
                    if (!before.isEmpty()) {
                        emitStrftime(args.get(0), before, sb);
                        first = false;
                    }
                    if (!first) sb.append(", ");
                    sb.append("lpad(CAST((CAST(EXTRACT(microsecond FROM CAST(");
                    emitExpr(args.get(0), sb);
                    sb.append(" AS TIMESTAMP)) AS BIGINT) % 1000000) / 1000 AS VARCHAR), 3, '0')");
                    if (!after.isEmpty()) {
                        sb.append(", ");
                        emitStrftime(args.get(0), after, sb);
                    }
                    sb.append(")");
                    return true;
                }
                sb.append("strftime(");
                emitExpr(args.get(0), sb);
                sb.append(", '").append(javaFormatToStrftime(pattern).replace("'", "''")).append("')");
                return true;
            }
            case "named_struct" -> {
                if (args.isEmpty() || args.size() % 2 != 0) return false;
                sb.append("struct_pack(");
                for (int i = 0; i < args.size(); i += 2) {
                    if (i > 0) sb.append(", ");
                    Object fieldName = args.get(i) instanceof io.github.joegxx.localsql.ir.expression.Literal l
                            && l.kind() == io.github.joegxx.localsql.ir.expression.Literal.Kind.STRING ? l.value() : null;
                    if (fieldName == null) {
                        throw new UnsupportedOperationException(
                                "named_struct field names must be string literals; got: " + args.get(i));
                    }
                    sb.append(quote(String.valueOf(fieldName))).append(" := ");
                    emitExpr(args.get(i + 1), sb);
                }
                sb.append(")");
                return true;
            }
            case "from_json" -> {
                if (args.size() < 2 || args.size() > 3) return false;
                Object schema = args.get(1) instanceof io.github.joegxx.localsql.ir.expression.Literal l
                        && l.kind() == io.github.joegxx.localsql.ir.expression.Literal.Kind.STRING ? l.value() : null;
                if (schema == null) {
                    throw new UnsupportedOperationException(
                            "from_json requires a literal schema string (e.g. 'STRUCT<a INT, b STRING>' or 'a INT, b STRING')");
                }
                // Spark returns NULL on malformed JSON; DuckDB errors, so guard with json_valid
                sb.append("CASE WHEN json_valid(");
                emitExpr(args.get(0), sb);
                sb.append(") THEN from_json(");
                emitExpr(args.get(0), sb);
                sb.append(", '").append(sparkSchemaToDuckJson(String.valueOf(schema)).replace("'", "''")).append("') ELSE NULL END");
                return true;
            }
            case "map_zip_with" -> {
                if (args.size() != 3 || !(args.get(2) instanceof Lambda lam)
                        || lam.variables().size() != 3) {
                    throw new UnsupportedOperationException(
                            "map_zip_with requires (m1, m2, (k, v1, v2) -> expr)");
                }
                String k = lam.variables().get(0);
                String v1 = lam.variables().get(1);
                String v2 = lam.variables().get(2);
                // union keys, look up each value, apply the lambda over them
                java.util.Map<String, Expression> subst = java.util.Map.of(
                        v1, mapValueLookup(args.get(0), k),
                        v2, mapValueLookup(args.get(1), k));
                Expression body = io.github.joegxx.localsql.ir.transform.IdentifierSubstituter
                        .rewrite(lam.body(), subst);
                sb.append("map_from_entries(list_transform(list_distinct(list_concat(map_keys(");
                emitExpr(args.get(0), sb);
                sb.append("), map_keys(");
                emitExpr(args.get(1), sb);
                sb.append("))), ");
                sb.append(quote(k)).append(" -> {'key': ").append(quote(k)).append(", 'value': ");
                emitExpr(body, sb);
                sb.append("}))");
                return true;
            }
            case "bround" -> {
                if (args.size() < 1 || args.size() > 2) return false;
                // registered as a Java UDF on the DuckDB connection; binder needs
                // explicit casts (no implicit DECIMAL/INTEGER -> DOUBLE for UDFs)
                emitBuiltinUdfCall("bround", args, List.of("DOUBLE", "DOUBLE"), sb);
                return true;
            }
            case "conv" -> {
                if (args.size() != 3) return false;
                emitBuiltinUdfCall("conv", args, List.of("VARCHAR", "VARCHAR", "VARCHAR"), sb);
                return true;
            }
            case "parse_url" -> {
                if (args.size() < 2 || args.size() > 3) return false;
                // registered as a Java UDF on the DuckDB connection
                List<String> types = args.size() == 3
                        ? List.of("VARCHAR", "VARCHAR", "VARCHAR") : List.of("VARCHAR", "VARCHAR");
                emitBuiltinUdfCall("parse_url", args, types, sb);
                return true;
            }
            case "zip_with" -> {
                if (args.size() != 3 || !(args.get(2) instanceof Lambda lam) || lam.variables().size() != 2) {
                    throw new UnsupportedOperationException("zip_with requires (a, b, (x, y) -> expr)");
                }
                // DuckDB's list_zip produces structs with empty field names, so
                // the lambda cannot reference them; instead iterate 1..max(len)
                // by index and substitute each variable with a list_extract.
                // list_extract out of range yields NULL, matching Spark's NULL
                // padding for the shorter list.
                String x = lam.variables().get(0);
                String y = lam.variables().get(1);
                java.util.Map<String, Expression> subst = java.util.Map.of(
                        x, listExtractAt(args.get(0)),
                        y, listExtractAt(args.get(1)));
                Expression body = io.github.joegxx.localsql.ir.transform.IdentifierSubstituter
                        .rewrite(lam.body(), subst);
                sb.append("list_transform(range(1, greatest(len(");
                emitExpr(args.get(0), sb);
                sb.append("), len(");
                emitExpr(args.get(1), sb);
                sb.append(")) + 1), i -> ");
                emitExpr(body, sb);
                sb.append(")");
                return true;
            }
            case "reflect", "java_method" -> {
                // Only a whitelist of side-effect-free statics is supported.
                if (args.size() < 2) return false;
                String className = literalString(args.get(0));
                String methodName = literalString(args.get(1));
                if (className == null || methodName == null) {
                    throw new UnsupportedOperationException(
                            "reflect requires literal class and method names");
                }
                String mapped = REFLECT_MAP.get(className + "." + methodName);
                if (mapped == null) {
                    throw new UnsupportedOperationException(
                            "reflect: only these methods are supported: " + REFLECT_MAP.keySet());
                }
                sb.append(mapped).append("(");
                for (int i = 2; i < args.size(); i++) {
                    if (i > 2) sb.append(", ");
                    emitExpr(args.get(i), sb);
                }
                sb.append(")");
                return true;
            }
            case "array_repeat" -> {
                if (args.size() != 2) return false;
                // DuckDB has no list_repeat: list_resize(list_value(x), n, x)
                // grows the one-element list to n entries filled with x
                sb.append("list_resize(list_value(");
                emitExpr(args.get(0), sb);
                sb.append("), CAST(");
                emitExpr(args.get(1), sb);
                sb.append(" AS BIGINT), ");
                emitExpr(args.get(0), sb);
                sb.append(")");
                return true;
            }
            case "shiftleft" -> {
                if (args.size() != 2) return false;
                sb.append("(");
                emitExpr(args.get(0), sb);
                sb.append(" << ");
                emitExpr(args.get(1), sb);
                sb.append(")");
                return true;
            }
            case "shiftright" -> {
                if (args.size() != 2) return false;
                sb.append("(");
                emitExpr(args.get(0), sb);
                sb.append(" >> ");
                emitExpr(args.get(1), sb);
                sb.append(")");
                return true;
            }
            case "shiftrightunsigned" -> {
                if (args.size() != 2) return false;
                // mask to unsigned 64-bit first so the shift is logical, not
                // arithmetic (Spark fills with zeros even for negatives)
                sb.append("CAST((");
                emitExpr(args.get(0), sb);
                sb.append(" & 18446744073709551615) AS UBIGINT) >> ");
                emitExpr(args.get(1), sb);
                return true;
            }
            case "getbit", "bit_get" -> {
                if (args.size() != 2) return false;
                sb.append("(");
                emitExpr(args.get(0), sb);
                sb.append(" >> ");
                emitExpr(args.get(1), sb);
                sb.append(") & 1");
                return true;
            }
            case "bit_and", "bit_or", "bit_xor" -> {
                if (args.size() != 2) return false;
                // scalar (non-aggregate) form; the aggregate form passes
                // through FN_MAP untouched (DuckDB bit_and/bit_or/bit_xor)
                if (name.equals("bit_xor")) {
                    sb.append("xor(");
                    emitExpr(args.get(0), sb);
                    sb.append(", ");
                    emitExpr(args.get(1), sb);
                    sb.append(")");
                } else {
                    sb.append("(");
                    emitExpr(args.get(0), sb);
                    sb.append(name.equals("bit_and") ? " & " : " | ");
                    emitExpr(args.get(1), sb);
                    sb.append(")");
                }
                return true;
            }
            case "try_add" -> {
                if (args.size() != 2) return false;
                // Spark returns NULL on overflow; DuckDB errors. Wrap each side
                // with try_cast to BIGINT so overflow yields NULL.
                sb.append("(try_cast(");
                emitExpr(args.get(0), sb);
                sb.append(" AS HUGEINT) + try_cast(");
                emitExpr(args.get(1), sb);
                sb.append(" AS HUGEINT))");
                return true;
            }
            case "from_utc_timestamp", "to_utc_timestamp" -> {
                if (args.size() != 2) return false;
                // from_utc: naive ts is UTC -> render in tz
                //   timezone(tz, CAST(ts AS TIMESTAMP) AT TIME ZONE 'UTC')
                // to_utc: naive ts is tz-local -> render in UTC
                //   timezone('UTC', CAST(ts AS TIMESTAMP) AT TIME ZONE tz)
                if (name.equals("from_utc_timestamp")) {
                    sb.append("timezone(");
                    emitExpr(args.get(1), sb);
                    sb.append(", CAST(");
                    emitExpr(args.get(0), sb);
                    sb.append(" AS TIMESTAMP) AT TIME ZONE 'UTC')");
                } else {
                    sb.append("timezone('UTC', CAST(");
                    emitExpr(args.get(0), sb);
                    sb.append(" AS TIMESTAMP) AT TIME ZONE ");
                    emitExpr(args.get(1), sb);
                    sb.append(")");
                }
                return true;
            }
            case "localtimestamp" -> {
                if (!args.isEmpty()) return false;
                sb.append("CAST(now() AS TIMESTAMP)");
                return true;
            }
            case "timestamp_seconds", "timestamp_millis", "timestamp_micros" -> {
                if (args.size() != 1) return false;
                // epoch value -> TIMESTAMP; DuckDB to_timestamp takes seconds
                // (accepts fractional for sub-second precision)
                String divisor = name.equals("timestamp_seconds") ? "1"
                        : name.equals("timestamp_millis") ? "1000" : "1000000";
                sb.append("CAST(to_timestamp(CAST(");
                if (divisor.equals("1")) {
                    emitExpr(args.get(0), sb);
                } else {
                    sb.append("(");
                    emitExpr(args.get(0), sb);
                    sb.append(") / ").append(divisor);
                }
                sb.append(" AS DOUBLE)) AS TIMESTAMP)");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** list_extract(list, i) for the zip_with index-iteration rewrite. */
    private Expression listExtractAt(Expression list) {
        return new FunctionCall("list_extract",
                List.of(list, new Identifier("i", false)));
    }

    private static String literalString(Expression e) {
        if (e instanceof Literal l && l.kind() == Literal.Kind.STRING) return String.valueOf(l.value());
        return null;
    }

    /** reflect/java_method whitelist: Spark-style class.method -> DuckDB scalar. */
    private static final Map<String, String> REFLECT_MAP = Map.ofEntries(
            Map.entry("java.lang.Math.max", "greatest"),
            Map.entry("java.lang.Math.min", "least"),
            Map.entry("java.lang.Math.abs", "abs"),
            Map.entry("java.lang.Math.sqrt", "sqrt"),
            Map.entry("java.lang.Math.pow", "pow"),
            Map.entry("java.lang.Math.round", "round"),
            Map.entry("java.lang.Math.floor", "floor"),
            Map.entry("java.lang.Math.ceil", "ceil"),
            Map.entry("java.lang.Math.log", "ln"),
            Map.entry("java.lang.Math.log10", "log10"),
            Map.entry("java.lang.Math.exp", "exp")
    );

    /** Emits fn(CAST(a1 AS t1), ...) for a connection-registered Java UDF. */
    private void emitBuiltinUdfCall(String fn, List<Expression> args, List<String> types, StringBuilder sb) {
        sb.append(fn).append('(');
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("CAST(");
            emitExpr(args.get(i), sb);
            sb.append(" AS ").append(types.get(i)).append(')');
        }
        sb.append(')');
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

    private void emitStrftime(Expression date, String strftimePattern, StringBuilder sb) {
        sb.append("strftime(");
        emitExpr(date, sb);
        sb.append(", '").append(strftimePattern.replace("'", "''")).append("')");
    }

    /**
     * Translates a Spark schema string literal into DuckDB's from_json JSON
     * schema form: {'name': 'TYPE', ...}, where a TYPE is a primitive, a
     * DECIMAL(p,s), a nested {'name': TYPE} object, or a [TYPE] array.
     * Recursively supports STRUCT<> and ARRAY<> nesting.
     */
    static String sparkSchemaToDuckJson(String schema) {
        SchemaParser p = new SchemaParser(schema);
        return p.parseSchema();
    }

    /** Recursive Spark schema parser producing the DuckDB JSON schema string. */
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

    /** Builds list_extract(map_extract(map, key), 1) as an IR expression (map value lookup). */
    private Expression mapValueLookup(Expression mapExpr, String keyName) {
        return new FunctionCall("list_extract",
                List.of(new FunctionCall("map_extract", List.of(mapExpr, new Identifier(keyName, false))),
                        new Literal(1L, io.github.joegxx.localsql.ir.expression.Literal.Kind.INTEGER)));
    }

    /** Emits the scale argument of bround/round(x, d): the arg or literal 0. */
    private void emitScale(List<Expression> args, StringBuilder sb) {
        if (args.size() == 2) emitExpr(args.get(1), sb);
        else sb.append("0");
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
