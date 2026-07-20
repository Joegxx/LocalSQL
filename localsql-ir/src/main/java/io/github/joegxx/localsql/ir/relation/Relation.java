package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.IrNode;
import io.github.joegxx.localsql.ir.expression.AttributeReference;

public abstract class Relation extends IrNode {
    public abstract java.util.List<AttributeReference> output();
}
