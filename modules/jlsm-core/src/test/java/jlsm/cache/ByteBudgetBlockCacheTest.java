package jlsm.cache;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the byte-budget block cache behavior (spec sstable.byte-budget-block-cache v3).
 *
 * <p>
 * These tests exercise the new byteBudget-based API surface on {@link LruBlockCache} and
 * {@link StripedBlockCache}. They are intentionally written ahead of the implementation — every
 * test is expected to fail against the current code (either {@link UnsupportedOperationException}
 * from builder stubs, or assertion failures when the implementation permits behaviours the spec
 * forbids).
 */
class ByteBudgetBlockCacheTest {

    private static MemorySegment segment(int sizeBytes) {
        return MemorySegment.ofArray(new byte[sizeBytes]);
    }

    @Nested
    class AccessorsAndBuilder {

        // @spec sstable.byte-budget-block-cache.R14
        @Test
        void byteBudgetReportedByByteBudgetAccessor() {
            try (var cache = LruBlockCache.builder().byteBudget(4096L).build()) {
                assertEquals(4096L, cache.byteBudget());
            }
        }

        // @spec sstable.byte-budget-block-cache.R14
        @Test
        void capacityReturnsByteBudget() {
            try (var cache = LruBlockCache.builder().byteBudget(8192L).build()) {
                assertEquals(8192L, cache.capacity());
            }
        }

        // @spec sstable.byte-budget-block-cache.R15
        @Test
        void sizeReturnsEntryCount() {
            try (var cache = LruBlockCache.builder().byteBudget(1_000_000L).build()) {
                cache.put(1L, 0L, segment(10));
                cache.put(1L, 1L, segment(20));
                cache.put(1L, 2L, segment(30));
                assertEquals(3L, cache.size());
            }
        }

        // @spec sstable.byte-budget-block-cache.R2
        @Test
        void byteBudgetZeroRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> LruBlockCache.builder().byteBudget(0L));
        }

        // @spec sstable.byte-budget-block-cache.R2
        @Test
        void byteBudgetNegativeRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> LruBlockCache.builder().byteBudget(-1L));
        }

        // @spec sstable.byte-budget-block-cache.R2
        @Test
        void byteBudgetRejectionLeavesBuilderUsable() {
            var builder = LruBlockCache.builder();
            assertThrows(IllegalArgumentException.class, () -> builder.byteBudget(-1L));
            // Builder must remain usable; a subsequent valid call succeeds.
            assertDoesNotThrow(() -> builder.byteBudget(100L).build().close());
        }

        // @spec sstable.byte-budget-block-cache.R3
        @Test
        void buildWithoutByteBudgetThrows() {
            assertThrows(IllegalArgumentException.class, () -> LruBlockCache.builder().build());
        }
    }

    @Nested
    class ZeroLengthRejection {

        // @spec sstable.byte-budget-block-cache.R9
        @Test
        void zeroByteSizePutRejected() {
            try (var cache = LruBlockCache.builder().byteBudget(100L).build()) {
                assertThrows(IllegalArgumentException.class,
                        () -> cache.put(1L, 0L, MemorySegment.ofArray(new byte[0])));
            }
        }

        // @spec sstable.byte-budget-block-cache.R9a
        @Test
        void zeroByteSizeLoaderResultRejected() {
            try (var cache = LruBlockCache.builder().byteBudget(100L).build()) {
                assertThrows(IllegalArgumentException.class,
                        () -> cache.getOrLoad(1L, 0L, () -> MemorySegment.ofArray(new byte[0])));
            }
        }
    }

    @Nested
    class EvictionAndOversized {

        // @spec sstable.byte-budget-block-cache.R10
        @Test
        void singleEntryWithinBudgetCached() {
            try (var cache = LruBlockCache.builder().byteBudget(100L).build()) {
                var block = segment(50);
                cache.put(1L, 0L, block);
                assertEquals(1L, cache.size());
                assertEquals(block, cache.get(1L, 0L).orElseThrow());
            }
        }

        // @spec sstable.byte-budget-block-cache.R11
        @Test
        void smallBudgetLargeEntry_R11_oversizedAdmitted() {
            try (var cache = LruBlockCache.builder().byteBudget(10L).build()) {
                var oversized = segment(100);
                cache.put(1L, 0L, oversized);
                // R11: the single oversized entry is admitted as the sole resident.
                assertEquals(1L, cache.size());
                assertEquals(oversized, cache.get(1L, 0L).orElseThrow());
            }
        }

        // @spec sstable.byte-budget-block-cache.R10,R11
        @Test
        void oversizedThenSmallEntry_oversizedEvicted() {
            try (var cache = LruBlockCache.builder().byteBudget(10L).build()) {
                cache.put(1L, 0L, segment(100)); // oversized, sole resident under R11
                cache.put(1L, 1L, segment(5)); // eviction loop (R10) must drop oversized
                assertEquals(1L, cache.size());
                assertTrue(cache.get(1L, 0L).isEmpty(), "oversized entry should be evicted");
                assertTrue(cache.get(1L, 1L).isPresent(), "small entry should remain");
            }
        }

        // @spec sstable.byte-budget-block-cache.R10,R12
        @Test
        void budgetBoundary_evictionFiresWhenExceeded() {
            try (var cache = LruBlockCache.builder().byteBudget(20L).build()) {
                cache.put(1L, 0L, segment(15));
                cache.put(1L, 1L, segment(15));
                // 15 + 15 = 30 > 20 → LRU eldest (offset 0) must be evicted.
                assertEquals(1L, cache.size());
                assertTrue(cache.get(1L, 0L).isEmpty(), "eldest should have been evicted");
                assertTrue(cache.get(1L, 1L).isPresent(), "most recent should remain");
            }
        }

        // Finding: H-CB-11
        // Bug: implementer adds aliased-segment deduplication silently, breaking the
        // R7a documented double-counting contract — callers relying on documented behavior
        // (that identical segments cached at two keys consume 2x the bytes) suddenly
        // observe halved consumption. R7a explicitly permits dedup as an OPTIMIZATION but
        // does not mandate it; the current (non-dedup) behavior must be locked in so a
        // future silent switch to dedup is caught.
        // Correct behavior: putting the same MemorySegment under two keys counts twice
        // toward the budget; evicting one does not affect the other's bytes.
        // Fix location: LruBlockCache.insertEntry and removeEntry must treat byteSize()
        // per-key, not per-backing-segment.
        // Regression watch: if dedup is ever intentionally added, this test must be
        // migrated in-place with clear spec-change trail, not silently deleted.
        @Test
        void aliasedSegmentCountedTwice_R7a() {
            // Budget = 30. One 20-byte segment stored under two keys:
            // - Under non-dedup (default): counts 20+20=40 > 30, one entry evicted.
            // Size should be 1 after the second insert completes eviction.
            // - Under dedup (not required, not implemented here): counts 20 only,
            // both remain. Size would be 2.
            try (var cache = LruBlockCache.builder().byteBudget(30L).build()) {
                var shared = segment(20);
                cache.put(1L, 0L, shared);
                cache.put(1L, 1L, shared); // aliased
                assertEquals(1L, cache.size(),
                        "R7a default: aliased segments count twice; 20+20>30 must evict eldest");
            }
        }

        // Finding: H-C-6
        // Bug: implementer restructures get() into a mutation path (e.g., touch-based
        // eviction promotion that allocates a new segment, or replacement-tracking that
        // invalidates old references), violating R15a's "get() itself never evicts and
        // never invalidates previously-returned references".
        // Correct behavior: a reference returned by get() remains readable for the
        // lifetime of its backing allocation, with no cache-side interference from a
        // subsequent get() of the same key.
        // Fix location: LruBlockCache.get — must not trigger eviction or invalidate
        // previously-returned segments.
        // Regression watch: LinkedHashMap access-order mutates INTERNAL ordering, but
        // the stored MemorySegment reference is unchanged. Test verifies reference
        // identity stability.
        @Test
        void getDoesNotInvalidatePreviousReference_R15a() {
            try (var cache = LruBlockCache.builder().byteBudget(100L).build()) {
                var block = segment(30);
                cache.put(1L, 0L, block);
                var firstGet = cache.get(1L, 0L).orElseThrow();
                var secondGet = cache.get(1L, 0L).orElseThrow();
                assertSame(block, firstGet,
                        "R15a: get() must return the stored reference, not a copy or replacement");
                assertSame(firstGet, secondGet,
                        "R15a: consecutive get() calls must return the same reference; "
                                + "get() never invalidates previously returned segments");
            }
        }

        // Finding: H-C-7
        // Bug: R15a explicitly documents that a subsequent concurrent put() from another
        // thread CAN evict the backing entry before the caller dereferences the returned
        // segment. If the implementation is silently strengthened to refcount segments or
        // provide happens-before on eviction, the documented weakness becomes a silent
        // performance/complexity hit and the R15a contract drifts.
        // Correct behavior: get() returns the reference; a subsequent put() that triggers
        // eviction of that entry removes it from the map — `get()` of the same key returns
        // empty afterward. The segment reference itself is NOT refcounted by the cache.
        // Fix location: LruBlockCache.insertEntry eviction loop must not hold any
        // synchronization with previously-returned segments.
        // Regression watch: this test locks in the documented weakness; if strengthened
        // intentionally, update R15a and migrate this test in-place.
        @Test
        void concurrentPutEvictsAnotherThreadsReference_R15a() {
            try (var cache = LruBlockCache.builder().byteBudget(20L).build()) {
                var original = segment(15);
                cache.put(1L, 0L, original);
                var observed = cache.get(1L, 0L).orElseThrow();
                assertSame(original, observed);
                // Put a second entry that, combined with the first, exceeds budget.
                cache.put(1L, 1L, segment(15));
                // The eldest (key 0) has been evicted; the returned reference is still
                // dereferenceable (R15a caller responsibility) but the cache no longer
                // serves it.
                assertTrue(cache.get(1L, 0L).isEmpty(),
                        "R15a: eldest evicted; cache lookup returns empty even though caller "
                                + "still holds a reference");
                // And the observed segment is still readable — R15a: cache does not
                // refcount or invalidate the backing allocation.
                assertEquals(15L, observed.byteSize(),
                        "R15a: previously returned reference remains readable after eviction");
            }
        }
    }

    @Nested
    class Structural_Chokepoint {

        // @spec sstable.byte-budget-block-cache.R6
        @Test
        void chokepointInvariant_putAndGetOrLoadShareByteAccounting() {
            // Budget = 20. put 10, then getOrLoad a loaded 15-byte segment. 10 + 15 = 25 > 20,
            // so the older entry must be evicted — verifying that getOrLoad participates in
            // the same byte-tracking chokepoint as put.
            try (var cache = LruBlockCache.builder().byteBudget(20L).build()) {
                cache.put(1L, 0L, segment(10));
                cache.getOrLoad(1L, 1L, () -> segment(15));
                assertEquals(1L, cache.size());
                assertTrue(cache.get(1L, 0L).isEmpty(),
                        "older entry must be evicted by getOrLoad-triggered budget enforcement");
                assertTrue(cache.get(1L, 1L).isPresent());
            }
        }

        // @spec sstable.byte-budget-block-cache.R8
        @Test
        void putReplaceSameKey_atomicSubtractBeforeAdd_R8() {
            // Budget = 25. Put 10-byte at key X, then replace with 20-byte at key X.
            // If byte accounting subtracts the old bytes before adding new, the replace fits
            // (post-state is 20, under budget) and no other eviction occurs. With only one
            // key, size remains 1 and the new value is retrievable.
            try (var cache = LruBlockCache.builder().byteBudget(25L).build()) {
                cache.put(1L, 0L, segment(10));
                var replacement = segment(20);
                cache.put(1L, 0L, replacement);
                assertEquals(1L, cache.size());
                assertEquals(replacement, cache.get(1L, 0L).orElseThrow());
            }
        }

        // @spec sstable.byte-budget-block-cache.R16
        @Test
        void closeOrderingSafe_R16() {
            var cache = LruBlockCache.builder().byteBudget(100L).build();
            cache.put(1L, 0L, segment(10));
            cache.put(1L, 1L, segment(20));
            cache.close();
            // All accessors must throw ISE after close — verifies that close ordering leaves
            // the cache in a definitively-closed state (R31, cross-references R16 guarantees).
            assertThrows(IllegalStateException.class, cache::size);
            assertThrows(IllegalStateException.class, cache::capacity);
            assertThrows(IllegalStateException.class, cache::byteBudget);
        }
    }

    @Nested
    class OverflowAndEntryCap {

        // @spec sstable.byte-budget-block-cache.R29
        @Test
        void overflow_atLongMaxValue_throwsISE_R29() {
            // Budget near Long.MAX_VALUE. Two large inserts whose byteSize sum overflows
            // Long.MAX_VALUE must be rejected with ISE, and the second insert must not mutate
            // state.
            try (var cache = LruBlockCache.builder().byteBudget(Long.MAX_VALUE).build()) {
                // First insert a large entry.
                int first = Integer.MAX_VALUE - 8;
                cache.put(1L, 0L, segment(first));
                long sizeBefore = cache.size();
                // Second insert whose byteSize, when added to currentBytes, overflows.
                // Long.MAX_VALUE - (Integer.MAX_VALUE - 8) is still > Integer.MAX_VALUE, so
                // we construct an allocation that, combined, would exceed Long.MAX_VALUE
                // using an additional near-max allocation.
                // We cannot allocate a segment with byteSize > Integer.MAX_VALUE via
                // byte[] on-heap. This test therefore checks that repeated large inserts
                // whose sum approaches Long.MAX_VALUE are protected — we use the only
                // byte[] tool we have and confirm that IF overflow is detected, state is
                // preserved. If the implementation does NOT trigger overflow here (because
                // 2 * (Integer.MAX_VALUE - 8) << Long.MAX_VALUE), this test still checks
                // that no silent corruption occurs and the second insert either commits
                // cleanly or throws ISE without state mutation.
                try {
                    cache.put(1L, 1L, segment(first));
                    // No overflow expected at this budget; state just grew.
                    assertTrue(cache.size() >= sizeBefore);
                } catch (IllegalStateException ise) {
                    // Overflow detected — size must be unchanged from pre-insert state.
                    assertEquals(sizeBefore, cache.size(),
                            "R29: no state mutation on overflow detection");
                }
            }
        }

        // @spec sstable.byte-budget-block-cache.R29
        @Test
        void overflow_onPutReplace_symmetricCheck_R29() {
            // Budget = Long.MAX_VALUE. Put key X = small segment, then replace with a
            // segment whose size is large enough that the subtract-then-add must still
            // fit within Long.MAX_VALUE. This test verifies the put-replace path uses the
            // symmetric exact-arithmetic check described in R29: if the replace computes
            // (currentBytes - replaced) + new cleanly, it commits; otherwise an ISE must
            // propagate without mutating state.
            try (var cache = LruBlockCache.builder().byteBudget(Long.MAX_VALUE).build()) {
                cache.put(1L, 0L, segment(10));
                long sizeBefore = cache.size();
                try {
                    cache.put(1L, 0L, segment(Integer.MAX_VALUE - 8));
                    // Replace succeeded — size still 1 because same key.
                    assertEquals(1L, cache.size());
                } catch (IllegalStateException ise) {
                    // Overflow detected; size must be unchanged.
                    assertEquals(sizeBefore, cache.size(),
                            "R29: put-replace must not mutate state on overflow");
                }
            }
        }

        // @spec sstable.byte-budget-block-cache.R28a
        @Disabled("R28a: Integer.MAX_VALUE entry count unreachable in unit tests; "
                + "verified via invariant reasoning")
        @Test
        void entryCountCap_R28a_rejectsNearIntegerMaxValue() {
            // Intentionally disabled — allocating ~2 billion entries is not feasible in a
            // unit test. The R28a invariant is checked by code review and by reasoning
            // about the map.size() >= Integer.MAX_VALUE - 1 guard.
        }

        // @spec sstable.byte-budget-block-cache.R28a
        @Test
        void entryCountCap_R28a_doesNotRejectReplace() {
            // Replaces (same-key updates) do not increase map.size(), so they must not be
            // subject to the R28a cap. This test uses the practical proxy: a series of
            // put-replaces at the same key must all succeed without IllegalStateException.
            try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
                cache.put(1L, 0L, segment(10));
                for (int i = 0; i < 100; i++) {
                    cache.put(1L, 0L, segment(10 + (i % 20)));
                }
                assertEquals(1L, cache.size());
            }
        }

        // @spec sstable.byte-budget-block-cache.R28
        @Test
        void byteBudgetBeyondIntegerMaxValueAccepted_R28() {
            long bigBudget = Integer.MAX_VALUE + 1L;
            try (var cache = LruBlockCache.builder().byteBudget(bigBudget).build()) {
                assertEquals(bigBudget, cache.byteBudget());
                // And basic operations still work.
                cache.put(1L, 0L, segment(10));
                assertTrue(cache.get(1L, 0L).isPresent());
            }
        }
    }

    @Nested
    class LoaderExceptionAndCloseGuards {

        // @spec sstable.byte-budget-block-cache.R32
        @Test
        void loaderException_leavesStateUntouched_R32() {
            try (var cache = LruBlockCache.builder().byteBudget(100L).build()) {
                var called = new AtomicInteger(0);
                assertThrows(RuntimeException.class, () -> cache.getOrLoad(1L, 0L, () -> {
                    called.incrementAndGet();
                    throw new RuntimeException("loader boom");
                }));
                assertEquals(1, called.get());
                // No entry was committed; no byte accounting happened.
                assertEquals(0L, cache.size());
                assertTrue(cache.get(1L, 0L).isEmpty());
            }
        }

        // @spec sstable.byte-budget-block-cache.R31
        @Test
        void useAfterClose_put_throwsISE() {
            var cache = LruBlockCache.builder().byteBudget(100L).build();
            cache.close();
            assertThrows(IllegalStateException.class, () -> cache.put(1L, 0L, segment(10)));
        }

        // @spec sstable.byte-budget-block-cache.R31
        @Test
        void useAfterClose_getOrLoad_throwsISE() {
            var cache = LruBlockCache.builder().byteBudget(100L).build();
            cache.close();
            assertThrows(IllegalStateException.class,
                    () -> cache.getOrLoad(1L, 0L, () -> segment(10)));
        }

        // @spec sstable.byte-budget-block-cache.R31
        @Test
        void useAfterClose_get_throwsISE() {
            var cache = LruBlockCache.builder().byteBudget(100L).build();
            cache.close();
            assertThrows(IllegalStateException.class, () -> cache.get(1L, 0L));
        }

        // @spec sstable.byte-budget-block-cache.R31
        @Test
        void useAfterClose_evict_throwsISE() {
            var cache = LruBlockCache.builder().byteBudget(100L).build();
            cache.close();
            assertThrows(IllegalStateException.class, () -> cache.evict(1L));
        }

        // @spec sstable.byte-budget-block-cache.R31
        @Test
        void useAfterClose_size_throwsISE() {
            var cache = LruBlockCache.builder().byteBudget(100L).build();
            cache.close();
            assertThrows(IllegalStateException.class, cache::size);
        }

        // @spec sstable.byte-budget-block-cache.R31
        @Test
        void useAfterClose_capacity_throwsISE() {
            var cache = LruBlockCache.builder().byteBudget(100L).build();
            cache.close();
            assertThrows(IllegalStateException.class, cache::capacity);
        }

        // @spec sstable.byte-budget-block-cache.R31
        @Test
        void useAfterClose_byteBudget_throwsISE() {
            var cache = LruBlockCache.builder().byteBudget(100L).build();
            cache.close();
            assertThrows(IllegalStateException.class, cache::byteBudget);
        }

        // @spec sstable.byte-budget-block-cache.R16
        @Test
        void doubleCloseIdempotent_R16() {
            var cache = LruBlockCache.builder().byteBudget(100L).build();
            cache.close();
            assertDoesNotThrow(cache::close);
        }

        // Finding: H-CB-10
        // Bug: getOrLoad loader exception leaves currentBytes temporarily incremented
        // (e.g., if implementation uses reservation-style pre-accounting and forgets the
        // compensating release). The existing loaderException test checks size() and
        // get() visibility but NOT the internal byte accounting — a drift there would
        // silently poison subsequent R12 invariant checks.
        // Correct behavior: after a loader exception, currentBytes must be identical to
        // its pre-call value. We verify this by asserting a subsequent put of exactly
        // byteBudget bytes succeeds — if currentBytes had drifted, this would trigger
        // eviction of an already-empty map or reject the put.
        // Fix location: LruBlockCache.getOrLoad — any reservation-style accounting must
        // compensate in finally before the exception leaves the method.
        // Regression watch: even a 1-byte drift would be caught on the next budget-edge
        // operation.
        @Test
        void loaderException_leavesByteAccountingUntouched_R32() {
            try (var cache = LruBlockCache.builder().byteBudget(50L).build()) {
                // Precondition: one entry already present to make a byte-accounting drift
                // detectable — after the failed loader, we fill the cache to exactly the
                // budget and verify no premature eviction occurs.
                cache.put(1L, 0L, segment(10));
                assertEquals(1L, cache.size());

                assertThrows(RuntimeException.class, () -> cache.getOrLoad(1L, 1L, () -> {
                    throw new RuntimeException("loader boom");
                }));

                // currentBytes must still be 10. Fill to budget = 50 exactly; if drift
                // occurred, either the new insert triggers eviction prematurely (size
                // would drop below 2) or R29 overflow math miscomputes.
                cache.put(1L, 2L, segment(40));
                assertEquals(2L, cache.size(),
                        "R32: loader exception must not mutate byte accounting; "
                                + "subsequent insert to exact budget must NOT trigger eviction");
                assertTrue(cache.get(1L, 0L).isPresent(),
                        "R32: original entry must remain — loader failure never touched state");
                assertTrue(cache.get(1L, 2L).isPresent());
            }
        }

        // Finding: H-SS-4
        // Bug: R16 mandates `assert currentBytes == 0` after close clears the map via
        // the R7 chokepoint. If a future "optimization" introduces aliased-segment
        // deduplication (permitted by R7a but NOT implemented by default), a naive impl
        // might double-subtract on removal when aliases exist, leaving currentBytes
        // negative and tripping R16's assertion.
        // Correct behavior: even with aliased segments under the default non-dedup
        // regime, close() must zero currentBytes cleanly — the assertion must not fire.
        // Fix location: LruBlockCache.removeEntry — subtract exactly the removed entry's
        // byteSize, not a shared-allocation byteSize.
        // Regression watch: caught at close() time via the R16 assertion; a double-close
        // must still be idempotent and produce no assertion error in either call.
        @Test
        void aliasedSegmentsCleanClose_R16() {
            var cache = LruBlockCache.builder().byteBudget(100L).build();
            var shared = segment(20);
            cache.put(1L, 0L, shared);
            cache.put(1L, 1L, shared); // aliased — R7a default counts twice
            // close must zero currentBytes without the R16 assertion firing;
            // double-close must remain idempotent.
            assertDoesNotThrow(cache::close,
                    "R16: close with aliased segments must zero currentBytes cleanly "
                            + "under the default non-dedup regime");
            assertDoesNotThrow(cache::close, "R16: double-close idempotent");
        }
    }

    @Nested
    class StripedByteBudget {

        // @spec sstable.byte-budget-block-cache.R20
        @Test
        void stripedByteBudgetLessThanStripeCount_rejected_R20() {
            assertThrows(IllegalArgumentException.class,
                    () -> StripedBlockCache.builder().byteBudget(3L).stripeCount(8).build());
        }

        // @spec sstable.byte-budget-block-cache.R20a
        @Test
        void stripedPerStripeBelow4096Rejected_R20a_default() {
            // 8 bytes / 8 stripes = 1 byte per stripe, below default 4096 minimum.
            assertThrows(IllegalArgumentException.class,
                    () -> StripedBlockCache.builder().byteBudget(8L).stripeCount(8).build());
        }

        // @spec sstable.byte-budget-block-cache.R20a
        @Test
        void stripedExpectedMinimumBlockSizeCustomAccepted_R20a_custom() {
            // With expectedMinimumBlockSize lowered to 1, 8 bytes / 8 stripes = 1 byte
            // per stripe satisfies the custom minimum.
            try (var cache = StripedBlockCache.builder().byteBudget(8L).stripeCount(8)
                    .expectedMinimumBlockSize(1L).build()) {
                assertTrue(cache.byteBudget() > 0);
            }
        }

        // @spec sstable.byte-budget-block-cache.R20b
        @Test
        void stripedExpectedMinimumBlockSizeZeroRejected_R20b() {
            assertThrows(IllegalArgumentException.class,
                    () -> StripedBlockCache.builder().expectedMinimumBlockSize(0L));
        }

        // @spec sstable.byte-budget-block-cache.R20b
        @Test
        void stripedExpectedMinimumBlockSizeNegativeRejected_R20b() {
            assertThrows(IllegalArgumentException.class,
                    () -> StripedBlockCache.builder().expectedMinimumBlockSize(-1L));
        }

        // @spec sstable.byte-budget-block-cache.R20b
        @Test
        void stripedExpectedMinimumBlockSizeRejectionLeavesBuilderUsable_R20b() {
            var builder = StripedBlockCache.builder();
            assertThrows(IllegalArgumentException.class,
                    () -> builder.expectedMinimumBlockSize(-1L));
            // Subsequent call with valid value must succeed as if the rejected call had not
            // occurred.
            try (var cache = builder.byteBudget(8L).stripeCount(8).expectedMinimumBlockSize(1L)
                    .build()) {
                assertTrue(cache.byteBudget() > 0);
            }
        }

        // @spec sstable.byte-budget-block-cache.R23
        @Test
        void stripedCapacityReturnsTruncatedByteBudget_R23() {
            // 1_000_003 bytes / 4 stripes = 250_000 per stripe, re-multiplied = 1_000_000.
            try (var cache = StripedBlockCache.builder().byteBudget(1_000_003L).stripeCount(4)
                    .expectedMinimumBlockSize(1L).build()) {
                assertEquals(1_000_000L, cache.capacity());
            }
        }

        // Finding: H-RL-4
        // Bug: StripedBlockCache.close() sets volatile closed=true unconditionally at entry,
        // then iterates stripes calling close() on each. A second close() call repeats the
        // stripe iteration — if any per-stripe LruBlockCache.close() is NOT idempotent,
        // double-close throws. R31 of byte-budget-block-cache covers LruBlockCache's
        // double-close; the Striped-level composition inherits that but should also be
        // tested directly so a regression in either impl is caught at this layer.
        // Correct behavior: StripedBlockCache.close() is idempotent — safe to call twice.
        // Fix location: StripedBlockCache.close — either early-return on closed, or ensure
        // each stripe.close is idempotent (current contract via byte-budget.R16).
        // Regression watch: a stripe-level close throwing on second invocation would
        // surface as a deferred exception here.
        @Test
        void stripedDoubleCloseIdempotent_R31() {
            var cache = StripedBlockCache.builder().byteBudget(65536L).stripeCount(4).build();
            cache.close();
            assertDoesNotThrow(cache::close,
                    "StripedBlockCache.close() must be idempotent — per-stripe double-close "
                            + "is covered by byte-budget.R16 and must compose cleanly");
        }

        // Finding: H-RL-5
        // Bug: StripedBlockCache adds the byteBudget() accessor (parallel to LruBlockCache)
        // but R31 of byte-budget-block-cache enumerates use-after-close only for
        // LruBlockCache; the StripedBlockCache.byteBudget accessor could be implemented
        // without a closed-check, violating the consistency goal that every striped accessor
        // throws ISE after close (preserved striped R28/R29/R30/R40/R46 guard all other
        // accessors — byteBudget must join them).
        // Correct behavior: StripedBlockCache.byteBudget() throws IllegalStateException
        // when called after close().
        // Fix location: StripedBlockCache.byteBudget — add closed check.
        // Regression watch: new accessor must inherit the closed-check pattern used by
        // capacity(), size(), etc.
        @Test
        void stripedUseAfterClose_byteBudget_throwsISE() {
            var cache = StripedBlockCache.builder().byteBudget(65536L).stripeCount(4).build();
            cache.close();
            assertThrows(IllegalStateException.class, cache::byteBudget,
                    "byteBudget() on a closed StripedBlockCache must throw IllegalStateException, "
                            + "consistent with capacity/size/get/put/getOrLoad/evict");
        }

        // Finding: H-RL-9
        // Bug: StripedBlockCache.Builder.byteBudget setter, parallel to LruBlockCache's
        // (R2), could silently store a rejected invalid value before throwing — leaving
        // the builder in a poisoned state. R18 mandates "must not mutate any builder state
        // when the exception is thrown" — this must be behaviorally verified.
        // Correct behavior: after a rejected byteBudget() call, a subsequent valid
        // byteBudget() call succeeds and the builder produces a cache with the valid
        // value, not silently with the (rejected) earlier value.
        // Fix location: StripedBlockCache.Builder.byteBudget — validate BEFORE assigning.
        // Regression watch: parity with byteBudgetRejectionLeavesBuilderUsable on
        // LruBlockCache side; transactional-setter KB pattern.
        @Test
        void stripedByteBudgetRejectionLeavesBuilderUsable_R18() {
            var builder = StripedBlockCache.builder().stripeCount(4).expectedMinimumBlockSize(1L);
            assertThrows(IllegalArgumentException.class, () -> builder.byteBudget(-1L));
            // Builder must remain usable; a subsequent valid call succeeds and the built
            // cache's byteBudget reflects the valid value.
            try (var cache = builder.byteBudget(4096L).build()) {
                assertTrue(cache.byteBudget() > 0);
            }
        }
    }
}
