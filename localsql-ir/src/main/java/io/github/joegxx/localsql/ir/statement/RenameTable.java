package io.github.joegxx.localsql.ir.statement;

import java.util.List;

/** ALTER TABLE/VIEW from RENAME TO to. */
public final class RenameTable extends Statement {
    private final List<String> from;
    private final List<String> to;

    public RenameTable(List<String> from, List<String> to) {
        this.from = List.copyOf(from);
        this.to = List.copyOf(to);
    }

    public List<String> from() { return from; }
    public List<String> to() { return to; }

    @Override
    public String toString() { return "RenameTable " + String.join(".", from) + " -> " + String.join(".", to); }
}
