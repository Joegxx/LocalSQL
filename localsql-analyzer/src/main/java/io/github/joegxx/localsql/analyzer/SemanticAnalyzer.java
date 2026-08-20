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

    /** Analyze a top-level statement: resolves query children and validates DML/DDL targets. */
    public io.github.joegxx.localsql.ir.statement.Statement analyze(
            io.github.joegxx.localsql.ir.statement.Statement stmt) {
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.QueryStatement q) {
            analyze(q.query());
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.Insert i) {
            analyze(i.query());
            validateInsert(i);
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.CreateTable ct) {
            if (ct.query() != null) analyze(ct.query());
            if (ct.likeSource() != null
                    && catalog.resolveTable(ct.likeSource()).isEmpty()) {
                throw new IllegalArgumentException(
                        "Source table does not exist: " + String.join(".", ct.likeSource()));
            }
            String db = databaseOf(ct.tableName());
            if (catalog.getDatabase(db).isEmpty()) {
                throw new IllegalArgumentException("Database does not exist: " + db);
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.CreateView cv) {
            analyze(cv.query());
            return stmt;
        }
        throw new UnsupportedOperationException("Unsupported statement: " + stmt.getClass().getSimpleName());
    }

    private void validateInsert(io.github.joegxx.localsql.ir.statement.Insert i) {
        Catalog.Table target = catalog.resolveTable(i.tableName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Table does not exist: " + String.join(".", i.tableName())));
        if (!"TABLE".equalsIgnoreCase(target.tableType())) {
            throw new IllegalArgumentException("INSERT target is not a table: "
                    + String.join(".", i.tableName()));
        }
        if (i.columns() != null) {
            for (String col : i.columns()) {
                boolean found = target.columns().stream()
                        .anyMatch(c -> c.name().equalsIgnoreCase(col));
                if (!found) {
                    throw new IllegalArgumentException(
                            "Column does not exist in table " + String.join(".", i.tableName()) + ": " + col);
                }
            }
        }
    }

    private static String databaseOf(List<String> parts) {
        return parts.size() >= 2 ? parts.get(parts.size() - 2) : "default";
    }

    private static final class AnalyzerVisitor extends IrVisitor<Void, Void> {
        private final Catalog catalog;

        AnalyzerVisitor(Catalog catalog) { this.catalog = catalog; }

        @Override
        public Void visitTableScan(TableScan r, Void ctx) {
            catalog.resolveTable(r.tableName()).ifPresent(table -> {
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
