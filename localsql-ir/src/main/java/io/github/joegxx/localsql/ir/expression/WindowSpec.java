package io.github.joegxx.localsql.ir.expression;

import io.github.joegxx.localsql.ir.relation.Sort;

import java.util.List;

/**
 * Window specification attached to a FunctionCall: PARTITION BY / ORDER BY / frame.
 * Frame bounds follow SQL: UNBOUNDED PRECEDING .. CURRENT ROW .. UNBOUNDED FOLLOWING,
 * or a value expression offset.
 */
public final class WindowSpec {

    public record Frame(boolean rows, Bound start, Bound end) {}

    public record Bound(Type type, Expression offset) {
        public enum Type { UNBOUNDED_PRECEDING, UNBOUNDED_FOLLOWING, CURRENT_ROW, PRECEDING, FOLLOWING }
        public static Bound unboundedPreceding() { return new Bound(Type.UNBOUNDED_PRECEDING, null); }
        public static Bound unboundedFollowing() { return new Bound(Type.UNBOUNDED_FOLLOWING, null); }
        public static Bound currentRow() { return new Bound(Type.CURRENT_ROW, null); }
    }

    private final List<Expression> partitionBy;
    private final List<Sort.SortOrder> orderBy;
    private final Frame frame;

    public WindowSpec(List<Expression> partitionBy, List<Sort.SortOrder> orderBy, Frame frame) {
        this.partitionBy = List.copyOf(partitionBy);
        this.orderBy = List.copyOf(orderBy);
        this.frame = frame;
    }

    public List<Expression> partitionBy() { return partitionBy; }
    public List<Sort.SortOrder> orderBy() { return orderBy; }
    public Frame frame() { return frame; }

    @Override
    public String toString() {
        return "WindowSpec[partition=" + partitionBy + ", order=" + orderBy + ", frame=" + frame + "]";
    }
}