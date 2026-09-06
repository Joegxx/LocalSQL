package io.github.joegxx.localsql.rewrite;

import io.github.joegxx.localsql.ir.expression.Alias;
import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;
import io.github.joegxx.localsql.ir.expression.FunctionCall;
import io.github.joegxx.localsql.ir.expression.Identifier;
import io.github.joegxx.localsql.ir.expression.Star;
import io.github.joegxx.localsql.ir.relation.Filter;
import io.github.joegxx.localsql.ir.relation.Generate;
import io.github.joegxx.localsql.ir.relation.Limit;
import io.github.joegxx.localsql.ir.relation.Project;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.relation.Sort;
import io.github.joegxx.localsql.ir.relation.TableScan;
import io.github.joegxx.localsql.ir.visitor.IrVisitor;
import io.github.joegxx.localsql.udf.api.UdfDefinition;
import io.github.joegxx.localsql.udf.api.UdfKind;
import io.github.joegxx.localsql.udf.api.UdfRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Splits queries containing registered GenericUDTF (TABLE kind) calls.
 * The LATERAL VIEW Generate node is replaced by a physical DuckDB temp
 * table: stage 1 materializes the source columns plus UDTF argument
 * expressions, the gateway expands rows, the pipeline creates the temp
 * table and this splitter rebuilds the IR around a TableScan of it.
 *
 * <p>MVP placement: {@code SELECT <items> FROM src [filters] LATERAL VIEW
 * udtf(args) [alias] AS c1... [filters] [ORDER BY] [LIMIT]} where outer
 * references are unqualified column names and items are plain column
 * references (optionally aliased). Everything else is rejected with an
 * explicit error instead of silently mis-translating.
 */
public final class UdtfGenerateSplitter {

    public record UdtfPlan(
            Relation stage1,
            UdfDefinition definition,
            List<Integer> argIndexes,
            List<String> outputNames,
            List<Expression> postFilters,
            Sort sortWrapper,
            Limit limitWrapper,
            List<String> sourceNames,
            List<Expression> projectItems,
            boolean distinct) {}

    private final UdfRegistry registry;

    public UdtfGenerateSplitter(UdfRegistry registry) {
        this.registry = registry;
    }

    /** Returns null when no registered TABLE UDF applies to this query. */
    public UdtfPlan trySplit(Relation top) {
        if (registry == null || registry.definitions().stream().noneMatch(d -> d.kind() == UdfKind.TABLE)) {
            return null;
        }
        // Cheap pre-check: never reject or reshape queries without a TABLE udf,
        // so GENERIC/SCALAR udfs and plain builtins take the normal path.
        List<FunctionCall> present = new ArrayList<>();
        collectUdtfsInto(top, present);
        if (present.isEmpty()) return null;

        Sort sort = null;
        Limit limit = null;
        Relation current = top;
        if (current instanceof Sort s) { sort = s; current = s.child(); }
        if (current instanceof Limit l) { limit = l; current = l.child(); }
        if (!(current instanceof Project project)) {
            requireNoUdtf(top, "only top-level SELECT ... LATERAL VIEW is supported");
            return null;
        }
        validateOuterItems(project);

        // walk down: collect post-expansion filters until the Generate node
        List<Expression> postFilters = new ArrayList<>();
        Relation chain = project.child();
        Generate generate = null;
        while (true) {
            if (chain instanceof Filter f) {
                postFilters.add(f.condition());
                chain = f.child();
            } else if (chain instanceof Generate g) {
                generate = g;
                break;
            } else {
                requireNoUdtf(top, "LATERAL VIEW must sit directly under the SELECT (filters between "
                        + "SELECT and LATERAL VIEW are supported; other operators are not)");
                return null;
            }
        }

        if (!(generate.generator() instanceof FunctionCall call) || call.windowSpec() != null) {
            requireNoUdtf(top, "LATERAL VIEW generator must be a registered Java UDTF function call");
            return null;
        }
        UdfDefinition definition = registry.resolveByName(call.name(), call.arguments().size())
                .filter(d -> d.kind() == UdfKind.TABLE)
                .orElse(null);
        if (definition == null) {
            // generator is a builtin (e.g. explode) or unregistered: leave the
            // query untouched unless a TABLE udf appears elsewhere in the tree
            requireNoUdtf(top, "only the LATERAL VIEW generator position is supported");
            return null;
        }

        List<FunctionCall> nested = new ArrayList<>();
        for (Expression arg : call.arguments()) collectUdtfsInto(arg, nested);
        if (!nested.isEmpty()) {
            throw new UnsupportedOperationException(
                    "UDTF arguments must not contain other Java UDF calls: " + nested);
        }
        Set<String> seen = new HashSet<>();
        List<String> sourceNames = new ArrayList<>();
        for (AttributeReference attr : generate.child().output()) {
            if (!seen.add(attr.name().toLowerCase())) {
                throw new UnsupportedOperationException(
                        "Duplicate source column name under LATERAL VIEW: " + attr.name());
            }
            sourceNames.add(attr.name());
        }

        // stage 1: source columns + UDTF argument expressions
        List<Expression> stage1Items = new ArrayList<>();
        stage1Items.addAll(generate.child().output());
        List<Integer> argIndexes = new ArrayList<>();
        for (Expression arg : call.arguments()) {
            stage1Items.add(new Alias(arg, "__udtf_arg_" + argIndexes.size(), List.of()));
            argIndexes.add(stage1Items.size() - 1);
        }
        Relation stage1 = new Project(generate.child(), stage1Items);
        return new UdtfPlan(stage1, definition, argIndexes,
                List.copyOf(generate.generatorOutputNames()), List.copyOf(postFilters),
                sort, limit, List.copyOf(sourceNames),
                List.copyOf(project.projectList()), project.distinct());
    }

    /** Rebuilds the original query shape around the materialized UDTF table. */
    public Relation rebuild(Relation expandedTable, UdtfPlan plan) {
        Relation rel = expandedTable;
        for (int i = plan.postFilters().size() - 1; i >= 0; i--) {
            rel = new Filter(rel, plan.postFilters().get(i));
        }
        if (plan.limitWrapper() != null) rel = new Limit(rel, plan.limitWrapper().limit(), plan.limitWrapper().offset());
        if (plan.sortWrapper() != null) rel = new Sort(rel, plan.sortWrapper().order());
        return new Project(rel, plan.projectItems(), plan.distinct());
    }

    /** Physical temp-table scan with unqualified output attributes. */
    public Relation expandedScan(String tempTableName, List<String> columnNames) {
        List<AttributeReference> output = new ArrayList<>();
        for (String name : columnNames) output.add(new AttributeReference(name, List.of()));
        return new TableScan(List.of(tempTableName), output);
    }

    private void validateOuterItems(Project project) {
        if (project.distinct()) {
            throw new UnsupportedOperationException(
                    "SELECT DISTINCT combined with Java UDTF is not supported yet");
        }
        for (Expression item : project.projectList()) {
            if (item instanceof Star) {
                throw new UnsupportedOperationException(
                        "SELECT * combined with Java UDTF is not supported yet");
            }
            Expression inner = item;
            if (item instanceof Alias a) inner = a.child();
            boolean plainColumn = inner instanceof AttributeReference || inner instanceof Identifier;
            if (!plainColumn) {
                throw new UnsupportedOperationException(
                        "With a Java UDTF, SELECT items must be plain column references "
                                + "(optionally aliased); got: " + item);
            }
            if (inner instanceof AttributeReference ref && !ref.qualifier().isEmpty()) {
                throw new UnsupportedOperationException(
                        "Qualified references (" + ref + ") are not supported with Java UDTF yet; "
                                + "use unqualified column names");
            }
        }
    }

    private void requireNoUdtf(Relation rel, String placement) {
        List<FunctionCall> found = new ArrayList<>();
        collectUdtfsInto(rel, found);
        if (!found.isEmpty()) {
            throw new UnsupportedOperationException(
                    "Java UDTF placement not supported yet (" + placement + "): " + found);
        }
    }

    private void collectUdtfsInto(Object node, List<FunctionCall> out) {
        UdtfScanVisitor v = new UdtfScanVisitor(out);
        if (node instanceof Expression e) v.visit(e, null);
        else if (node instanceof Relation r) v.visitRelation(r, null);
    }

    private final class UdtfScanVisitor extends IrVisitor<Void, Void> {
        private final List<FunctionCall> out;
        UdtfScanVisitor(List<FunctionCall> out) { this.out = out; }

        @Override
        public Void visitFunctionCall(FunctionCall e, Void ctx) {
            if (registry.resolveByName(e.name(), e.arguments().size())
                    .map(d -> d.kind() == UdfKind.TABLE).orElse(false)) {
                out.add(e);
            }
            return super.visitFunctionCall(e, ctx);
        }
    }
}
