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
                    rewriteList(ag.aggregateExpressions()),
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
            return new Generate(lower(g.generator()), g.generatorOutputNames(), g.viewAlias(),
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

    private static String literalString(Expression e) {
        if (e instanceof Literal l && l.kind() == Literal.Kind.STRING) return String.valueOf(l.value());
        return null;
    }

    private static Boolean literalBool(Expression e) {
        if (e instanceof Literal l && l.kind() == Literal.Kind.BOOLEAN) return (Boolean) l.value();
        return null;
    }

    private List<Expression> rewriteList(List<Expression> list) {
        List<Expression> out = new ArrayList<>(list.size());
        for (Expression e : list) out.add(lower(e));
        return out;
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
            return new StructExpr(fields);
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
                    int scale = a.size() == 3 ? literalInt(a.get(2)) : 0;
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
