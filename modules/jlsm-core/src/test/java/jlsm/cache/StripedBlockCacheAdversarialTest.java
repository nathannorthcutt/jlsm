package jlsm.cache;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for StripedBlockCache and LruBlockCache. Targets contract gaps and
 * implementation risks identified in spec-analysis.md.
 */
class StripedBlockCacheAdversarialTest {

    // --- Finding 1: CAPACITY-TRUNCATION ---
    // When capacity is not evenly divisible by stripeCount, integer division
    // truncates the remainder. capacity() reports the configured total, but
    // the actual effective capacity is (capacity / stripeCount) * stripeCount.

    @Test
    void capacityReportsEffectiveNotConfiguredWhenTruncated() {
        // capacity=7, stripeCount=4 → per-stripe=1, effective=4
        // capacity() should report 4, not 7
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(7).build()) {
            long perStripe = 7 / 4; // 1
            long effective = perStripe * 4; // 4
            assertEquals(effective, cache.capacity(),
                    "capacity() should report effective capacity (perStripeCapacity * stripeCount), "
                            + "not the raw configured value when truncation occurs");
        }
    }

    @Test
    void capacityTruncationDoesNotCausePrematureEviction() {
        // capacity=10, stripeCount=3 → per-stripe=3, effective=9
        // If we insert 9 entries distributed across 3 stripes (3 per stripe),
        // none should be evicted. But if capacity tracking is wrong, eviction
        // might trigger early.
        try (var cache = StripedBlockCache.builder().stripeCount(3).capacity(10).build()) {
            int inserted = 0;
            // Find 3 entries per stripe by probing keys
            int[] perStripeCount = new int[3];
            for (long offset = 0; inserted < 9; offset++) {
                int stripe = StripedBlockCache.stripeIndex(1L, offset, 3);
                if (perStripeCount[stripe] < 3) {
                    cache.put(1L, offset, MemorySegment.ofArray(new byte[]{ (byte) offset }));
                    perStripeCount[stripe]++;
                    inserted++;
                }
            }
            assertEquals(9, cache.size(),
                    "9 entries (3 per stripe with per-stripe capacity 3) should all fit "
                            + "without eviction");
        }
    }

    @Test
    void effectiveCapacityMatchesActualMaxEntries() {
        // capacity=5, stripeCount=2 → per-stripe=2, effective=4
        // Insert 4 entries (2 per stripe) — all should fit.
        // Insert a 5th into a stripe already holding 2 — it should evict.
        try (var cache = StripedBlockCache.builder().stripeCount(2).capacity(5).build()) {
            // Find 2 entries for stripe 0 and 2 entries for stripe 1
            long[] stripe0Keys = new long[2];
            long[] stripe1Keys = new long[2];
            int s0 = 0, s1 = 0;
            for (long offset = 0; s0 < 2 || s1 < 2; offset++) {
                int stripe = StripedBlockCache.stripeIndex(1L, offset, 2);
                if (stripe == 0 && s0 < 2) {
                    stripe0Keys[s0++] = offset;
                } else if (stripe == 1 && s1 < 2) {
                    stripe1Keys[s1++] = offset;
                }
            }

            // Insert all 4 — should fit exactly
            for (long key : stripe0Keys) {
                cache.put(1L, key, MemorySegment.ofArray(new byte[]{ 1 }));
            }
            for (long key : stripe1Keys) {
                cache.put(1L, key, MemorySegment.ofArray(new byte[]{ 2 }));
            }
            assertEquals(4, cache.size(), "4 entries (2 per stripe) should fit");

            // The 5th entry goes to stripe 0, evicting the LRU entry there
            long extraKey = -1;
            for (long offset = 0; offset < 10000; offset++) {
                if (StripedBlockCache.stripeIndex(1L, offset, 2) == 0 && offset != stripe0Keys[0]
                        && offset != stripe0Keys[1]) {
                    extraKey = offset;
                    break;
                }
            }
            assert extraKey >= 0 : "should find a key hashing to stripe 0";
            cache.put(1L, extraKey, MemorySegment.ofArray(new byte[]{ 3 }));

            // Size should still be 4 (one evicted from stripe 0), not 5
            assertEquals(4, cache.size(),
                    "effective capacity is 4, not 5 — 5th entry should trigger per-stripe eviction");
        }
    }

    // --- Finding 2: LONG-CAPACITY-UNBOUNDED ---
    // LruBlockCache.Builder accepts any positive long for capacity, but
    // LinkedHashMap.size() returns int. If capacity > Integer.MAX_VALUE,
    // the removeEldestEntry check (size() > cap) can never trigger because
    // size() is int and can never exceed Integer.MAX_VALUE.

    @Test
    void lruBlockCacheRejectsCapacityExceedingIntRange() {
        // capacity = Integer.MAX_VALUE + 1L should be rejected since
        // LinkedHashMap cannot honor eviction at that size
        assertThrows(IllegalArgumentException.class,
                () -> LruBlockCache.builder().capacity((long) Integer.MAX_VALUE + 1L).build(),
                "capacity exceeding Integer.MAX_VALUE should be rejected because "
                        + "LinkedHashMap.size() returns int and eviction would never trigger");
    }

    @Test
    void stripedBlockCacheRejectsPerStripeCapacityExceedingIntRange() {
        // If capacity / stripeCount > Integer.MAX_VALUE, per-stripe eviction breaks
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(1)
                        .capacity((long) Integer.MAX_VALUE + 1L).build(),
                "per-stripe capacity exceeding Integer.MAX_VALUE should be rejected");
    }

    @Test
    void lruBlockCacheAcceptsMaxIntCapacity() {
        // Integer.MAX_VALUE should be accepted as a valid capacity
        assertDoesNotThrow(
                () -> LruBlockCache.builder().capacity(Integer.MAX_VALUE).build().close(),
                "capacity of Integer.MAX_VALUE should be accepted");
    }
}
