package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.analyzer.BuiltInFunctionCatalog;
import io.github.joegxx.localsql.analyzer.SemanticAnalyzer;
import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.ir.expression.CaseWhen;
import io.github.joegxx.localsql.ir.expression.Expression;
import io.github.joegxx.localsql.ir.expression.FunctionCall;
import io.github.joegxx.localsql.ir.expression.UnaryExpression;
import io.github.joegxx.localsql.ir.relation.Project;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.parser.SparkSqlParser;
import io.github.joegxx.localsql.rewrite.RewriteEngine;
import io.github.joegxx.localsql.spark.SparkAstBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 contract for the expression lowering batch: Spark calls that need
 * argument restructuring are lowered to plain IR (CASE/CAST/arith/function
 * calls) during rewrite; the generator emits the lowered form with no
 * function-specific branch left.
 */
class ExpressionLoweringContractTest {

    private final SparkAstBuilder astBuilder = new SparkAstBuilder();
    private final SparkSqlParser parser = new SparkSqlParser();

    private Relation analyzeRewrite(String sql) throws Exception {
        try (DuckDbExecutor executor = new DuckDbExecutor()) {
            CatalogStore store = new DuckDbCatalogStore(executor);
            CatalogService catalog = new CatalogService(store);
            catalog.registerTable("default", "t", "TABLE",
                    List.of(new CatalogService.ColumnDef("a",
                            new io.github.joegxx.localsql.ir.type.StringType(), true, null),
                            new CatalogService.ColumnDef("n",
                                    io.github.joegxx.localsql.ir.type.IntegralType.INT, true, null)),
                    null, "duckdb", null, null, null);
            executor.run("CREATE TABLE t (a VARCHAR, n INTEGER)");
            var stmt = new SemanticAnalyzer(catalog.catalog(), BuiltInFunctionCatalog.instance(), false)
                    .analyze(astBuilder.buildStatement(sql, s -> parser.parseStatement(s)));
            Relation rel = ((io.github.joegxx.localsql.ir.statement.QueryStatement) stmt).query();
            return new RewriteEngine().rewrite(rel);
        }
    }

    private Expression item(Relation rel) {
        assertInstanceOf(Project.class, rel);
        return ((Project) rel).projectList().get(0);
    }

    @Test
    void ifLowersToCaseWhen() throws Exception {
        Relation rel = analyzeRewrite("SELECT if(n > 1, 'x', 'y') FROM t");
        Expression e = item(rel);
        assertInstanceOf(CaseWhen.class, e);
        assertTrue(generate(rel).contains("CASE WHEN"), generate(rel));
    }

    @Test
    void isnullLowersToIsNullUnary() throws Exception {
        Relation rel = analyzeRewrite("SELECT isnull(a) FROM t");
        assertInstanceOf(UnaryExpression.class, item(rel));
        String sql = generate(rel);
        assertTrue(sql.contains("IS NULL"), sql);
        assertTrue(!sql.contains("isnull("), sql);
    }

    @Test
    void datediffReordersArguments() throws Exception {
        Relation rel = analyzeRewrite("SELECT datediff('2020-02-01', '2020-01-25') FROM t");
        FunctionCall call = unwrapCall(item(rel));
        assertEquals("date_diff", call.name());
        // date_diff('day', start, end): 'day' first, then start, then end
        assertEquals("day", String.valueOf(
                ((io.github.joegxx.localsql.ir.expression.Literal) call.arguments().get(0)).value()));
        String sql = generate(rel);
        assertTrue(sql.contains("date_diff('day'"), sql);
    }

    @Test
    void nestedLoweringInsideAggregates() throws Exception {
        Relation rel = analyzeRewrite("SELECT count_if(n > 1) FROM t GROUP BY a");
        String sql = generate(rel);
        assertTrue(sql.contains("count(CASE WHEN"), sql);
        assertTrue(!sql.contains("count_if("), sql);
    }

    @Test
    void castTypeFunctionLowersToIrCast() throws Exception {
        Relation rel = analyzeRewrite("SELECT string(a) FROM t");
        assertInstanceOf(io.github.joegxx.localsql.ir.expression.Cast.class, item(rel));
        String sql = generate(rel);
        assertTrue(sql.contains("CAST("), sql);
    }

    @Test
    void decimalFunctionCarriesPrecisionAndScale() throws Exception {
        Relation rel = analyzeRewrite("SELECT decimal(n, 8, 2) FROM t");
        io.github.joegxx.localsql.ir.expression.Cast c =
                assertInstanceOf(io.github.joegxx.localsql.ir.expression.Cast.class, item(rel));
        io.github.joegxx.localsql.ir.type.DecimalType d =
                assertInstanceOf(io.github.joegxx.localsql.ir.type.DecimalType.class, c.target());
        assertEquals(8, d.precision());
        assertEquals(2, d.scale());
        // the generated SQL carries the full type
        assertTrue(generate(rel).contains("DECIMAL(8,2)"), generate(rel));
    }

    @Test
    void dateTruncRequiresLiteralUnit() throws Exception {
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> analyzeRewrite("SELECT date_trunc(a, current_date) FROM t"));
        assertTrue(ex.getMessage().contains("literal"), () -> ex.getMessage());
    }

    @Test
    void reflectMapsWhitelistedMethods() throws Exception {
        Relation rel = analyzeRewrite("SELECT reflect('java.lang.Math', 'abs', n) FROM t");
        FunctionCall call = unwrapCall(item(rel));
        assertEquals("abs", call.name());
    }

    @Test
    void shiftleftLowersToShiftOperator() throws Exception {
        Relation rel = analyzeRewrite("SELECT shiftleft(n, 2) FROM t");
        io.github.joegxx.localsql.ir.expression.BinaryExpression b =
                assertInstanceOf(io.github.joegxx.localsql.ir.expression.BinaryExpression.class, item(rel));
        assertEquals(io.github.joegxx.localsql.ir.expression.BinaryExpression.Op.SHIFT_LEFT, b.op());
        assertTrue(generate(rel).contains("<<"), generate(rel));
    }

    @Test
    void tryAddUsesTryCastForOverflowNull() throws Exception {
        Relation rel = analyzeRewrite("SELECT try_add(n, n) FROM t");
        io.github.joegxx.localsql.ir.expression.BinaryExpression b =
                assertInstanceOf(io.github.joegxx.localsql.ir.expression.BinaryExpression.class, item(rel));
        io.github.joegxx.localsql.ir.expression.Cast left =
                assertInstanceOf(io.github.joegxx.localsql.ir.expression.Cast.class, b.left());
        assertTrue(left.tryCast());
        String sql = generate(rel);
        assertTrue(sql.contains("TRY_CAST("), sql);
    }

    private FunctionCall unwrapCall(Expression e) {
        if (e instanceof FunctionCall f) return f;
        if (e instanceof io.github.joegxx.localsql.ir.expression.Alias a) return unwrapCall(a.child());
        if (e instanceof io.github.joegxx.localsql.ir.expression.Cast c) return unwrapCall(c.child());
        throw new IllegalStateException("no call under: " + e);
    }

    private String generate(Relation rel) {
        return new io.github.joegxx.localsql.duckdb.DuckDbSqlGenerator().generate(rel);
    }
}
