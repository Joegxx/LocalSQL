package io.github.joegxx.localsql.analyzer;

import io.github.joegxx.localsql.catalog.Catalog;
import io.github.joegxx.localsql.ir.expression.*;
import io.github.joegxx.localsql.ir.relation.*;
import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.ir.type.UnknownType;
import io.github.joegxx.localsql.ir.visitor.IrVisitor;

import java.util.ArrayList;
import java.util.List;

public final class SemanticAnalyzer {

    private final Catalog catalog;

    public SemanticAnalyzer(Catalog catalog) { this.catalog = catalog; }

    public Relation analyze(Relation rel) {
        AnalyzerVisitor v = new AnalyzerVisitor(catalog);
        v.visitRelation(rel, null);
        return rel;
    }

    private static final class AnalyzerVisitor extends IrVisitor<Void, Void> {
        private final Catalog catalog;

        AnalyzerVisitor(Catalog catalog) { this.catalog = catalog; }

        @Override
        public Void visitTableScan(TableScan r, Void ctx) {
            catalog.getTable(r.tableName()).ifPresent(table -> {
                List<AttributeReference> out = new ArrayList<>();
                List<String> qualifier = r.alias() != null
                        ? List.of(r.alias())
                        : r.tableName();
                for (Catalog.Column c : table.columns()) {
                    AttributeReference attr = new AttributeReference(c.name(), qualifier);
                    attr.setDataType(c.type());
                    out.add(attr);
                }
                r.setOutput(out);
            });
            return null;
        }

        @Override
        public Void visitAttributeReference(AttributeReference e, Void ctx) {
            if (e.dataType() instanceof UnknownType) e.setDataType(new io.github.joegxx.localsql.ir.type.StringType());
            return null;
        }

        @Override
        public Void visitLiteral(Literal e, Void ctx) {
            e.setDataType(inferLiteral(e));
            return null;
        }

        private DataType inferLiteral(Literal e) {
            return switch (e.kind()) {
                case NULL -> new io.github.joegxx.localsql.ir.type.NullType();
                case BOOLEAN -> new io.github.joegxx.localsql.ir.type.BooleanType();
                case INTEGER -> io.github.joegxx.localsql.ir.type.IntegralType.INT;
                case DECIMAL -> new io.github.joegxx.localsql.ir.type.FractionalType(64);
                case DOUBLE -> io.github.joegxx.localsql.ir.type.FractionalType.DOUBLE;
                case STRING -> new io.github.joegxx.localsql.ir.type.StringType();
                case DATE -> new io.github.joegxx.localsql.ir.type.DateType();
                case TIMESTAMP -> new io.github.joegxx.localsql.ir.type.TimestampType();
            };
        }
    }
}
