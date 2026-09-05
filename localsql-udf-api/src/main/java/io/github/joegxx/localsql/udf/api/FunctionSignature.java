package io.github.joegxx.localsql.udf.api;

import io.github.joegxx.localsql.ir.type.DataType;

import java.util.List;

public record FunctionSignature(String name, List<DataType> argumentTypes) {
    public FunctionSignature {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Function name is required");
        name = name.toLowerCase(java.util.Locale.ROOT);
        argumentTypes = List.copyOf(argumentTypes == null ? List.of() : argumentTypes);
    }
}
