package io.github.joegxx.localsql.duckdb;

import io.github.joegxx.localsql.ir.expression.*;
import io.github.joegxx.localsql.ir.relation.*;
import io.github.joegxx.localsql.ir.type.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DuckDbSqlGenerator {

    public String generate(Relation rel) {
        StringBuilder sb = new StringBuilder();
        emit(rel, sb);
        return sb.toString();
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
        if (p.projectList().size() == 1 && p.projectList().get(0) instanceof Star) {
            sb.append("*");
        } else {
            for (int i = 0; i < p.projectList().size(); i++) {
                if (i > 0) sb.append(", ");
                emitExpr(p.projectList().get(i), sb);
            }
        }
        if (!(p.child() instanceof Values)) {
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
        if (j.condition() != null) {
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
        sb.append(" FROM ");
        emitChildSource(a.child(), sb);
        if (!a.groupingExpressions().isEmpty()) {
            sb.append(" GROUP BY ");
            for (int i = 0; i < a.groupingExpressions().size(); i++) {
                if (i > 0) sb.append(", ");
                emitExpr(a.groupingExpressions().get(i), sb);
            }
        }
        if (a.havingCondition() != null) {
            sb.append(" HAVING ");
            emitExpr(a.havingCondition(), sb);
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
        for (int i = 0; i < u.children().size(); i++) {
            if (i > 0) sb.append(u.distinct() ? " UNION " : " UNION ALL ");
            sb.append("(");
            emit(u.children().get(i), sb);
            sb.append(")");
        }
    }

    private void emitTableScan(TableScan t, StringBuilder sb) {
        sb.append(String.join(".", t.tableName().stream().map(this::quote).toList()));
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
        else if (e instanceof AttributeReference a) {
            if (a.qualifier().isEmpty()) sb.append(quote(a.name()));
            else sb.append(String.join(".", a.qualifier().stream().map(this::quote).toList())).append(".").append(quote(a.name()));
        }
        else if (e instanceof Alias al) {
            emitExpr(al.child(), sb);
            sb.append(" AS ").append(quote(al.name()));
        }
        else if (e instanceof Star s) {
            if (s.qualifier().isEmpty()) sb.append("*");
            else sb.append(String.join(".", s.qualifier().stream().map(this::quote).toList())).append(".*");
        }
        else if (e instanceof BinaryExpression b) {
            sb.append("(");
            emitExpr(b.left(), sb);
            sb.append(" ").append(b.op().symbol()).append(" ");
            emitExpr(b.right(), sb);
            sb.append(")");
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

    private static final Map<String, String> FN_MAP = Map.ofEntries(
            Map.entry("size", "array_length"),
            Map.entry("explode", "unnest"),
            Map.entry("posexplode", "unnest"),
            Map.entry("length", "length"),
            Map.entry("substr", "substring"),
            Map.entry("substring", "substring"),
            Map.entry("concat", "concat"),
            Map.entry("coalesce", "coalesce"),
            Map.entry("if", "if"),
            Map.entry("cast", "cast"),
            Map.entry("count", "count"),
            Map.entry("sum", "sum"),
            Map.entry("avg", "avg"),
            Map.entry("min", "min"),
            Map.entry("max", "max"),
            Map.entry("instr", "instr"),
            Map.entry("split", "string_split"),
            Map.entry("date_format", "strftime"),
            Map.entry("to_date", "strptime"),
            Map.entry("current_date", "current_date"),
            Map.entry("current_timestamp", "current_timestamp"),
            Map.entry("get_json_object", "json_extract_string"),
            Map.entry("json_extract", "json_extract")
    );

    private void emitFunctionCall(FunctionCall f, StringBuilder sb) {
        String name = f.name().toLowerCase();
        String mapped = FN_MAP.getOrDefault(name, name);
        sb.append(mapped).append("(");
        if (f.distinct()) sb.append("DISTINCT ");
        for (int i = 0; i < f.arguments().size(); i++) {
            if (i > 0) sb.append(", ");
            emitExpr(f.arguments().get(i), sb);
        }
        sb.append(")");
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
        return switch (t.typeName()) {
            case "BOOLEAN" -> "BOOLEAN";
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

    private String quote(String id) {
        if (id == null || id.isEmpty()) return id;
        if (id.matches("[a-z_][a-z0-9_]*")) return id;
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }
}
