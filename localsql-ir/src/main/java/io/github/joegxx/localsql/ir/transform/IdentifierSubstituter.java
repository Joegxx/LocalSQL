package io.github.joegxx.localsql.ir.transform;

import io.github.joegxx.localsql.ir.expression.Alias;
import io.github.joegxx.localsql.ir.expression.ArrayExpr;
import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.BinaryExpression;
import io.github.joegxx.localsql.ir.expression.CaseWhen;
import io.github.joegxx.localsql.ir.expression.Cast;
import io.github.joegxx.localsql.ir.expression.Expression;
import io.github.joegxx.localsql.ir.expression.FunctionCall;
import io.github.joegxx.localsql.ir.expression.Identifier;
import io.github.joegxx.localsql.ir.expression.InList;
import io.github.joegxx.localsql.ir.expression.Lambda;
import io.github.joegxx.localsql.ir.expression.MapExpr;
import io.github.joegxx.localsql.ir.expression.StructExpr;
import io.github.joegxx.localsql.ir.expression.UnaryExpression;

import java.util.List;
import java.util.Map;

/**
 * Rebuilds an expression tree replacing Identifier nodes (lambda variables)
 * with substitute expressions. Used to lower Spark's two-variable map lambdas
 * onto DuckDB's single-variable struct lambdas: k/v become e.key/e.value.
 */
public final class IdentifierSubstituter {

    private IdentifierSubstituter() {}

    public static Expression rewrite(Expression e, Map<String, Expression> substitutions) {
        if (e instanceof Identifier id && substitutions.containsKey(id.name())) {
            return substitutions.get(id.name());
        }
        if (e instanceof io.github.joegxx.localsql.ir.expression.Literal) return e;
        if (e instanceof AttributeReference a) {
            return new AttributeReference(a.name(), a.qualifier());
        }
        if (e instanceof Alias a) return new Alias(rewrite(a.child(), substitutions), a.name(), a.qualifier());
        if (e instanceof BinaryExpression b) {
            return new BinaryExpression(rewrite(b.left(), substitutions), b.op(), rewrite(b.right(), substitutions));
        }
        if (e instanceof UnaryExpression u) return new UnaryExpression(u.op(), rewrite(u.child(), substitutions));
        if (e instanceof Cast c) return new Cast(rewrite(c.child(), substitutions), c.target());
        if (e instanceof FunctionCall f) {
            List<Expression> args = f.arguments().stream()
                    .map(a -> rewrite(a, substitutions)).toList();
            return new FunctionCall(f.name(), args, f.distinct());
        }
        if (e instanceof InList i) {
            return new InList(rewrite(i.value(), substitutions),
                    i.list().stream().map(a -> rewrite(a, substitutions)).toList(), i.negated());
        }
        if (e instanceof ArrayExpr a) {
            return new ArrayExpr(a.elements().stream().map(x -> rewrite(x, substitutions)).toList());
        }
        if (e instanceof StructExpr s) {
            return new StructExpr(s.fields().stream().map(x -> rewrite(x, substitutions)).toList());
        }
        if (e instanceof MapExpr m) {
            return new MapExpr(m.entries().stream()
                    .map(en -> Map.entry(rewrite(en.getKey(), substitutions), rewrite(en.getValue(), substitutions)))
                    .toList());
        }
        if (e instanceof CaseWhen c) {
            List<CaseWhen.WhenBranch> branches = c.branches().stream()
                    .map(b -> new CaseWhen.WhenBranch(rewrite(b.condition(), substitutions),
                            rewrite(b.value(), substitutions)))
                    .toList();
            return new CaseWhen(branches, c.elseValue() == null ? null : rewrite(c.elseValue(), substitutions));
        }
        if (e instanceof Lambda l) {
            // bound variables shadow outer substitutions; keep body intact for its own scope
            Map<String, Expression> inner = new java.util.HashMap<>(substitutions);
            for (String v : l.variables()) inner.remove(v);
            return new Lambda(l.variables(), rewrite(l.body(), inner));
        }
        return e;
    }
}
