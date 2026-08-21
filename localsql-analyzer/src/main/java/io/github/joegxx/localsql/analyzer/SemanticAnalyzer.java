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
            String db = databaseOf(cv.tableName());
            if (catalog.getDatabase(db).isEmpty()) {
                throw new IllegalArgumentException("Database does not exist: " + db);
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.CreateDatabase cd) {
            if (catalog.getDatabase(cd.name()).isPresent() && !cd.ifNotExists()) {
                throw new IllegalArgumentException("Database already exists: " + cd.name());
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.DropDatabase dd) {
            if (catalog.getDatabase(dd.name()).isEmpty() && !dd.ifExists()) {
                throw new IllegalArgumentException("Database does not exist: " + dd.name());
            }
            if ("default".equalsIgnoreCase(dd.name())) {
                throw new IllegalArgumentException("Cannot drop default database");
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.AlterDatabase ad) {
            if (catalog.getDatabase(ad.name()).isEmpty()) {
                throw new IllegalArgumentException("Database does not exist: " + ad.name());
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.RenameTable rt) {
            String fromDb = databaseOf(rt.from());
            String toDb = databaseOf(rt.to());
            if (catalog.getDatabase(fromDb).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + fromDb);
            if (catalog.getDatabase(toDb).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + toDb);
            catalog.resolveTable(rt.from()).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", rt.from())));
            if (catalog.resolveTable(rt.to()).isPresent()) throw new IllegalArgumentException("Target table already exists: " + String.join(".", rt.to()));
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.AddColumns ac) {
            String db = databaseOf(ac.tableName());
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            Catalog.Table t = catalog.resolveTable(ac.tableName()).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", ac.tableName())));
            for (var c : ac.columns()) {
                if (t.columns().stream().anyMatch(col -> col.name().equalsIgnoreCase(c.name()))) {
                    throw new IllegalArgumentException("Column already exists: " + c.name());
                }
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.DropColumns dc) {
            String db = databaseOf(dc.tableName());
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            Catalog.Table t = catalog.resolveTable(dc.tableName()).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", dc.tableName())));
            for (String col : dc.columns()) {
                if (t.columns().stream().noneMatch(c -> c.name().equalsIgnoreCase(col))) {
                    throw new IllegalArgumentException("Column does not exist: " + col);
                }
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.RenameColumn rc) {
            String db = databaseOf(rc.tableName());
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            Catalog.Table t = catalog.resolveTable(rc.tableName()).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", rc.tableName())));
            if (t.columns().stream().noneMatch(c -> c.name().equalsIgnoreCase(rc.from()))) throw new IllegalArgumentException("Column does not exist: " + rc.from());
            if (t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(rc.to()))) throw new IllegalArgumentException("Target column already exists: " + rc.to());
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.AlterColumn alc) {
            String db = databaseOf(alc.tableName());
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            Catalog.Table t = catalog.resolveTable(alc.tableName()).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", alc.tableName())));
            if (t.columns().stream().noneMatch(c -> c.name().equalsIgnoreCase(alc.column()))) throw new IllegalArgumentException("Column does not exist: " + alc.column());
            if (alc.newName() != null && !alc.newName().equalsIgnoreCase(alc.column())
                    && t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(alc.newName()))) {
                throw new IllegalArgumentException("Target column already exists: " + alc.newName());
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.ReplaceColumns rpc) {
            String db = databaseOf(rpc.tableName());
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            catalog.resolveTable(rpc.tableName()).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", rpc.tableName())));
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.DropTable dt) {
            String db = databaseOf(dt.tableName());
            if (catalog.resolveTable(dt.tableName()).isEmpty() && !dt.ifExists()) {
                throw new IllegalArgumentException((dt.view() ? "View" : "Table") + " does not exist: " + String.join(".", dt.tableName()));
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.SetTableProperties stp) {
            String db = databaseOf(stp.tableName());
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            catalog.resolveTable(stp.tableName()).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", stp.tableName())));
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.UnsetTableProperties utp) {
            String db = databaseOf(utp.tableName());
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            catalog.resolveTable(utp.tableName()).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", utp.tableName())));
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
