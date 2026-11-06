package io.github.joegxx.localsql.rewrite;

import io.github.joegxx.localsql.ir.expression.Alias;
import io.github.joegxx.localsql.ir.type.BinaryType;
import io.github.joegxx.localsql.ir.type.BooleanType;
import io.github.joegxx.localsql.ir.type.DecimalType;
import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.expression.ArrayExpr;
import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.BinaryExpression;
import io.github.joegxx.localsql.ir.expression.CaseWhen;
import io.github.joegxx.localsql.ir.expression.Cast;
import io.github.joegxx.localsql.ir.expression.Expression;
import io.github.joegxx.localsql.ir.expression.FunctionCall;
import io.github.joegxx.localsql.ir.expression.Identifier;
import io.github.joegxx.localsql.ir.expression.InList;
import io.github.joegxx.localsql.ir.expression.Lambda;
import io.github.joegxx.localsql.ir.expression.Literal;
import io.github.joegxx.localsql.ir.expression.MapExpr;
import io.github.joegxx.localsql.ir.expression.ScalarSubquery;
import io.github.joegxx.localsql.ir.expression.StructExpr;
import io.github.joegxx.localsql.ir.expression.Subquery;
import io.github.joegxx.localsql.ir.expression.UnaryExpression;
import io.github.joegxx.localsql.ir.relation.Aggregate;
import io.github.joegxx.localsql.ir.relation.Filter;
import io.github.joegxx.localsql.ir.relation.Generate;
import io.github.joegxx.localsql.ir.relation.Join;
import io.github.joegxx.localsql.ir.relation.Limit;
import io.github.joegxx.localsql.ir.relation.Project;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.relation.Sort;
import io.github.joegxx.localsql.ir.relation.SubqueryAlias;
import io.github.joegxx.localsql.ir.relation.Union;
import io.github.joegxx.localsql.ir.relation.Values;
import io.github.joegxx.localsql.ir.relation.Window;
import io.github.joegxx.localsql.ir.relation.With;
import io.github.joegxx.localsql.ir.relation.CTERelation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A1 step: lowers Spark function calls whose DuckDB form requires argument
 * restructuring onto plain IR expressions (function calls, CASE, CAST,
 * arithmetic). Owns the conversion for these functions exclusively; the
 * generator emits the lowered IR without any function-specific logic.
 * The input tree is never modified; use the returned relation.
 */
public final class ExpressionLowering {

    private static final IntegralType BIGINT = IntegralType.BIGINT;
    private static final io.github.joegxx.localsql.ir.type.StringType VARCHAR =
            new io.github.joegxx.localsql.ir.type.StringType();
    private static final io.github.joegxx.localsql.ir.type.DateType DATE =
            new io.github.joegxx.localsql.ir.type.DateType();
    private static final io.github.joegxx.localsql.ir.type.TimestampType TIMESTAMP =
            new io.github.joegxx.localsql.ir.type.TimestampType();
    private static final io.github.joegxx.localsql.ir.type.FractionalType DOUBLE =
            io.github.joegxx.localsql.ir.type.FractionalType.DOUBLE;
    private static final IntegralType INT = IntegralType.INT;
    private static final io.github.joegxx.localsql.ir.type.BinaryType BLOB =
            new io.github.joegxx.localsql.ir.type.BinaryType();
    private final java.util.Set<String> udtfNames;

    public ExpressionLowering() { this(java.util.Set.of()); }

    /**
     * @param udtfNames registered UDTF source names (TABLE kind). A LATERAL
     *                  VIEW generator matching one of these passes through
     *                  untouched for UdtfGenerateSplitter, which runs after
     *                  this rewrite and consumes the tree before generation.
     */
    public ExpressionLowering(java.util.Set<String> udtfNames) {
        this.udtfNames = udtfNames == null ? java.util.Set.of() : udtfNames;
    }

    private static final IntegralType UBIGINT = new IntegralType(64, false);
    private static final IntegralType HUGEINT = new IntegralType(128, true);

    public Relation rewrite(Relation rel) {
        return rewriteRelation(rel);
    }

    // ------------------------------------------------------------------
    // relation tree rebuild
    // ------------------------------------------------------------------

    private Relation rewriteRelation(Relation rel) {
        if (rel instanceof Project p) {
            return new Project(rewriteRelation(p.child()), rewriteList(p.projectList()), p.distinct());
        }
        if (rel instanceof Filter f) {
            return new Filter(rewriteRelation(f.child()), lower(f.condition()));
        }
        if (rel instanceof Join j) {
            return new Join(rewriteRelation(j.left()), rewriteRelation(j.right()), j.joinType(),
                    j.condition() == null ? null : lower(j.condition()), j.usingColumns());
        }
        if (rel instanceof Aggregate ag) {
            List<Aggregate.GroupingAnalytics> groups = new ArrayList<>();
            for (Aggregate.GroupingAnalytics ga : ag.groupingAnalytics()) {
                List<List<Expression>> sets = new ArrayList<>();
                for (List<Expression> set : ga.sets()) sets.add(rewriteList(set));
                groups.add(new Aggregate.GroupingAnalytics(ga.kind(), sets));
            }
            return new Aggregate(rewriteRelation(ag.child()), rewriteList(ag.groupingExpressions()),
                    rewriteList(ag.resultExpressions()),
                    ag.havingCondition() == null ? null : lower(ag.havingCondition()), groups);
        }
        if (rel instanceof Sort s) {
            List<Sort.SortOrder> orders = new ArrayList<>();
            for (Sort.SortOrder o : s.order()) {
                orders.add(new Sort.SortOrder(lower(o.expr()), o.ascending(), o.nullsLast()));
            }
            return new Sort(rewriteRelation(s.child()), orders);
        }
        if (rel instanceof Limit l) return new Limit(rewriteRelation(l.child()), l.limit(), l.offset());
        if (rel instanceof Union u) {
            List<Relation> children = new ArrayList<>();
            for (Relation c : u.children()) children.add(rewriteRelation(c));
            return u.kind() == null ? new Union(children, u.distinct()) : new Union(children, u.distinct(), u.kind());
        }
        if (rel instanceof With w) {
            List<CTERelation> ctes = new ArrayList<>();
            for (CTERelation cte : w.ctes()) {
                ctes.add(new CTERelation(cte.name(), rewriteRelation(cte.definition()), cte.output()));
            }
            return new With(ctes, rewriteRelation(w.body()));
        }
        if (rel instanceof Window wnd) {
            List<Expression> items = new ArrayList<>();
            for (Expression item : wnd.windowExpressions()) items.add(lower(item));
            return new Window(rewriteRelation(wnd.child()), items, wnd.spec());
        }
        if (rel instanceof Generate g) {
            LoweredGenerator gen = lowerGenerator(g);
            return new Generate(gen.argument(), gen.outputNames(), g.viewAlias(), gen.ordinal(),
                    rewriteRelation(g.child()));
        }
        if (rel instanceof SubqueryAlias s) {
            return new SubqueryAlias(rewriteRelation(s.child()), s.alias());
        }
        if (rel instanceof Values v) {
            List<List<Expression>> rows = new ArrayList<>();
            for (List<Expression> row : v.rows()) rows.add(rewriteList(row));
            return new Values(rows);
        }
        return rel;
    }

    /** CAST(x AS type) for 1-arg type functions; other arities keep the old path. */
    private Expression lowerCastCall(List<Expression> a, DataType target) {
        if (a.isEmpty() || a.size() > 3) return null;
        return new Cast(lower(a.get(0)), target);
    }

    private static final java.util.Map<String, String> REFLECT_MAP = java.util.Map.ofEntries(
            java.util.Map.entry("java.lang.Math.max", "greatest"),
            java.util.Map.entry("java.lang.Math.min", "least"),
            java.util.Map.entry("java.lang.Math.abs", "abs"),
            java.util.Map.entry("java.lang.Math.sqrt", "sqrt"),
            java.util.Map.entry("java.lang.Math.pow", "pow"),
            java.util.Map.entry("java.lang.Math.round", "round"),
            java.util.Map.entry("java.lang.Math.floor", "floor"),
            java.util.Map.entry("java.lang.Math.ceil", "ceil"),
            java.util.Map.entry("java.lang.Math.log", "ln"),
            java.util.Map.entry("java.lang.Math.log10", "log10"),
            java.util.Map.entry("java.lang.Math.exp", "exp"));

    /** Integer literal value, or -1 when the expression is not an integer literal. */
    private static int literalInt(Expression e) {
        if (e instanceof Literal l && l.kind() == Literal.Kind.INTEGER && l.value() instanceof Number n) {
            return n.intValue();
        }
        return -1;
    }

    /**
     * Integer literal value; rejects anything else loudly. Callers whose Spark
     * semantics need a compile-time constant must use this instead of
     * {@link #literalInt} so a non-literal argument fails with a clear error
     * instead of silently lowering with the -1 sentinel.
     */
    private static int requireLiteralInt(Expression e, String what) {
        if (e instanceof Literal l && l.kind() == Literal.Kind.INTEGER && l.value() instanceof Number n) {
            return n.intValue();
        }
        throw new UnsupportedOperationException(what);
    }

    private static String literalString(Expression e) {
        if (e instanceof Literal l && l.kind() == Literal.Kind.STRING) return String.valueOf(l.value());
        return null;
    }

    private static Boolean literalBool(Expression e) {
        if (e instanceof Literal l && l.kind() == Literal.Kind.BOOLEAN) return (Boolean) l.value();
        return null;
    }

    /**
     * Value lookup for map lambdas: map_extract(m, k) yields the list of
     * matching values (empty when absent), list_extract(..., 1) takes the
     * first; out-of-range list_extract is NULL, matching Spark's missing-key
     * NULL for map_zip_with.
     */
    private Expression mapValueLookup(Expression map, String key) {
        return new FunctionCall("list_extract", List.of(
                new FunctionCall("map_extract", List.of(map, new AttributeReference(key, List.of()))),
                Literal.ofInt(1)));
    }

    /** EXTRACT(year*12 + month) of a timestamp. */
    private Expression monthsComponent(Expression ts) {
        return new BinaryExpression(
                new BinaryExpression(
                        new FunctionCall("date_part", List.of(Literal.ofString("year"), ts)),
                        BinaryExpression.Op.MUL, Literal.ofInt(12)),
                BinaryExpression.Op.ADD,
                new FunctionCall("date_part", List.of(Literal.ofString("month"), ts)));
    }

    /** day + (h*3600 + m*60 + s) / 86400.0 of a timestamp. */
    private Expression dayFraction(Expression ts) {
        return new BinaryExpression(
                new FunctionCall("date_part", List.of(Literal.ofString("day"), ts)),
                BinaryExpression.Op.ADD,
                new BinaryExpression(
                        new BinaryExpression(
                                new BinaryExpression(
                                        new BinaryExpression(
                                                new FunctionCall("date_part", List.of(Literal.ofString("hour"), ts)),
                                                BinaryExpression.Op.MUL, Literal.ofInt(3600)),
                                        BinaryExpression.Op.ADD,
                                        new BinaryExpression(
                                                new FunctionCall("date_part", List.of(Literal.ofString("minute"), ts)),
                                                BinaryExpression.Op.MUL, Literal.ofInt(60))),
                                BinaryExpression.Op.ADD,
                                new FunctionCall("date_part", List.of(Literal.ofString("second"), ts))),
                        BinaryExpression.Op.DIV, Literal.ofDecimal("86400.0")));
    }

    /** list_extract(list, i) for the zip_with index-iteration rewrite. */
    private Expression listExtract(Expression list) {
        return new FunctionCall("list_extract", List.of(list, new AttributeReference("i", List.of())));
    }

    /**
     * Lowers the lambda body first, then substitutes already-lowered lookup
     * expressions. Substitute expressions are never re-lowered, so function
     * forms produced by lowering (e.g. the two-list form of map) cannot be
     * mistaken for a Spark source call and re-processed.
     */
    private Expression lowerBody(Expression body, java.util.Map<String, Expression> substitutions) {
        java.util.Map<String, Expression> lowered = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Expression> en : substitutions.entrySet()) {
            lowered.put(en.getKey(), lower(en.getValue()));
        }
        Expression loweredBody = lower(body);
        return io.github.joegxx.localsql.ir.transform.IdentifierSubstituter.rewrite(loweredBody, lowered);
    }

    private List<Expression> rewriteList(List<Expression> list) {
        List<Expression> out = new ArrayList<>(list.size());
        for (Expression e : list) out.add(lower(e));
        return out;
    }

    /**
     * A lowered LATERAL VIEW generator: the pure argument expression the
     * UNNEST expands, whether a position column is appended (posexplode),
     * and the physical output column names. The name rewriter skips the
     * generator position, so only this pass owns the conversion; anything
     * that is not the supported builtin family fails loudly here instead of
     * emitting invalid SQL downstream.
     */
    private record LoweredGenerator(Expression argument, boolean ordinal, List<String> outputNames) {}

    /**
     * Lowers the generator function of a LATERAL VIEW. Supported family:
     * explode, explode_outer (NULL/empty arrays yield one NULL row) and
     * posexplode (position column via DuckDB's WITH ORDINALITY, which the
     * DuckDB generator appends after the value column, so the stored output
     * names are in physical order: value first, pos last). explode with two
     * declared output columns is a map explode and is rejected explicitly;
     * unknown generator functions are rejected (registered UDTFs are split
     * out by UdtfGenerateSplitter before the rewrite runs).
     */
    private LoweredGenerator lowerGenerator(Generate g) {
        Expression gen = g.generator();
        String name = gen instanceof FunctionCall f ? f.name().toLowerCase(java.util.Locale.ROOT) : "";
        List<String> names = g.generatorOutputNames();
        if ("posexplode".equals(name)) {
            if (!(gen instanceof FunctionCall f) || f.arguments().size() != 1) {
                throw new UnsupportedOperationException("posexplode requires exactly one argument");
            }
            if (!names.isEmpty() && names.size() != 2) {
                throw new UnsupportedOperationException(
                        "posexplode requires two output column names (pos, col), got: " + names);
            }
            // physical order: value column first, WITH ORDINALITY pos last
            List<String> physical = names.isEmpty()
                    ? List.of("col", "pos")
                    : List.of(names.get(1), names.get(0));
            return new LoweredGenerator(lower(f.arguments().get(0)), true, physical);
        }
        if ("explode".equals(name) || "explode_outer".equals(name)) {
            if (!(gen instanceof FunctionCall f) || f.arguments().size() != 1) {
                throw new UnsupportedOperationException(name + " requires exactly one argument");
            }
            if (names.size() > 1) {
                throw new UnsupportedOperationException(
                        "explode on maps (two output columns " + names + ") is not supported; "
                                + "explode the keys instead, e.g. via map_keys");
            }
            Expression arr = lower(f.arguments().get(0));
            if (name.endsWith("_outer")) {
                // Spark explode_outer: a NULL or empty array still yields one
                // row with NULL; DuckDB UNNEST drops such rows, so substitute
                // a single-NULL list in those cases.
                arr = new CaseWhen(List.of(new CaseWhen.WhenBranch(
                        new BinaryExpression(
                                new UnaryExpression(UnaryExpression.Op.IS_NULL, arr),
                                BinaryExpression.Op.OR,
                                new BinaryExpression(
                                        new FunctionCall("len", List.of(arr)),
                                        BinaryExpression.Op.EQ, Literal.ofInt(0))),
                        new ArrayExpr(List.of(Literal.ofNull())))),
                        arr);
            }
            return new LoweredGenerator(arr, false, names);
        }
        if (udtfNames.contains(name)) {
            // registered UDTF: passed through untouched; UdtfGenerateSplitter
            // consumes the tree after this rewrite, before generation
            return new LoweredGenerator(lower(gen), false, names);
        }
        throw new UnsupportedOperationException(
                "LATERAL VIEW generator '" + name + "' is not supported; supported: explode, explode_outer, posexplode");
    }

    // ------------------------------------------------------------------
    // expression lowering
    // ------------------------------------------------------------------

    Expression lower(Expression e) {
        if (e instanceof FunctionCall f && f.windowSpec() == null) {
            Expression lowered = lowerCall(f);
            if (lowered != null) return lowered;
        }
        if (e instanceof Alias a) return new Alias(lower(a.child()), a.name(), a.qualifier());
        if (e instanceof BinaryExpression b) return new BinaryExpression(lower(b.left()), b.op(), lower(b.right()));
        if (e instanceof UnaryExpression u) return new UnaryExpression(u.op(), lower(u.child()));
        if (e instanceof Cast c) return new Cast(lower(c.child()), c.target());
        if (e instanceof InList i) {
            List<Expression> list = new ArrayList<>();
            for (Expression item : i.list()) list.add(lower(item));
            return new InList(lower(i.value()), list, i.negated());
        }
        if (e instanceof ArrayExpr a) {
            List<Expression> els = new ArrayList<>();
            for (Expression el : a.elements()) els.add(lower(el));
            return new ArrayExpr(els);
        }
        if (e instanceof MapExpr m) {
            List<Map.Entry<Expression, Expression>> entries = new ArrayList<>();
            for (Map.Entry<Expression, Expression> en : m.entries()) {
                entries.add(Map.entry(lower(en.getKey()), lower(en.getValue())));
            }
            return new MapExpr(entries);
        }
        if (e instanceof StructExpr s) {
            List<Expression> fields = new ArrayList<>();
            for (Expression fld : s.fields()) fields.add(lower(fld));
            return new StructExpr(fields, s.fieldNames());
        }
        if (e instanceof CaseWhen c) {
            List<CaseWhen.WhenBranch> branches = new ArrayList<>();
            for (CaseWhen.WhenBranch b : c.branches()) {
                branches.add(new CaseWhen.WhenBranch(lower(b.condition()), lower(b.value())));
            }
            return new CaseWhen(branches, c.elseValue() == null ? null : lower(c.elseValue()));
        }
        if (e instanceof FunctionCall f) {
            List<Expression> args = new ArrayList<>();
            for (Expression a : f.arguments()) args.add(lower(a));
            FunctionCall rebuilt = new FunctionCall(f.name(), args, f.distinct());
            rebuilt.setWindowSpec(f.windowSpec());
            return rebuilt;
        }
        if (e instanceof Lambda l) return new Lambda(l.variables(), lower(l.body()));
        if (e instanceof ScalarSubquery sq) {
            return new ScalarSubquery(rewriteRelation(sq.query()));
        }
        if (e instanceof Subquery sq) {
            return new Subquery(rewriteRelation(sq.query()));
        }
        return e;
    }

    /** @return the lowered expression, or null when the call keeps its current form. */
    private Expression lowerCall(FunctionCall f) {
        List<Expression> a = f.arguments();
        String name = f.name().toLowerCase();
        switch (name) {
            case "if" -> {
                if (a.size() != 3) return null;
                return new CaseWhen(
                        List.of(new CaseWhen.WhenBranch(lower(a.get(0)), lower(a.get(1)))),
                        lower(a.get(2)));
            }
            case "isnull" -> {
                if (a.size() != 1) return null;
                return new UnaryExpression(UnaryExpression.Op.IS_NULL, lower(a.get(0)));
            }
            case "isnotnull" -> {
                if (a.size() != 1) return null;
                return new UnaryExpression(UnaryExpression.Op.IS_NOT_NULL, lower(a.get(0)));
            }
            case "positive" -> {
                if (a.size() != 1) return null;
                return lower(a.get(0));
            }
            case "negative" -> {
                if (a.size() != 1) return null;
                return new UnaryExpression(UnaryExpression.Op.NEG, lower(a.get(0)));
            }
            case "expm1" -> {
                if (a.size() != 1) return null;
                return new BinaryExpression(new FunctionCall("exp", List.of(lower(a.get(0)))),
                        BinaryExpression.Op.SUB, Literal.ofInt(1));
            }
            case "log1p" -> {
                if (a.size() != 1) return null;
                return new FunctionCall("ln", List.of(new BinaryExpression(
                        lower(a.get(0)), BinaryExpression.Op.ADD, Literal.ofInt(1))));
            }
            case "log" -> {
                if (a.size() == 1) return new FunctionCall("ln", List.of(lower(a.get(0))));
                if (a.size() == 2) {
                    return new BinaryExpression(
                            new FunctionCall("ln", List.of(lower(a.get(1)))),
                            BinaryExpression.Op.DIV,
                            new FunctionCall("ln", List.of(lower(a.get(0)))));
                }
                return null;
            }
            case "hypot" -> {
                if (a.size() != 2) return null;
                Expression x = lower(a.get(0));
                Expression y = lower(a.get(1));
                return new FunctionCall("sqrt", List.of(new BinaryExpression(
                        new BinaryExpression(x, BinaryExpression.Op.MUL, x),
                        BinaryExpression.Op.ADD,
                        new BinaryExpression(y, BinaryExpression.Op.MUL, y))));
            }
            case "nanvl" -> {
                if (a.size() != 2) return null;
                return new CaseWhen(
                        List.of(new CaseWhen.WhenBranch(
                                new FunctionCall("isnan", List.of(lower(a.get(0)))), lower(a.get(1)))),
                        lower(a.get(0)));
            }
            case "elt" -> {
                if (a.size() < 2) return null;
                List<Expression> values = new ArrayList<>();
                for (int i = 1; i < a.size(); i++) values.add(lower(a.get(i)));
                return new FunctionCall("list_element",
                        List.of(new FunctionCall("list_value", values), lower(a.get(0))));
            }
            case "space" -> {
                if (a.size() != 1) return null;
                return new FunctionCall("repeat",
                        List.of(Literal.ofString(" "), lower(a.get(0))));
            }
            case "find_in_set" -> {
                if (a.size() != 2) return null;
                return new FunctionCall("coalesce", List.of(
                        new FunctionCall("list_position", List.of(
                                new FunctionCall("string_split", List.of(
                                        new Cast(lower(a.get(1)), VARCHAR), Literal.ofString(","))),
                                new Cast(lower(a.get(0)), VARCHAR))),
                        Literal.ofInt(0)));
            }
            case "date_add", "date_sub" -> {
                if (a.size() != 2) return null;
                BinaryExpression.Op op = name.equals("date_add") ? BinaryExpression.Op.ADD : BinaryExpression.Op.SUB;
                return new Cast(new BinaryExpression(
                        new Cast(lower(a.get(0)), DATE), op, lower(a.get(1))), DATE);
            }
            case "datediff" -> {
                if (a.size() != 2) return null;
                // Spark datediff(end, start); DuckDB date_diff(part, start, end)
                return new FunctionCall("date_diff", List.of(
                        Literal.ofString("day"),
                        new Cast(lower(a.get(1)), DATE),
                        new Cast(lower(a.get(0)), DATE)));
            }
            case "add_months" -> {
                if (a.size() != 2) return null;
                return new Cast(new BinaryExpression(
                        new Cast(lower(a.get(0)), DATE),
                        BinaryExpression.Op.ADD,
                        new FunctionCall("to_months", List.of(lower(a.get(1))))), DATE);
            }
            case "unix_timestamp" -> {
                if (a.isEmpty()) {
                    return new Cast(new FunctionCall("epoch", List.of(
                            new Cast(new FunctionCall("current_timestamp", List.of()), TIMESTAMP))), BIGINT);
                }
                if (a.size() == 1) {
                    return new Cast(new FunctionCall("epoch", List.of(
                            new Cast(lower(a.get(0)), TIMESTAMP))), BIGINT);
                }
                return null;
            }
            case "from_unixtime" -> {
                if (a.size() != 1) return null;
                return new FunctionCall("strftime", List.of(
                        new FunctionCall("to_timestamp", List.of(lower(a.get(0)))),
                        Literal.ofString("%Y-%m-%d %H:%M:%S")));
            }
            case "weekday" -> {
                if (a.size() != 1) return null;
                // Spark weekday: Monday=0..Sunday=6; DuckDB weekday is 1-based
                return new BinaryExpression(
                        new FunctionCall("weekday", List.of(lower(a.get(0)))),
                        BinaryExpression.Op.SUB, Literal.ofInt(1));
            }
            case "dayofweek" -> {
                if (a.size() != 1) return null;
                // Spark dayofweek: Sunday=1..Saturday=7; DuckDB weekday: Monday=1..Sunday=7
                return new BinaryExpression(
                        new BinaryExpression(
                                new FunctionCall("weekday", List.of(lower(a.get(0)))),
                                BinaryExpression.Op.MOD, Literal.ofInt(7)),
                        BinaryExpression.Op.ADD, Literal.ofInt(1));
            }
            case "regexp_replace" -> {
                if (a.size() < 2 || a.size() > 3) return null;
                // Spark replaces all occurrences; DuckDB needs the 'g' flag
                List<Expression> args = new ArrayList<>();
                args.add(lower(a.get(0)));
                args.add(lower(a.get(1)));
                args.add(a.size() == 3 ? lower(a.get(2)) : Literal.ofString(""));
                args.add(Literal.ofString("g"));
                return new FunctionCall("regexp_replace", args);
            }
            case "regexp_count" -> {
                if (a.size() < 2 || a.size() > 3) return null;
                return new FunctionCall("len", List.of(new FunctionCall("regexp_extract_all",
                        List.of(lower(a.get(0)), lower(a.get(1))))));
            }
            case "to_date" -> {
                if (a.size() == 1) return new Cast(lower(a.get(0)), DATE);
                if (a.size() == 2) {
                    return new Cast(new FunctionCall("strptime",
                            List.of(lower(a.get(0)), lower(a.get(1)))), DATE);
                }
                return null;
            }
            case "to_timestamp" -> {
                if (a.size() != 1) return null;
                return new Cast(lower(a.get(0)), TIMESTAMP);
            }
            case "unix_seconds", "unix_millis", "unix_micros" -> {
                if (a.size() != 1) return null;
                String fn = name.equals("unix_seconds") ? "epoch"
                        : name.equals("unix_millis") ? "epoch_ms" : "epoch_us";
                return new Cast(new FunctionCall(fn, List.of(new Cast(lower(a.get(0)), TIMESTAMP))), BIGINT);
            }
            case "to_unix_timestamp" -> {
                if (a.size() != 1) return null;
                return new Cast(new FunctionCall("epoch", List.of(
                        new Cast(lower(a.get(0)), TIMESTAMP))), BIGINT);
            }
            case "count_if" -> {
                if (a.size() != 1) return null;
                return new FunctionCall("count", List.of(new CaseWhen(
                        List.of(new CaseWhen.WhenBranch(lower(a.get(0)), Literal.ofInt(1))), null)));
            }
            case "collect_set" -> {
                if (a.size() != 1) return null;
                return new FunctionCall("list", List.of(lower(a.get(0))), true);
            }
            case "try_divide" -> {
                if (a.size() != 2) return null;
                return new BinaryExpression(lower(a.get(0)), BinaryExpression.Op.DIV,
                        new FunctionCall("nullif", List.of(lower(a.get(1)), Literal.ofInt(0))));
            }
            case "current_catalog" -> { return Literal.ofString("spark_catalog"); }
            case "current_timezone" -> { return new FunctionCall("current_setting", List.of(Literal.ofString("TimeZone"))); }
            case "localtimestamp" -> {
                if (!a.isEmpty()) return null;
                return new Cast(new FunctionCall("now", List.of()), TIMESTAMP);
            }
            case "e" -> { return new FunctionCall("exp", List.of(Literal.ofInt(1))); }
            case "string" -> {
                if (a.isEmpty() || a.size() > 3) return null;
                return new Cast(lower(a.get(0)), VARCHAR);
            }
            case "tinyint" -> { return lowerCastCall(a, IntegralType.TINYINT); }
            case "smallint" -> { return lowerCastCall(a, IntegralType.SMALLINT); }
            case "int" -> { return lowerCastCall(a, IntegralType.INT); }
            case "bigint" -> { return lowerCastCall(a, IntegralType.BIGINT); }
            case "float" -> { return lowerCastCall(a, io.github.joegxx.localsql.ir.type.FractionalType.FLOAT); }
            case "double" -> { return lowerCastCall(a, io.github.joegxx.localsql.ir.type.FractionalType.DOUBLE); }
            case "boolean" -> { return lowerCastCall(a, new BooleanType()); }
            case "date" -> { return lowerCastCall(a, DATE); }
            case "timestamp" -> { return lowerCastCall(a, TIMESTAMP); }
            case "binary" -> { return lowerCastCall(a, new BinaryType()); }
            case "decimal" -> {
                if (a.isEmpty() || a.size() > 3) return null;
                DataType target;
                if (a.size() == 1) {
                    target = new DecimalType(10, 0);
                } else {
                    int precision = literalInt(a.get(1));
                    if (precision < 0) {
                        throw new UnsupportedOperationException("decimal(x, p[, s]) requires literal precision");
                    }
                    int scale = a.size() == 3
                            ? requireLiteralInt(a.get(2), "decimal(x, p[, s]) requires a literal scale") : 0;
                    target = new DecimalType(precision, Math.max(scale, 0));
                }
                return new Cast(lower(a.get(0)), target);
            }
            case "sort_array" -> {
                if (a.isEmpty() || a.size() > 2) return null;
                boolean desc = false;
                if (a.size() == 2) {
                    Boolean asc = literalBool(a.get(1));
                    if (asc == null) {
                        throw new UnsupportedOperationException(
                                "sort_array's second argument must be a boolean literal; comparator lambdas are not supported");
                    }
                    desc = !asc;
                }
                return new FunctionCall("list_sort",
                        List.of(lower(a.get(0)), Literal.ofString(desc ? "DESC" : "ASC")));
            }
            case "sequence" -> {
                if (a.size() < 2 || a.size() > 3) return null;
                Expression start = lower(a.get(0));
                Expression stop = lower(a.get(1));
                List<Expression> up = new ArrayList<>(List.of(start,
                        new BinaryExpression(stop, BinaryExpression.Op.ADD, Literal.ofInt(1))));
                if (a.size() == 3) up.add(lower(a.get(2)));
                List<Expression> down = List.of(start,
                        new BinaryExpression(stop, BinaryExpression.Op.SUB, Literal.ofInt(1)),
                        Literal.ofInt(-1));
                return new CaseWhen(
                        List.of(new CaseWhen.WhenBranch(
                                new BinaryExpression(stop, BinaryExpression.Op.GTE, start),
                                new FunctionCall("range", up))),
                        new FunctionCall("range", down));
            }
            case "date_trunc" -> {
                if (a.size() != 2) return null;
                String unit = literalString(a.get(0));
                if (unit == null) {
                    throw new UnsupportedOperationException("date_trunc requires a literal unit");
                }
                String translated = switch (unit.toLowerCase()) {
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
                return new FunctionCall("date_trunc",
                        List.of(Literal.ofString(translated), lower(a.get(1))));
            }
            case "sha2" -> {
                if (a.size() != 2) return null;
                int bits = literalInt(a.get(1));
                if (bits != 0 && bits != 256) {
                    throw new UnsupportedOperationException(
                            "sha2 supports 0/256 bits via DuckDB sha256; requested: " + a.get(1));
                }
                return new FunctionCall("sha256", List.of(lower(a.get(0))));
            }
            case "reflect", "java_method" -> {
                if (a.size() < 2) return null;
                String className = literalString(a.get(0));
                String methodName = literalString(a.get(1));
                if (className == null || methodName == null) {
                    throw new UnsupportedOperationException(
                            "reflect requires literal class and method names");
                }
                String mapped = REFLECT_MAP.get(className + "." + methodName);
                if (mapped == null) {
                    throw new UnsupportedOperationException(
                            "reflect: only these methods are supported: " + REFLECT_MAP.keySet());
                }
                List<Expression> rest = new ArrayList<>();
                for (int i = 2; i < a.size(); i++) rest.add(lower(a.get(i)));
                return new FunctionCall(mapped, rest);
            }
            case "exists" -> {
                if (a.size() != 2) return null;
                return new BinaryExpression(
                        new FunctionCall("len", List.of(new FunctionCall("list_filter",
                                List.of(lower(a.get(0)), lower(a.get(1)))))),
                        BinaryExpression.Op.GT, Literal.ofInt(0));
            }
            case "forall" -> {
                if (a.size() != 2) return null;
                Expression arr = lower(a.get(0));
                return new BinaryExpression(
                        new FunctionCall("len", List.of(new FunctionCall("list_filter",
                                List.of(arr, lower(a.get(1)))))),
                        BinaryExpression.Op.EQ,
                        new FunctionCall("len", List.of(arr)));
            }
            case "aggregate" -> {
                if (a.size() != 3) {
                    throw new UnsupportedOperationException(
                            "aggregate with a finish lambda is not supported yet; use aggregate(arr, zero, merge)");
                }
                return new FunctionCall("list_reduce", List.of(
                        new FunctionCall("list_concat", List.of(
                                new FunctionCall("list_value", List.of(lower(a.get(1)))),
                                lower(a.get(0)))),
                        lower(a.get(2))));
            }
            case "array_except" -> {
                if (a.size() != 2) return null;
                return new FunctionCall("list_distinct", List.of(new FunctionCall("list_filter", List.of(
                        lower(a.get(0)),
                        new Lambda(List.of("x"), new UnaryExpression(UnaryExpression.Op.NOT,
                                new FunctionCall("list_contains", List.of(
                                        lower(a.get(1)), new AttributeReference("x", List.of())))))))));
            }
            case "array_remove" -> {
                if (a.size() != 2) return null;
                return new FunctionCall("list_filter", List.of(
                        lower(a.get(0)),
                        new Lambda(List.of("x"), new UnaryExpression(UnaryExpression.Op.NOT,
                                new BinaryExpression(
                                        new AttributeReference("x", List.of()),
                                        BinaryExpression.Op.EQ_NULL_SAFE,
                                        lower(a.get(1)))))));
            }
            case "transform_values", "transform_keys", "map_filter" -> {
                if (a.size() != 2 || !(a.get(1) instanceof Lambda lam) || lam.variables().size() != 2) {
                    throw new UnsupportedOperationException(name
                            + " requires a two-variable lambda, got: " + (a.size() >= 2 ? a.get(1) : ""));
                }
                // lower the two-variable Spark lambda onto a single-variable
                // DuckDB lambda over map_entries structs: k -> e.key, v -> e.value
                String k = lam.variables().get(0);
                String v = lam.variables().get(1);
                Expression body = lowerBody(lam.body(), java.util.Map.of(
                        k, new AttributeReference("key", List.of("e")),
                        v, new AttributeReference("value", List.of("e"))));
                Expression lambdaVar = new AttributeReference("e", List.of());
                if (name.equals("map_filter")) {
                    return new FunctionCall("map_from_entries", List.of(
                            new FunctionCall("list_filter", List.of(
                                    new FunctionCall("map_entries", List.of(lower(a.get(0)))),
                                    new Lambda(List.of("e"), body)))));
                }
                // transform_keys: {'key': body, 'value': e.value}
                // transform_values: {'key': e.key, 'value': body}
                Expression keyExpr = name.equals("transform_keys")
                        ? body : new AttributeReference("key", List.of("e"));
                Expression valueExpr = name.equals("transform_keys")
                        ? new AttributeReference("value", List.of("e")) : body;
                Expression entry = new StructExpr(
                        List.of(keyExpr, valueExpr), List.of("key", "value"));
                return new FunctionCall("map_from_entries", List.of(
                        new FunctionCall("list_transform", List.of(
                                new FunctionCall("map_entries", List.of(lower(a.get(0)))),
                                new Lambda(List.of("e"), entry)))));
            }
            case "map_zip_with" -> {
                if (a.size() != 3 || !(a.get(2) instanceof Lambda lam) || lam.variables().size() != 3) {
                    throw new UnsupportedOperationException(
                            "map_zip_with requires (m1, m2, (k, v1, v2) -> expr)");
                }
                String k = lam.variables().get(0);
                String v1 = lam.variables().get(1);
                String v2 = lam.variables().get(2);
                Expression lookup1 = mapValueLookup(a.get(0), k);
                Expression lookup2 = mapValueLookup(a.get(1), k);
                Expression body = lowerBody(lam.body(), java.util.Map.of(v1, lookup1, v2, lookup2));
                Expression entry = new StructExpr(
                        List.of(new AttributeReference(k, List.of()),
                                body),
                        List.of("key", "value"));
                return new FunctionCall("map_from_entries", List.of(
                        new FunctionCall("list_transform", List.of(
                                new FunctionCall("list_distinct", List.of(
                                        new FunctionCall("list_concat", List.of(
                                                new FunctionCall("map_keys", List.of(lower(a.get(0)))),
                                                new FunctionCall("map_keys", List.of(lower(a.get(1)))))))),
                                new Lambda(List.of(k), entry)))));
            }
            case "named_struct" -> {
                if (a.isEmpty() || a.size() % 2 != 0) return null;
                List<Expression> fields = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (int i = 0; i < a.size(); i += 2) {
                    String fieldName = literalString(a.get(i));
                    if (fieldName == null) {
                        throw new UnsupportedOperationException(
                                "named_struct field names must be string literals; got: " + a.get(i));
                    }
                    names.add(fieldName);
                    fields.add(lower(a.get(i + 1)));
                }
                return new StructExpr(fields, names);
            }
            case "substring_index" -> {
                if (a.size() != 3) return null;
                int count = requireLiteralInt(a.get(2), "substring_index requires a literal count");
                Expression split = new FunctionCall("string_split", List.of(
                        new Cast(lower(a.get(0)), VARCHAR),
                        new Cast(lower(a.get(1)), VARCHAR)));
                Expression delim = new Cast(lower(a.get(1)), VARCHAR);
                // count > 0: first count fields; count < 0: last |count|;
                // count = 0: empty string
                Expression positive = new FunctionCall("array_to_string", List.of(
                        new FunctionCall("list_slice", List.of(split,
                                Literal.ofInt(1), Literal.ofInt(count))), delim));
                Expression negative = new FunctionCall("array_to_string", List.of(
                        new FunctionCall("list_slice", List.of(split,
                                Literal.ofInt(count), Literal.ofInt(-1))), delim));
                return new CaseWhen(List.of(
                                new CaseWhen.WhenBranch(
                                        new BinaryExpression(lower(a.get(2)), BinaryExpression.Op.GT, Literal.ofInt(0)),
                                        positive),
                                new CaseWhen.WhenBranch(
                                        new BinaryExpression(lower(a.get(2)), BinaryExpression.Op.LT, Literal.ofInt(0)),
                                        negative)),
                        Literal.ofString(""));
            }
            case "next_day" -> {
                if (a.size() != 2) return null;
                String dayName = literalString(a.get(1));
                if (dayName == null) {
                    throw new UnsupportedOperationException("next_day requires a literal day name");
                }
                int target = SparkDialectFormats.dayIndex(dayName);
                if (target < 0) {
                    throw new UnsupportedOperationException("next_day: unknown day name " + dayName);
                }
                // (target - weekday(ts)+1) mod 7 in [0..6]; 0 means same day ->
                // advance a full week (Spark wants the NEXT such day)
                Expression ts = lower(a.get(0));
                Expression weekday = new FunctionCall("weekday", List.of(ts));
                Expression delta = new BinaryExpression(
                        new BinaryExpression(
                                new BinaryExpression(
                                        new BinaryExpression(Literal.ofInt(target),
                                                BinaryExpression.Op.SUB,
                                                new BinaryExpression(weekday, BinaryExpression.Op.SUB, Literal.ofInt(1))),
                                        BinaryExpression.Op.MOD, Literal.ofInt(7)),
                                BinaryExpression.Op.ADD, Literal.ofInt(7)),
                        BinaryExpression.Op.MOD, Literal.ofInt(7));
                Expression days = new CaseWhen(
                        List.of(new CaseWhen.WhenBranch(
                                new BinaryExpression(delta, BinaryExpression.Op.EQ, Literal.ofInt(0)),
                                Literal.ofInt(7))),
                        delta);
                return new Cast(new BinaryExpression(
                        new Cast(ts, DATE), BinaryExpression.Op.ADD, new Cast(days, INT)), DATE);
            }
            case "format_number" -> {
                if (a.size() != 2) return null;
                int scale = requireLiteralInt(a.get(1), "format_number requires a literal scale");
                return new FunctionCall("printf", List.of(
                        Literal.ofString("%,." + Math.max(scale, 0) + "f"), lower(a.get(0))));
            }
            case "date_format" -> {
                if (a.size() != 2) return null;
                String pattern = literalString(a.get(1));
                if (pattern == null) {
                    throw new UnsupportedOperationException(
                            "date_format requires a literal format string (Java -> strftime translation)");
                }
                int sss = pattern.indexOf("SSS");
                if (sss >= 0) {
                    if (pattern.indexOf("SSS", sss + 3) >= 0) {
                        throw new UnsupportedOperationException("date_format: multiple SSS segments are not supported: " + pattern);
                    }
                    String before = SparkDialectFormats.javaFormatToStrftime(pattern.substring(0, sss));
                    String after = SparkDialectFormats.javaFormatToStrftime(pattern.substring(sss + 3));
                    Expression millis = new FunctionCall("lpad", List.of(
                            new Cast(new BinaryExpression(
                                    new BinaryExpression(
                                            new FunctionCall("microsecond", List.of(new Cast(lower(a.get(0)), TIMESTAMP))),
                                            BinaryExpression.Op.MOD, Literal.ofInt(1000000)),
                                    BinaryExpression.Op.INT_DIV, Literal.ofInt(1000)), VARCHAR),
                            Literal.ofInt(3), Literal.ofString("0")));
                    List<Expression> parts = new ArrayList<>();
                    if (!before.isEmpty()) parts.add(new FunctionCall("strftime", List.of(lower(a.get(0)), Literal.ofString(before))));
                    parts.add(millis);
                    if (!after.isEmpty()) parts.add(new FunctionCall("strftime", List.of(lower(a.get(0)), Literal.ofString(after))));
                    return new FunctionCall("concat", parts);
                }
                String strftime = SparkDialectFormats.javaFormatToStrftime(pattern);
                return new FunctionCall("strftime", List.of(lower(a.get(0)), Literal.ofString(strftime)));
            }
            case "from_json" -> {
                if (a.size() < 2 || a.size() > 3) return null;
                String schema = literalString(a.get(1));
                if (schema == null) {
                    throw new UnsupportedOperationException(
                            "from_json requires a literal schema string (e.g. 'STRUCT<a INT, b STRING>' or 'a INT, b STRING')");
                }
                String duckSchema = SparkDialectFormats.sparkSchemaToDuckJson(schema);
                Expression json = lower(a.get(0));
                return new CaseWhen(List.of(new CaseWhen.WhenBranch(
                                new FunctionCall("json_valid", List.of(json)),
                                new FunctionCall("from_json", List.of(json, Literal.ofString(duckSchema))))),
                        Literal.ofNull());
            }
            case "from_utc_timestamp", "to_utc_timestamp" -> {
                if (a.size() != 2) return null;
                Expression ts = new Cast(lower(a.get(0)), TIMESTAMP);
                Expression tz = lower(a.get(1));
                // from_utc: naive is UTC -> timezone('UTC', ts) makes it
                // TIMESTAMPTZ, timezone(tz, ...) renders it in tz
                // to_utc: naive is tz-local -> timezone(tz, ts) makes it
                // TIMESTAMPTZ, timezone('UTC', ...) renders it in UTC
                Expression asUtc = new FunctionCall("timezone", List.of(Literal.ofString("UTC"), ts));
                Expression asTz = new FunctionCall("timezone", List.of(tz, ts));
                return name.equals("from_utc_timestamp")
                        ? new FunctionCall("timezone", List.of(tz, asUtc))
                        : new FunctionCall("timezone", List.of(Literal.ofString("UTC"), asTz));
            }
            case "bround" -> {
                if (a.isEmpty() || a.size() > 2) return null;
                // connection-registered Java UDF; binder needs explicit casts
                List<Expression> args = new ArrayList<>();
                args.add(new Cast(lower(a.get(0)), DOUBLE));
                if (a.size() == 2) args.add(new Cast(lower(a.get(1)), DOUBLE));
                return new FunctionCall("bround", args);
            }
            case "conv" -> {
                if (a.size() != 3) return null;
                return new FunctionCall("conv", List.of(
                        new Cast(lower(a.get(0)), VARCHAR),
                        new Cast(lower(a.get(1)), VARCHAR),
                        new Cast(lower(a.get(2)), VARCHAR)));
            }
            case "parse_url" -> {
                if (a.size() < 2 || a.size() > 3) return null;
                List<Expression> args = new ArrayList<>();
                args.add(new Cast(lower(a.get(0)), VARCHAR));
                args.add(new Cast(lower(a.get(1)), VARCHAR));
                if (a.size() == 3) args.add(new Cast(lower(a.get(2)), VARCHAR));
                return new FunctionCall("parse_url", args);
            }
            case "initcap" -> {
                if (a.size() != 1) return null;
                // connection-registered Java UDF (DuckDB has no initcap)
                return new FunctionCall("initcap", List.of(new Cast(lower(a.get(0)), VARCHAR)));
            }
            case "nvl2" -> {
                if (a.size() != 3) return null;
                return new CaseWhen(List.of(new CaseWhen.WhenBranch(
                                new UnaryExpression(UnaryExpression.Op.IS_NOT_NULL, lower(a.get(0))),
                                lower(a.get(1)))),
                        lower(a.get(2)));
            }
            case "equal_null" -> {
                if (a.size() != 2) return null;
                return new BinaryExpression(lower(a.get(0)), BinaryExpression.Op.EQ_NULL_SAFE, lower(a.get(1)));
            }
            case "mod", "pmod" -> {
                if (a.size() != 2) return null;
                // DuckDB % matches Spark mod (dividend sign). Spark pmod makes
                // the result carry the divisor sign instead: when the raw
                // remainder and the divisor disagree on sign (and raw is not
                // zero), shift by one divisor.
                Expression raw = new BinaryExpression(lower(a.get(0)), BinaryExpression.Op.MOD, lower(a.get(1)));
                if (name.equals("mod")) return raw;
                Expression divisor = lower(a.get(1));
                Expression signMismatch = new BinaryExpression(
                        new BinaryExpression(
                                new BinaryExpression(raw, BinaryExpression.Op.LT, Literal.ofInt(0)),
                                BinaryExpression.Op.AND,
                                new BinaryExpression(divisor, BinaryExpression.Op.GT, Literal.ofInt(0))),
                        BinaryExpression.Op.OR,
                        new BinaryExpression(
                                new BinaryExpression(raw, BinaryExpression.Op.GT, Literal.ofInt(0)),
                                BinaryExpression.Op.AND,
                                new BinaryExpression(divisor, BinaryExpression.Op.LT, Literal.ofInt(0))));
                return new CaseWhen(List.of(new CaseWhen.WhenBranch(
                                signMismatch,
                                new BinaryExpression(raw, BinaryExpression.Op.ADD, divisor))),
                        raw);
            }
            case "array_union" -> {
                if (a.size() != 2) return null;
                return new FunctionCall("list_distinct", List.of(new FunctionCall("list_concat", List.of(
                        lower(a.get(0)), lower(a.get(1))))));
            }
            case "locate", "position" -> {
                if (a.size() == 2) {
                    // Spark locate(substr, str); DuckDB strpos(str, substr)
                    return new FunctionCall("strpos", List.of(lower(a.get(1)), lower(a.get(0))));
                }
                if (a.size() == 3) {
                    // search inside substring(str, pos) then offset back
                    Expression substr = new FunctionCall("substr", List.of(
                            lower(a.get(1)), lower(a.get(2))));
                    Expression found = new FunctionCall("strpos", List.of(substr, lower(a.get(0))));
                    // Spark: 0 when absent, else strpos(substr(...)) + pos - 1
                    return new CaseWhen(List.of(new CaseWhen.WhenBranch(
                                    new BinaryExpression(found, BinaryExpression.Op.EQ, Literal.ofInt(0)),
                                    Literal.ofInt(0))),
                            new BinaryExpression(found, BinaryExpression.Op.ADD,
                                    new BinaryExpression(lower(a.get(2)), BinaryExpression.Op.SUB, Literal.ofInt(1))));
                }
                return null;
            }
            case "map" -> {
                if (a.isEmpty() || a.size() % 2 != 0) return null;
                List<Expression> keys = new ArrayList<>();
                List<Expression> values = new ArrayList<>();
                for (int i = 0; i < a.size(); i += 2) {
                    keys.add(lower(a.get(i)));
                    values.add(lower(a.get(i + 1)));
                }
                return new FunctionCall("map", List.of(
                        new FunctionCall("list_value", keys),
                        new FunctionCall("list_value", values)));
            }
            case "octet_length" -> {
                if (a.size() != 1) return null;
                // DuckDB's octet_length only binds for BLOB; strings need a cast
                return new FunctionCall("octet_length", List.of(new Cast(lower(a.get(0)), BLOB)));
            }
            case "slice" -> {
                if (a.size() != 3) return null;
                return new FunctionCall("list_slice", List.of(
                        lower(a.get(0)), lower(a.get(1)),
                        new BinaryExpression(
                                new BinaryExpression(lower(a.get(1)), BinaryExpression.Op.ADD, lower(a.get(2))),
                                BinaryExpression.Op.SUB, Literal.ofInt(1))));
            }
            case "date_from_unix_date" -> {
                if (a.size() != 1) return null;
                return new Cast(new BinaryExpression(
                        new Cast(Literal.ofString("1970-01-01"), DATE),
                        BinaryExpression.Op.ADD,
                        new Cast(lower(a.get(0)), INT)), DATE);
            }
            case "unix_date" -> {
                if (a.size() != 1) return null;
                return new FunctionCall("date_diff", List.of(
                        Literal.ofString("day"),
                        new Cast(Literal.ofString("1970-01-01"), DATE),
                        new Cast(lower(a.get(0)), DATE)));
            }
            case "months_between" -> {
                if (a.size() != 2) return null;
                // Spark: (y1*12+m1) - (y2*12+m2) + day/time fraction difference / 31
                Expression t1 = new Cast(lower(a.get(0)), TIMESTAMP);
                Expression t2 = new Cast(lower(a.get(1)), TIMESTAMP);
                Expression months1 = monthsComponent(t1);
                Expression months2 = monthsComponent(t2);
                Expression frac1 = dayFraction(t1);
                Expression frac2 = dayFraction(t2);
                return new BinaryExpression(
                        new BinaryExpression(months1, BinaryExpression.Op.SUB, months2),
                        BinaryExpression.Op.ADD,
                        new BinaryExpression(
                                new BinaryExpression(frac1, BinaryExpression.Op.SUB, frac2),
                                BinaryExpression.Op.DIV, Literal.ofDecimal("31.0")));
            }
            case "array_join" -> {
                if (a.size() == 2) {
                    // DuckDB array_to_string is a direct match for the
                    // no-null-substitute form
                    return new FunctionCall("array_to_string",
                            List.of(lower(a.get(0)), lower(a.get(1))));
                }
                if (a.size() != 3) return null;
                // 3-arg form null-substitutes inside the element transform
                return new FunctionCall("array_to_string", List.of(
                        new FunctionCall("list_transform", List.of(
                                lower(a.get(0)),
                                new Lambda(List.of("x"), new FunctionCall("coalesce", List.of(
                                        new Cast(new AttributeReference("x", List.of()), VARCHAR),
                                        lower(a.get(2))))))),
                        lower(a.get(1))));
            }
            case "map_from_arrays" -> {
                if (a.size() != 2) return null;
                // DuckDB map(keys[], values[]) matches directly; emitted as
                // "map" without re-entering the k/v-pairs map lowering
                return new FunctionCall("map", List.of(lower(a.get(0)), lower(a.get(1))));
            }
            case "zip_with" -> {
                if (a.size() != 3 || !(a.get(2) instanceof Lambda lam) || lam.variables().size() != 2) {
                    throw new UnsupportedOperationException("zip_with requires (a, b, (x, y) -> expr)");
                }
                String x = lam.variables().get(0);
                String y = lam.variables().get(1);
                Expression loweredBody = lowerBody(lam.body(), java.util.Map.of(
                        x, listExtract(a.get(0)),
                        y, listExtract(a.get(1))));
                Expression indexRange = new FunctionCall("range", List.of(
                        Literal.ofInt(1),
                        new BinaryExpression(
                                new FunctionCall("greatest", List.of(
                                        new FunctionCall("len", List.of(lower(a.get(0)))),
                                        new FunctionCall("len", List.of(lower(a.get(1)))))),
                                BinaryExpression.Op.ADD,
                                Literal.ofInt(1))));
                return new FunctionCall("list_transform", List.of(
                        indexRange, new Lambda(List.of("i"), loweredBody)));
            }
            case "shiftleft" -> {
                if (a.size() != 2) return null;
                return new BinaryExpression(lower(a.get(0)), BinaryExpression.Op.SHIFT_LEFT, lower(a.get(1)));
            }
            case "shiftright" -> {
                if (a.size() != 2) return null;
                return new BinaryExpression(lower(a.get(0)), BinaryExpression.Op.SHIFT_RIGHT, lower(a.get(1)));
            }
            case "shiftrightunsigned" -> {
                if (a.size() != 2) return null;
                // mask to unsigned 64-bit first so the shift is logical, not
                // arithmetic (Spark fills with zeros even for negatives)
                return new BinaryExpression(
                        new Cast(new BinaryExpression(lower(a.get(0)),
                                BinaryExpression.Op.BIT_AND, Literal.ofDecimal("18446744073709551615")),
                                UBIGINT),
                        BinaryExpression.Op.SHIFT_RIGHT,
                        lower(a.get(1)));
            }
            case "getbit", "bit_get" -> {
                if (a.size() != 2) return null;
                return new BinaryExpression(
                        new BinaryExpression(lower(a.get(0)), BinaryExpression.Op.SHIFT_RIGHT, lower(a.get(1))),
                        BinaryExpression.Op.BIT_AND, Literal.ofInt(1));
            }
            case "bit_and" -> {
                if (a.size() != 2) return null;
                return new BinaryExpression(lower(a.get(0)), BinaryExpression.Op.BIT_AND, lower(a.get(1)));
            }
            case "bit_or" -> {
                if (a.size() != 2) return null;
                return new BinaryExpression(lower(a.get(0)), BinaryExpression.Op.BIT_OR, lower(a.get(1)));
            }
            case "bit_xor" -> {
                if (a.size() != 2) return null;
                return new FunctionCall("xor", List.of(lower(a.get(0)), lower(a.get(1))));
            }
            case "try_add" -> {
                if (a.size() != 2) return null;
                // Spark returns NULL on overflow; DuckDB errors. try_cast to
                // HUGEINT so overflow yields NULL instead.
                return new BinaryExpression(
                        new Cast(lower(a.get(0)), HUGEINT, true),
                        BinaryExpression.Op.ADD,
                        new Cast(lower(a.get(1)), HUGEINT, true));
            }
            case "array_repeat" -> {
                if (a.size() != 2) return null;
                return new FunctionCall("list_resize", List.of(
                        new FunctionCall("list_value", List.of(lower(a.get(0)))),
                        new Cast(lower(a.get(1)), IntegralType.BIGINT),
                        lower(a.get(0))));
            }
            case "timestamp_seconds", "timestamp_millis", "timestamp_micros" -> {
                if (a.size() != 1) return null;
                Expression raw;
                if (name.equals("timestamp_seconds")) {
                    raw = lower(a.get(0));
                } else {
                    raw = new BinaryExpression(lower(a.get(0)), BinaryExpression.Op.DIV,
                            Literal.ofInt(name.equals("timestamp_millis") ? 1000 : 1000000));
                }
                return new Cast(new FunctionCall("to_timestamp", List.of(new Cast(raw, DOUBLE))), TIMESTAMP);
            }
            default -> { return null; }
        }
    }

    private List<CaseWhen.WhenBranch> loweredBranches(List<CaseWhen.WhenBranch> branches) {
        List<CaseWhen.WhenBranch> out = new ArrayList<>();
        for (CaseWhen.WhenBranch b : branches) {
            out.add(new CaseWhen.WhenBranch(lower(b.condition()), lower(b.value())));
        }
        return out;
    }
}
