package io.github.joegxx.localsql.rewrite;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Cast;
import io.github.joegxx.localsql.ir.expression.FunctionCall;
import io.github.joegxx.localsql.ir.expression.Identifier;
import io.github.joegxx.localsql.ir.expression.Literal;
import io.github.joegxx.localsql.ir.relation.Project;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.relation.TableScan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 方言作用域契约：StarRocks 专属改名/降级只对 scope=true 生效；共享表两个
 * scope 都生效。这是"Spark 会话行为不被 StarRocks 批次静默放宽"的守卫。
 */
class DialectScopeRewriteTest {

    private static Relation project(String fn, Object... args) {
        List<io.github.joegxx.localsql.ir.expression.Expression> exprs = new java.util.ArrayList<>();
        exprs.add(new AttributeReference("a", List.of()));
        List<io.github.joegxx.localsql.ir.expression.Expression> callArgs = new java.util.ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof String s) callArgs.add(new Identifier(s, false));
            else callArgs.add((io.github.joegxx.localsql.ir.expression.Expression) arg);
        }
        exprs.add(new FunctionCall(fn, callArgs));
        return new Project(new TableScan(List.of("t"), List.of()), exprs);
    }

    @Test
    void starRocksOnlyRenamesApplyOnlyInScope() {
        FunctionCall in = (FunctionCall) ((Project) project(
                "str_to_date", "x", new Literal("%Y-%m-%d", Literal.Kind.STRING)))
                .projectList().get(1);

        Relation spark = new RewriteEngine(Set.of(), Set.of(), false).rewrite(new Project(
                new TableScan(List.of("t"), List.of()), List.of(in)));
        assertEquals("str_to_date", ((FunctionCall) ((Project) spark).projectList().get(0)).name(),
                "Spark scope must leave StarRocks-only names untouched");

        Relation starRocks = new RewriteEngine(Set.of(), Set.of(), true).rewrite(new Project(
                new TableScan(List.of("t"), List.of()), List.of(in)));
        assertEquals("strptime", ((FunctionCall) ((Project) starRocks).projectList().get(0)).name());
    }

    @Test
    void starRocksOnlyLoweringsApplyOnlyInScope() {
        FunctionCall in = (FunctionCall) ((Project) project("get_json_int", "j", "$.a"))
                .projectList().get(1);

        Relation spark = new RewriteEngine(Set.of(), Set.of(), false).rewrite(new Project(
                new TableScan(List.of("t"), List.of()), List.of(in)));
        assertEquals("get_json_int", ((FunctionCall) ((Project) spark).projectList().get(0)).name());

        Relation starRocks = new RewriteEngine(Set.of(), Set.of(), true).rewrite(new Project(
                new TableScan(List.of("t"), List.of()), List.of(in)));
        Cast cast = (Cast) ((Project) starRocks).projectList().get(0);
        assertEquals("json_extract_string", ((FunctionCall) cast.child()).name());
    }

    @Test
    void sharedRenamesApplyInBothScopes() {
        FunctionCall in = (FunctionCall) ((Project) project("nvl", "x", "y")).projectList().get(1);
        for (boolean scope : new boolean[]{false, true}) {
            Relation rel = new RewriteEngine(Set.of(), Set.of(), scope).rewrite(new Project(
                    new TableScan(List.of("t"), List.of()), List.of(in)));
            assertEquals("coalesce", ((FunctionCall) ((Project) rel).projectList().get(0)).name(),
                    "shared table must apply in both scopes");
        }
    }

    @Test
    void starRocksTablesHaveNoSparkVocabularyCollision() {
        // 碰撞审计：专属表的名字不得出现在共享表或保留名集合里
        for (String name : FunctionNameRewriter.STARROCKS_RENAMES.keySet()) {
            assertEquals(false, FunctionNameRewriter.RENAMES.containsKey(name),
                    "collision in rename tables: " + name);
        }
    }
}
