package io.github.joegxx.localsql.ir.type;

import java.util.List;

public record StructType(List<StructField> fields) implements DataType {
    @Override public String typeName() {
        return "STRUCT<" + fields.stream().map(f -> f.name() + ":" + f.dataType().typeName()).reduce((a, b) -> a + "," + b).orElse("") + ">";
    }
}
