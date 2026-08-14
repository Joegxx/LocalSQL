package io.github.joegxx.localsql.ir.visitor;

import io.github.joegxx.localsql.ir.IrNode;
import io.github.joegxx.localsql.ir.expression.*;
import io.github.joegxx.localsql.ir.relation.*;

import java.util.List;

/**
 * IrVisitor: open recursion over the IR tree.
 * Override only the methods you care about; default impls recurse into children.
 *
 * @param <R> return type
 * @param <C> context type
 */
public class IrVisitor<R, C> {

    public R visitNode(IrNode node, C ctx) { return defaultResult(node, ctx); }

    protected R defaultResult(IrNode node, C ctx) { return null; }

    protected R aggregateResult(R aggregate, R nextResult) { return nextResult != null ? nextResult : aggregate; }

    public R visit(Expression expr, C ctx) {
        if (expr == null) return null;
        if (expr instanceof Literal e) return visitLiteral(e, ctx);
        if (expr instanceof Identifier e) return visitIdentifier(e, ctx);
        if (expr instanceof AttributeReference e) return visitAttributeReference(e, ctx);
        if (expr instanceof Alias e) return visitAlias(e, ctx);
        if (expr instanceof BinaryExpression e) return visitBinary(e, ctx);
        if (expr instanceof UnaryExpression e) return visitUnary(e, ctx);
        if (expr instanceof FunctionCall e) return visitFunctionCall(e, ctx);
        if (expr instanceof CaseWhen e) return visitCaseWhen(e, ctx);
        if (expr instanceof Cast e) return visitCast(e, ctx);
        if (expr instanceof InList e) return visitInList(e, ctx);
        if (expr instanceof ArrayExpr e) return visitArrayExpr(e, ctx);
        if (expr instanceof MapExpr e) return visitMapExpr(e, ctx);
        if (expr instanceof StructExpr e) return visitStructExpr(e, ctx);
        if (expr instanceof Star e) return visitStar(e, ctx);
        if (expr instanceof Subquery e) return visitSubquery(e, ctx);
        if (expr instanceof ScalarSubquery e) return visitScalarSubquery(e, ctx);
        return visitNode(expr, ctx);
    }

    public R visitLiteral(Literal e, C ctx) { return visitNode(e, ctx); }
    public R visitIdentifier(Identifier e, C ctx) { return visitNode(e, ctx); }
    public R visitAttributeReference(AttributeReference e, C ctx) { return visitNode(e, ctx); }
    public R visitAlias(Alias e, C ctx) { return visit(e.child(), ctx); }
    public R visitStar(Star e, C ctx) { return visitNode(e, ctx); }

    public R visitBinary(BinaryExpression e, C ctx) {
        R r = defaultResult(e, ctx);
        r = aggregateResult(r, visit(e.left(), ctx));
        r = aggregateResult(r, visit(e.right(), ctx));
        return r;
    }

    public R visitUnary(UnaryExpression e, C ctx) { return visit(e.child(), ctx); }

    public R visitFunctionCall(FunctionCall e, C ctx) {
        R r = defaultResult(e, ctx);
        for (Expression arg : e.arguments()) r = aggregateResult(r, visit(arg, ctx));
        return r;
    }

    public R visitCaseWhen(CaseWhen e, C ctx) {
        R r = defaultResult(e, ctx);
        for (CaseWhen.WhenBranch b : e.branches()) {
            r = aggregateResult(r, visit(b.condition(), ctx));
            r = aggregateResult(r, visit(b.value(), ctx));
        }
        if (e.elseValue() != null) r = aggregateResult(r, visit(e.elseValue(), ctx));
        return r;
    }

    public R visitCast(Cast e, C ctx) { return visit(e.child(), ctx); }

    public R visitInList(InList e, C ctx) {
        R r = defaultResult(e, ctx);
        r = aggregateResult(r, visit(e.value(), ctx));
        for (Expression item : e.list()) r = aggregateResult(r, visit(item, ctx));
        return r;
    }

    public R visitArrayExpr(ArrayExpr e, C ctx) {
        R r = defaultResult(e, ctx);
        for (Expression el : e.elements()) r = aggregateResult(r, visit(el, ctx));
        return r;
    }

    public R visitMapExpr(MapExpr e, C ctx) {
        R r = defaultResult(e, ctx);
        for (var entry : e.entries()) {
            r = aggregateResult(r, visit(entry.getKey(), ctx));
            r = aggregateResult(r, visit(entry.getValue(), ctx));
        }
        return r;
    }

    public R visitStructExpr(StructExpr e, C ctx) {
        R r = defaultResult(e, ctx);
        for (Expression f : e.fields()) r = aggregateResult(r, visit(f, ctx));
        return r;
    }

    public R visitSubquery(Subquery e, C ctx) { return visitRelation(e.query(), ctx); }
    public R visitScalarSubquery(ScalarSubquery e, C ctx) { return visitRelation(e.query(), ctx); }

    public R visitRelation(Relation rel, C ctx) {
        if (rel == null) return null;
        if (rel instanceof TableScan r) return visitTableScan(r, ctx);
        if (rel instanceof Project r) return visitProject(r, ctx);
        if (rel instanceof Filter r) return visitFilter(r, ctx);
        if (rel instanceof Join r) return visitJoin(r, ctx);
        if (rel instanceof Aggregate r) return visitAggregate(r, ctx);
        if (rel instanceof Sort r) return visitSort(r, ctx);
        if (rel instanceof Limit r) return visitLimit(r, ctx);
        if (rel instanceof Union r) return visitUnion(r, ctx);
        if (rel instanceof With r) return visitWith(r, ctx);
        if (rel instanceof Window r) return visitWindow(r, ctx);
        if (rel instanceof Generate r) return visitGenerate(r, ctx);
        if (rel instanceof Values r) return visitValues(r, ctx);
        if (rel instanceof CTERelation r) return visitCTERelation(r, ctx);
        if (rel instanceof SubqueryAlias r) return visitSubqueryAlias(r, ctx);
        return visitNode(rel, ctx);
    }

    public R visitTableScan(TableScan r, C ctx) { return visitNode(r, ctx); }

    public R visitProject(Project r, C ctx) {
        R res = defaultResult(r, ctx);
        res = aggregateResult(res, visitRelation(r.child(), ctx));
        for (Expression e : r.projectList()) res = aggregateResult(res, visit(e, ctx));
        return res;
    }

    public R visitFilter(Filter r, C ctx) {
        R res = defaultResult(r, ctx);
        res = aggregateResult(res, visitRelation(r.child(), ctx));
        res = aggregateResult(res, visit(r.condition(), ctx));
        return res;
    }

    public R visitJoin(Join r, C ctx) {
        R res = defaultResult(r, ctx);
        res = aggregateResult(res, visitRelation(r.left(), ctx));
        res = aggregateResult(res, visitRelation(r.right(), ctx));
        if (r.condition() != null) res = aggregateResult(res, visit(r.condition(), ctx));
        return res;
    }

    public R visitAggregate(Aggregate r, C ctx) {
        R res = defaultResult(r, ctx);
        res = aggregateResult(res, visitRelation(r.child(), ctx));
        for (Expression e : r.groupingExpressions()) res = aggregateResult(res, visit(e, ctx));
        for (Expression e : r.aggregateExpressions()) res = aggregateResult(res, visit(e, ctx));
        if (r.havingCondition() != null) res = aggregateResult(res, visit(r.havingCondition(), ctx));
        return res;
    }

    public R visitSort(Sort r, C ctx) {
        R res = defaultResult(r, ctx);
        res = aggregateResult(res, visitRelation(r.child(), ctx));
        for (Sort.SortOrder o : r.order()) res = aggregateResult(res, visit(o.expr(), ctx));
        return res;
    }

    public R visitLimit(Limit r, C ctx) { return visitRelation(r.child(), ctx); }

    public R visitUnion(Union r, C ctx) {
        R res = defaultResult(r, ctx);
        for (Relation c : r.children()) res = aggregateResult(res, visitRelation(c, ctx));
        return res;
    }

    public R visitWith(With r, C ctx) {
        R res = defaultResult(r, ctx);
        for (CTERelation cte : r.ctes()) res = aggregateResult(res, visitRelation(cte.definition(), ctx));
        res = aggregateResult(res, visitRelation(r.body(), ctx));
        return res;
    }

    public R visitWindow(Window r, C ctx) {
        R res = defaultResult(r, ctx);
        res = aggregateResult(res, visitRelation(r.child(), ctx));
        for (Expression e : r.windowExpressions()) res = aggregateResult(res, visit(e, ctx));
        if (r.spec() != null) {
            for (Expression e : r.spec().partitionBy()) res = aggregateResult(res, visit(e, ctx));
            for (Sort.SortOrder o : r.spec().orderBy()) res = aggregateResult(res, visit(o.expr(), ctx));
        }
        return res;
    }

    public R visitGenerate(Generate r, C ctx) {
        R res = defaultResult(r, ctx);
        res = aggregateResult(res, visit(r.generator(), ctx));
        res = aggregateResult(res, visitRelation(r.child(), ctx));
        return res;
    }

    public R visitValues(Values r, C ctx) {
        R res = defaultResult(r, ctx);
        for (List<Expression> row : r.rows()) for (Expression e : row) res = aggregateResult(res, visit(e, ctx));
        return res;
    }

    public R visitCTERelation(CTERelation r, C ctx) { return visitRelation(r.definition(), ctx); }

    public R visitSubqueryAlias(SubqueryAlias r, C ctx) { return visitRelation(r.child(), ctx); }
}
