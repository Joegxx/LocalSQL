package io.github.joegxx.localsql.parser;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.misc.Interval;

/**
 * A CharStream that uppercases characters for lexing but preserves the
 * original text (like Spark's UpperCaseCharStream). Keywords are defined
 * uppercase in SqlBase.g4; tokenizing through this stream makes the grammar
 * case-insensitive while token text (string literals, quoted identifiers)
 * keeps its original casing via {@link #getText(Interval)}.
 */
final class UpperCaseCharStream implements CharStream {

    private final CharStream wrapped;

    UpperCaseCharStream(CharStream wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public int LA(int offset) {
        int la = wrapped.LA(offset);
        if (la == 0 || la == IntStream.EOF) return la;
        return Character.toUpperCase(la);
    }

    @Override
    public String getText(Interval interval) {
        return wrapped.getText(interval);
    }

    @Override
    public void consume() { wrapped.consume(); }
    @Override
    public int index() { return wrapped.index(); }
    @Override
    public int size() { return wrapped.size(); }
    @Override
    public String getSourceName() { return wrapped.getSourceName(); }
    @Override
    public void seek(int index) { wrapped.seek(index); }
    @Override
    public int mark() { return wrapped.mark(); }
    @Override
    public void release(int marker) { wrapped.release(marker); }
}