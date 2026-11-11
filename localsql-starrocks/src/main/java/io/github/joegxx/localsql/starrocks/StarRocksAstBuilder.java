package io.github.joegxx.localsql.starrocks;

import com.starrocks.sql.parser.StarRocksParser;
import com.starrocks.sql.parser.StarRocksParser.*;
import io.github.joegxx.localsql.ir.expression.Alias;
import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;
import io.github.joegxx.localsql.ir.expression.Star;
import io.github.joegxx.localsql.ir.relation.*;
import io.github.joegxx.localsql.ir.statement.QueryStatement;
import io.github.joegxx.localsql.ir.statement.Statement;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;

/**
 * StarRocks ParseTree -> IR。M1 范围是 DQL（queryStatement）；其余语句族在
 * AdminStatementHandler（管理语句）或后续里程碑接入，这里显式拒绝。
 * 三段名（catalog.db.table）原样进 IR：analyzer 的 resolveTableScan 取后两段。
 */
public final class StarRocksAstBuilder {

    private final StarRocksExpressionBuilder exprBuilder = new StarRocksExpressionBuilder(this);

    public Statement buildStatement(String sql, StarRocksParserAccess access) {
        SingleStatementContext ctx = access.parse(sql);
        return visitSingleStatement(ctx);
    }

    Statement visitSingleStatement(SingleStatementContext ctx) {
        StatementContext stmt = ctx.statement();
        // unlabeled alternatives share the base StatementContext; the DQL alt
        // is detected by its nested queryStatement() accessor
        if (stmt.queryStatement() != null) {
            return new QueryStatement(visitQueryStatement(stmt.queryStatement()));
        }
        throw new UnsupportedOperationException(
                "StarRocks front-end currently supports DQL only. Got: " + stmt.getText());
    }

    private Relation visitQueryStatement(QueryStatementContext ctx) {
        if (ctx.explainDesc() != null || ctx.optimizerTrace() != null) {
            throw new UnsupportedOperationException("EXPLAIN/TRACE is not supported yet");
        }
        if (ctx.outfile() != null) {
            throw new UnsupportedOperationException("INTO OUTFILE is not supported");
        }
        return visitQueryRelation(ctx.queryRelation());
    }

    Relation visitQueryRelation(QueryRelationContext ctx) {
        Relation body = visitQueryNoWith(ctx.queryNoWith());
        if (ctx.withClause() != null) body = visitWithClause(ctx.withClause(), body);
        return body;
    }

    private Relation visitWithClause(WithClauseContext ctx, Relation body) {
        List<CTERelation> ctes = new ArrayList<>();
        for (CommonTableExpressionContext cte : ctx.commonTableExpression()) {
            if (cte.columnAliases() != null) {
                throw new UnsupportedOperationException("CTE column aliases are not supported");
            }
            String name = unquote(cte.name.getText());
            Relation def = visitQueryRelation(cte.queryRelation());
            ctes.add(new CTERelation(name, def, def.output()));
        }
        return new With(ctes, body);
    }

    private Relation visitQueryNoWith(QueryNoWithContext ctx) {
        Relation input = visitQueryPrimary(ctx.queryPrimary());
        if (ctx.ORDER() != null) {
            List<Sort.SortOrder> orders = new ArrayList<>();
            for (SortItemContext si : ctx.sortItem()) orders.add(exprBuilder.sortItem(si));
            input = new Sort(input, orders);
        }
        if (ctx.limitElement() != null) input = visitLimit(ctx.limitElement(), input);
        return input;
    }

    private Relation visitLimit(LimitElementContext ctx, Relation input) {
        // LIMIT n [OFFSET m] | LIMIT offset ',' limit
        long limit = limitValue(ctx.limit);
        long offset = ctx.offset != null ? limitValue(ctx.offset) : 0;
        return new Limit(input, limit, offset);
    }

    private long limitValue(LimitConstExprContext ctx) {
        if (ctx.INTEGER_VALUE() != null) return Long.parseLong(ctx.INTEGER_VALUE().getText());
        throw new UnsupportedOperationException("LIMIT must be an integer literal, got: " + ctx.getText());
    }

    private Relation visitQueryPrimary(QueryPrimaryContext ctx) {
        if (ctx instanceof QueryPrimaryDefaultContext c) {
            return visitQuerySpecification(c.querySpecification());
        }
        if (ctx instanceof QueryWithParenthesesContext c) {
            return visitQueryRelation(c.subquery().queryRelation());
        }
        if (ctx instanceof SetOperationContext c) {
            Relation left = visitQueryPrimary(c.left);
            Relation right = visitQueryPrimary(c.right);
            boolean distinct = c.setQuantifier() == null || c.setQuantifier().DISTINCT() != null;
            int op = c.operator.getType();
            if (op == StarRocksParser.UNION) return new Union(List.of(left, right), distinct, Union.Kind.UNION);
            if (op == StarRocksParser.INTERSECT) return new Union(List.of(left, right), distinct, Union.Kind.INTERSECT);
            // MINUS is StarRocks's alias for EXCEPT (Oracle style)
            if (op == StarRocksParser.EXCEPT || op == StarRocksParser.MINUS) {
                return new Union(List.of(left, right), distinct, Union.Kind.EXCEPT);
            }
        }
        throw new IllegalStateException("Unknown queryPrimary: " + ctx.getClass().getSimpleName());
    }

    private Relation visitQuerySpecification(QuerySpecificationContext ctx) {
        if (ctx.QUALIFY() != null) {
            throw new UnsupportedOperationException("QUALIFY is not supported; use a subquery with WHERE");
        }
        Relation input = null;
        if (ctx.fromClause() instanceof FromContext from) {
            if (from.pivotClause() != null) {
                throw new UnsupportedOperationException("PIVOT is not supported");
            }
            // FROM-less queries (SELECT 1) still produce a FromContext with a
            // null relations element
            if (from.relations() != null) input = visitRelations(from.relations());
        } else if (ctx.fromClause() instanceof DualContext) {
            input = null; // FROM DUAL behaves like no FROM
        }

        if (ctx.where != null && input != null) {
            input = new Filter(input, exprBuilder.visit(ctx.where));
        }

        List<Expression> selectItems = new ArrayList<>();
        boolean distinct = ctx.setQuantifier() != null && ctx.setQuantifier().DISTINCT() != null;
        for (SelectItemContext si : ctx.selectItem()) selectItems.add(visitSelectItem(si));

        boolean aggregated = false;
        if (ctx.groupingElement() != null && input != null) {
            input = buildAggregate(ctx.groupingElement(), input, selectItems);
            aggregated = true;
        }
        if (ctx.having != null && input != null) {
            Expression having = exprBuilder.visit(ctx.having);
            if (aggregated && input instanceof Aggregate aggregate) {
                input = new Aggregate(aggregate.child(), aggregate.groupingExpressions(),
                        aggregate.resultExpressions(), having, aggregate.groupingAnalytics());
            } else if (!aggregated) {
                // HAVING without GROUP BY: implicit single-group aggregate
                input = new Aggregate(input, List.of(), selectItems, having);
                aggregated = true;
            }
        }

        if (input == null) {
            return new Project(new Values(List.of(List.of())), selectItems, distinct);
        }
        // When aggregated, selectItems live inside the Aggregate node; a query
        // whose FROM is an aggregated subquery still needs its own Project
        return aggregated ? input : new Project(input, selectItems, distinct);
    }

    private Relation buildAggregate(GroupingElementContext ge, Relation input, List<Expression> selectItems) {
        List<Expression> grouping = new ArrayList<>();
        List<Aggregate.GroupingAnalytics> analytics = new ArrayList<>();
        if (ge instanceof SingleGroupingSetContext s) {
            for (ExpressionContext e : s.expressionList().expression()) grouping.add(exprBuilder.visit(e));
        } else if (ge instanceof RollupContext r) {
            analytics.add(Aggregate.GroupingAnalytics.rollup(exprList(r.expressionList())));
        } else if (ge instanceof CubeContext cu) {
            analytics.add(Aggregate.GroupingAnalytics.cube(exprList(cu.expressionList())));
        } else if (ge instanceof MultipleGroupingSetsContext m) {
            List<List<Expression>> sets = new ArrayList<>();
            for (GroupingSetContext gs : m.groupingSet()) {
                sets.add(exprList(gs.expression()));
            }
            analytics.add(Aggregate.GroupingAnalytics.groupingSets(sets));
        }
        return new Aggregate(input, grouping, selectItems, null, analytics);
    }

    private List<Expression> exprList(ExpressionListContext ctx) {
        List<Expression> out = new ArrayList<>();
        if (ctx != null) for (ExpressionContext e : ctx.expression()) out.add(exprBuilder.visit(e));
        return out;
    }

    private List<Expression> exprList(List<ExpressionContext> exprs) {
        List<Expression> out = new ArrayList<>();
        if (exprs != null) for (ExpressionContext e : exprs) out.add(exprBuilder.visit(e));
        return out;
    }

    private Expression visitSelectItem(SelectItemContext ctx) {
        if (ctx instanceof SelectSingleContext c) {
            Expression e = exprBuilder.visit(c.expression());
            if (c.identifier() != null) return new Alias(e, unquote(c.identifier().getText()), List.of());
            if (c.string() != null) return new Alias(e, StarRocksExpressionBuilder.text(c.string()), List.of());
            return e;
        }
        if (ctx instanceof SelectAllContext c) {
            List<String> q = new ArrayList<>();
            if (c.qualifiedName() != null) q.addAll(qualifiedNameParts(c.qualifiedName()));
            return new Star(q);
        }
        throw new IllegalStateException("Unknown selectItem: " + ctx.getClass().getSimpleName());
    }

    private Relation visitRelations(RelationsContext ctx) {
        for (int i = 0; i < ctx.relation().size(); i++) {
            if (ctx.LATERAL(i) != null) {
                throw new UnsupportedOperationException("LATERAL join is not supported");
            }
        }
        Relation left = visitRelation(ctx.relation(0));
        for (int i = 1; i < ctx.relation().size(); i++) {
            left = new Join(left, visitRelation(ctx.relation(i)), Join.JoinType.CROSS, null, List.of());
        }
        return left;
    }

    private Relation visitRelation(RelationContext ctx) {
        Relation left = visitRelationPrimary(ctx.relationPrimary());
        for (JoinRelationContext jr : ctx.joinRelation()) {
            left = visitJoinRelation(jr, left);
        }
        return left;
    }

    private Relation visitJoinRelation(JoinRelationContext jr, Relation left) {
        if (jr.LATERAL() != null) {
            throw new UnsupportedOperationException("LATERAL join is not supported");
        }
        Relation right = visitRelationPrimary(jr.rightRelation);
        Expression cond = null;
        List<String> usingColumns = List.of();
        if (jr.joinCriteria() != null) {
            if (jr.joinCriteria().ON() != null) {
                cond = exprBuilder.visit(jr.joinCriteria().expression());
            } else {
                usingColumns = new ArrayList<>();
                for (IdentifierContext id : jr.joinCriteria().identifier()) {
                    usingColumns.add(unquote(id.getText()));
                }
            }
        }
        if (jr.crossOrInnerJoinType() != null) {
            Join.JoinType jt = jr.crossOrInnerJoinType().CROSS() != null
                    ? Join.JoinType.CROSS : Join.JoinType.INNER;
            return new Join(left, right, jt, cond, usingColumns);
        }
        OuterAndSemiJoinTypeContext o = jr.outerAndSemiJoinType();
        // IR has only LEFT_SEMI/LEFT_ANTI; RIGHT SEMI/ANTI swap the sides
        if (o.SEMI() != null) {
            return o.RIGHT() != null
                    ? new Join(right, left, Join.JoinType.LEFT_SEMI, cond, usingColumns)
                    : new Join(left, right, Join.JoinType.LEFT_SEMI, cond, usingColumns);
        }
        if (o.ANTI() != null) {
            return o.RIGHT() != null
                    ? new Join(right, left, Join.JoinType.LEFT_ANTI, cond, usingColumns)
                    : new Join(left, right, Join.JoinType.LEFT_ANTI, cond, usingColumns);
        }
        Join.JoinType jt;
        if (o.FULL() != null) jt = Join.JoinType.FULL;
        else if (o.RIGHT() != null) jt = Join.JoinType.RIGHT;
        else jt = Join.JoinType.LEFT;
        return new Join(left, right, jt, cond, usingColumns);
    }

    private Relation visitRelationPrimary(RelationPrimaryContext ctx) {
        if (ctx instanceof TableAtomContext c) {
            if (c.queryPeriod() != null) {
                throw new UnsupportedOperationException("Temporal queries (FOR SYSTEM_TIME) are not supported");
            }
            if (c.partitionNames() != null || c.tabletList() != null
                    || c.replicaList() != null || c.sampleClause() != null || c.BEFORE() != null) {
                throw new UnsupportedOperationException("Table suffix modifiers are not supported: " + ctx.getText());
            }
            TableScan scan = scan(qualifiedNameParts(c.qualifiedName()));
            return alias(scan, c.alias);
        }
        if (ctx instanceof InlineTableContext c) {
            if (c.columnAliases() != null) {
                throw new UnsupportedOperationException("VALUES column aliases are not supported");
            }
            List<List<Expression>> rows = new ArrayList<>();
            for (RowConstructorContext rc : c.rowConstructor()) {
                rows.add(exprList(rc.expressionList()));
            }
            return alias(new Values(rows), c.alias);
        }
        if (ctx instanceof SubqueryWithAliasContext c) {
            if (c.columnAliases() != null) {
                throw new UnsupportedOperationException("Derived-table column aliases are not supported");
            }
            Relation q = visitQueryRelation(c.subquery().queryRelation());
            return alias(q, c.alias);
        }
        if (ctx instanceof TableFunctionContext || ctx instanceof NormalizedTableFunctionContext
                || ctx instanceof FileTableFunctionContext) {
            throw new UnsupportedOperationException("Table functions are not supported: " + ctx.getText());
        }
        if (ctx instanceof ParenthesizedRelationContext c) {
            return visitRelations(c.relations());
        }
        throw new IllegalStateException("Unknown relationPrimary: " + ctx.getClass().getSimpleName());
    }

    private Relation alias(Relation r, IdentifierContext alias) {
        if (alias == null) return r;
        String aliasName = unquote(alias.getText());
        if (aliasName == null) return r;
        if (r instanceof TableScan t) {
            return new TableScan(t.tableName(), t.output(), aliasName);
        }
        return new SubqueryAlias(r, aliasName);
    }

    private TableScan scan(List<String> parts) {
        return new TableScan(parts, new ArrayList<>());
    }

    static List<String> qualifiedNameParts(QualifiedNameContext q) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < q.getChildCount(); i++) {
            ParseTree child = q.getChild(i);
            if (child instanceof IdentifierContext id) {
                out.add(unquote(id.getText()));
            } else if (child instanceof TerminalNode tn
                    && tn.getSymbol().getType() == StarRocksParser.DOT_IDENTIFIER) {
                // '.123abc' digit-identifier segments carry the leading dot
                out.add(unquote(tn.getText().substring(1)));
            }
        }
        return out;
    }

    static String unquote(String text) {
        if (text == null) return null;
        if (text.length() >= 2 && text.startsWith("`") && text.endsWith("`")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    public interface StarRocksParserAccess {
        SingleStatementContext parse(String sql);
    }
}
