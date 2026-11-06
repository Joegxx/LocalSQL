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
        return generateInsert(i, null);
    }

    /** @param schema physical DuckDB schema of the target (null/main: bare name). */
    public String generateInsert(io.github.joegxx.localsql.ir.statement.Insert i, String schema) {
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(emitTarget(i.tableName(), schema));
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
        return generateCreateTable(t, null);
    }

    /** @param schema physical DuckDB schema of the target (null/main: bare name). */
    public String generateCreateTable(io.github.joegxx.localsql.ir.statement.CreateTable t, String schema) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        sb.append(emitTarget(t.tableName(), schema));
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
        return generateCreateTableAs(t, null);
    }

    /** @param schema physical DuckDB schema of the target (null/main: bare name). */
    public String generateCreateTableAs(io.github.joegxx.localsql.ir.statement.CreateTable t, String schema) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        sb.append(emitTarget(t.tableName(), schema));
        sb.append(" AS ");
        emit(t.query(), sb);
        return sb.toString();
    }

    public String generateCreateView(io.github.joegxx.localsql.ir.statement.CreateView v) {
        return generateCreateView(v, null);
    }

    /** @param schema physical DuckDB schema of the target (null/main: bare name). */
    public String generateCreateView(io.github.joegxx.localsql.ir.statement.CreateView v, String schema) {
        StringBuilder sb = new StringBuilder("CREATE ");
        if (v.orReplace()) sb.append("OR REPLACE ");
        sb.append("VIEW ");
        sb.append(emitTarget(v.tableName(), schema));
        sb.append(" AS ");
        emit(v.query(), sb);
        return sb.toString();
    }

    public String generateRenameTable(io.github.joegxx.localsql.ir.statement.RenameTable rt, boolean isView) {
        return generateRenameTable(rt, isView, null);
    }

    /** @param schema physical DuckDB schema of the source (null/main: bare); rename stays within it. */
    public String generateRenameTable(io.github.joegxx.localsql.ir.statement.RenameTable rt, boolean isView,
                                       String schema) {
        String keyword = isView ? "VIEW " : "TABLE ";
        return "ALTER " + keyword + emitTarget(rt.from(), schema) + " RENAME TO " + bareName(rt.to());
    }

    public String generateAddColumn(List<String> tableName, io.github.joegxx.localsql.ir.statement.AddColumns.ColDef col) {
        return generateAddColumn(tableName, col, null);
    }

    public String generateAddColumn(List<String> tableName, io.github.joegxx.localsql.ir.statement.AddColumns.ColDef col,
                                    String schema) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ");
        sb.append(emitTarget(tableName, schema)).append(" ADD COLUMN ").append(quote(col.name()))
                .append(" ").append(duckType(col.type()));
        if (!col.nullable()) sb.append(" NOT NULL");
        // position ignored for DuckDB (always appends); comment ignored as hint
        return sb.toString();
    }

    public String generateDropColumn(List<String> tableName, String col) {
        return generateDropColumn(tableName, col, null);
    }

    public String generateDropColumn(List<String> tableName, String col, String schema) {
        return "ALTER TABLE " + emitTarget(tableName, schema) + " DROP COLUMN " + quote(col);
    }

    public String generateRenameColumn(List<String> tableName, String from, String to) {
        return generateRenameColumn(tableName, from, to, null);
    }

    public String generateRenameColumn(List<String> tableName, String from, String to, String schema) {
        return "ALTER TABLE " + emitTarget(tableName, schema) + " RENAME COLUMN " + quote(from) + " TO " + quote(to);
    }

    public String generateAlterColumnType(List<String> tableName, String col, DataType newType) {
        return generateAlterColumnType(tableName, col, newType, null);
    }

    public String generateAlterColumnType(List<String> tableName, String col, DataType newType, String schema) {
        return "ALTER TABLE " + emitTarget(tableName, schema) + " ALTER " + quote(col) + " TYPE " + duckType(newType);
    }

    public String generateAlterColumnSetNotNull(List<String> tableName, String col, boolean setNotNull) {
        return generateAlterColumnSetNotNull(tableName, col, setNotNull, null);
    }

    public String generateAlterColumnSetNotNull(List<String> tableName, String col, boolean setNotNull,
                                                String schema) {
        // DuckDB 1.5 supports ALTER COLUMN ... SET/DROP NOT NULL
        return "ALTER TABLE " + emitTarget(tableName, schema) + " ALTER " + quote(col) + (setNotNull ? " SET NOT NULL" : " DROP NOT NULL");
    }

    /** Emits only the last name part (bare form). */
    private String bareName(List<String> parts) {
        return quote(parts.get(parts.size() - 1));
    }

    /**
     * Emits a table reference with its physical schema prefix. A null, blank
     * or {@code main} schema keeps the bare name, matching legacy layouts;
     * anything else (a per-database schema) is emitted as {@code schema.table}.
     */
    static String emitTarget(List<String> parts, String schema) {
        String bare = bareNameStatic(parts);
        if (schema == null || schema.isBlank() || "main".equalsIgnoreCase(schema)) {
            return bare;
        }
        return quote(schema) + "." + bare;
    }

    private static String bareNameStatic(List<String> parts) {
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
            // invisible to the outer select list). A Generate child must stay a
            // comma-joined FROM item for the same reason.
            sb.append(" FROM ");
            if (f.child() instanceof Generate g) {
                emitGenerateSource(g, sb);
            } else {
                emitChildSource(f.child(), sb);
            }
            sb.append(" WHERE ");
            emitExpr(f.condition(), sb);
        } else if (p.child() instanceof Generate g) {
            // Inline the lateral view as a FROM-item comma list; wrapping it
            // in a derived table would strand the child's table qualifiers.
            sb.append(" FROM ");
            emitGenerateSource(g, sb);
        } else if (!(p.child() instanceof Values)) {
            sb.append(" FROM ");
            emitChildSource(p.child(), sb);
        }
    }

    private void emitFilter(Filter f, StringBuilder sb) {
        sb.append("SELECT * FROM ");
        if (f.child() instanceof Generate g) {
            // comma-joined FROM items; a derived table would strand the
            // child's table qualifiers (see emitProject)
            emitGenerateSource(g, sb);
        } else {
            emitChildSource(f.child(), sb);
        }
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
        if (a.resultExpressions() != null && !a.resultExpressions().isEmpty()) {
            out.addAll(a.resultExpressions());
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
        if (src instanceof Generate g) {
            // comma-joined FROM items; a derived table would strand the
            // child's table qualifiers (see emitProject)
            emitGenerateSource(g, sb);
        } else {
            emitChildSource(src, sb);
        }
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
        // After analysis the name is [physicalSchema, table] (CTE references
        // keep their original single-part name). Main-resident tables keep
        // the bare form for backward compatibility with legacy stores.
        List<String> parts = t.tableName();
        String table = parts.get(parts.size() - 1);
        if (parts.size() >= 2) {
            String schema = parts.get(parts.size() - 2);
            if (schema != null && !schema.isBlank() && !"main".equalsIgnoreCase(schema)) {
                sb.append(quote(schema)).append('.');
            }
        }
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
        emitGenerateSource(g, sb);
    }

    /**
     * Emits the LATERAL VIEW as a FROM-item comma list:
     * {@code <child source>, UNNEST(<generator>) AS <viewAlias>(<col>)}. The
     * table-function alias form is required (a plain column alias makes
     * DuckDB return the expanded rows as structs); the table alias is the
     * LATERAL VIEW name so qualified references keep working, falling back
     * to a fixed name when the view is anonymous. Inline form keeps the
     * child's table qualifiers visible to the enclosing projection and
     * preserves chained lateral views by recursing into a Generate child.
     */
    private void emitGenerateSource(Generate g, StringBuilder sb) {
        if (g.child() instanceof Generate inner) {
            // a derived table here would strand the earlier view's output
            // names, so chained views stay in one comma list
            emitGenerateSource(inner, sb);
        } else {
            emitChildSource(g.child(), sb);
        }
        sb.append(", UNNEST(");
        emitExpr(g.generator(), sb);
        sb.append(")");
        // posexplode: DuckDB appends the 1-based position column after the
        // value, matching the stored physical output-name order
        if (g.ordinalOutput()) sb.append(" WITH ORDINALITY");
        sb.append(" AS ").append(quote(g.viewAlias() == null ? "gen" : g.viewAlias()));
        sb.append("(");
        if (g.generatorOutputNames().isEmpty()) {
            sb.append(quote("gen_col"));
        } else {
            sb.append(g.generatorOutputNames().stream()
                    .map(DuckDbSqlGenerator::quote).collect(java.util.stream.Collectors.joining(", ")));
        }
        sb.append(")");
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
            sb.append(c.tryCast() ? "TRY_CAST(" : "CAST(");
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
            List<String> names = s.fieldNames();
            for (int i = 0; i < s.fields().size(); i++) {
                if (i > 0) sb.append(", ");
                if (names != null) {
                    sb.append(quote(names.get(i))).append(" := ");
                }
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

    /** Source function names with a generator-owned custom rename or template rule. */
    public java.util.Set<String> customFunctionNames() {
        java.util.Set<String> names = new java.util.HashSet<>(customRenames.keySet());
        names.addAll(customTemplates.keySet());
        return names;
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


    private static String literalString(Expression e) {
        if (e instanceof Literal l && l.kind() == Literal.Kind.STRING) return String.valueOf(l.value());
        return null;
    }

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
        sb.append(name).append("(");
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




    /** Emits the scale argument of bround/round(x, d): the arg or literal 0. */
    private void emitScale(List<Expression> args, StringBuilder sb) {
        if (args.size() == 2) emitExpr(args.get(1), sb);
        else sb.append("0");
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
            case "UBIGINT" -> "UBIGINT";
            case "HUGEINT" -> "HUGEINT";
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
