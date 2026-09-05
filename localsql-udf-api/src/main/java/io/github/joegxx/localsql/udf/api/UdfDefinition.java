package io.github.joegxx.localsql.udf.api;

import io.github.joegxx.localsql.ir.type.DataType;

import java.util.List;

public record UdfDefinition(
        String id,
        String name,
        String className,
        UdfKind kind,
        List<DataType> argumentTypes,
        DataType returnType,
        String jarPath,
        String hiveApiVersion,
        int priority) {

    public UdfDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("UDF id is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("UDF name is required");
        if (className == null || className.isBlank()) throw new IllegalArgumentException("UDF class is required");
        if (kind == null) throw new IllegalArgumentException("UDF kind is required");
        argumentTypes = List.copyOf(argumentTypes == null ? List.of() : argumentTypes);
        hiveApiVersion = hiveApiVersion == null || hiveApiVersion.isBlank() ? "default" : hiveApiVersion;
    }

    public FunctionSignature signature() {
        return new FunctionSignature(name, argumentTypes);
    }
}
