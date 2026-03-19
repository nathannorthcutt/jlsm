package jlsm.engine;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngineMetricsTest {

    // --- Happy path ---

    @Test
    void testEngineMetricsCreation() {
        var perTable = Map.of("users", 3, "orders", 1);
        var perSourcePerTable = Map.of("users", Map.of("thread-1", 2, "thread-2", 1));

        var metrics = new EngineMetrics(2, 4, perTable, perSourcePerTable);

        assertEquals(2, metrics.tableCount());
        assertEquals(4, metrics.totalOpenHandles());
        assertEquals(3, metrics.handlesPerTable().get("users"));
        assertEquals(1, metrics.handlesPerTable().get("orders"));
        assertEquals(2, metrics.handlesPerSourcePerTable().get("users").get("thread-1"));
    }

    @Test
    void testEngineMetricsDefensiveCopy() {
        var perTable = new HashMap<String, Integer>();
        perTable.put("users", 1);
        var perSourcePerTable = new HashMap<String, Map<String, Integer>>();
        perSourcePerTable.put("users", Map.of("src", 1));

        var metrics = new EngineMetrics(1, 1, perTable, perSourcePerTable);

        // Mutating the source map should not affect the record
        perTable.put("orders", 5);
        assertNull(metrics.handlesPerTable().get("orders"));

        // The returned map should be unmodifiable
        assertThrows(UnsupportedOperationException.class,
                () -> metrics.handlesPerTable().put("hack", 99));
        assertThrows(UnsupportedOperationException.class,
                () -> metrics.handlesPerSourcePerTable().put("hack", Map.of()));
    }

    // --- Error cases ---

    @Test
    void testEngineMetricsNullHandlesPerTableThrows() {
        assertThrows(NullPointerException.class, () -> new EngineMetrics(0, 0, null, Map.of()));
    }

    @Test
    void testEngineMetricsNullHandlesPerSourcePerTableThrows() {
        assertThrows(NullPointerException.class, () -> new EngineMetrics(0, 0, Map.of(), null));
    }

    // --- Boundary ---

    @Test
    void testEngineMetricsZeroCounts() {
        var metrics = new EngineMetrics(0, 0, Map.of(), Map.of());

        assertEquals(0, metrics.tableCount());
        assertEquals(0, metrics.totalOpenHandles());
        assertTrue(metrics.handlesPerTable().isEmpty());
        assertTrue(metrics.handlesPerSourcePerTable().isEmpty());
    }
}
