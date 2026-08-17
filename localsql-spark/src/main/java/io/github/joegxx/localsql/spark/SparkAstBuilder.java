package io.github.joegxx.localsql.spark;

import io.github.joegxx.localsql.ir.expression.*;
import io.github.joegxx.localsql.ir.relation.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser.*;

import java.util.ArrayList;
import java.util.List;

public final class SparkAstBuilder {

    private final SparkExpressionBuilder exprBuilder = new SparkExpressionBuilder(this);

    public Relation buildStatement(String sql, SparkSqlParserAccess parserAccess) {
        SingleStatementContext ctx = parserAccess.parse(sql);
        return visitSingleStatement(ctx);
    }

    Relation visitSingleStatement(SingleStatementContext ctx) {
        StatementContext stmt = ctx.statement();
        if (stmt instanceof StatementDefaultContext sd) {
            return visitQuery(sd.query());
        }
        throw new UnsupportedOperationException("MVP only supports DQL (SELECT). Got: " + stmt.getText());
    }

    Relation visitQuery(QueryContext ctx) {
        Relation body = visitQueryTerm(ctx.queryTerm());
        body = visitQueryOrganization(ctx.queryOrganization(), body);
        if (ctx.ctes() != null) body = visitCtes(ctx.ctes(), body);
        return body;
    }

    private Relation visitCtes(CtesContext ctx, Relation body) {
        List<CTERelation> ctes = new ArrayList<>();
        for (NamedQueryContext nq : ctx.namedQuery()) {
            String name = nq.name.getText();
            Relation def = visitQuery(nq.query());
            ctes.add(new CTERelation(name, def, def.output()));
        }
        return new With(ctes, body);
    }

    Relation visitQueryTerm(QueryTermContext ctx) {
        if (ctx instanceof QueryTermDefaultContext c) return visitQueryPrimary(c.queryPrimary());
        if (ctx instanceof SetOperationContext c) {
            Relation left = visitQueryTerm(c.left);
            Relation right = visitQueryTerm(c.right);
            int op = c.operator.getType();
            boolean distinct = c.setQuantifier() == null || c.setQuantifier().DISTINCT() != null;
            if (op == SqlBaseParser.UNION) return new Union(List.of(left, right), distinct);
            if (op == SqlBaseParser.INTERSECT) return new Union(List.of(left, right), true);
            if (op == SqlBaseParser.EXCEPT || op == SqlBaseParser.SETMINUS) return new Union(List.of(left, right), true);
        }
        throw new IllegalStateException("Unknown queryTerm: " + ctx.getClass().getSimpleName());
    }

    private Relation visitQueryPrimary(QueryPrimaryContext ctx) {
        if (ctx instanceof QueryPrimaryDefaultContext c) return visitQuerySpecification(c.querySpecification());
        if (ctx instanceof SubqueryContext c) return visitQuery(c.query());
        if (ctx instanceof TableContext c) return scan(parts(c.multipartIdentifier()));
        if (ctx instanceof InlineTableDefault1Context c) return visitInlineTable(c.inlineTable());
        throw new IllegalStateException("Unknown queryPrimary: " + ctx.getClass().getSimpleName());
    }

    private Relation visitQuerySpecification(QuerySpecificationContext ctx) {
        if (!(ctx instanceof RegularQuerySpecificationContext reg)) {
            throw new UnsupportedOperationException("Transform query not supported in MVP");
        }
        SelectClauseContext select = reg.selectClause();
        FromClauseContext from = reg.fromClause();
        WhereClauseContext where = reg.whereClause();
        AggregationClauseContext agg = reg.aggregationClause();
        HavingClauseContext having = reg.havingClause();

        Relation input = from == null ? null : visitFrom(from);

        if (reg.lateralView() != null && !reg.lateralView().isEmpty()) {
            for (LateralViewContext lv : reg.lateralView()) {
                input = visitLateralView(lv, input);
            }
        }

        if (where != null && input != null) {
            input = new Filter(input, exprBuilder.visit(where.booleanExpression()));
        }

        List<Expression> selectItems = new ArrayList<>();
        boolean distinct = select.setQuantifier() != null && select.setQuantifier().DISTINCT() != null;
        for (NamedExpressionContext ne : select.namedExpressionSeq().namedExpression()) {
            selectItems.add(visitNamedExpression(ne));
        }

        if (agg != null && input != null) {
            input = visitAggregate(agg, input, selectItems);
        }
        if (having != null && input instanceof Aggregate aggregate) {
            input = new Aggregate(aggregate.child(), aggregate.groupingExpressions(),
                    aggregate.aggregateExpressions(), exprBuilder.visit(having.booleanExpression()));
        } else if (having != null && input != null) {
            input = new Aggregate(input, List.of(), selectItems, exprBuilder.visit(having.booleanExpression()));
        }

        if (input == null) {
            return new Project(new Values(List.of(List.of())), selectItems);
        }
        if (agg != null || input instanceof Aggregate) return input;
        return new Project(input, selectItems);
    }

    private Relation visitLateralView(LateralViewContext lv, Relation input) {
        List<String> nameParts = SparkExpressionBuilder.parts(lv.qualifiedName());
        String fn = String.join(".", nameParts);
        List<Expression> args = new ArrayList<>();
        if (lv.expression() != null) for (ExpressionContext e : lv.expression()) args.add(exprBuilder.visit(e));
        FunctionCall gen = new FunctionCall(fn, args);
        List<String> outs = new ArrayList<>();
        if (lv.colName != null) for (var id : lv.colName) outs.add(id.getText());
        if (outs.isEmpty() && lv.tblName != null) outs.add(lv.tblName.getText());
        return new Generate(gen, outs, input);
    }

    private Relation visitAggregate(AggregationClauseContext agg, Relation input, List<Expression> selectItems) {
        List<Expression> grouping = new ArrayList<>();
        if (agg.groupingExpressions != null) {
            for (ExpressionContext e : agg.groupingExpressions) grouping.add(exprBuilder.visit(e));
        }
        if (agg.groupingExpressionsWithGroupingAnalytics != null) {
            for (GroupByClauseContext gbc : agg.groupingExpressionsWithGroupingAnalytics) {
                if (gbc.expression() != null) grouping.add(exprBuilder.visit(gbc.expression()));
                else throw new UnsupportedOperationException("ROLLUP/CUBE/GROUPING SETS not in MVP");
            }
        }
        return new Aggregate(input, grouping, selectItems);
    }

    private Relation visitFrom(FromClauseContext from) {
        Relation left = visitRelation(from.relation(0));
        for (int i = 1; i < from.relation().size(); i++) {
            Relation right = visitRelation(from.relation(i));
            left = new Join(left, right, Join.JoinType.CROSS, null, false);
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
        Relation right = visitRelationPrimary(jr.right);
        Join.JoinType jt = Join.JoinType.INNER;
        if (jr.joinType() != null) jt = mapJoinType(jr.joinType());
        if (jr.NATURAL() != null) jt = Join.JoinType.CROSS;
        Expression cond = null;
        boolean using = false;
        if (jr.joinCriteria() != null) {
            if (jr.joinCriteria().ON() != null) cond = exprBuilder.visit(jr.joinCriteria().booleanExpression());
            else using = true;
        }
        return new Join(left, right, jt, cond, using);
    }

    private Join.JoinType mapJoinType(JoinTypeContext jt) {
        if (jt.CROSS() != null) return Join.JoinType.CROSS;
        if (jt.LEFT() != null && jt.SEMI() != null) return Join.JoinType.LEFT_SEMI;
        if (jt.LEFT() != null && jt.ANTI() != null) return Join.JoinType.LEFT_ANTI;
        if (jt.LEFT() != null) return Join.JoinType.LEFT;
        if (jt.RIGHT() != null) return Join.JoinType.RIGHT;
        if (jt.FULL() != null) return Join.JoinType.FULL;
        if (jt.SEMI() != null) return Join.JoinType.LEFT_SEMI;
        if (jt.ANTI() != null) return Join.JoinType.LEFT_ANTI;
        return Join.JoinType.INNER;
    }

    private Relation visitRelationPrimary(RelationPrimaryContext ctx) {
        if (ctx instanceof TableNameContext c) {
            TableScan scan = scan(parts(c.multipartIdentifier()));
            return alias(scan, c.tableAlias());
        }
        if (ctx instanceof AliasedQueryContext c) {
            Relation q = visitQuery(c.query());
            return alias(q, c.tableAlias());
        }
        if (ctx instanceof AliasedRelationContext c) {
            Relation r = visitRelation(c.relation());
            return alias(r, c.tableAlias());
        }
        if (ctx instanceof InlineTableDefault2Context c) {
            return visitInlineTable(c.inlineTable());
        }
        throw new UnsupportedOperationException("relationPrimary not supported: " + ctx.getClass().getSimpleName());
    }

    private Relation visitInlineTable(InlineTableContext c) {
        List<List<Expression>> rows = new ArrayList<>();
        for (ExpressionContext e : c.expression()) rows.add(List.of(exprBuilder.visit(e)));
        return alias(new Values(rows), c.tableAlias());
    }

    private Relation alias(Relation r, TableAliasContext alias) {
        if (alias == null) return r;
        String aliasName = null;
        if (alias.strictIdentifier() != null) aliasName = alias.strictIdentifier().getText();
        if (r instanceof TableScan t) {
            return new TableScan(t.tableName(), t.output(), aliasName);
        }
        return new SubqueryAlias(r, aliasName);
    }

    private Relation visitQueryOrganization(QueryOrganizationContext org, Relation input) {
        if (org.order != null && !org.order.isEmpty()) {
            List<Sort.SortOrder> orders = new ArrayList<>();
            for (SortItemContext si : org.order) {
                Expression e = exprBuilder.visit(si.expression());
                boolean asc = si.ordering == null || si.ordering.getType() == SqlBaseParser.ASC;
                boolean nullsLast = si.nullOrder == null || si.nullOrder.getType() == SqlBaseParser.LAST;
                orders.add(new Sort.SortOrder(e, asc, nullsLast));
            }
            input = new Sort(input, orders);
        }
        if (org.limit != null) {
            Expression lim = exprBuilder.visit(org.limit);
            if (lim instanceof Literal l && l.kind() == Literal.Kind.INTEGER) {
                input = new Limit(input, (Long) l.value(), 0);
            }
        }
        return input;
    }

    Expression visitNamedExpression(NamedExpressionContext ne) {
        Expression e = exprBuilder.visit(ne.expression());
        if (ne.name != null) {
            return new Alias(e, ne.name.getText(), List.of());
        }
        if (ne.identifierList() != null) {
            List<String> names = new ArrayList<>();
            for (ErrorCapturingIdentifierContext id : ne.identifierList().identifierSeq().ident) names.add(id.getText());
            return new Alias(e, names.isEmpty() ? e.toString() : names.get(0), names);
        }
        return e;
    }

    private TableScan scan(List<String> parts) {
        List<AttributeReference> out = new ArrayList<>();
        return new TableScan(parts, out);
    }

    private static List<String> parts(MultipartIdentifierContext mi) {
        List<String> out = new ArrayList<>();
        for (ErrorCapturingIdentifierContext id : mi.parts) out.add(id.getText());
        return out;
    }

    public interface SparkSqlParserAccess {
        SingleStatementContext parse(String sql);
    }
}
