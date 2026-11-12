package io.github.joegxx.localsql.starrocks;

import com.starrocks.sql.parser.StarRocksParser;
import io.github.joegxx.localsql.ir.expression.*;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.relation.Sort;
import io.github.joegxx.localsql.ir.type.DataType;

import java.util.ArrayList;
import java.util.List;

/**
 * StarRocks 表达式 -> IR 表达式。方言差异在此归一到 IR 的 Spark 约定：
 * - REGEXP/RLIKE/LIKE -> "like"/"rlike" 函数（generator 发射中缀语法）
 * - BIT_SHIFT_LEFT/RIGHT -> shiftleft/shiftright 函数（ExpressionLowering 转 IR 位移）
 * - CONCAT（来自 ||）-> STRING_CONCAT
 * - x -> '$.a' JSON 路径 -> json_extract
 * - DATETIME 'x' 字面量 -> TIMESTAMP 字面量
 */
final class StarRocksExpressionBuilder {

    private final StarRocksAstBuilder ast;

    StarRocksExpressionBuilder(StarRocksAstBuilder ast) { this.ast = ast; }

    Expression visit(StarRocksParser.ExpressionContext ctx) {
        if (ctx instanceof StarRocksParser.ExpressionDefaultContext c) {
            if (c.BINARY() != null) {
                throw new UnsupportedOperationException("BINARY prefix operator is not supported");
            }
            return visit(c.booleanExpression());
        }
        if (ctx instanceof StarRocksParser.LogicalNotContext c) {
            return new UnaryExpression(UnaryExpression.Op.NOT, visit(c.expression()));
        }
        if (ctx instanceof StarRocksParser.LogicalBinaryContext c) {
            BinaryExpression.Op op = c.operator.getType() == StarRocksParser.AND
                    || c.operator.getType() == StarRocksParser.LOGICAL_AND
                    ? BinaryExpression.Op.AND : BinaryExpression.Op.OR;
            return new BinaryExpression(visit(c.left), op, visit(c.right));
        }
        throw new IllegalStateException("Unknown expression: " + ctx.getClass().getSimpleName());
    }

    private Expression visit(StarRocksParser.BooleanExpressionContext ctx) {
        return visitBoolean(ctx);
    }

    private Expression visitBoolean(StarRocksParser.BooleanExpressionContext ctx) {
        if (ctx instanceof StarRocksParser.BooleanExpressionDefaultContext c) {
            return visitPredicate(c.predicate());
        }
        if (ctx instanceof StarRocksParser.IsNullContext c) {
            Expression v = visitBoolean(c.booleanExpression());
            return new UnaryExpression(c.NOT() != null
                    ? UnaryExpression.Op.IS_NOT_NULL : UnaryExpression.Op.IS_NULL, v);
        }
        if (ctx instanceof StarRocksParser.ComparisonContext c) {
            return new BinaryExpression(visitBoolean(c.left),
                    cmpOp(c.comparisonOperator()), visitPredicate(c.right));
        }
        if (ctx instanceof StarRocksParser.ScalarSubqueryContext c) {
            Relation q = ast.visitQueryRelation(c.queryRelation());
            return new BinaryExpression(visitBoolean(c.booleanExpression()),
                    cmpOp(c.comparisonOperator()), new ScalarSubquery(q));
        }
        throw new IllegalStateException("Unknown booleanExpression: " + ctx.getClass().getSimpleName());
    }

    private Expression visitPredicate(StarRocksParser.PredicateContext ctx) {
        if (ctx.tupleInSubquery() != null) {
            // (a, b) IN (subquery) needs tuple semantics the IR doesn't carry
            throw new UnsupportedOperationException("Multi-column IN subquery is not supported");
        }
        Expression value = visitValue(ctx.valueExpression());
        if (ctx.predicateOperations() == null) return value;
        return applyPredicate(value, ctx.predicateOperations());
    }

    private BinaryExpression.Op cmpOp(StarRocksParser.ComparisonOperatorContext op) {
        return switch (op.getStart().getType()) {
            case StarRocksParser.EQ -> BinaryExpression.Op.EQ;
            case StarRocksParser.NEQ -> BinaryExpression.Op.NEQ;
            case StarRocksParser.LT -> BinaryExpression.Op.LT;
            case StarRocksParser.LTE -> BinaryExpression.Op.LTE;
            case StarRocksParser.GT -> BinaryExpression.Op.GT;
            case StarRocksParser.GTE -> BinaryExpression.Op.GTE;
            case StarRocksParser.EQ_FOR_NULL -> BinaryExpression.Op.EQ_NULL_SAFE;
            default -> throw new IllegalStateException("Unsupported comparison: " + op.getText());
        };
    }

    private Expression applyPredicate(Expression value, StarRocksParser.PredicateOperationsContext pred) {
        // every alternative of predicateOperations is labeled, so dispatch on
        // the generated subclasses instead of the (accessor-less) parent
        if (pred instanceof StarRocksParser.InSubqueryContext c) {
            return new InList(value, List.of(new Subquery(ast.visitQueryRelation(c.queryRelation()))), c.NOT() != null);
        }
        if (pred instanceof StarRocksParser.InListContext c) {
            List<Expression> items = new ArrayList<>();
            for (StarRocksParser.ExpressionContext e : c.expressionList().expression()) items.add(visit(e));
            return new InList(value, items, c.NOT() != null);
        }
        if (pred instanceof StarRocksParser.InIntegerListContext c) {
            List<Expression> items = new ArrayList<>();
            for (var v : c.integerList().INTEGER_VALUE()) items.add(Literal.ofInt(Long.parseLong(v.getText())));
            return new InList(value, items, c.NOT() != null);
        }
        if (pred instanceof StarRocksParser.InStringListContext c) {
            List<Expression> items = new ArrayList<>();
            for (StarRocksParser.StringContext s : c.stringList().string()) items.add(Literal.ofString(text(s)));
            return new InList(value, items, c.NOT() != null);
        }
        if (pred instanceof StarRocksParser.BetweenContext c) {
            Expression lo = visitValue(c.lower);
            Expression hi = visitPredicate(c.upper);
            FunctionCall call = new FunctionCall("between", List.of(value, lo, hi));
            return c.NOT() != null ? new UnaryExpression(UnaryExpression.Op.NOT, call) : call;
        }
        if (pred instanceof StarRocksParser.LikeContext c) {
            // REGEXP/RLIKE share the rlike lowering; generator emits infix REGEXP
            String fn = c.LIKE() != null ? "like" : "rlike";
            FunctionCall call = new FunctionCall(fn, List.of(value, visitValue(c.pattern)));
            return c.NOT() != null ? new UnaryExpression(UnaryExpression.Op.NOT, call) : call;
        }
        throw new IllegalStateException("Unsupported predicate: " + pred.getText());
    }

    private Expression visitValue(StarRocksParser.ValueExpressionContext ctx) {
        if (ctx instanceof StarRocksParser.ValueExpressionDefaultContext c) {
            return visitPrimary(c.primaryExpression());
        }
        if (ctx instanceof StarRocksParser.ArithmeticBinaryContext c) {
            int t = c.operator.getType();
            // keyword-form shifts have no IR operator; route through the shared
            // Spark function names so ExpressionLowering's shift handling applies
            if (t == StarRocksParser.BIT_SHIFT_LEFT) {
                return new FunctionCall("shiftleft", List.of(visitValue(c.left), visitValue(c.right)));
            }
            if (t == StarRocksParser.BIT_SHIFT_RIGHT) {
                return new FunctionCall("shiftright", List.of(visitValue(c.left), visitValue(c.right)));
            }
            if (t == StarRocksParser.BIT_SHIFT_RIGHT_LOGICAL) {
                return new FunctionCall("shiftrightunsigned", List.of(visitValue(c.left), visitValue(c.right)));
            }
            return new BinaryExpression(visitValue(c.left), arithOp(t), visitValue(c.right));
        }
        throw new IllegalStateException("Unknown valueExpression: " + ctx.getClass().getSimpleName());
    }

    private BinaryExpression.Op arithOp(int t) {
        return switch (t) {
            case StarRocksParser.PLUS_SYMBOL -> BinaryExpression.Op.ADD;
            case StarRocksParser.MINUS_SYMBOL -> BinaryExpression.Op.SUB;
            case StarRocksParser.ASTERISK_SYMBOL -> BinaryExpression.Op.MUL;
            case StarRocksParser.SLASH_SYMBOL -> BinaryExpression.Op.DIV;
            case StarRocksParser.PERCENT_SYMBOL, StarRocksParser.MOD -> BinaryExpression.Op.MOD;
            case StarRocksParser.INT_DIV -> BinaryExpression.Op.INT_DIV;
            case StarRocksParser.BITAND -> BinaryExpression.Op.BIT_AND;
            case StarRocksParser.BITOR -> BinaryExpression.Op.BIT_OR;
            case StarRocksParser.BITXOR -> BinaryExpression.Op.BIT_XOR;
            default -> throw new IllegalStateException("Unsupported arithmetic op: " + t);
        };
    }

    private Expression visitPrimary(StarRocksParser.PrimaryExpressionContext ctx) {
        if (ctx instanceof StarRocksParser.LiteralContext c) return visitLiteral(c.literalExpression());
        if (ctx instanceof StarRocksParser.ColumnRefContext c) {
            return new Identifier(unquote(c.columnReference().identifier().getText()), false);
        }
        if (ctx instanceof StarRocksParser.DereferenceContext c) {
            Expression base = visitPrimary(c.base);
            String field = unquote(c.fieldName.getText());
            return qualify(base, field);
        }
        if (ctx instanceof StarRocksParser.ConcatContext c) {
            return new BinaryExpression(visitPrimary(c.left), BinaryExpression.Op.STRING_CONCAT, visitPrimary(c.right));
        }
        if (ctx instanceof StarRocksParser.ArithmeticUnaryContext c) {
            Expression child = visitPrimary(c.primaryExpression());
            return switch (c.operator.getType()) {
                case StarRocksParser.MINUS_SYMBOL -> new UnaryExpression(UnaryExpression.Op.NEG, child);
                case StarRocksParser.PLUS_SYMBOL -> child;
                case StarRocksParser.BITNOT -> new FunctionCall("bitwise_not", List.of(child));
                case StarRocksParser.LOGICAL_NOT -> new UnaryExpression(UnaryExpression.Op.NOT, child);
                default -> throw new IllegalStateException("Unsupported unary op");
            };
        }
        if (ctx instanceof StarRocksParser.ParenthesizedExpressionContext c) return visit(c.expression());
        if (ctx instanceof StarRocksParser.ExistsContext c) {
            return new FunctionCall("exists", List.of(new Subquery(ast.visitQueryRelation(c.queryRelation()))));
        }
        if (ctx instanceof StarRocksParser.SubqueryExpressionContext c) {
            return new Subquery(ast.visitQueryRelation(c.subquery().queryRelation()));
        }
        if (ctx instanceof StarRocksParser.CastContext c) {
            return new Cast(visit(c.expression()), StarRocksTypeBuilder.build(c.type()));
        }
        if (ctx instanceof StarRocksParser.ConvertContext c) {
            return new Cast(visit(c.expression()), StarRocksTypeBuilder.build(c.type()));
        }
        if (ctx instanceof StarRocksParser.SimpleCaseContext c) {
            return buildCase(c.whenClause(), c.elseExpression != null ? visit(c.elseExpression) : null, visit(c.caseExpr));
        }
        if (ctx instanceof StarRocksParser.SearchedCaseContext c) {
            return buildCase(c.whenClause(), c.elseExpression != null ? visit(c.elseExpression) : null, null);
        }
        if (ctx instanceof StarRocksParser.ArrayConstructorContext c) {
            List<Expression> elements = new ArrayList<>();
            if (c.expressionList() != null) {
                for (StarRocksParser.ExpressionContext e : c.expressionList().expression()) elements.add(visit(e));
            }
            return new ArrayExpr(elements);
        }
        if (ctx instanceof StarRocksParser.MapConstructorContext c) {
            List<java.util.Map.Entry<Expression, Expression>> entries = new ArrayList<>();
            if (c.mapExpressionList() != null) {
                for (StarRocksParser.MapExpressionContext m : c.mapExpressionList().mapExpression()) {
                    entries.add(java.util.Map.entry(visit(m.key), visit(m.value)));
                }
            }
            return new MapExpr(entries);
        }
        if (ctx instanceof StarRocksParser.CollectionSubscriptContext c) {
            return new FunctionCall("element_at", List.of(visitPrimary(c.value), visitValue(c.index)));
        }
        if (ctx instanceof StarRocksParser.ArrowExpressionContext c) {
            // MySQL-style JSON path: col -> '$.a' == json_extract(col, '$.a')
            return new FunctionCall("json_extract",
                    List.of(visitPrimary(c.primaryExpression()), Literal.ofString(text(c.string()))));
        }
        if (ctx instanceof StarRocksParser.LambdaFunctionExprContext c) {
            List<String> vars = new ArrayList<>();
            if (c.identifier() != null) {
                vars.add(unquote(c.identifier().getText()));
            } else if (c.identifierList() != null) {
                for (StarRocksParser.IdentifierContext id : c.identifierList().identifier()) {
                    vars.add(unquote(id.getText()));
                }
            }
            if (c.expression() != null) {
                return new Lambda(vars, visit(c.expression()));
            }
            // (x, y) -> (a, b): multi-value lambda body, no IR representation yet
            throw new UnsupportedOperationException("Lambda returning multiple values is not supported");
        }
        if (ctx instanceof StarRocksParser.FunctionCallExpressionContext c) {
            return visitFunctionCall(c.functionCall());
        }
        throw new UnsupportedOperationException("Unsupported primary expression: " + ctx.getClass().getSimpleName());
    }

    private Expression visitLiteral(StarRocksParser.LiteralExpressionContext ctx) {
        if (ctx instanceof StarRocksParser.NullLiteralContext) return Literal.ofNull();
        if (ctx instanceof StarRocksParser.BooleanLiteralContext b) {
            return Literal.ofBool(b.booleanValue().getText().equalsIgnoreCase("TRUE"));
        }
        if (ctx instanceof StarRocksParser.NumericLiteralContext n) return visitNumber(n.number());
        if (ctx instanceof StarRocksParser.StringLiteralContext s) return Literal.ofString(text(s.string()));
        if (ctx instanceof StarRocksParser.DateLiteralContext d) {
            String v = text(d.string());
            return d.DATETIME() != null ? Literal.ofTimestamp(v) : Literal.ofDate(v);
        }
        if (ctx instanceof StarRocksParser.IntervalLiteralContext il) {
            StarRocksParser.IntervalContext iv = il.interval();
            // INTERVAL n unit: value is a plain expression; keep its source text
            String value = iv.value.getText();
            String unit = iv.from.getText().toUpperCase();
            if (value.startsWith("-") || value.startsWith("+")) {
                value = value.substring(1);
            }
            return new IntervalLiteral(value, unit);
        }
        throw new UnsupportedOperationException("Unsupported literal: " + ctx.getText());
    }

    private Expression visitNumber(StarRocksParser.NumberContext n) {
        // StarRocks grammar does NOT fold the sign into number literals
        // (unlike Spark); negatives arrive via unary minus
        if (n instanceof StarRocksParser.IntegerValueContext i) {
            return Literal.ofInt(Long.parseLong(i.INTEGER_VALUE().getText()));
        }
        if (n instanceof StarRocksParser.DecimalValueContext d) {
            return Literal.ofDecimal(d.DECIMAL_VALUE().getText());
        }
        if (n instanceof StarRocksParser.DoubleValueContext d) {
            return Literal.ofDouble(Double.parseDouble(d.DOUBLE_VALUE().getText()));
        }
        return Literal.ofNull();
    }

    private Expression visitFunctionCall(StarRocksParser.FunctionCallContext ctx) {
        if (ctx instanceof StarRocksParser.ExtractContext c) {
            return new FunctionCall("extract", List.of(
                    Literal.ofString(unquote(c.identifier().getText())), visitValue(c.valueExpression())));
        }
        if (ctx instanceof StarRocksParser.GroupingOperationContext c) {
            String fn = c.GROUPING() != null ? "grouping" : "grouping_id";
            return new FunctionCall(fn, visitArgs(c.expression()));
        }
        if (ctx instanceof StarRocksParser.InformationFunctionContext c) {
            String name = c.informationFunctionExpression().name.getText().toLowerCase();
            if (name.equals("catalog")) name = "current_catalog";
            if (name.equals("schema")) name = "database";
            return new FunctionCall(name, List.of());
        }
        if (ctx instanceof StarRocksParser.SpecialDateTimeContext c) {
            return new FunctionCall(c.specialDateTimeExpression().name.getText().toLowerCase(), List.of());
        }
        if (ctx instanceof StarRocksParser.SpecialFunctionContext c) {
            StarRocksParser.SpecialFunctionExpressionContext sfe = c.specialFunctionExpression();
            int t = sfe.getStart().getType();
            List<Expression> args = new ArrayList<>();
            if (t == StarRocksParser.TIMESTAMPADD || t == StarRocksParser.TIMESTAMPDIFF) {
                args.add(Literal.ofString(sfe.unitIdentifier().getText().toUpperCase()));
            }
            args.addAll(visitArgs(sfe.expression()));
            String name = switch (t) {
                case StarRocksParser.LIKE -> "like";
                case StarRocksParser.REGEXP, StarRocksParser.RLIKE -> "rlike";
                default -> sfe.getStart().getText().toLowerCase();
            };
            return new FunctionCall(name, args);
        }
        if (ctx instanceof StarRocksParser.AggregationFunctionCallContext c) {
            FunctionCall call = visitAggregation(c.aggregationFunction());
            if (c.over() != null) call.setWindowSpec(visitOver(c.over()));
            return call;
        }
        if (ctx instanceof StarRocksParser.WindowFunctionCallContext c) {
            // ignoreNulls appears in several windowFunction alternatives, so the
            // generated accessor is the list form (never null, empty when absent)
            boolean ignoreNulls = !c.windowFunction().ignoreNulls().isEmpty();
            String name = c.windowFunction().name.getText().toLowerCase();
            List<Expression> args = visitArgs(c.windowFunction().expression());
            FunctionCall call = new FunctionCall(name, args);
            call.setIgnoreNulls(ignoreNulls);
            call.setWindowSpec(visitOver(c.over()));
            return call;
        }
        if (ctx instanceof StarRocksParser.SimpleFunctionCallContext c) {
            String name = StarRocksTypeBuilder.unquote(
                    c.qualifiedName().identifier(c.qualifiedName().identifier().size() - 1).getText());
            FunctionCall call = new FunctionCall(name, visitArgs(c.expression()));
            if (c.over() != null) call.setWindowSpec(visitOver(c.over()));
            return call;
        }
        throw new UnsupportedOperationException("Unsupported function call: " + ctx.getText());
    }

    private FunctionCall visitAggregation(StarRocksParser.AggregationFunctionContext c) {
        boolean distinct = c.setQuantifier() != null && c.setQuantifier().DISTINCT() != null;
        if (c.ORDER() != null) {
            // ARRAY_AGG/GROUP_CONCAT support ORDER BY inside the aggregate;
            // IR aggregates carry no ordering, so refuse instead of dropping it
            throw new UnsupportedOperationException(
                    "ORDER BY inside aggregate is not supported: " + c.getText());
        }
        if (c.COUNT() != null) {
            List<Expression> args = c.ASTERISK_SYMBOL() != null
                    ? List.of(new Star(List.of())) : visitArgs(c.expression());
            return new FunctionCall("count", args, distinct);
        }
        if (c.GROUP_CONCAT() != null) {
            if (c.SEPARATOR() != null) {
                throw new UnsupportedOperationException(
                        "GROUP_CONCAT ... SEPARATOR is not supported yet");
            }
            return new FunctionCall("group_concat", visitArgs(c.expression()), distinct);
        }
        String name;
        if (c.AVG() != null) name = "avg";
        else if (c.MAX() != null) name = "max";
        else if (c.MIN() != null) name = "min";
        else if (c.SUM() != null) name = "sum";
        else if (c.ARRAY_AGG() != null || c.ARRAY_AGG_DISTINCT() != null) name = "array_agg";
        else throw new IllegalStateException("Unknown aggregation: " + c.getText());
        return new FunctionCall(name, visitArgs(c.expression()), distinct || c.ARRAY_AGG_DISTINCT() != null);
    }

    private List<Expression> visitArgs(List<StarRocksParser.ExpressionContext> args) {
        List<Expression> out = new ArrayList<>();
        if (args != null) for (StarRocksParser.ExpressionContext e : args) out.add(visit(e));
        return out;
    }

    private WindowSpec visitOver(StarRocksParser.OverContext ctx) {
        if (ctx.bracketHint() != null) {
            throw new UnsupportedOperationException("Hints are not supported: " + ctx.bracketHint().getText());
        }
        List<Expression> partitionBy = new ArrayList<>();
        if (ctx.partition != null) {
            for (StarRocksParser.ExpressionContext e : ctx.partition) partitionBy.add(visit(e));
        }
        List<Sort.SortOrder> orderBy = new ArrayList<>();
        if (ctx.sortItem() != null) {
            for (StarRocksParser.SortItemContext si : ctx.sortItem()) orderBy.add(sortItem(si));
        }
        WindowSpec.Frame frame = ctx.windowFrame() != null ? visitFrame(ctx.windowFrame()) : null;
        return new WindowSpec(partitionBy, orderBy, frame);
    }

    private WindowSpec.Frame visitFrame(StarRocksParser.WindowFrameContext ctx) {
        boolean rows = ctx.frameType.getType() == StarRocksParser.ROWS;
        WindowSpec.Bound start = visitBound(ctx.start);
        WindowSpec.Bound end = ctx.end != null ? visitBound(ctx.end) : null;
        return new WindowSpec.Frame(rows, start, end);
    }

    private WindowSpec.Bound visitBound(StarRocksParser.FrameBoundContext ctx) {
        if (ctx instanceof StarRocksParser.UnboundedFrameContext c) {
            return c.boundType.getType() == StarRocksParser.PRECEDING
                    ? WindowSpec.Bound.unboundedPreceding() : WindowSpec.Bound.unboundedFollowing();
        }
        if (ctx instanceof StarRocksParser.CurrentRowBoundContext) {
            return WindowSpec.Bound.currentRow();
        }
        StarRocksParser.BoundedFrameContext c = (StarRocksParser.BoundedFrameContext) ctx;
        return new WindowSpec.Bound(
                c.boundType.getType() == StarRocksParser.PRECEDING
                        ? WindowSpec.Bound.Type.PRECEDING : WindowSpec.Bound.Type.FOLLOWING,
                visit(c.expression()));
    }

    private Expression buildCase(List<StarRocksParser.WhenClauseContext> whens, Expression elseValue, Expression subject) {
        List<CaseWhen.WhenBranch> branches = new ArrayList<>();
        for (StarRocksParser.WhenClauseContext w : whens) {
            Expression cond = visit(w.condition);
            if (subject != null) cond = new BinaryExpression(subject, BinaryExpression.Op.EQ, cond);
            branches.add(new CaseWhen.WhenBranch(cond, visit(w.result)));
        }
        return new CaseWhen(branches, elseValue);
    }

    private Expression qualify(Expression base, String field) {
        if (base instanceof Identifier id) {
            return new AttributeReference(field, List.of(id.name()));
        }
        if (base instanceof AttributeReference attr) {
            List<String> q = new ArrayList<>(attr.qualifier());
            q.add(attr.name());
            return new AttributeReference(field, q);
        }
        return new FunctionCall("get_field", List.of(base, Literal.ofString(field)));
    }

    Sort.SortOrder sortItem(StarRocksParser.SortItemContext si) {
        Expression e = visit(si.expression());
        boolean asc = si.ordering == null || si.ordering.getType() == StarRocksParser.ASC;
        boolean nullsLast = si.nullOrdering == null || si.nullOrdering.getType() == StarRocksParser.LAST;
        return new Sort.SortOrder(e, asc, nullsLast);
    }

    static String text(StarRocksParser.StringContext s) {
        String raw = s.getText();
        return raw.length() >= 2 ? raw.substring(1, raw.length() - 1) : raw;
    }

    static String unquote(String text) {
        if (text == null) return null;
        if (text.length() >= 2 && text.startsWith("`") && text.endsWith("`")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
