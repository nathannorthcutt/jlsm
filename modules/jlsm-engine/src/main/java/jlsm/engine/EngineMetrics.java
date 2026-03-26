package jlsm.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A snapshot of engine-wide handle counts and statistics.
 *
 * <p>
 * Governed by: {@code .decisions/engine-api-surface-design/adr.md}
 *
 * @param tableCount the number of registered tables
 * @param totalOpenHandles the total number of open table handles across all tables
 * @param handlesPerTable open handle count per table name; never null
 * @param handlesPerSourcePerTable open handle count per source per table; never null
 */
public record EngineMetrics(int tableCount, int totalOpenHandles,
        Map<String, Integer> handlesPerTable,
        Map<String, Map<String, Integer>> handlesPerSourcePerTable) {

    public EngineMetrics {
        assert tableCount >= 0 : "tableCount must be non-negative";
        assert totalOpenHandles >= 0 : "totalOpenHandles must be non-negative";
        Objects.requireNonNull(handlesPerTable, "handlesPerTable must not be null");
        Objects.requireNonNull(handlesPerSourcePerTable,
                "handlesPerSourcePerTable must not be null");
        handlesPerTable = Map.copyOf(handlesPerTable);
        // Deep copy: ensure inner maps are also unmodifiable
        final var deepCopy = new HashMap<String, Map<String, Integer>>(
                handlesPerSourcePerTable.size());
        for (final var entry : handlesPerSourcePerTable.entrySet()) {
            deepCopy.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        handlesPerSourcePerTable = Map.copyOf(deepCopy);
    }
}
