package io.github.joegxx.localsql.ir;

import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.ir.type.UnknownType;
import io.github.joegxx.localsql.ir.visitor.IrVisitor;

public abstract class IrNode {
    public <R, C> R accept(IrVisitor<R, C> visitor, C context) {
        return visitor.visitNode(this, context);
    }

    private DataType resolvedType = UnknownType.INSTANCE;

    public DataType dataType() { return resolvedType; }

    public void setDataType(DataType type) { this.resolvedType = type; }
}
