package io.github.joegxx.localsql.starrocks;

import com.starrocks.sql.parser.StarRocksParser;
import io.github.joegxx.localsql.ir.expression.Alias;
import io.github.joegxx.localsql.ir.expression.BinaryExpression;
import io.github.joegxx.localsql.ir.expression.Cast;
import io.github.joegxx.localsql.ir.expression.Expression;
import io.github.joegxx.localsql.ir.expression.FunctionCall;
import io.github.joegxx.localsql.ir.expression.InList;
import io.github.joegxx.localsql.ir.expression.Literal;
import io.github.joegxx.localsql.ir.expression.Subquery;
import io.github.joegxx.localsql.ir.relation.Aggregate;
import io.github.joegxx.localsql.ir.relation.Limit;
import io.github.joegxx.localsql.ir.relation.Project;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.relation.TableScan;
import io.github.joegxx.localsql.ir.relation.Union;
import io.github.joegxx.localsql.ir.statement.QueryStatement;
import io.github.joegxx.localsql.ir.statement.Statement;
import io.github.joegxx.localsql.ir.type.TimestampType;
import io.github.joegxx.localsql.parser.StarRocksSqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 归一化孪生回归：StarRocks 方言形状 -> IR 的 Spark 约定。每条对应一个方言差异，
 * 防止归一化悄悄走样。
 */
class StarRocksAstBuilderTest {

    private final StarRocksSqlParser parser = new StarRocksSqlParser();
    private final StarRocksAstBuilder builder = new StarRocksAstBuilder();

    private Relation rel(String sql) {
        Statement stmt = builder.buildStatement(sql, parser::parseStatement);
        return ((QueryStatement) stmt).query();
    }

    @Test
    void limitCommaMapsToOffsetAndLimit() {
        // LIMIT offset, count (MySQL 风格) -> Limit(count, offset)
        Limit limit = (Limit) rel("SELECT a FROM t LIMIT 5, 10");
        assertEquals(10, limit.limit());
        assertEquals(5, limit.offset());
        Limit offsetForm = (Limit) rel("SELECT a FROM t LIMIT 10 OFFSET 5");
        assertEquals(10, offsetForm.limit());
        assertEquals(5, offsetForm.offset());
    }

    @Test
    void minusMapsToExcept() {
        Union union = (Union) rel("SELECT a FROM t1 MINUS SELECT a FROM t2");
        assertEquals(Union.Kind.EXCEPT, union.kind());
    }

    @Test
    void doubleQuotedAliasBecomesAliasName() {
        // 与 Spark 相反：双引号是字符串，可作别名（TPC-DS q16 的 as "order count"）
        Project project = (Project) rel("SELECT 1 AS \"order count\"");
        Alias alias = (Alias) project.projectList().get(0);
        assertEquals("order count", alias.name());
    }

    @Test
    void rollupMapsToGroupingAnalytics() {
        Aggregate aggregate = (Aggregate) rel("SELECT a, count(*) FROM t GROUP BY ROLLUP (a)");
        assertEquals(1, aggregate.groupingAnalytics().size());
        assertEquals(Aggregate.GroupingAnalytics.Kind.ROLLUP, aggregate.groupingAnalytics().get(0).kind());
    }

    @Test
    void concatTokenMapsToStringConcat() {
        // sqlMode=PIPES_AS_CONCAT：`||` 词法化为 CONCAT -> STRING_CONCAT
        Project project = (Project) rel("SELECT 'store' || a FROM t");
        BinaryExpression concat = (BinaryExpression) project.projectList().get(0);
        assertEquals(BinaryExpression.Op.STRING_CONCAT, concat.op());
    }

    @Test
    void threePartNameKeepsAllSegments() {
        // catalog.db.table 原样进 IR；analyzer 的 resolveTableScan 取后两段
        Project project = (Project) rel("SELECT a FROM hive_catalog.hive_db.hive_table");
        TableScan scan = (TableScan) project.child();
        assertEquals(3, scan.tableName().size());
        assertEquals("hive_catalog", scan.tableName().get(0));
        assertEquals("hive_table", scan.tableName().get(2));
    }

    @Test
    void datetimeLiteralMapsToTimestamp() {
        Project project = (Project) rel("SELECT DATETIME '2026-01-01 00:00:00'");
        Literal literal = (Literal) project.projectList().get(0);
        assertEquals(Literal.Kind.TIMESTAMP, literal.kind());
        Literal date = (Literal) ((Project) rel("SELECT DATE '2026-01-01'")).projectList().get(0);
        assertEquals(Literal.Kind.DATE, date.kind());
    }

    @Test
    void castDatetimeMapsToTimestampType() {
        Project project = (Project) rel("SELECT CAST(a AS DATETIME) FROM t");
        Cast cast = (Cast) project.projectList().get(0);
        assertInstanceOf(TimestampType.class, cast.target());
    }

    @Test
    void keywordShiftsRouteThroughSharedFunctionNames() {
        // BITSHIFTLEFT 等关键字形式 -> shiftleft 函数（ExpressionLowering 转 IR 位移）
        Project project = (Project) rel("SELECT a BITSHIFTLEFT 2 FROM t");
        FunctionCall call = (FunctionCall) project.projectList().get(0);
        assertEquals("shiftleft", call.name());
    }

    @Test
    void inSubqueryAndNullSafeEqual() {
        Project project = (Project) rel(
                "SELECT a FROM t WHERE a <=> 1 AND b IN (SELECT x FROM s)");
        io.github.joegxx.localsql.ir.relation.Filter filter =
                (io.github.joegxx.localsql.ir.relation.Filter) project.child();
        BinaryExpression and = (BinaryExpression) filter.condition();
        assertEquals(BinaryExpression.Op.AND, and.op());
        assertEquals(BinaryExpression.Op.EQ_NULL_SAFE, ((BinaryExpression) and.left()).op());
        InList inList = (InList) and.right();
        assertInstanceOf(Subquery.class, inList.list().get(0));
    }

    @Test
    void windowOverPreservedInline() {
        // 窗口函数以 FunctionCall.windowSpec 内联表达（与 Spark 路径同构）
        Project project = (Project) rel("SELECT rank() OVER (ORDER BY a) FROM t");
        FunctionCall rank = (FunctionCall) project.projectList().get(0);
        assertEquals("rank", rank.name());
        assertEquals(true, rank.windowSpec() != null);

        FunctionCall sum = (FunctionCall) ((Project) rel(
                "SELECT sum(a) OVER (ORDER BY b ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM t"))
                .projectList().get(0);
        assertEquals(true, sum.windowSpec() != null);
        assertEquals(true, sum.windowSpec().frame() != null);
    }

    @Test
    void explicitRejects() {
        assertThrows(UnsupportedOperationException.class, () -> rel(
                "SELECT a FROM t QUALIFY rank() OVER (ORDER BY b) = 1"));
        assertThrows(UnsupportedOperationException.class, () -> rel(
                "SELECT a FROM t PIVOT (sum(x) FOR y IN (1, 2))"));
        assertThrows(UnsupportedOperationException.class, () -> rel(
                "SELECT a FROM t JOIN LATERAL fn(a) v"));
        assertThrows(UnsupportedOperationException.class, () -> rel(
                "SELECT * FROM (VALUES (1,2)) t (a, b)"));
        assertThrows(UnsupportedOperationException.class, () -> rel(
                "SELECT a FROM TABLE (fn(1))"));
        assertThrows(UnsupportedOperationException.class, () -> rel(
                "CREATE TABLE t (a INT)"));
        assertThrows(UnsupportedOperationException.class, () -> rel(
                "SELECT a, b FROM t WHERE (a, b) IN (SELECT x, y FROM s)"));
        assertThrows(UnsupportedOperationException.class, () -> rel(
                "SELECT first_value(a IGNORE NULLS) OVER (ORDER BY b) FROM t"));
    }
}
