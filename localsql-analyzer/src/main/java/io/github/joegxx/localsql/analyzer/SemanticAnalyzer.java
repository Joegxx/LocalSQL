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
        return analyze(rel, "default");
    }

    /** @param defaultDatabase session database used to resolve unqualified names. */
    public Relation analyze(Relation rel, String defaultDatabase) {
        AnalyzerVisitor v = new AnalyzerVisitor(catalog, defaultDatabase);
        v.visitRelation(rel, null);
        return rel;
    }

    /** Analyze a top-level statement: resolves query children and validates DML/DDL targets. */
    public io.github.joegxx.localsql.ir.statement.Statement analyze(
            io.github.joegxx.localsql.ir.statement.Statement stmt) {
        return analyze(stmt, "default");
    }

    /** @param defaultDatabase session database used to resolve unqualified names. */
    public io.github.joegxx.localsql.ir.statement.Statement analyze(
            io.github.joegxx.localsql.ir.statement.Statement stmt, String defaultDatabase) {
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.QueryStatement q) {
            analyze(q.query(), defaultDatabase);
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.Insert i) {
            analyze(i.query(), defaultDatabase);
            validateInsert(i, defaultDatabase);
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.CreateTable ct) {
            if (ct.query() != null) analyze(ct.query(), defaultDatabase);
            if (ct.likeSource() != null
                    && resolve(ct.likeSource(), defaultDatabase).isEmpty()) {
                throw new IllegalArgumentException(
                        "Source table does not exist: " + String.join(".", ct.likeSource()));
            }
            String db = databaseOf(ct.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) {
                throw new IllegalArgumentException("Database does not exist: " + db);
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.CreateView cv) {
            analyze(cv.query(), defaultDatabase);
            String db = databaseOf(cv.tableName(), defaultDatabase);
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
            String fromDb = databaseOf(rt.from(), defaultDatabase);
            String toDb = databaseOf(rt.to(), defaultDatabase);
            if (catalog.getDatabase(fromDb).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + fromDb);
            if (catalog.getDatabase(toDb).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + toDb);
            resolve(rt.from(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", rt.from())));
            if (resolve(rt.to(), defaultDatabase).isPresent()) throw new IllegalArgumentException("Target table already exists: " + String.join(".", rt.to()));
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.AddColumns ac) {
            String db = databaseOf(ac.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            Catalog.Table t = resolve(ac.tableName(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", ac.tableName())));
            if ("VIEW".equalsIgnoreCase(t.tableType())) throw new IllegalArgumentException("Cannot alter columns of a view: " + String.join(".", ac.tableName()));
            for (var c : ac.columns()) {
                if (t.columns().stream().anyMatch(col -> col.name().equalsIgnoreCase(c.name()))) {
                    throw new IllegalArgumentException("Column already exists: " + c.name());
                }
                if (c.position() != null) throw new UnsupportedOperationException("Column position FIRST/AFTER not supported: " + c.name());
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.DropColumns dc) {
            String db = databaseOf(dc.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            Catalog.Table t = resolve(dc.tableName(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", dc.tableName())));
            if ("VIEW".equalsIgnoreCase(t.tableType())) throw new IllegalArgumentException("Cannot alter columns of a view: " + String.join(".", dc.tableName()));
            for (String col : dc.columns()) {
                if (t.columns().stream().noneMatch(c -> c.name().equalsIgnoreCase(col))) {
                    throw new IllegalArgumentException("Column does not exist: " + col);
                }
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.RenameColumn rc) {
            String db = databaseOf(rc.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            Catalog.Table t = resolve(rc.tableName(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", rc.tableName())));
            if ("VIEW".equalsIgnoreCase(t.tableType())) throw new IllegalArgumentException("Cannot alter columns of a view: " + String.join(".", rc.tableName()));
            if (t.columns().stream().noneMatch(c -> c.name().equalsIgnoreCase(rc.from()))) throw new IllegalArgumentException("Column does not exist: " + rc.from());
            if (t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(rc.to()))) throw new IllegalArgumentException("Target column already exists: " + rc.to());
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.AlterColumn alc) {
            String db = databaseOf(alc.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            Catalog.Table t = resolve(alc.tableName(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", alc.tableName())));
            if ("VIEW".equalsIgnoreCase(t.tableType())) throw new IllegalArgumentException("Cannot alter columns of a view: " + String.join(".", alc.tableName()));
            if (t.columns().stream().noneMatch(c -> c.name().equalsIgnoreCase(alc.column()))) throw new IllegalArgumentException("Column does not exist: " + alc.column());
            if (alc.newName() != null && !alc.newName().equalsIgnoreCase(alc.column())
                    && t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(alc.newName()))) {
                throw new IllegalArgumentException("Target column already exists: " + alc.newName());
            }
            if (alc.position() != null) throw new UnsupportedOperationException("Column position FIRST/AFTER not supported: " + alc.column());
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.ReplaceColumns rpc) {
            String db = databaseOf(rpc.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            Catalog.Table t = resolve(rpc.tableName(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", rpc.tableName())));
            if ("VIEW".equalsIgnoreCase(t.tableType())) throw new IllegalArgumentException("Cannot alter columns of a view: " + String.join(".", rpc.tableName()));
            for (var c : rpc.columns()) {
                if (c.position() != null) throw new UnsupportedOperationException("Column position FIRST/AFTER not supported: " + c.name());
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.DropTable dt) {
            if (resolve(dt.tableName(), defaultDatabase).isEmpty() && !dt.ifExists()) {
                throw new IllegalArgumentException((dt.view() ? "View" : "Table") + " does not exist: " + String.join(".", dt.tableName()));
            }
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.SetTableProperties stp) {
            String db = databaseOf(stp.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            resolve(stp.tableName(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", stp.tableName())));
            return stmt;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.UnsetTableProperties utp) {
            String db = databaseOf(utp.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            resolve(utp.tableName(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", utp.tableName())));
            return stmt;
        }
        throw new UnsupportedOperationException("Unsupported statement: " + stmt.getClass().getSimpleName());
    }

    private void validateInsert(io.github.joegxx.localsql.ir.statement.Insert i, String defaultDatabase) {
        Catalog.Table target = resolve(i.tableName(), defaultDatabase)
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

    /** Resolve identifier parts against the session database for unqualified names. */
    private java.util.Optional<Catalog.Table> resolve(List<String> parts, String defaultDatabase) {
        if (parts == null || parts.isEmpty()) return java.util.Optional.empty();
        if (parts.size() == 1) return catalog.getTable(defaultDatabase, parts.get(0));
        return catalog.resolveTable(parts);
    }

    private static String databaseOf(List<String> parts, String defaultDatabase) {
        return parts.size() == 1 ? defaultDatabase : parts.get(parts.size() - 2);
    }

    private static final class AnalyzerVisitor extends IrVisitor<Void, Void> {
        private final Catalog catalog;
        private final String defaultDatabase;

        AnalyzerVisitor(Catalog catalog, String defaultDatabase) {
            this.catalog = catalog;
            this.defaultDatabase = defaultDatabase;
        }

        @Override
        public Void visitTableScan(TableScan r, Void ctx) {
            java.util.Optional<Catalog.Table> found = r.tableName().size() == 1
                    ? catalog.getTable(defaultDatabase, r.tableName().get(0))
                    : catalog.resolveTable(r.tableName());
            found.ifPresent(table -> {
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
