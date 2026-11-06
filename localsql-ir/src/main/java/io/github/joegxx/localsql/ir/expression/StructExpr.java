package io.github.joegxx.localsql.ir.expression;

import java.util.List;

public final class StructExpr extends Expression {
    private final List<Expression> fields;
    private final List<String> fieldNames;

    public StructExpr(List<Expression> fields) {
        this(fields, null);
    }

    /**
     * @param fieldNames field labels for the packed form ({@code name := value});
     *                   null for positional/anonymous structs.
     */
    public StructExpr(List<Expression> fields, List<String> fieldNames) {
        this.fields = List.copyOf(fields);
        this.fieldNames = fieldNames == null ? null : List.copyOf(fieldNames);
        if (fieldNames != null && fieldNames.size() != this.fields.size()) {
            throw new IllegalArgumentException("field name count does not match field count");
        }
    }

    public List<Expression> fields() { return fields; }

    /** Field labels, or null when the struct is anonymous. */
    public List<String> fieldNames() { return fieldNames; }

    @Override
    public String toString() {
        return "struct(" + String.join(", ", fields.stream().map(Object::toString).toList()) + ")";
    }
}
