package io.github.joegxx.localsql.parser;

import com.starrocks.sql.parser.StarRocksLexer;
import com.starrocks.sql.parser.StarRocksParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * StarRocks 方言入口（语法 verbatim 拷贝自 starrocks 3.5.21 tag，唯一改动是 lexer 中
 * 两处 SqlModeHelper 引用内联为字面量，与上游 fe-grammar 模块拆分时的做法一致）。
 *
 * sqlMode 固定为 MODE_DEFAULT | MODE_PIPES_AS_CONCAT，即 {@code ||} 词法化为 CONCAT：
 * 官方 TPC-DS 基准语料（docs/zh/benchmarking/tpc_ds_99_sql.md）中 12 处 {@code ||}
 * 全部按字符串拼接使用，且 DuckDB 的 {@code ||} 即 concat，语义吻合。StarRocks
 * 默认 sql_mode 下 {@code ||} 是 LOGICAL_OR，该差异记录在 docs/starrocks-compat-plan.md。
 *
 * 关键字大小写折叠复用 {@link UpperCaseCharStream}（与 Spark 语法同一机制）。
 */
public final class StarRocksSqlParser {

    static final long SQL_MODE = 32L | (1L << 1);

    public StarRocksParser.SingleStatementContext parseStatement(String sql) {
        StarRocksLexer lexer = new StarRocksLexer(toCharStream(sql));
        lexer.setSqlMode(SQL_MODE);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        StarRocksParser parser = new StarRocksParser(tokens);
        ErrorCollector collector = new ErrorCollector();
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(collector);
        parser.addErrorListener(collector);
        StarRocksParser.SingleStatementContext ctx = parser.singleStatement();
        if (!collector.errors.isEmpty()) {
            throw new SqlParseException(String.join("; ", collector.errors));
        }
        return ctx;
    }

    private static CharStream toCharStream(String sql) {
        return new UpperCaseCharStream(CharStreams.fromString(sql));
    }

    public static final class SqlParseException extends RuntimeException {
        public SqlParseException(String message) { super(message); }
    }

    private static final class ErrorCollector extends BaseErrorListener {
        private final List<String> errors = new ArrayList<>();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            errors.add("line " + line + ":" + charPositionInLine + " " + msg);
        }
    }
}
