package io.github.joegxx.localsql.ir.statement;

import io.github.joegxx.localsql.ir.IrNode;

/**
 * Base class for top-level SQL statements. Unlike Relation, a statement does
 * not produce rows by itself; the executor dispatches on the concrete subclass.
 */
public abstract class Statement extends IrNode {}
