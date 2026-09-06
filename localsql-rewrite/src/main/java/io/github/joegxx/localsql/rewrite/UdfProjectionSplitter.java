package io.github.joegxx.localsql.rewrite;

import io.github.joegxx.localsql.ir.expression.Alias;
import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;
import io.github.joegxx.localsql.ir.expression.FunctionCall;
import io.github.joegxx.localsql.ir.expression.Star;
import io.github.joegxx.localsql.ir.relation.Limit;
import io.github.joegxx.localsql.ir.relation.Project;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.relation.Sort;
import io.github.joegxx.localsql.ir.visitor.IrVisitor;
import io.github.joegxx.localsql.udf.api.UdfDefinition;
import io.github.joegxx.localsql.udf.api.UdfRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Splits the top-level projection of an analyzed query when it contains
 * registered Java UDF calls. The stage-1 relation replaces every UDF call
 * with its argument expressions; the caller executes stage 1, runs the UDFs
 * through the gateway and reassembles the final result set.
 *
 * <p>Supported placement: a UDF call may be a top-level projection item,
 * optionally wrapped in an alias, under optional Sort/Limit wrappers whose
 * ORDER BY / LIMIT do not reference UDF outputs. UDFs nested inside larger
 * expressions or appearing in WHERE / JOIN / GROUP BY / window specs are
 * rejected with an explicit error instead of silently changing semantics.
 */
public final class UdfProjectionSplitter {

    public sealed interface OutputSpec {
        record Column(int stage1Index) implements OutputSpec {}
        record Udf(UdfDefinition definition, List<Integer> argIndexes, String name) implements OutputSpec {}
    }

    public record SplitPlan(Relation stage1, List<OutputSpec> outputs) {}

    private final UdfRegistry registry;

    public UdfProjectionSplitter(UdfRegistry registry) {
        this.registry = registry;
    }

    /** Returns null when no registered UDF applies to this query. */
    public SplitPlan trySplit(Relation top) {
        if (registry == null || registry.definitions().isEmpty()) return null;

        Sort sort = null;
        Limit limit = null;
        Relation current = top;
        if (current instanceof Sort s) { sort = s; current = s.child(); }
        if (current instanceof Limit l) { limit = l; current = l.child(); }
        if (!(current instanceof Project project)) {
            requireNoUdf(top, "only top-level SELECT projection is supported");
            return null;
        }

        if (project.distinct() && !collectUdfCalls(project).isEmpty()) {
            throw new UnsupportedOperationException(
                    "SELECT DISTINCT combined with Java UDF is not supported yet");
        }
        List<FunctionCall> orderUdfs = new ArrayList<>();
        if (sort != null) for (Sort.SortOrder o : sort.order()) collectUdfsInto(o.expr(), orderUdfs);
        if (!orderUdfs.isEmpty()) {
            throw new UnsupportedOperationException(
                    "ORDER BY must not contain Java UDF calls; found: " + orderUdfs);
        }

        List<Expression> stage1Items = new ArrayList<>();
        List<OutputSpec> outputs = new ArrayList<>();
        boolean anyUdf = false;
        for (Expression item : project.projectList()) {
            Expression inner = item instanceof Alias a ? a.child() : item;
            Optional<UdfDefinition> udf = inner instanceof FunctionCall call && call.windowSpec() == null
                    ? resolveUdf(call) : Optional.empty();
            if (udf.isPresent()) {
                anyUdf = true;
                FunctionCall call = (FunctionCall) inner;
                List<Integer> argIndexes = new ArrayList<>();
                for (Expression arg : call.arguments()) {
                    stage1Items.add(new Alias(arg, "__udf_arg_" + outputs.size() + "_" + argIndexes.size(), List.of()));
                    argIndexes.add(stage1Items.size() - 1);
                }
                String name = item instanceof Alias a ? a.name() : udf.get().name();
                outputs.add(new OutputSpec.Udf(udf.get(), argIndexes, name));
            } else {
                List<FunctionCall> nested = new ArrayList<>();
                collectUdfsInto(item, nested);
                if (!nested.isEmpty()) {
                    throw new UnsupportedOperationException(
                            "Java UDF calls are only supported as top-level projection items; "
                                    + "found nested inside: " + item);
                }
                if (item instanceof Star) {
                    throw new UnsupportedOperationException(
                            "SELECT * combined with Java UDF is not supported yet");
                }
                stage1Items.add(item);
                outputs.add(new OutputSpec.Column(stage1Items.size() - 1));
            }
        }
        if (!anyUdf) {
            requireNoUdf(top, "only top-level SELECT projection is supported (not WHERE/JOIN/aggregate/window)");
            return null;
        }

        Project stage1 = new Project(project.child(), stage1Items);
        Relation wrapped = stage1;
        if (limit != null) wrapped = new Limit(stage1, limit.limit(), limit.offset());
        if (sort != null) wrapped = new Sort(wrapped, sort.order());
        return new SplitPlan(wrapped, List.copyOf(outputs));
    }

    private Optional<UdfDefinition> resolveUdf(FunctionCall call) {
        return registry.resolveByName(call.name(), call.arguments().size());
    }

    private void requireNoUdf(Relation rel, String placement) {
        List<FunctionCall> found = new ArrayList<>();
        collectUdfsInto(rel, found);
        if (!found.isEmpty()) {
            throw new UnsupportedOperationException(
                    "Java UDF placement not supported yet (" + placement + "): " + found);
        }
    }

    private List<FunctionCall> collectUdfCalls(Project project) {
        List<FunctionCall> found = new ArrayList<>();
        for (Expression item : project.projectList()) collectUdfsInto(item, found);
        return found;
    }

    /** Collects all FunctionCalls matching the registry anywhere under the node. */
    private void collectUdfsInto(Object node, List<FunctionCall> out) {
        UdfScanVisitor v = new UdfScanVisitor(out);
        if (node instanceof Expression e) v.visit(e, null);
        else if (node instanceof Relation r) v.visitRelation(r, null);
    }

    private final class UdfScanVisitor extends IrVisitor<Void, Void> {
        private final List<FunctionCall> out;
        UdfScanVisitor(List<FunctionCall> out) { this.out = out; }

        @Override
        public Void visitFunctionCall(FunctionCall e, Void ctx) {
            if (resolveUdf(e).isPresent()) out.add(e);
            return super.visitFunctionCall(e, ctx);
        }
    }
}
