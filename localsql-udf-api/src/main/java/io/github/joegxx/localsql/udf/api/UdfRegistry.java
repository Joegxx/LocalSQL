package io.github.joegxx.localsql.udf.api;

import io.github.joegxx.localsql.ir.type.DataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class UdfRegistry {
    private final Map<FunctionSignature, List<UdfDefinition>> definitions = new LinkedHashMap<>();

    public void register(UdfDefinition definition) {
        FunctionSignature signature = definition.signature();
        List<UdfDefinition> candidates = definitions.computeIfAbsent(signature, ignored -> new ArrayList<>());
        if (candidates.stream().anyMatch(existing -> existing.id().equals(definition.id()))) {
            throw new IllegalArgumentException("UDF is already registered: " + definition.id());
        }
        candidates.add(definition);
        candidates.sort(Comparator.comparingInt(UdfDefinition::priority).reversed());
    }

    public Optional<UdfDefinition> resolve(String name, List<DataType> argumentTypes) {
        FunctionSignature signature = new FunctionSignature(name.toLowerCase(Locale.ROOT), argumentTypes);
        List<UdfDefinition> candidates = definitions.get(signature);
        return candidates == null || candidates.isEmpty()
                ? Optional.empty() : Optional.of(candidates.get(0));
    }

    /**
     * Resolve by name and argument count only (highest priority first). Used
     * by the SQL pipeline where argument types are not reliably known yet.
     */
    public Optional<UdfDefinition> resolveByName(String name, int arity) {
        String key = name.toLowerCase(Locale.ROOT);
        return definitions.values().stream()
                .flatMap(List::stream)
                .filter(d -> d.signature().name().equals(key) && d.argumentTypes().size() == arity)
                .findFirst();
    }

    public List<UdfDefinition> definitions() {
        return definitions.values().stream().flatMap(List::stream).toList();
    }
}
