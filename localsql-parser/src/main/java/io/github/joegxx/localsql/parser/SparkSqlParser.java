package io.github.joegxx.localsql.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.apache.spark.sql.catalyst.parser.SqlBaseLexer;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser.SingleStatementContext;

import java.util.ArrayList;
import java.util.List;

public final class SparkSqlParser {

    public SingleStatementContext parseStatement(String sql) {
        SqlBaseLexer lexer = new SqlBaseLexer(toCharStream(sql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        SqlBaseParser parser = new SqlBaseParser(tokens);
        ErrorCollector collector = new ErrorCollector();
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(collector);
        parser.addErrorListener(collector);
        SingleStatementContext ctx = parser.singleStatement();
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
