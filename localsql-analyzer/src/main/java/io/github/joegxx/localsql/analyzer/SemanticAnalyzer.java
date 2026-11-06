package io.github.joegxx.localsql.analyzer;

import io.github.joegxx.localsql.catalog.Catalog;
import io.github.joegxx.localsql.ir.expression.*;
import io.github.joegxx.localsql.ir.relation.*;
import io.github.joegxx.localsql.ir.statement.CreateTable;
import io.github.joegxx.localsql.ir.statement.CreateView;
import io.github.joegxx.localsql.ir.statement.Insert;
import io.github.joegxx.localsql.ir.statement.Statement;
import io.github.joegxx.localsql.ir.type.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Analyzer: binds every name in a query to a concrete relation/column and
 * infers types, returning a new tree (the input is never mutated).
 *
 * <p>Scope layering: each SELECT block has a {@link Scope} whose FROM
 * relations (with alias/table qualifiers) and CTEs are visible; parent
 * scopes provide correlated references. Unqualified columns resolve only
 * against the current FROM list; qualified {@code q.c} names must match a
 * visible relation qualifier. Table names resolve to the session database
 * (bare) or to an explicit database (qualified), never silently to a
 * different physical table. Ambiguous or unresolved references fail before
 * execution.
 */
public final class SemanticAnalyzer {

    private final Catalog catalog;
    private final FunctionCatalog functions;
    private final boolean strictFunctions;

    public SemanticAnalyzer(Catalog catalog) { this(catalog, null, false); }

    /**
     * @param functions read-only function metadata used to bind function
     *                  identities; may be null (no function checking).
     * @param strictFunctions when true, calls to functions unknown to the
     *                  catalog are rejected during analysis; when false
     *                  unknown functions pass through unchecked.
     */
    public SemanticAnalyzer(Catalog catalog, FunctionCatalog functions, boolean strictFunctions) {
        this.catalog = catalog;
        this.functions = functions;
        this.strictFunctions = strictFunctions;
    }

    public Relation analyze(Relation rel) {
        return analyze(rel, "default");
    }

    /** @param defaultDatabase session database used to resolve unqualified names. */
    public Relation analyze(Relation rel, String defaultDatabase) {
        if (rel == null) return null;
        Resolver r = new Resolver(new Scope(null, defaultDatabase), catalog, functions, strictFunctions);
        return r.resolveRelation(rel);
    }

    /** Analyze a top-level statement; returns the (possibly rebuilt) statement. */
    public Statement analyze(Statement stmt, String defaultDatabase) {
        if (stmt instanceof Insert i) {
            Resolver r = new Resolver(new Scope(null, defaultDatabase), catalog, functions, strictFunctions);
            Relation query = r.resolveRelation(i.query());
            validateInsert(i, defaultDatabase);
            return new Insert(i.tableName(), i.overwrite(), i.columns(), query);
        }
        if (stmt instanceof CreateTable ct) {
            Resolver r = new Resolver(new Scope(null, defaultDatabase), catalog, functions, strictFunctions);
            Relation query = ct.query() == null ? null : r.resolveRelation(ct.query());
            validateCreateTable(ct, defaultDatabase);
            return new CreateTable(ct.tableName(), ct.ifNotExists(), ct.columns(), query, ct.likeSource(),
                    ct.partitionColumnNames());
        }
        if (stmt instanceof CreateView cv) {
            Resolver r = new Resolver(new Scope(null, defaultDatabase), catalog, functions, strictFunctions);
            Relation query = r.resolveRelation(cv.query());
            String db = databaseOf(cv.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) {
                throw new IllegalArgumentException("Database does not exist: " + db);
            }
            return new CreateView(cv.tableName(), cv.orReplace(), cv.ifNotExists(), query);
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.QueryStatement q) {
            return new io.github.joegxx.localsql.ir.statement.QueryStatement(analyze(q.query(), defaultDatabase));
        }
        validateStatement(stmt, defaultDatabase);
        return stmt;
    }

    public Statement analyze(Statement stmt) {
        return analyze(stmt, "default");
    }

    private void validateInsert(Insert i, String defaultDatabase) {
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

    private void validateCreateTable(CreateTable ct, String defaultDatabase) {
        if (ct.likeSource() != null && resolve(ct.likeSource(), defaultDatabase).isEmpty()) {
            throw new IllegalArgumentException(
                    "Source table does not exist: " + String.join(".", ct.likeSource()));
        }
        String db = databaseOf(ct.tableName(), defaultDatabase);
        if (catalog.getDatabase(db).isEmpty()) {
            throw new IllegalArgumentException("Database does not exist: " + db);
        }
    }

    private void validateStatement(Statement stmt, String defaultDatabase) {
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.CreateDatabase cd) {
            if (catalog.getDatabase(cd.name()).isPresent() && !cd.ifNotExists()) {
                throw new IllegalArgumentException("Database already exists: " + cd.name());
            }
            return;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.DropDatabase dd) {
            if (catalog.getDatabase(dd.name()).isEmpty() && !dd.ifExists()) {
                throw new IllegalArgumentException("Database does not exist: " + dd.name());
            }
            if ("default".equalsIgnoreCase(dd.name())) {
                throw new IllegalArgumentException("Cannot drop default database");
            }
            return;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.AlterDatabase ad) {
            if (catalog.getDatabase(ad.name()).isEmpty()) {
                throw new IllegalArgumentException("Database does not exist: " + ad.name());
            }
            return;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.RenameTable rt) {
            String fromDb = databaseOf(rt.from(), defaultDatabase);
            String toDb = databaseOf(rt.to(), defaultDatabase);
            if (catalog.getDatabase(fromDb).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + fromDb);
            if (catalog.getDatabase(toDb).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + toDb);
            resolve(rt.from(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", rt.from())));
            if (resolve(rt.to(), defaultDatabase).isPresent()) throw new IllegalArgumentException("Target table already exists: " + String.join(".", rt.to()));
            return;
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
            return;
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
            return;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.RenameColumn rc) {
            String db = databaseOf(rc.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            Catalog.Table t = resolve(rc.tableName(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", rc.tableName())));
            if ("VIEW".equalsIgnoreCase(t.tableType())) throw new IllegalArgumentException("Cannot alter columns of a view: " + String.join(".", rc.tableName()));
            if (t.columns().stream().noneMatch(c -> c.name().equalsIgnoreCase(rc.from()))) throw new IllegalArgumentException("Column does not exist: " + rc.from());
            if (t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(rc.to()))) throw new IllegalArgumentException("Target column already exists: " + rc.to());
            return;
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
            return;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.ReplaceColumns rpc) {
            String db = databaseOf(rpc.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            Catalog.Table t = resolve(rpc.tableName(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", rpc.tableName())));
            if ("VIEW".equalsIgnoreCase(t.tableType())) throw new IllegalArgumentException("Cannot alter columns of a view: " + String.join(".", rpc.tableName()));
            for (var c : rpc.columns()) {
                if (c.position() != null) throw new UnsupportedOperationException("Column position FIRST/AFTER not supported: " + c.name());
            }
            return;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.DropTable dt) {
            if (resolve(dt.tableName(), defaultDatabase).isEmpty() && !dt.ifExists()) {
                throw new IllegalArgumentException((dt.view() ? "View" : "Table") + " does not exist: " + String.join(".", dt.tableName()));
            }
            return;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.SetTableProperties stp) {
            String db = databaseOf(stp.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            resolve(stp.tableName(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", stp.tableName())));
            return;
        }
        if (stmt instanceof io.github.joegxx.localsql.ir.statement.UnsetTableProperties utp) {
            String db = databaseOf(utp.tableName(), defaultDatabase);
            if (catalog.getDatabase(db).isEmpty()) throw new IllegalArgumentException("Database does not exist: " + db);
            resolve(utp.tableName(), defaultDatabase).orElseThrow(() -> new IllegalArgumentException("Table does not exist: " + String.join(".", utp.tableName())));
            return;
        }
    }

    /** Resolve identifier parts against the session database for unqualified names. */
    private Optional<Catalog.Table> resolve(List<String> parts, String defaultDatabase) {
        if (parts == null || parts.isEmpty()) return Optional.empty();
        if (parts.size() == 1) return catalog.getTable(defaultDatabase, parts.get(0));
        String db = parts.get(parts.size() - 2);
        if (catalog.getDatabase(db).isEmpty()) {
            throw new IllegalArgumentException("Database does not exist: " + db);
        }
        return catalog.getTable(db, parts.get(parts.size() - 1));
    }

    private static String databaseOf(List<String> parts, String defaultDatabase) {
        return parts.size() == 1 ? defaultDatabase : parts.get(parts.size() - 2);
    }

    /** Binds a query tree in a given scope; see the class javadoc. */
    private static final class Resolver {
        private final Scope scope;
        private final Catalog catalog;
        private final FunctionCatalog functions;
        private final boolean strictFunctions;

        Resolver(Scope scope, Catalog catalog, FunctionCatalog functions, boolean strictFunctions) {
            this.scope = scope;
            this.catalog = catalog;
            this.functions = functions;
            this.strictFunctions = strictFunctions;
        }

        Relation resolveRelation(Relation rel) {
            if (rel == null) return null;
            if (rel instanceof With w) return resolveWith(w);
            if (rel instanceof Project p) return resolveProject(p);
            if (rel instanceof Filter f) return resolveFilter(f);
            if (rel instanceof Aggregate a) return resolveAggregate(a);
            if (rel instanceof Sort s) return resolveSort(s);
            if (rel instanceof Limit l) return new Limit(resolveRelation(l.child()), l.limit(), l.offset());
            if (rel instanceof Join j) return resolveJoin(j);
            if (rel instanceof Union u) return resolveUnion(u);
            if (rel instanceof TableScan t) return resolveTableScan(t);
            if (rel instanceof SubqueryAlias s) return resolveSubqueryAlias(s);
            if (rel instanceof Values v) return v;
            if (rel instanceof Generate g) return resolveGenerate(g);
            if (rel instanceof Window wn) return resolveWindow(wn);
            return rel;
        }

        private Relation resolveWith(With w) {
            List<CTERelation> resolvedCtes = new ArrayList<>();
            for (CTERelation cte : w.ctes()) {
                Relation def = new Resolver(scope, catalog, functions, strictFunctions).resolveRelation(cte.definition());
                List<AttributeReference> out = def.output();
                CTERelation resolved = new CTERelation(cte.name(), def, out);
                scope.addCte(resolved);
                resolvedCtes.add(resolved);
            }
            Relation body = resolveRelation(w.body());
            return new With(resolvedCtes, body);
        }

        private Relation resolveTableScan(TableScan t) {
            List<String> parts = t.tableName();
            List<AttributeReference> base;
            List<String> physical = new ArrayList<>(2);
            if (parts.size() == 1) {
                CTERelation cte = scope.findCte(parts.get(0));
                if (cte != null) {
                    base = new ArrayList<>(cte.output());
                } else {
                    base = resolveCatalogTable(scope.sessionDatabase(), parts.get(0), physical);
                }
            } else if (parts.size() == 2) {
                base = resolveCatalogTable(parts.get(0), parts.get(1), physical);
            } else {
                base = resolveCatalogTable(parts.get(parts.size() - 2), parts.get(parts.size() - 1), physical);
            }
            String qualifier = t.alias() != null ? t.alias() : parts.get(parts.size() - 1);
            List<AttributeReference> qualified = qualify(qualifier, base);
            // Resolved scans carry the physical location: [schema, table]
            // (main-resident tables stay addressable by the bare name the
            // generator emits). CTE references keep their original name.
            List<String> resolvedParts = physical.isEmpty() ? parts : List.copyOf(physical);
            TableScan resolved = new TableScan(resolvedParts, qualified, t.alias());
            resolved.setOutput(qualified);
            return resolved;
        }

        /**
         * Binds a catalog table's columns and records its physical location
         * into {@code physicalParts} as {@code [schema, table]}. The schema
         * is where the DuckDB object actually lives (per-database schema or
         * main); the generator emits it as a schema prefix unless it is main.
         */
        private List<AttributeReference> resolveCatalogTable(String db, String table, List<String> physicalParts) {
            if (catalog.getDatabase(db).isEmpty()) {
                throw new IllegalArgumentException(
                        "Database does not exist: " + db + " (referenced by table " + table + ")");
            }
            Optional<Catalog.Table> found = catalog.getTable(db, table);
            if (found.isEmpty()) {
                throw new IllegalArgumentException("Table does not exist: " + db + "." + table);
            }
            Catalog.Table catalogTable = found.get();
            physicalParts.clear();
            physicalParts.add(catalogTable.physicalSchema() == null || catalogTable.physicalSchema().isBlank()
                    ? "main" : catalogTable.physicalSchema());
            physicalParts.add(table);
            List<AttributeReference> out = new ArrayList<>();
            for (Catalog.Column c : catalogTable.columns()) {
                AttributeReference attr = new AttributeReference(c.name(), List.of(table));
                attr.setDataType(c.type());
                out.add(attr);
            }
            return out;
        }

        private static List<AttributeReference> qualify(String qualifier, List<AttributeReference> out) {
            List<AttributeReference> result = new ArrayList<>(out.size());
            for (AttributeReference a : out) {
                AttributeReference q = new AttributeReference(a.name(), List.of(qualifier));
                q.setDataType(a.dataType());
                result.add(q);
            }
            return result;
        }

        private Relation resolveProject(Project p) {
            Scope childScope = new Scope(scope, scope.sessionDatabase());
            Relation child = resolveRelation(p.child());
            registerOutputs(child, childScope);

            List<Expression> items = new ArrayList<>();
            for (Expression item : p.projectList()) {
                items.addAll(resolveStar(item, childScope));
            }
            Project resolved = new Project(child, items, p.distinct());
            resolved.setOutput(outputOf(items));
            return resolved;
        }

        /**
         * Expands Star items ({@code *} and {@code q.*}) into the concrete
         * column list of the visible relation(s); anything else passes
         * through as a single resolved expression.
         */
        private List<Expression> resolveStar(Expression item, Scope s) {
            if (!(item instanceof Star star)) return List.of(resolveExpr(item, s));
            if (!star.qualifier().isEmpty()) {
                String q = star.qualifier().get(star.qualifier().size() - 1);
                List<AttributeReference> cols = s.columnsOfQualifier(q);
                if (cols == null) {
                    throw new IllegalArgumentException(
                            "Relation in star expression does not exist in FROM clause: " + q);
                }
                return new ArrayList<>(cols);
            }
            return new ArrayList<>(s.allColumns());
        }

        private Relation resolveFilter(Filter f) {
            Scope childScope = new Scope(scope, scope.sessionDatabase());
            Relation child = resolveRelation(f.child());
            registerOutputs(child, childScope);
            return new Filter(child, resolveExpr(f.condition(), childScope));
        }

        private Relation resolveAggregate(Aggregate a) {
            Scope childScope = new Scope(scope, scope.sessionDatabase());
            Relation child = resolveRelation(a.child());
            registerOutputs(child, childScope);

            List<Expression> aggExprs = new ArrayList<>();
            for (Expression e : a.resultExpressions()) aggExprs.add(resolveExpr(e, childScope));
            List<Expression> grouping = new ArrayList<>();
            for (Expression e : a.groupingExpressions()) grouping.add(resolveExpr(e, childScope));
            // GROUP BY ordinals refer to the select list (Spark semantics):
            // translate them to the referenced result expression so the IR
            // never depends on downstream ordinal interpretation
            for (int i = 0; i < grouping.size(); i++) {
                grouping.set(i, resolveGroupByOrdinal(grouping.get(i), aggExprs));
            }

            // HAVING sees select aliases first (Spark semantics), then the
            // raw FROM columns beneath the aggregation. Same explicit
            // two-stage lookup as ORDER BY: select output wins on conflict.
            Expression having = null;
            if (a.havingCondition() != null) {
                Scope selectScope = new Scope(childScope, scope.sessionDatabase());
                selectScope.addRelation(List.of(), outputOf(aggExprs));
                Scope havingScope = new Scope(selectScope, scope.sessionDatabase());
                registerFromColumns(child, havingScope);
                try {
                    having = resolveExpr(a.havingCondition(), selectScope);
                } catch (IllegalArgumentException e) {
                    having = resolveExpr(a.havingCondition(), havingScope);
                }
            }

            List<Aggregate.GroupingAnalytics> analytics = new ArrayList<>();
            for (Aggregate.GroupingAnalytics ga : a.groupingAnalytics()) {
                List<List<Expression>> sets = new ArrayList<>();
                for (List<Expression> set : ga.sets()) {
                    List<Expression> rs = new ArrayList<>();
                    for (Expression e : set) rs.add(resolveGroupByOrdinal(resolveExpr(e, childScope), aggExprs));
                    sets.add(rs);
                }
                analytics.add(new Aggregate.GroupingAnalytics(ga.kind(), sets));
            }
            validateGroupBy(aggExprs, grouping, analytics);
            Aggregate resolved = new Aggregate(child, grouping, aggExprs, having, analytics);
            resolved.setOutput(outputOf(aggExprs));
            return resolved;
        }

        /**
         * {@code GROUP BY 2} refers to the second select item: replace the
         * ordinal literal with the (alias-unwrapped) resolved result
         * expression. Ordinals outside the result list are passed through
         * and fail downstream, mirroring Spark.
         */
        private static Expression resolveGroupByOrdinal(Expression e, List<Expression> resultExprs) {
            if (e instanceof Literal l && l.kind() == Literal.Kind.INTEGER && l.value() instanceof Number n) {
                int ord = n.intValue();
                if (ord >= 1 && ord <= resultExprs.size()) {
                    Expression target = resultExprs.get(ord - 1);
                    return target instanceof Alias al ? al.child() : target;
                }
            }
            return e;
        }

        /**
         * Spark semantics: every result expression of an aggregate must be
         * functionally determined by the GROUP BY keys. Following Catalyst's
         * checkAnalysis: a subtree that structurally equals a grouping
         * expression is covered (stop descending); a bare attribute must
         * itself be a grouping expression; aggregate and window calls are
         * covered without descending into their arguments. Functions unknown
         * to the catalog (e.g. grouping()) and subqueries are passed — their
         * grouping rules are out of scope here.
         */
        private void validateGroupBy(List<Expression> resultExprs, List<Expression> grouping,
                                      List<Aggregate.GroupingAnalytics> analytics) {
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (Expression g : grouping) keys.add(unwrapAlias(g).toString());
            for (Aggregate.GroupingAnalytics ga : analytics) {
                for (List<Expression> set : ga.sets()) {
                    for (Expression e : set) keys.add(unwrapAlias(e).toString());
                }
            }
            for (Expression r : resultExprs) {
                Expression base = unwrapAlias(r);
                if (base instanceof Star) continue;
                if (coveredByGrouping(base, keys)) continue;
                throw new IllegalArgumentException("Column " + base
                        + " must appear in the GROUP BY clause or be used in an aggregate function");
            }
        }

        private static Expression unwrapAlias(Expression e) {
            return e instanceof Alias a ? a.child() : e;
        }

        /** Catalyst-style upward coverage check (see {@link #validateGroupBy}). */
        private boolean coveredByGrouping(Expression e, java.util.Set<String> keys) {
            if (e == null) return true;
            if (keys.contains(e.toString())) return true;
            if (e instanceof ScalarSubquery || e instanceof Subquery || e instanceof Lambda) return true;
            if (e instanceof FunctionCall f) {
                if (f.windowSpec() != null) return true;
                Optional<FunctionInfo> info = functions == null ? Optional.empty() : functions.lookup(f.name());
                if (info.isEmpty()) return true;
                FunctionKind kind = info.get().kind();
                if (kind == FunctionKind.AGGREGATE || kind == FunctionKind.WINDOW) return true;
                for (Expression a : f.arguments()) {
                    if (!coveredByGrouping(a, keys)) return false;
                }
                return true;
            }
            if (e instanceof Alias a) return coveredByGrouping(a.child(), keys);
            if (e instanceof AttributeReference || e instanceof Identifier) return false;
            if (e instanceof BinaryExpression b) {
                return coveredByGrouping(b.left(), keys) && coveredByGrouping(b.right(), keys);
            }
            if (e instanceof UnaryExpression u) return coveredByGrouping(u.child(), keys);
            if (e instanceof Cast c) return coveredByGrouping(c.child(), keys);
            if (e instanceof CaseWhen c) {
                for (CaseWhen.WhenBranch b : c.branches()) {
                    if (!coveredByGrouping(b.condition(), keys) || !coveredByGrouping(b.value(), keys)) return false;
                }
                return coveredByGrouping(c.elseValue(), keys);
            }
            if (e instanceof InList il) {
                if (!coveredByGrouping(il.value(), keys)) return false;
                for (Expression item : il.list()) {
                    if (!coveredByGrouping(item, keys)) return false;
                }
                return true;
            }
            if (e instanceof ArrayExpr ae) {
                for (Expression el : ae.elements()) {
                    if (!coveredByGrouping(el, keys)) return false;
                }
                return true;
            }
            if (e instanceof StructExpr se) {
                for (Expression fld : se.fields()) {
                    if (!coveredByGrouping(fld, keys)) return false;
                }
                return true;
            }
            if (e instanceof MapExpr me) {
                for (Map.Entry<Expression, Expression> en : me.entries()) {
                    if (!coveredByGrouping(en.getKey(), keys) || !coveredByGrouping(en.getValue(), keys)) return false;
                }
                return true;
            }
            return true;
        }

        /**
         * One output attribute per select item: an Alias contributes its name
         * (no qualifier — the projection renames the column); a bare column
         * reference keeps its name and drops the qualifier it was referenced
         * with (Spark semantics: SELECT t.id exposes column "id"); anything
         * else takes its rendered text as the column name.
         */
        private static List<AttributeReference> outputOf(List<Expression> items) {
            List<AttributeReference> out = new ArrayList<>(items.size());
            for (Expression item : items) {
                if (item instanceof Alias a) {
                    AttributeReference attr = new AttributeReference(a.name(), List.of());
                    attr.setDataType(a.child().dataType());
                    out.add(attr);
                } else if (item instanceof AttributeReference ref) {
                    AttributeReference attr = new AttributeReference(ref.name(), List.of());
                    attr.setDataType(ref.dataType());
                    out.add(attr);
                } else {
                    AttributeReference attr = new AttributeReference(item.toString(), List.of());
                    attr.setDataType(item.dataType());
                    out.add(attr);
                }
            }
            return out;
        }

        private Relation resolveSort(Sort s) {
            Relation child = resolveRelation(s.child());

            // ORDER BY sees the select-list output FIRST (a name binding to a
            // select item wins even when FROM columns share it) and the
            // projection's own FROM columns as fallback, so "SELECT name FROM
            // t ORDER BY id" keeps working. Try the output scope explicitly:
            // chaining it as a parent would hit FROM-column ambiguity first.
            Scope outputScope = new Scope(scope, scope.sessionDatabase());
            outputScope.addRelation(List.of(), child.output());
            Scope fromScope = new Scope(outputScope, scope.sessionDatabase());
            registerFromColumns(child, fromScope);

            List<Sort.SortOrder> orders = new ArrayList<>();
            for (Sort.SortOrder o : s.order()) {
                Expression resolved;
                try {
                    resolved = resolveExpr(o.expr(), outputScope);
                } catch (IllegalArgumentException e) {
                    resolved = resolveExpr(o.expr(), fromScope);
                }
                orders.add(new Sort.SortOrder(resolved, o.ascending(), o.nullsLast()));
            }
            return new Sort(child, orders);
        }

        private Relation resolveJoin(Join j) {
            Relation left = resolveRelation(j.left());
            Relation right = resolveRelation(j.right());

            // join condition sees both sides with all their visible qualifiers
            Scope joinScope = new Scope(scope, scope.sessionDatabase());
            registerOutputs(left, joinScope);
            registerOutputs(right, joinScope);
            Expression cond = j.condition() == null ? null : resolveExpr(j.condition(), joinScope);
            Join resolved = new Join(left, right, j.joinType(), cond, j.usingColumns());
            return resolved;
        }

        /**
         * Registers a resolved relation's output under every qualifier it
         * exposes. A Join is decomposed: each side registers its own output,
         * so qualified references bind to the correct half. Pass-through
         * nodes (Filter, Sort, Limit, ...) recurse to the qualifier-bearing
         * leaves beneath them.
         */
        private void registerOutputs(Relation rel, Scope target) {
            if (rel == null) return;
            if (rel instanceof Join j) {
                registerOutputs(j.left(), target);
                registerOutputs(j.right(), target);
                return;
            }
            if (rel instanceof Project || rel instanceof Aggregate) {
                // A projection hides the columns it does not select; the
                // parent block only sees its own output.
                if (rel.output().isEmpty()) return;
                target.addRelation(visibleQualifiers(rel), rel.output());
                return;
            }
            if (rel instanceof Filter f) { registerOutputs(f.child(), target); return; }
            if (rel instanceof Sort s) { registerOutputs(s.child(), target); return; }
            if (rel instanceof Limit l) { registerOutputs(l.child(), target); return; }
            if (rel instanceof Window w) { registerOutputs(w.child(), target); return; }
            // Generate (LATERAL VIEW) adds its own output columns next to the
            // child's, so the enclosing block must see both.
            if (rel.output().isEmpty()) return;
            target.addRelation(visibleQualifiers(rel), rel.output());
        }

        /**
         * Registers the columns ORDER BY / HAVING may reference beyond the
         * block's own select output: everything the projection itself saw,
         * i.e. the FROM-level columns beneath a Project/Aggregate.
         */
        private void registerFromColumns(Relation rel, Scope target) {
            if (rel instanceof Project p) {
                registerFromColumns(p.child(), target);
            } else if (rel instanceof Aggregate a) {
                registerFromColumns(a.child(), target);
            } else if (rel instanceof Sort s) {
                registerFromColumns(s.child(), target);
            } else {
                registerOutputs(rel, target);
            }
        }

        /**
         * All qualifiers under which this relation's columns are addressable.
         * Spark semantics: an alias SHADOWS the table name — once {@code FROM
         * t x} is written, only {@code x.c} resolves, never {@code t.c}.
         * Without an alias the table name is the qualifier.
         */
        private static List<String> visibleQualifiers(Relation rel) {
            if (rel instanceof TableScan t) {
                if (t.alias() != null) return List.of(t.alias());
                return List.of(t.tableName().get(t.tableName().size() - 1));
            }
            if (rel instanceof SubqueryAlias s) return List.of(s.alias());
            if (rel instanceof Generate g && g.viewAlias() != null) return List.of(g.viewAlias());
            return List.of();
        }

        private Relation resolveUnion(Union u) {
            List<Relation> children = new ArrayList<>();
            for (Relation c : u.children()) {
                children.add(new Resolver(new Scope(scope, scope.sessionDatabase()), catalog, functions, strictFunctions).resolveRelation(c));
            }
            return new Union(children, u.distinct(), u.kind());
        }

        private Relation resolveSubqueryAlias(SubqueryAlias s) {
            Scope childScope = new Scope(scope, scope.sessionDatabase());
            Relation child = new Resolver(childScope, catalog, functions, strictFunctions).resolveRelation(s.child());
            List<AttributeReference> qualified = qualify(s.alias(), child.output());
            return new SubqueryAlias(child, s.alias(), qualified);
        }

        private Relation resolveGenerate(Generate g) {
            Scope childScope = new Scope(scope, scope.sessionDatabase());
            Relation child = resolveRelation(g.child());
            registerOutputs(child, childScope);
            Expression gen = resolveExpr(g.generator(), childScope);
            // the generator's own output columns resolve inside the block as
            // bare names (t.tok through the view alias is bound at registerOutputs)
            Scope genScope = new Scope(childScope, scope.sessionDatabase());
            List<AttributeReference> genCols = new ArrayList<>();
            for (String name : g.generatorOutputNames()) {
                genCols.add(new AttributeReference(name, List.of(g.viewAlias() != null ? g.viewAlias() : name)));
            }
            genScope.addRelation(g.viewAlias() != null ? List.of(g.viewAlias()) : List.of(), genCols);
            return new Generate(gen, g.generatorOutputNames(), g.viewAlias(), child);
        }

        private Relation resolveWindow(Window wn) {
            Scope childScope = new Scope(scope, scope.sessionDatabase());
            Relation child = resolveRelation(wn.child());
            registerOutputs(child, childScope);
            List<Expression> exprs = new ArrayList<>();
            for (Expression e : wn.windowExpressions()) exprs.add(resolveExpr(e, childScope));
            Window.WindowSpec spec = wn.spec();
            Window.WindowSpec resolvedSpec = null;
            if (spec != null) {
                List<Expression> parts = new ArrayList<>();
                for (Expression p : spec.partitionBy()) parts.add(resolveExpr(p, childScope));
                List<Sort.SortOrder> orders = new ArrayList<>();
                for (Sort.SortOrder o : spec.orderBy()) {
                    orders.add(new Sort.SortOrder(resolveExpr(o.expr(), childScope), o.ascending(), o.nullsLast()));
                }
                resolvedSpec = new Window.WindowSpec(parts, orders);
            }
            return new Window(child, exprs, resolvedSpec);
        }

        private Expression resolveExpr(Expression expr, Scope s) {
            if (expr == null) return null;
            if (expr instanceof Identifier id) {
                AttributeReference resolved = s.resolveColumn(id.name());
                if (resolved == null) {
                    throw new IllegalArgumentException(
                            "Column does not exist in FROM clause: " + id.name());
                }
                // resolved carries the bound data type; rebuild would drop it
                return resolved;
            }
            if (expr instanceof AttributeReference a) {
                if (!a.qualifier().isEmpty()) {
                    AttributeReference resolved = s.resolveQualified(a.qualifier().get(a.qualifier().size() - 1), a.name());
                    if (resolved == null) {
                        throw new IllegalArgumentException(
                                "Column does not exist in FROM clause: " + a.qualifier() + "." + a.name());
                    }
                    return resolved;
                }
                AttributeReference resolved = s.resolveColumn(a.name());
                if (resolved == null) {
                    throw new IllegalArgumentException("Column does not exist in FROM clause: " + a.name());
                }
                return resolved;
            }
            if (expr instanceof Star star) {
                // Star is expanded by the generator; leave as-is but verify the
                // qualifier (if any) names a visible relation.
                if (!star.qualifier().isEmpty() && !s.hasQualifier(star.qualifier().get(star.qualifier().size() - 1))) {
                    throw new IllegalArgumentException(
                            "Relation in star expression does not exist in FROM clause: " + star.qualifier());
                }
                return star;
            }
            if (expr instanceof Alias al) {
                return new Alias(resolveExpr(al.child(), s), al.name(), al.qualifier());
            }
            if (expr instanceof Literal lit) {
                lit.setDataType(inferLiteral(lit));
                return lit;
            }
            if (expr instanceof IntervalLiteral iv) return iv;
            if (expr instanceof BinaryExpression b) {
                return new BinaryExpression(resolveExpr(b.left(), s), b.op(), resolveExpr(b.right(), s));
            }
            if (expr instanceof UnaryExpression u) {
                return new UnaryExpression(u.op(), resolveExpr(u.child(), s));
            }
            if (expr instanceof FunctionCall f) {
                bindFunction(f);
                List<Expression> args = new ArrayList<>();
                for (Expression a : f.arguments()) args.add(resolveExpr(a, s));
                FunctionCall rebuilt = new FunctionCall(f.name(), args, f.distinct());
                rebuilt.setWindowSpec(f.windowSpec());
                return rebuilt;
            }
            if (expr instanceof CaseWhen c) {
                List<CaseWhen.WhenBranch> branches = new ArrayList<>();
                for (CaseWhen.WhenBranch b : c.branches()) {
                    branches.add(new CaseWhen.WhenBranch(
                            resolveExpr(b.condition(), s), resolveExpr(b.value(), s)));
                }
                return new CaseWhen(branches, resolveExpr(c.elseValue(), s));
            }
            if (expr instanceof Cast c) {
                return new Cast(resolveExpr(c.child(), s), c.target());
            }
            if (expr instanceof InList il) {
                List<Expression> list = new ArrayList<>();
                for (Expression item : il.list()) list.add(resolveExpr(item, s));
                return new InList(resolveExpr(il.value(), s), list, il.negated());
            }
            if (expr instanceof ArrayExpr ae) {
                List<Expression> els = new ArrayList<>();
                for (Expression el : ae.elements()) els.add(resolveExpr(el, s));
                return new ArrayExpr(els);
            }
            if (expr instanceof MapExpr me) {
                List<Map.Entry<Expression, Expression>> entries = new ArrayList<>();
                for (Map.Entry<Expression, Expression> e : me.entries()) {
                    entries.add(Map.entry(resolveExpr(e.getKey(), s), resolveExpr(e.getValue(), s)));
                }
                return new MapExpr(entries);
            }
            if (expr instanceof StructExpr se) {
                List<Expression> fields = new ArrayList<>();
                for (Expression f : se.fields()) fields.add(resolveExpr(f, s));
                return new StructExpr(fields);
            }
            if (expr instanceof Lambda lam) {
                // lambda variables shadow outer columns while the body
                // resolves; DuckDB's higher-order functions bind them by name
                Scope varScope = new Scope(s, s.sessionDatabase());
                List<AttributeReference> vars = new ArrayList<>();
                for (String v : lam.variables()) {
                    vars.add(new AttributeReference(v, List.of()));
                }
                varScope.addRelation(List.of(), vars);
                return new Lambda(lam.variables(), resolveExpr(lam.body(), varScope));
            }
            if (expr instanceof ScalarSubquery sq) {
                return new ScalarSubquery(new Resolver(new Scope(s, s.sessionDatabase()), catalog, functions, strictFunctions).resolveRelation(sq.query()));
            }
            if (expr instanceof Subquery sq) {
                return new Subquery(new Resolver(new Scope(s, s.sessionDatabase()), catalog, functions, strictFunctions).resolveRelation(sq.query()));
            }
            return expr;
        }

        /**
         * Binds a call to the function catalog: unknown functions are
         * rejected in strict mode, and arity mismatches are always reported
         * when the function is known (A3 contract). In lenient mode unknown
         * functions pass through so the transition period keeps native
         * passthrough behavior.
         */
        private void bindFunction(FunctionCall f) {
            if (functions == null) return;
            Optional<FunctionInfo> info = functions.lookup(f.name());
            if (info.isEmpty()) {
                if (strictFunctions) {
                    throw new IllegalArgumentException("Function not found: " + f.name());
                }
                return;
            }
            if (!info.get().acceptsArity(f.arguments().size())) {
                throw new IllegalArgumentException(
                        "Function " + info.get().name() + " does not accept " + f.arguments().size()
                                + " argument(s): expected " + info.get().minArgs() + ".." + info.get().maxArgs());
            }
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

