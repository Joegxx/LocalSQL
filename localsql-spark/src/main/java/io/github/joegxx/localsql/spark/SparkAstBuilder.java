package io.github.joegxx.localsql.spark;

import io.github.joegxx.localsql.ir.expression.*;
import io.github.joegxx.localsql.ir.relation.*;
import io.github.joegxx.localsql.ir.statement.Statement;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser.*;

import java.util.ArrayList;
import java.util.List;

public final class SparkAstBuilder {

    private final SparkExpressionBuilder exprBuilder = new SparkExpressionBuilder(this);

    public io.github.joegxx.localsql.ir.statement.Statement buildStatement(String sql, SparkSqlParserAccess parserAccess) {
        SingleStatementContext ctx = parserAccess.parse(sql);
        return visitSingleStatement(ctx);
    }

    io.github.joegxx.localsql.ir.statement.Statement visitSingleStatement(SingleStatementContext ctx) {
        StatementContext stmt = ctx.statement();
        if (stmt instanceof StatementDefaultContext sd) {
            return new io.github.joegxx.localsql.ir.statement.QueryStatement(visitQuery(sd.query()));
        }
        if (stmt instanceof DmlStatementContext dml) return buildInsert(dml);
        if (stmt instanceof CreateTableContext ct) return buildCreateTable(ct);
        if (stmt instanceof CreateTableLikeContext ctl) return buildCreateTableLike(ctl);
        if (stmt instanceof CreateViewContext cv) return buildCreateView(cv);
        if (stmt instanceof DropTableContext dt) return buildDrop(dt);
        if (stmt instanceof DropViewContext dv) return buildDrop(dv);
        if (stmt instanceof CreateNamespaceContext cns) return buildCreateDatabase(cns);
        if (stmt instanceof DropNamespaceContext dns) return buildDropDatabase(dns);
        if (stmt instanceof SetNamespacePropertiesContext snp) return buildAlterDatabaseProps(snp);
        if (stmt instanceof SetNamespaceLocationContext snl) return buildAlterDatabaseLocation(snl);
        if (stmt instanceof RenameTableContext rt) return buildRenameTable(rt);
        if (stmt instanceof SetTablePropertiesContext stp) return buildSetTableProperties(stp);
        if (stmt instanceof UnsetTablePropertiesContext utp) return buildUnsetTableProperties(utp);
        if (stmt instanceof AddTableColumnsContext atc) return buildAddColumns(atc);
        if (stmt instanceof DropTableColumnsContext dtc) return buildDropColumns(dtc);
        if (stmt instanceof RenameTableColumnContext rtc) return buildRenameColumn(rtc);
        if (stmt instanceof AlterTableAlterColumnContext atac) return buildAlterColumn(atac);
        if (stmt instanceof HiveChangeColumnContext hcc) return buildHiveChangeColumn(hcc);
        if (stmt instanceof HiveReplaceColumnsContext hrc) return buildReplaceColumns(hrc);
        throw new UnsupportedOperationException("MVP only supports DQL and basic DDL/DML. Got: " + stmt.getText());
    }

    private io.github.joegxx.localsql.ir.statement.DropTable buildDrop(DropTableContext dt) {
        if (dt.PURGE() != null) {
            throw new UnsupportedOperationException("DROP TABLE PURGE is not supported");
        }
        return new io.github.joegxx.localsql.ir.statement.DropTable(
                parts(dt.multipartIdentifier()), false, dt.IF() != null);
    }

    private io.github.joegxx.localsql.ir.statement.DropTable buildDrop(DropViewContext dv) {
        return new io.github.joegxx.localsql.ir.statement.DropTable(
                parts(dv.multipartIdentifier()), true, dv.IF() != null);
    }

    private io.github.joegxx.localsql.ir.statement.Insert buildInsert(DmlStatementContext dml) {
        DmlStatementNoWithContext noWith = dml.dmlStatementNoWith();
        if (!(noWith instanceof SingleInsertQueryContext sq)) {
            throw new UnsupportedOperationException(
                    "Only single-target INSERT is supported (multi-insert / DELETE / UPDATE / MERGE are not)");
        }
        InsertIntoContext i = sq.insertInto();
        if (i instanceof InsertOverwriteHiveDirContext || i instanceof InsertOverwriteDirContext) {
            throw new UnsupportedOperationException("INSERT OVERWRITE DIRECTORY is not supported");
        }
        MultipartIdentifierContext target;
        boolean overwrite;
        if (i instanceof InsertOverwriteTableContext ow) {
            overwrite = true;
            target = ow.multipartIdentifier();
            if (ow.partitionSpec() != null) {
                throw new UnsupportedOperationException("INSERT with PARTITION spec is not supported");
            }
            if (ow.identifierList() != null) {
                throw new UnsupportedOperationException(
                        "INSERT OVERWRITE with column list is not supported");
            }
        } else if (i instanceof InsertIntoTableContext into) {
            overwrite = false;
            target = into.multipartIdentifier();
            if (into.partitionSpec() != null) {
                throw new UnsupportedOperationException("INSERT with PARTITION spec is not supported");
            }
            if (into.IF() != null) {
                throw new UnsupportedOperationException("INSERT IF NOT EXISTS is not supported");
            }
        } else {
            throw new UnsupportedOperationException("Unsupported INSERT form");
        }
        List<String> cols = null;
        if (i instanceof InsertIntoTableContext into && into.identifierList() != null) {
            cols = new ArrayList<>();
            for (ErrorCapturingIdentifierContext id : into.identifierList().identifierSeq().ident) {
                cols.add(SparkExpressionBuilder.unquote(id.getText()));
            }
        }
        Relation body = visitQueryTerm(sq.queryTerm());
        body = visitQueryOrganization(sq.queryOrganization(), body);
        if (dml.ctes() != null) body = visitCtes(dml.ctes(), body);
        return new io.github.joegxx.localsql.ir.statement.Insert(
                parts(target), overwrite, cols, body);
    }

    private io.github.joegxx.localsql.ir.statement.CreateTable buildCreateTable(CreateTableContext ct) {
        CreateTableHeaderContext header = ct.createTableHeader();
        if (header.TEMPORARY() != null) {
            throw new UnsupportedOperationException("CREATE TEMPORARY TABLE is not supported");
        }
        if (header.EXTERNAL() != null) {
            throw new UnsupportedOperationException("CREATE EXTERNAL TABLE is not supported");
        }
        CreateTableClausesContext clauses = ct.createTableClauses();
        // SKEWED BY / CLUSTERED BY (bucketing) are storage hints with no effect
        // on DuckDB (no bucketing concept); accept and ignore them.
        boolean ifNotExists = header.IF() != null;
        List<io.github.joegxx.localsql.ir.statement.CreateTable.ColumnDef> columns = new ArrayList<>();
        if (ct.colTypeList() != null) {
            for (ColTypeContext c : ct.colTypeList().colType()) {
                columns.add(new io.github.joegxx.localsql.ir.statement.CreateTable.ColumnDef(
                        SparkExpressionBuilder.unquote(c.colName.getText()),
                        SparkDataTypeBuilder.build(c.dataType()),
                        c.NOT() == null,
                        comment(c.commentSpec())));
            }
        }
        // PARTITIONED BY: for a plain DDL the partition columns (with a type)
        // are appended to the schema (Spark puts partition columns last); bare
        // references must already exist in the declared schema. For CTAS the
        // clause is a storage hint on DuckDB (no partitioning concept) and is
        // accepted as a no-op — the query's own output already provides the
        // column; we skip the schema mutation entirely.
        List<String> partitionColumns = new ArrayList<>();
        if (clauses != null && !clauses.partitionFieldList().isEmpty() && ct.query() == null) {
            for (PartitionFieldContext pf : clauses.partitionFieldList().get(0).partitionField()) {
                if (pf instanceof PartitionTransformContext t) {
                    if (t.transform() instanceof IdentityTransformContext id) {
                        String name = SparkExpressionBuilder.parts(id.qualifiedName()).get(
                                SparkExpressionBuilder.parts(id.qualifiedName()).size() - 1);
                        if (columns.stream().noneMatch(c -> c.name().equalsIgnoreCase(name))) {
                            throw new IllegalArgumentException(
                                    "PARTITIONED BY column not defined in table schema: " + name);
                        }
                        partitionColumns.add(name);
                    } else {
                        throw new UnsupportedOperationException(
                                "Partition transforms (e.g. years(dt)) are not supported");
                    }
                } else if (pf instanceof PartitionColumnContext pc) {
                    String name = SparkExpressionBuilder.unquote(pc.colType().colName.getText());
                    columns.add(new io.github.joegxx.localsql.ir.statement.CreateTable.ColumnDef(
                            name,
                            SparkDataTypeBuilder.build(pc.colType().dataType()),
                            pc.colType().NOT() == null,
                            comment(pc.colType().commentSpec())));
                    partitionColumns.add(name);
                }
            }
        }
        Relation query = ct.query() != null ? visitQuery(ct.query()) : null;
        if (columns.isEmpty() && query == null) {
            throw new IllegalArgumentException("CREATE TABLE requires a column list or AS query");
        }
        return new io.github.joegxx.localsql.ir.statement.CreateTable(
                parts(header.multipartIdentifier()), ifNotExists, columns, query, null, partitionColumns);
    }

    private io.github.joegxx.localsql.ir.statement.CreateTable buildCreateTableLike(CreateTableLikeContext ctl) {
        boolean ifNotExists = ctl.IF() != null;
        List<String> target = new ArrayList<>();
        if (ctl.target.db != null) target.add(SparkExpressionBuilder.unquote(ctl.target.db.getText()));
        target.add(SparkExpressionBuilder.unquote(ctl.target.table.getText()));
        List<String> source = new ArrayList<>();
        if (ctl.source.db != null) source.add(SparkExpressionBuilder.unquote(ctl.source.db.getText()));
        source.add(SparkExpressionBuilder.unquote(ctl.source.table.getText()));
        return new io.github.joegxx.localsql.ir.statement.CreateTable(
                target, ifNotExists, List.of(), null, source);
    }

    private io.github.joegxx.localsql.ir.statement.CreateView buildCreateView(CreateViewContext cv) {
        if (cv.TEMPORARY() != null) {
            throw new UnsupportedOperationException("CREATE TEMPORARY VIEW is not supported");
        }
        if (cv.GLOBAL() != null) {
            throw new UnsupportedOperationException("CREATE GLOBAL TEMPORARY VIEW is not supported");
        }
        if (cv.identifierCommentList() != null) {
            throw new UnsupportedOperationException("CREATE VIEW with column alias list is not supported");
        }
        if (!cv.PARTITIONED().isEmpty()) {
            throw new UnsupportedOperationException("CREATE VIEW ... PARTITIONED ON is not supported");
        }
        boolean orReplace = cv.OR() != null;
        boolean ifNotExists = cv.IF() != null;
        if (cv.query() == null) {
            throw new IllegalArgumentException("CREATE VIEW requires AS query");
        }
        return new io.github.joegxx.localsql.ir.statement.CreateView(
                parts(cv.multipartIdentifier()), orReplace, ifNotExists, visitQuery(cv.query()));
    }

    private io.github.joegxx.localsql.ir.statement.CreateDatabase buildCreateDatabase(CreateNamespaceContext cns) {
        String name = parts(cns.multipartIdentifier()).get(parts(cns.multipartIdentifier()).size() - 1);
        boolean ifNotExists = cns.IF() != null;
        String comment = null;
        if (!cns.commentSpec().isEmpty() && cns.commentSpec(0).STRING() != null) {
            comment = SparkExpressionBuilder.unquote(cns.commentSpec(0).STRING().getText());
        }
        String location = null;
        if (!cns.locationSpec().isEmpty() && cns.locationSpec(0).STRING() != null) {
            location = SparkExpressionBuilder.unquote(cns.locationSpec(0).STRING().getText());
        }
        java.util.Map<String, String> props = new java.util.HashMap<>();
        for (TablePropertyListContext tpl : cns.tablePropertyList()) {
            for (TablePropertyContext tp : tpl.tableProperty()) {
                String key = SparkExpressionBuilder.unquote(tp.key.getText());
                String val = tp.value != null ? SparkExpressionBuilder.unquote(tp.value.getText()) : "";
                props.put(key, val);
            }
        }
        return new io.github.joegxx.localsql.ir.statement.CreateDatabase(name, ifNotExists, comment, location, props);
    }

    private io.github.joegxx.localsql.ir.statement.DropDatabase buildDropDatabase(DropNamespaceContext dns) {
        String name = parts(dns.multipartIdentifier()).get(parts(dns.multipartIdentifier()).size() - 1);
        boolean ifExists = dns.IF() != null;
        boolean cascade = dns.CASCADE() != null;
        return new io.github.joegxx.localsql.ir.statement.DropDatabase(name, ifExists, cascade);
    }

    private io.github.joegxx.localsql.ir.statement.RenameTable buildRenameTable(RenameTableContext rt) {
        List<String> fromParts = parts(rt.from);
        List<String> toParts = parts(rt.to);
        if (toParts.size() == 1 && fromParts.size() >= 2) {
            String fromDb = fromParts.get(fromParts.size() - 2);
            toParts = List.of(fromDb, toParts.get(0));
        }
        return new io.github.joegxx.localsql.ir.statement.RenameTable(fromParts, toParts);
    }

    private io.github.joegxx.localsql.ir.statement.SetTableProperties buildSetTableProperties(SetTablePropertiesContext stp) {
        java.util.Map<String, String> props = new java.util.HashMap<>();
        if (stp.tablePropertyList() != null) {
            for (TablePropertyContext tp : stp.tablePropertyList().tableProperty()) {
                String key = SparkExpressionBuilder.unquote(tp.key.getText());
                String val = tp.value != null ? SparkExpressionBuilder.unquote(tp.value.getText()) : "";
                props.put(key, val);
            }
        }
        return new io.github.joegxx.localsql.ir.statement.SetTableProperties(parts(stp.multipartIdentifier()), props);
    }

    private io.github.joegxx.localsql.ir.statement.UnsetTableProperties buildUnsetTableProperties(UnsetTablePropertiesContext utp) {
        java.util.Map<String, String> props = new java.util.HashMap<>();
        if (utp.tablePropertyList() != null) {
            for (TablePropertyContext tp : utp.tablePropertyList().tableProperty()) {
                String key = SparkExpressionBuilder.unquote(tp.key.getText());
                String val = tp.value != null ? SparkExpressionBuilder.unquote(tp.value.getText()) : "";
                props.put(key, val);
            }
        }
        return new io.github.joegxx.localsql.ir.statement.UnsetTableProperties(parts(utp.multipartIdentifier()), props, utp.IF() != null);
    }

    private io.github.joegxx.localsql.ir.statement.AlterDatabase buildAlterDatabaseProps(SetNamespacePropertiesContext snp) {
        String name = parts(snp.multipartIdentifier()).get(parts(snp.multipartIdentifier()).size() - 1);
        java.util.Map<String, String> props = new java.util.HashMap<>();
        if (snp.tablePropertyList() != null) {
            for (TablePropertyContext tp : snp.tablePropertyList().tableProperty()) {
                String key = SparkExpressionBuilder.unquote(tp.key.getText());
                String val = tp.value != null ? SparkExpressionBuilder.unquote(tp.value.getText()) : "";
                props.put(key, val);
            }
        }
        return new io.github.joegxx.localsql.ir.statement.AlterDatabase(name, props, null);
    }

    private io.github.joegxx.localsql.ir.statement.AlterDatabase buildAlterDatabaseLocation(SetNamespaceLocationContext snl) {
        String name = parts(snl.multipartIdentifier()).get(parts(snl.multipartIdentifier()).size() - 1);
        String loc = null;
        if (snl.locationSpec() != null && snl.locationSpec().STRING() != null) {
            loc = SparkExpressionBuilder.unquote(snl.locationSpec().STRING().getText());
        }
        return new io.github.joegxx.localsql.ir.statement.AlterDatabase(name, java.util.Map.of(), loc);
    }

    private io.github.joegxx.localsql.ir.statement.AddColumns buildAddColumns(AddTableColumnsContext atc) {
        List<io.github.joegxx.localsql.ir.statement.AddColumns.ColDef> cols = new ArrayList<>();
        for (QualifiedColTypeWithPositionContext q : atc.qualifiedColTypeWithPositionList().qualifiedColTypeWithPosition()) {
            List<String> np = parts(q.multipartIdentifier());
            String colName = np.get(np.size() - 1);
            cols.add(new io.github.joegxx.localsql.ir.statement.AddColumns.ColDef(
                    colName, SparkDataTypeBuilder.build(q.dataType()), q.NOT() == null,
                    comment(q.commentSpec()), position(q.colPosition())));
        }
        return new io.github.joegxx.localsql.ir.statement.AddColumns(parts(atc.multipartIdentifier()), cols);
    }

    private io.github.joegxx.localsql.ir.statement.DropColumns buildDropColumns(DropTableColumnsContext dtc) {
        List<String> cols = new ArrayList<>();
        MultipartIdentifierListContext list = dtc.columns != null ? dtc.columns : dtc.multipartIdentifierList();
        if (list != null) {
            for (MultipartIdentifierContext mi : list.multipartIdentifier()) {
                List<String> p = parts(mi);
                cols.add(p.get(p.size() - 1));
            }
        }
        return new io.github.joegxx.localsql.ir.statement.DropColumns(parts(dtc.multipartIdentifier()), cols);
    }

    private io.github.joegxx.localsql.ir.statement.RenameColumn buildRenameColumn(RenameTableColumnContext rtc) {
        String from = parts(rtc.from).get(parts(rtc.from).size() - 1);
        String to = SparkExpressionBuilder.unquote(rtc.to.getText());
        return new io.github.joegxx.localsql.ir.statement.RenameColumn(parts(rtc.table), from, to);
    }

    private io.github.joegxx.localsql.ir.statement.AlterColumn buildAlterColumn(AlterTableAlterColumnContext atac) {
        String col = parts(atac.column).get(parts(atac.column).size() - 1);
        AlterColumnActionContext act = atac.alterColumnAction();
        io.github.joegxx.localsql.ir.type.DataType newType = null;
        String newComment = null;
        Boolean setNotNull = null;
        String position = null;
        if (act != null) {
            if (act.dataType() != null) newType = SparkDataTypeBuilder.build(act.dataType());
            if (act.commentSpec() != null && act.commentSpec().STRING() != null) newComment = SparkExpressionBuilder.unquote(act.commentSpec().STRING().getText());
            if (act.colPosition() != null) position = position(act.colPosition());
            if (act.SET() != null && act.NOT() != null) setNotNull = true;
            else if (act.DROP() != null && act.NOT() != null) setNotNull = false;
        }
        return new io.github.joegxx.localsql.ir.statement.AlterColumn(parts(atac.table), col, newType, newComment, setNotNull, position);
    }

    private io.github.joegxx.localsql.ir.statement.AlterColumn buildHiveChangeColumn(HiveChangeColumnContext hcc) {
        String oldName = parts(hcc.colName).get(parts(hcc.colName).size() - 1);
        ColTypeContext ct = hcc.colType();
        String newName = SparkExpressionBuilder.unquote(ct.colName.getText());
        io.github.joegxx.localsql.ir.type.DataType newType = SparkDataTypeBuilder.build(ct.dataType());
        String newComment = comment(ct.commentSpec());
        Boolean setNotNull = ct.NOT() != null ? true : null;
        // Hive CHANGE can rename; if names differ, we handle as alter with new name via type change path
        // For now treat rename separately if names differ and delegate via newName in AlterColumn with type
        // We encode rename by using newName as column with type change - pipeline will handle RENAME if needed
        String position = position(hcc.colPosition());
        if (!newName.equalsIgnoreCase(oldName)) {
            return new io.github.joegxx.localsql.ir.statement.AlterColumn(parts(hcc.table), oldName, newName, newType, newComment, setNotNull, position);
        }
        return new io.github.joegxx.localsql.ir.statement.AlterColumn(parts(hcc.table), oldName, newType, newComment, setNotNull, position);
    }

    private io.github.joegxx.localsql.ir.statement.ReplaceColumns buildReplaceColumns(HiveReplaceColumnsContext hrc) {
        List<io.github.joegxx.localsql.ir.statement.AddColumns.ColDef> cols = new ArrayList<>();
        for (QualifiedColTypeWithPositionContext q : hrc.columns.qualifiedColTypeWithPosition()) {
            List<String> np = parts(q.multipartIdentifier());
            String colName = np.get(np.size() - 1);
            cols.add(new io.github.joegxx.localsql.ir.statement.AddColumns.ColDef(
                    colName, SparkDataTypeBuilder.build(q.dataType()), q.NOT() == null,
                    comment(q.commentSpec()), position(q.colPosition())));
        }
        return new io.github.joegxx.localsql.ir.statement.ReplaceColumns(parts(hrc.multipartIdentifier()), cols);
    }

    private static String position(ColPositionContext pc) {
        if (pc == null) return null;
        if (pc.FIRST() != null) return "FIRST";
        if (pc.AFTER() != null && pc.errorCapturingIdentifier() != null) {
            return "AFTER " + SparkExpressionBuilder.unquote(pc.errorCapturingIdentifier().getText());
        }
        return null;
    }

    private static String comment(CommentSpecContext cs) {
        return cs == null || cs.STRING() == null ? null
                : SparkExpressionBuilder.unquote(cs.STRING().getText());
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
            String name = SparkExpressionBuilder.unquote(nq.name.getText());
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
            if (op == SqlBaseParser.UNION) return new Union(List.of(left, right), distinct, Union.Kind.UNION);
            if (op == SqlBaseParser.INTERSECT) return new Union(List.of(left, right), distinct, Union.Kind.INTERSECT);
            if (op == SqlBaseParser.EXCEPT || op == SqlBaseParser.SETMINUS)
                return new Union(List.of(left, right), distinct, Union.Kind.EXCEPT);
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

        // The grammar attaches LATERAL VIEW to the fromClause
        // (FROM relation (, relation)* lateralView*), which is the standard
        // Spark position; the select-level lateralView* rarely binds.
        if (from != null && from.lateralView() != null) {
            for (LateralViewContext lv : from.lateralView()) {
                input = visitLateralView(lv, input);
            }
        }
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
            Expression item = visitNamedExpression(ne);
            // json_tuple(j, 'k1', ..., 'kn') is row-preserving (one output row
            // per input row), so it can expand inline to N json_extract
            // projections instead of going through the Generate machinery.
            if (item instanceof FunctionCall call && call.name().equalsIgnoreCase("json_tuple")
                    && call.arguments().size() >= 1) {
                for (int i = 1; i < call.arguments().size(); i++) {
                    Expression key = call.arguments().get(i);
                    if (!(key instanceof Literal l) || l.kind() != io.github.joegxx.localsql.ir.expression.Literal.Kind.STRING) {
                        throw new UnsupportedOperationException("json_tuple keys must be string literals: " + key);
                    }
                    String name = String.valueOf(l.value());
                    Expression extract = new FunctionCall("json_extract", List.of(
                            call.arguments().get(0),
                            new io.github.joegxx.localsql.ir.expression.Literal("$." + name,
                                    io.github.joegxx.localsql.ir.expression.Literal.Kind.STRING)));
                    selectItems.add(new io.github.joegxx.localsql.ir.expression.Alias(extract, name, List.of()));
                }
            } else {
                selectItems.add(item);
            }
        }

        boolean aggregated = false;
        if (agg != null && input != null) {
            input = visitAggregate(agg, input, selectItems);
            aggregated = true;
        }
        if (having != null && input != null) {
            if (aggregated && input instanceof Aggregate aggregate) {
                input = new Aggregate(aggregate.child(), aggregate.groupingExpressions(),
                        aggregate.aggregateExpressions(), exprBuilder.visit(having.booleanExpression()),
                        aggregate.groupingAnalytics());
            } else if (!aggregated) {
                // HAVING without GROUP BY: implicit single-group aggregate
                input = new Aggregate(input, List.of(), selectItems, exprBuilder.visit(having.booleanExpression()));
                aggregated = true;
            }
        }

        if (input == null) {
            return new Project(new Values(List.of(List.of())), selectItems, distinct);
        }
        // When aggregated, selectItems are already inside the Aggregate node.
        // Do NOT shortcut on 'input instanceof Aggregate': a plain query whose
        // FROM is an aggregated subquery still needs its own Project on top.
        if (aggregated) return input;
        return new Project(input, selectItems, distinct);
    }

    private Relation visitLateralView(LateralViewContext lv, Relation input) {
        List<String> nameParts = SparkExpressionBuilder.parts(lv.qualifiedName());
        String fn = String.join(".", nameParts);
        List<Expression> args = new ArrayList<>();
        if (lv.expression() != null) for (ExpressionContext e : lv.expression()) args.add(exprBuilder.visit(e));
        FunctionCall gen = new FunctionCall(fn, args);
        List<String> outs = new ArrayList<>();
        if (lv.colName != null) for (var id : lv.colName) outs.add(SparkExpressionBuilder.unquote(id.getText()));
        String viewAlias = lv.tblName != null ? SparkExpressionBuilder.unquote(lv.tblName.getText()) : null;
        if (outs.isEmpty() && viewAlias != null) {
            // LATERAL VIEW f(x) v without AS: the view name doubles as the column name
            outs.add(viewAlias);
        }
        return new Generate(gen, outs, viewAlias, input);
    }

    private Relation visitAggregate(AggregationClauseContext agg, Relation input, List<Expression> selectItems) {
        List<Expression> grouping = new ArrayList<>();
        List<Aggregate.GroupingAnalytics> analytics = new ArrayList<>();
        if (agg.groupingExpressions != null) {
            for (ExpressionContext e : agg.groupingExpressions) grouping.add(exprBuilder.visit(e));
            if (agg.kind != null) {
                // legacy: GROUP BY a, b WITH ROLLUP / WITH CUBE / GROUPING SETS (...)
                int kind = agg.kind.getType();
                if (kind == SqlBaseParser.ROLLUP) analytics.add(Aggregate.GroupingAnalytics.rollup(grouping));
                else if (kind == SqlBaseParser.CUBE) analytics.add(Aggregate.GroupingAnalytics.cube(grouping));
                else analytics.add(Aggregate.GroupingAnalytics.groupingSets(visitGroupingSets(agg.groupingSet())));
                if (!analytics.isEmpty()) grouping = new ArrayList<>();
            }
        }
        if (agg.groupingExpressionsWithGroupingAnalytics != null) {
            for (GroupByClauseContext gbc : agg.groupingExpressionsWithGroupingAnalytics) {
                if (gbc.expression() != null) grouping.add(exprBuilder.visit(gbc.expression()));
                else analytics.add(visitGroupingAnalytics(gbc.groupingAnalytics()));
            }
        }
        return new Aggregate(input, grouping, selectItems, null, analytics);
    }

    private Aggregate.GroupingAnalytics visitGroupingAnalytics(GroupingAnalyticsContext ga) {
        if (ga.ROLLUP() != null || ga.CUBE() != null) {
            List<Expression> exprs = new ArrayList<>();
            for (GroupingSetContext gs : ga.groupingSet()) {
                if (gs.expression() == null || gs.expression().size() != 1) {
                    throw new UnsupportedOperationException("ROLLUP/CUBE element must be a single expression list");
                }
                exprs.add(exprBuilder.visit(gs.expression(0)));
            }
            return ga.ROLLUP() != null
                    ? Aggregate.GroupingAnalytics.rollup(exprs)
                    : Aggregate.GroupingAnalytics.cube(exprs);
        }
        // GROUPING SETS '(' groupingElement (',' groupingElement)* ')'
        List<List<Expression>> sets = new ArrayList<>();
        for (GroupingElementContext ge : ga.groupingElement()) {
            if (ge.groupingAnalytics() != null) {
                throw new UnsupportedOperationException("Nested grouping analytics not in MVP");
            }
            sets.add(visitGroupingSet(ge.groupingSet()));
        }
        return Aggregate.GroupingAnalytics.groupingSets(sets);
    }

    private List<List<Expression>> visitGroupingSets(List<GroupingSetContext> sets) {
        List<List<Expression>> out = new ArrayList<>();
        for (GroupingSetContext gs : sets) out.add(visitGroupingSet(gs));
        return out;
    }

    private List<Expression> visitGroupingSet(GroupingSetContext gs) {
        List<Expression> exprs = new ArrayList<>();
        if (gs.expression() != null) for (ExpressionContext e : gs.expression()) exprs.add(exprBuilder.visit(e));
        return exprs;
    }

    private Relation visitFrom(FromClauseContext from) {
        Relation left = visitRelation(from.relation(0));
        for (int i = 1; i < from.relation().size(); i++) {
            Relation right = visitRelation(from.relation(i));
            left = new Join(left, right, Join.JoinType.CROSS, null, List.of());
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
        if (jr.NATURAL() != null) {
            // NATURAL join needs schema analysis to find common columns; the
            // MVP has no column-resolution for this, so refuse explicitly
            // instead of silently degrading to a cartesian product.
            throw new UnsupportedOperationException("NATURAL JOIN not supported: requires common-column resolution");
        }
        Expression cond = null;
        List<String> usingColumns = List.of();
        if (jr.joinCriteria() != null) {
            if (jr.joinCriteria().ON() != null) {
                cond = exprBuilder.visit(jr.joinCriteria().booleanExpression());
            } else {
                usingColumns = new ArrayList<>();
                for (var id : jr.joinCriteria().identifierList().identifierSeq().ident) {
                    usingColumns.add(SparkExpressionBuilder.unquote(id.getText()));
                }
            }
        }
        return new Join(left, right, jt, cond, usingColumns);
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
        for (ExpressionContext e : c.expression()) {
            Expression expr = exprBuilder.visit(e);
            // VALUES (1, 'a'), (2, 'b'): each row is a struct() row-constructor
            // that must be unfolded into columns; a bare VALUES 1 is a single column.
            if (expr instanceof StructExpr s && !s.fields().isEmpty()) {
                rows.add(s.fields());
            } else {
                rows.add(List.of(expr));
            }
        }
        return alias(new Values(rows), c.tableAlias());
    }

    private Relation alias(Relation r, TableAliasContext alias) {
        if (alias == null) return r;
        String aliasName = null;
        if (alias.strictIdentifier() != null) aliasName = SparkExpressionBuilder.unquote(alias.strictIdentifier().getText());
        if (aliasName == null) return r;
        if (r instanceof TableScan t) {
            return new TableScan(t.tableName(), t.output(), aliasName);
        }
        return new SubqueryAlias(r, aliasName);
    }

    private Relation visitQueryOrganization(QueryOrganizationContext org, Relation input) {
        if (org.sort != null && !org.sort.isEmpty()) {
            throw new UnsupportedOperationException(
                    "SORT BY is not supported (DuckDB has no per-partition sort); use ORDER BY");
        }
        if (org.clusterBy != null && !org.clusterBy.isEmpty()) {
            throw new UnsupportedOperationException("CLUSTER BY is not supported");
        }
        if (org.distributeBy != null && !org.distributeBy.isEmpty()) {
            throw new UnsupportedOperationException("DISTRIBUTE BY is not supported");
        }
        if (org.windowClause() != null) {
            throw new UnsupportedOperationException(
                    "Named windows (WINDOW w AS ...) are not supported; write OVER (...) inline");
        }
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
        if (org.ALL() != null) {
            throw new UnsupportedOperationException("LIMIT ALL is not supported");
        }
        if (org.limit != null) {
            Expression lim = exprBuilder.visit(org.limit);
            if (lim instanceof Literal l && l.kind() == Literal.Kind.INTEGER) {
                input = new Limit(input, (Long) l.value(), 0);
            } else {
                throw new UnsupportedOperationException(
                        "LIMIT must be an integer literal, got: " + org.limit.getText());
            }
        }
        return input;
    }

    Expression visitNamedExpression(NamedExpressionContext ne) {
        Expression e = exprBuilder.visit(ne.expression());
        if (ne.name != null) {
            return new Alias(e, SparkExpressionBuilder.unquote(ne.name.getText()), List.of());
        }
        if (ne.identifierList() != null) {
            List<String> names = new ArrayList<>();
            for (ErrorCapturingIdentifierContext id : ne.identifierList().identifierSeq().ident) names.add(SparkExpressionBuilder.unquote(id.getText()));
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
        for (ErrorCapturingIdentifierContext id : mi.parts) out.add(SparkExpressionBuilder.unquote(id.getText()));
        return out;
    }

    public interface SparkSqlParserAccess {
        SingleStatementContext parse(String sql);
    }
}
