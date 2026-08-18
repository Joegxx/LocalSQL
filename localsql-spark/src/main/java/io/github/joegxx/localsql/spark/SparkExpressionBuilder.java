package io.github.joegxx.localsql.spark;

import io.github.joegxx.localsql.ir.expression.*;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.relation.Sort;
import io.github.joegxx.localsql.ir.type.DataType;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser.*;

import java.util.ArrayList;
import java.util.List;

final class SparkExpressionBuilder {

    private final SparkAstBuilder ast;

    SparkExpressionBuilder(SparkAstBuilder ast) { this.ast = ast; }

    Expression visit(ExpressionContext ctx) {
        return visit(ctx.booleanExpression());
    }

    Expression visit(BooleanExpressionContext ctx) {
        if (ctx instanceof LogicalNotContext c) {
            return new UnaryExpression(UnaryExpression.Op.NOT, visit(c.booleanExpression()));
        }
        if (ctx instanceof LogicalBinaryContext c) {
            Expression left = visit(c.left);
            Expression right = visit(c.right);
            BinaryExpression.Op op = c.operator.getType() == SqlBaseParser.AND
                    ? BinaryExpression.Op.AND : BinaryExpression.Op.OR;
            return new BinaryExpression(left, op, right);
        }
        if (ctx instanceof ExistsContext c) {
            Relation q = ast.visitQuery(c.query());
            return new FunctionCall("exists", List.of(new Subquery(q)));
        }
        if (ctx instanceof PredicatedContext c) {
            Expression value = visitValue(c.valueExpression());
            PredicateContext pred = c.predicate();
            if (pred == null) return value;
            return applyPredicate(value, pred);
        }
        throw new IllegalStateException("Unknown booleanExpression: " + ctx.getClass().getSimpleName());
    }

    private Expression visitValue(ValueExpressionContext ctx) {
        if (ctx instanceof ValueExpressionDefaultContext c) return visitPrimary(c.primaryExpression());
        if (ctx instanceof ArithmeticUnaryContext c) {
            int op = c.operator.getType();
            Expression child = visitValue(c.valueExpression());
            if (op == SqlBaseParser.MINUS) return new UnaryExpression(UnaryExpression.Op.NEG, child);
            if (op == SqlBaseParser.PLUS) return child;
            if (op == SqlBaseParser.TILDE) return new FunctionCall("bitwise_not", List.of(child));
            return child;
        }
        if (ctx instanceof ArithmeticBinaryContext c) {
            Expression left = visitValue(c.left);
            Expression right = visitValue(c.right);
            return new BinaryExpression(left, arithOp(c.operator.getType()), right);
        }
        if (ctx instanceof ComparisonContext c) {
            Expression left = visitValue(c.left);
            Expression right = visitValue(c.right);
            return new BinaryExpression(left, cmpOp(c.comparisonOperator()), right);
        }
        throw new IllegalStateException("Unknown valueExpression: " + ctx.getClass().getSimpleName());
    }

    private Expression applyPredicate(Expression value, PredicateContext pred) {
        boolean negated = pred.NOT() != null;
        if (pred.kind != null) {
            int kind = pred.kind.getType();
            if (kind == SqlBaseParser.IN) {
                if (pred.query() != null) {
                    Relation q = ast.visitQuery(pred.query());
                    return new InList(value, List.of(new Subquery(q)), negated);
                }
                List<Expression> items = new ArrayList<>();
                for (ExpressionContext e : pred.expression()) items.add(visit(e));
                return new InList(value, items, negated);
            }
            if (kind == SqlBaseParser.NULL) {
                return new UnaryExpression(negated ? UnaryExpression.Op.IS_NOT_NULL : UnaryExpression.Op.IS_NULL, value);
            }
            if (kind == SqlBaseParser.LIKE || kind == SqlBaseParser.RLIKE) {
                String fn = kind == SqlBaseParser.LIKE ? "like" : "rlike";
                Expression pat = visitValue(pred.pattern);
                FunctionCall call = new FunctionCall(fn, List.of(value, pat));
                return negated ? new UnaryExpression(UnaryExpression.Op.NOT, call) : call;
            }
            if (kind == SqlBaseParser.BETWEEN) {
                Expression lo = visitValue(pred.lower);
                Expression hi = visitValue(pred.upper);
                FunctionCall call = new FunctionCall("between", List.of(value, lo, hi));
                return negated ? new UnaryExpression(UnaryExpression.Op.NOT, call) : call;
            }
        }
        throw new IllegalStateException("Unsupported predicate: " + pred.getText());
    }

    private BinaryExpression.Op cmpOp(ComparisonOperatorContext op) {
        int t = op.getStart().getType();
        return switch (t) {
            case SqlBaseParser.EQ -> BinaryExpression.Op.EQ;
            case SqlBaseParser.NEQ, SqlBaseParser.NEQJ -> BinaryExpression.Op.NEQ;
            case SqlBaseParser.LT -> BinaryExpression.Op.LT;
            case SqlBaseParser.LTE -> BinaryExpression.Op.LTE;
            case SqlBaseParser.GT -> BinaryExpression.Op.GT;
            case SqlBaseParser.GTE -> BinaryExpression.Op.GTE;
            case SqlBaseParser.NSEQ -> BinaryExpression.Op.EQ;
            default -> throw new IllegalStateException("Unsupported comparison: " + op.getText());
        };
    }

    private BinaryExpression.Op arithOp(int t) {
        return switch (t) {
            case SqlBaseParser.PLUS -> BinaryExpression.Op.ADD;
            case SqlBaseParser.MINUS -> BinaryExpression.Op.SUB;
            case SqlBaseParser.ASTERISK -> BinaryExpression.Op.MUL;
            case SqlBaseParser.SLASH -> BinaryExpression.Op.DIV;
            case SqlBaseParser.PERCENT -> BinaryExpression.Op.MOD;
            case SqlBaseParser.DIV -> BinaryExpression.Op.DIV;
            case SqlBaseParser.CONCAT_PIPE -> BinaryExpression.Op.STRING_CONCAT;
            case SqlBaseParser.AMPERSAND -> BinaryExpression.Op.MUL;
            case SqlBaseParser.HAT -> BinaryExpression.Op.MUL;
            case SqlBaseParser.PIPE -> BinaryExpression.Op.STRING_CONCAT;
            default -> throw new IllegalStateException("Unsupported arithmetic op: " + t);
        };
    }

    Expression visitPrimary(PrimaryExpressionContext ctx) {
        if (ctx instanceof ConstantDefaultContext c) return visitConstant(c.constant());
        if (ctx instanceof ColumnReferenceContext c) return new Identifier(c.identifier().getText(), false);
        if (ctx instanceof DereferenceContext c) {
            Expression base = visitPrimary(c.base);
            String field = c.fieldName.getText();
            return qualify(base, field);
        }
        if (ctx instanceof ParenthesizedExpressionContext c) return visit(c.expression());
        if (ctx instanceof CastContext c) {
            Expression child = visit(c.expression());
            DataType t = SparkDataTypeBuilder.build(c.dataType());
            return new Cast(child, t);
        }
        if (ctx instanceof FunctionCallContext c) {
            if (c.FILTER() != null) {
                throw new UnsupportedOperationException("Aggregate FILTER clause not in MVP");
            }
            String name = c.functionName().getText();
            List<Expression> args = new ArrayList<>();
            if (c.argument != null) for (ExpressionContext e : c.argument) args.add(visit(e));
            boolean distinct = c.setQuantifier() != null && c.setQuantifier().DISTINCT() != null;
            FunctionCall call = new FunctionCall(name, args, distinct);
            if (c.windowSpec() != null) call.setWindowSpec(visitWindowSpec(c.windowSpec()));
            return call;
        }
        if (ctx instanceof SearchedCaseContext c) return buildCase(c.whenClause(), c.elseExpression != null ? visit(c.elseExpression) : null, null);
        if (ctx instanceof SimpleCaseContext c) return buildCase(c.whenClause(), c.elseExpression != null ? visit(c.elseExpression) : null, visit(c.value));
        if (ctx instanceof StarContext c) {
            List<String> q = new ArrayList<>();
            if (c.qualifiedName() != null) q.addAll(parts(c.qualifiedName()));
            return new Star(q);
        }
        if (ctx instanceof SubqueryExpressionContext c) {
            return new Subquery(ast.visitQuery(c.query()));
        }
        if (ctx instanceof StructContext c) {
            List<Expression> fields = new ArrayList<>();
            if (c.argument != null) for (NamedExpressionContext ne : c.argument) fields.add(visit(ne.expression()));
            return new StructExpr(fields);
        }
        if (ctx instanceof SubstringContext c) {
            List<Expression> args = new ArrayList<>();
            args.add(visitValue(c.str));
            args.add(visitValue(c.pos));
            if (c.len != null) args.add(visitValue(c.len));
            return new FunctionCall("substring", args);
        }
        if (ctx instanceof TrimContext c) {
            List<Expression> args = new ArrayList<>();
            if (c.trimStr != null) args.add(visitValue(c.trimStr));
            args.add(visitValue(c.srcStr));
            String fn = "trim";
            if (c.trimOption != null) {
                int t = c.trimOption.getType();
                if (t == SqlBaseParser.LEADING) fn = "ltrim";
                else if (t == SqlBaseParser.TRAILING) fn = "rtrim";
            }
            return new FunctionCall(fn, args);
        }
        if (ctx instanceof CurrentLikeContext c) {
            return new FunctionCall(c.name.getText().toLowerCase(), List.of());
        }
        if (ctx instanceof PositionContext c) {
            return new FunctionCall("instr", List.of(visitValue(c.str), visitValue(c.substr)));
        }
        if (ctx instanceof ExtractContext c) {
            return new FunctionCall("extract", List.of(new Literal(c.field.getText(), Literal.Kind.STRING), visitValue(c.source)));
        }
        if (ctx instanceof OverlayContext c) {
            List<Expression> args = new ArrayList<>();
            args.add(visitValue(c.input));
            args.add(visitValue(c.replace));
            args.add(visitValue(c.position));
            if (c.length != null) args.add(visitValue(c.length));
            return new FunctionCall("overlay", args);
        }
        if (ctx instanceof FirstContext c) {
            return new FunctionCall("first", List.of(visit(c.expression())));
        }
        if (ctx instanceof LastContext c) {
            return new FunctionCall("last", List.of(visit(c.expression())));
        }
        if (ctx instanceof SubscriptContext c) {
            return new FunctionCall("element_at", List.of(visitPrimary(c.value), visitValue(c.index)));
        }
        if (ctx instanceof RowConstructorContext c) {
            List<Expression> fields = new ArrayList<>();
            for (NamedExpressionContext ne : c.namedExpression()) fields.add(visit(ne.expression()));
            return new StructExpr(fields);
        }
        if (ctx instanceof LambdaContext c) {
            throw new UnsupportedOperationException("Lambda not supported in MVP");
        }
        throw new IllegalStateException("Unsupported primary expression: " + ctx.getClass().getSimpleName());
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
        return new FunctionCall("get_field", List.of(base, new Literal(field, Literal.Kind.STRING)));
    }

    private Expression buildCase(List<WhenClauseContext> whens, Expression elseValue, Expression subject) {
        List<CaseWhen.WhenBranch> branches = new ArrayList<>();
        for (WhenClauseContext w : whens) {
            Expression cond = visit(w.condition);
            if (subject != null) cond = new BinaryExpression(subject, BinaryExpression.Op.EQ, cond);
            branches.add(new CaseWhen.WhenBranch(cond, visit(w.result)));
        }
        return new CaseWhen(branches, elseValue);
    }

    private Expression visitConstant(ConstantContext c) {
        if (c instanceof NullLiteralContext) return Literal.ofNull();
        if (c instanceof BooleanLiteralContext b) return Literal.ofBool(b.booleanValue().getText().equalsIgnoreCase("TRUE"));
        if (c instanceof NumericLiteralContext n) return visitNumber(n.number());
        if (c instanceof StringLiteralContext s) {
            StringBuilder sb = new StringBuilder();
            for (TerminalNode t : s.STRING()) {
                String txt = t.getText();
                sb.append(txt, 1, txt.length() - 1);
            }
            return Literal.ofString(sb.toString());
        }
        if (c instanceof IntervalLiteralContext il) {
            IntervalContext iv = il.interval();
            if (iv.errorCapturingMultiUnitsInterval() != null) {
                MultiUnitsIntervalContext multi = iv.errorCapturingMultiUnitsInterval().body;
                if (multi != null && !multi.intervalValue().isEmpty() && !multi.unit.isEmpty()) {
                    IntervalValueContext val = multi.intervalValue(0);
                    String text = val.getText();
                    String unit = multi.unit.get(0).getText().toUpperCase();
                    return new IntervalLiteral(stripSign(text), unit);
                }
            }
            if (iv.errorCapturingUnitToUnitInterval() != null) {
                UnitToUnitIntervalContext u2u = iv.errorCapturingUnitToUnitInterval().body;
                if (u2u != null && u2u.value != null && u2u.from != null) {
                    String text = u2u.value.getText();
                    String unit = u2u.from.getText().toUpperCase();
                    return new IntervalLiteral(stripSign(text), unit);
                }
            }
        }
        if (c instanceof TypeConstructorContext tc) {
            String type = tc.identifier().getText().toLowerCase();
            String raw = tc.STRING().getText();
            String v = raw.substring(1, raw.length() - 1);
            if (type.equals("date")) return Literal.ofDate(v);
            if (type.equals("timestamp")) return Literal.ofTimestamp(v);
            return new Cast(Literal.ofString(v), SparkDataTypeBuilder.build(null));
        }
        throw new IllegalStateException("Unsupported constant: " + c.getClass().getSimpleName());
    }

    private static String stripSign(String text) {
        String t = text.trim();
        if (t.startsWith("+") || t.startsWith("-")) return t.substring(1);
        return t;
    }

    private Expression visitNumber(NumberContext n) {
        if (n instanceof IntegerLiteralContext i) return Literal.ofInt(Long.parseLong(i.INTEGER_VALUE().getText()));
        if (n instanceof BigIntLiteralContext b) return Literal.ofInt(Long.parseLong(b.BIGINT_LITERAL().getText().replaceFirst("[Ll]$", "")));
        if (n instanceof DoubleLiteralContext d) return Literal.ofDouble(Double.parseDouble(d.DOUBLE_LITERAL().getText().replaceFirst("[Dd]$", "")));
        if (n instanceof DecimalLiteralContext d) return Literal.ofDecimal(d.DECIMAL_VALUE().getText());
        if (n instanceof SmallIntLiteralContext s) return Literal.ofInt(Long.parseLong(s.SMALLINT_LITERAL().getText().replaceFirst("[Ss]$", "")));
        if (n instanceof TinyIntLiteralContext t) return Literal.ofInt(Long.parseLong(t.TINYINT_LITERAL().getText().replaceFirst("[Yy]$", "")));
        if (n instanceof ExponentLiteralContext e) return Literal.ofDouble(Double.parseDouble(e.EXPONENT_VALUE().getText()));
        return Literal.ofNull();
    }

    private WindowSpec visitWindowSpec(WindowSpecContext ctx) {
        if (ctx instanceof WindowRefContext) {
            throw new UnsupportedOperationException("Named window reference not in MVP");
        }
        WindowDefContext def = (WindowDefContext) ctx;
        List<Expression> partitionBy = new ArrayList<>();
        for (ExpressionContext e : def.partition) partitionBy.add(visit(e));
        List<Sort.SortOrder> orderBy = new ArrayList<>();
        if (def.sortItem() != null) {
            for (SortItemContext si : def.sortItem()) orderBy.add(sortItem(si));
        }
        WindowSpec.Frame frame = def.windowFrame() != null ? visitWindowFrame(def.windowFrame()) : null;
        return new WindowSpec(partitionBy, orderBy, frame);
    }

    private WindowSpec.Frame visitWindowFrame(WindowFrameContext ctx) {
        boolean rows = ctx.frameType.getType() == SqlBaseParser.ROWS;
        WindowSpec.Bound start = visitFrameBound(ctx.start);
        WindowSpec.Bound end = ctx.end != null ? visitFrameBound(ctx.end) : null;
        return new WindowSpec.Frame(rows, start, end);
    }

    private WindowSpec.Bound visitFrameBound(FrameBoundContext ctx) {
        if (ctx.UNBOUNDED() != null) {
            return ctx.boundType.getType() == SqlBaseParser.PRECEDING
                    ? WindowSpec.Bound.unboundedPreceding() : WindowSpec.Bound.unboundedFollowing();
        }
        if (ctx.CURRENT() != null) return WindowSpec.Bound.currentRow();
        Expression offset = visit(ctx.expression());
        return new WindowSpec.Bound(
                ctx.boundType.getType() == SqlBaseParser.PRECEDING
                        ? WindowSpec.Bound.Type.PRECEDING : WindowSpec.Bound.Type.FOLLOWING,
                offset);
    }

    private Sort.SortOrder sortItem(SortItemContext si) {
        Expression e = visit(si.expression());
        boolean asc = si.ordering == null || si.ordering.getType() == SqlBaseParser.ASC;
        boolean nullsLast = si.nullOrder == null || si.nullOrder.getType() == SqlBaseParser.LAST;
        return new Sort.SortOrder(e, asc, nullsLast);
    }

    static List<String> parts(QualifiedNameContext q) {
        List<String> out = new ArrayList<>();
        for (IdentifierContext id : q.identifier()) out.add(id.getText());
        return out;
    }
}
