package jlsm.engine;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for public API types in {@code jlsm.engine}.
 *
 * <p>
 * Targets findings F1 (HandleEvictedException mutable array) and F3 (EngineMetrics shallow nested
 * map copy) from the spec-analysis.
 */
class EngineApiAdversarialTest {

    // ---- F1: HandleEvictedException.allocationSite() returns mutable array ----

    /**
     * F1 — allocationSite getter returns internal array without defensive copy. Mutating the
     * returned array corrupts the exception's diagnostic state.
     */
    @Test
    void allocationSiteGetterShouldReturnDefensiveCopy() {
        final StackTraceElement[] site = Thread.currentThread().getStackTrace();
        final var original0 = site[0];
        final var ex = new HandleEvictedException("t", "s", 0, site.clone(),
                HandleEvictedException.Reason.EVICTION);

        // Mutate the returned array
        final StackTraceElement[] returned = ex.allocationSite();
        returned[0] = new StackTraceElement("Evil", "method", "Evil.java", 1);

        // Bug: mutation is visible through the getter — no defensive copy
        assertEquals(original0, ex.allocationSite()[0],
                "allocationSite() should return a defensive copy; "
                        + "mutation of returned array should not affect internal state");
    }

    /**
     * F1 — constructor stores allocationSite array by reference without cloning. Mutating the
     * original array after construction corrupts the exception.
     */
    @Test
    void allocationSiteConstructorShouldCloneInput() {
        final StackTraceElement[] site = Thread.currentThread().getStackTrace();
        final var original0 = site[0];
        final var ex = new HandleEvictedException("t", "s", 0, site,
                HandleEvictedException.Reason.EVICTION);

        // Mutate the original array after construction
        site[0] = new StackTraceElement("Evil", "method", "Evil.java", 1);

        // Bug: mutation is visible through the exception — constructor didn't clone
        assertEquals(original0, ex.allocationSite()[0],
                "Constructor should defensively copy allocationSite; "
                        + "mutation of original array should not affect exception");
    }

    // ---- F3: EngineMetrics shallow nested map copy ----

    /**
     * F3 — EngineMetrics compact constructor uses Map.copyOf on outer map but does not deep-copy
     * inner maps. Mutable inner maps leak through the record.
     */
    @Test
    void mutableInnerMapShouldNotCorruptEngineMetrics() {
        final var inner = new HashMap<>(Map.of("source", 1));
        final var outerPerSource = new HashMap<String, Map<String, Integer>>();
        outerPerSource.put("table", inner);

        final var metrics = new EngineMetrics(1, 1, Map.of("table", 1), outerPerSource);

        // Mutate the inner map after construction
        inner.put("source2", 2);

        // Bug: inner map mutation is visible through the record
        assertEquals(1, metrics.handlesPerSourcePerTable().get("table").size(),
                "Inner maps should be defensively copied; "
                        + "mutation after construction should not be visible");
    }

    /** F3 — Inner maps returned by accessor should be unmodifiable. */
    @Test
    void innerMapReturnedByAccessorShouldBeUnmodifiable() {
        final var inner = new HashMap<>(Map.of("source", 1));
        final var outerPerSource = new HashMap<String, Map<String, Integer>>();
        outerPerSource.put("table", inner);

        final var metrics = new EngineMetrics(1, 1, Map.of("table", 1), outerPerSource);

        // Get the inner map through the accessor — should be unmodifiable
        final Map<String, Integer> returnedInner = metrics.handlesPerSourcePerTable().get("table");

        assertThrows(UnsupportedOperationException.class, () -> returnedInner.put("evil", 99),
                "Inner maps should be unmodifiable; " + "returned map should not allow mutation");
    }
}
