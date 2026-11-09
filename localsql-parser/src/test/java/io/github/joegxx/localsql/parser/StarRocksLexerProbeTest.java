package io.github.joegxx.localsql.parser;

import com.starrocks.sql.parser.StarRocksLexer;
import com.starrocks.sql.parser.StarRocksParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0 探针：验证官方 StarRocks 3.5.21 语法在 LocalSQL 词法机制下的行为。
 * 这些行为决定后续 AstBuilder 的归一化规则，任何一条失败都要先修词法层。
 */
class StarRocksLexerProbeTest {

    private final StarRocksSqlParser parser = new StarRocksSqlParser();

    private List<Token> tokensOf(String sql, long sqlMode) {
        StarRocksLexer lexer = new StarRocksLexer(
                new UpperCaseCharStream(CharStreams.fromString(sql)));
        lexer.setSqlMode(sqlMode);
        lexer.removeErrorListeners();
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();
        return stream.getTokens();
    }

    @Test
    void lowercaseKeywordsParse() {
        parser.parseStatement("select 1");
        parser.parseStatement("SeLeCt 1 As a From dual");
    }

    @Test
    void stringLiteralCasePreserved() {
        Token literal = tokensOf("SELECT 'AbC'", StarRocksSqlParser.SQL_MODE).stream()
                .filter(t -> t.getType() == StarRocksLexer.SINGLE_QUOTED_TEXT)
                .findFirst().orElseThrow();
        assertEquals("'AbC'", literal.getText());
    }

    @Test
    void identifierCasePreserved() {
        Token id = tokensOf("SELECT myTable", StarRocksSqlParser.SQL_MODE).stream()
                .filter(t -> t.getType() == StarRocksLexer.LETTER_IDENTIFIER)
                .findFirst().orElseThrow();
        assertEquals("myTable", id.getText());
    }

    @Test
    void pipesAsConcatUnderSessionMode() {
        // sqlMode = DEFAULT | PIPES_AS_CONCAT：`||` 是 CONCAT（官方 TPC-DS 语料语义）
        List<Token> tokens = tokensOf("SELECT 'a' || 'b'", StarRocksSqlParser.SQL_MODE);
        assertTrue(tokens.stream().anyMatch(t -> t.getType() == StarRocksParser.CONCAT));
        assertTrue(tokens.stream().noneMatch(t -> t.getType() == StarRocksLexer.LOGICAL_OR));
        parser.parseStatement("SELECT 'store' || s_store_id FROM store LIMIT 100");
    }

    @Test
    void pipesAsLogicalOrUnderDefaultMode() {
        // StarRocks 默认 sql_mode(32)：`||` 是 LOGICAL_OR——证明模式开关真实生效
        List<Token> tokens = tokensOf("SELECT a || b", 32L);
        assertTrue(tokens.stream().anyMatch(t -> t.getType() == StarRocksLexer.LOGICAL_OR));
    }

    @Test
    void doubleQuotedTextIsAString() {
        // 与 Spark 相反：StarRocks 双引号是字符串（TPC-DS q16 的 as "order count"）
        List<Token> tokens = tokensOf("SELECT 1 AS \"order count\"", StarRocksSqlParser.SQL_MODE);
        assertTrue(tokens.stream().anyMatch(t -> t.getType() == StarRocksLexer.DOUBLE_QUOTED_TEXT));
        parser.parseStatement("SELECT 1 AS \"order count\"");
    }

    @Test
    void backtickIdentifierParses() {
        parser.parseStatement("SELECT `col` FROM `db`.`tbl` LIMIT 100");
    }

    @Test
    void dialectShapeSmokeParse() {
        // 3.5.21 语法形状冒烟：ROLLUP / MINUS / 窗口 / 集合运算
        parser.parseStatement("SELECT i_item_id, sum(ss_net_profit) FROM store_sales "
                + "GROUP BY ROLLUP (i_item_id) ORDER BY i_item_id LIMIT 10");
        parser.parseStatement("SELECT a FROM t1 MINUS SELECT a FROM t2");
        parser.parseStatement("SELECT x, rank() OVER (PARTITION BY y ORDER BY z) r FROM t LIMIT 100");
        parser.parseStatement("SELECT * FROM t WHERE id IN (SELECT id FROM s INTERSECT SELECT id FROM u)");
    }

    @Test
    void syntaxErrorIsReportedNotThrownFromAntlr() {
        assertThrows(StarRocksSqlParser.SqlParseException.class,
                () -> parser.parseStatement("SELEC 1"));
    }
}
