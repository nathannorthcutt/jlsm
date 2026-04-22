package jlsm.cache;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for dispatch/routing bugs in the cache subsystem.
 *
 * <p>
 * The dispatch_routing domain focuses on how StripedBlockCache routes calls to the right stripe(s):
 * single-stripe dispatch for get/put/getOrLoad, fan-out dispatch for evict/size/close. Findings in
 * this domain target fall-through errors, discriminant corruption, and missing cases where a
 * fan-out dispatcher fails to visit all stripes.
 */
class DispatchRoutingAdversarialTest {

    // Finding: F-R1.dispatch_routing.1.1
    // Bug: StripedBlockCache.evict(sstableId) aborts the fan-out loop on first stripe failure,
    // leaving blocks on later stripes cached. Unlike close() which uses the deferred-exception
    // pattern to visit every stripe, evict() terminates on the first IllegalStateException
    // thrown by a stripe and returns the exception to the caller.
    // Correct behavior: evict must visit every stripe even if one throws. After evict returns
    // (normally or by rethrow), no block for the given sstableId may remain
    // cached in any non-throwing stripe.
    // Fix location: StripedBlockCache.evict(long), lines 245-268
    // Regression watch: the R30 "evict-after-close throws ISE" semantics (see
    // stripedEvictAfterCloseThrows in StripedBlockCacheAdversarialTest) must be
    // preserved — outer closed-state still produces ISE without visiting stripes.
    @Test
    void test_StripedBlockCache_evict_fanOutAbortsOnFirstStripeFailure() throws Exception {
        final int stripeCount = 4;
        var cache = StripedBlockCache.builder().stripeCount(stripeCount)
                .expectedMinimumBlockSize(1L).byteBudget(16L).build();

        final long sstableId = 7L;

        // Find one blockOffset per stripe so that every stripe owns at least one block for
        // sstableId. This lets us prove that blocks on stripes AFTER the throwing stripe remain
        // cached when the fan-out aborts early.
        long[] offsetPerStripe = new long[stripeCount];
        boolean[] found = new boolean[stripeCount];
        int remaining = stripeCount;
        for (long offset = 0; remaining > 0 && offset < 10_000L; offset++) {
            int idx = StripedBlockCache.stripeIndex(sstableId, offset, stripeCount);
            if (!found[idx]) {
                found[idx] = true;
                offsetPerStripe[idx] = offset;
                remaining--;
            }
        }
        assertEquals(0, remaining, "must find at least one blockOffset routed to each stripe");

        // Populate one block per stripe.
        for (int i = 0; i < stripeCount; i++) {
            cache.put(sstableId, offsetPerStripe[i], MemorySegment.ofArray(new byte[]{ (byte) i }));
        }
        assertEquals(stripeCount, cache.size(),
                "every stripe should hold exactly one block for sstableId before evict");

        // Close stripe 1 directly via reflection. The outer cache's `closed` flag remains false,
        // so StripedBlockCache.evict(sstableId) still passes its gate and enters the fan-out loop.
        // stripe[0].evict succeeds; stripe[1].evict throws IllegalStateException; stripes[2..3]
        // must still be visited — if the fan-out aborts, their blocks remain cached.
        Field stripesField = StripedBlockCache.class.getDeclaredField("stripes");
        stripesField.setAccessible(true);
        LruBlockCache[] stripes = (LruBlockCache[]) stripesField.get(cache);
        stripes[1].close();

        // The outer evict is expected to propagate the ISE from the closed stripe. That is fine —
        // the bug under test is that evict MUST still visit stripes[2..stripeCount-1] before
        // rethrowing, so no cached block survives for sstableId on any non-throwing stripe.
        assertThrows(IllegalStateException.class, () -> cache.evict(sstableId));

        // After evict returns (by rethrow), stripes[2] and stripes[3] must be empty for sstableId.
        // Under the buggy implementation the loop aborts at stripe[1] and never reaches 2 or 3.
        for (int i = 2; i < stripeCount; i++) {
            assertFalse(stripes[i].get(sstableId, offsetPerStripe[i]).isPresent(),
                    "stripe " + i + " still caches a block for sstableId after evict — "
                            + "fan-out aborted on first stripe failure instead of visiting all "
                            + "stripes (R42 requires evict to iterate every stripe)");
        }
    }

    // Finding: F-R1.dispatch_routing.1.2
    // Bug: StripedBlockCache.<init>(Builder) does not re-validate the stripeCount upper bound.
    // A reflective caller that writes Builder.stripeCount directly (bypassing the setter and
    // build()) with a value near Integer.MAX_VALUE causes roundUpToPowerOfTwo to overflow to
    // Integer.MIN_VALUE (negative), which then corrupts stripeCount and stripeMask before
    // the `new LruBlockCache[stripeCount]` allocation fails with NegativeArraySizeException
    // instead of a domain-appropriate IllegalArgumentException.
    // Correct behavior: <init> must enforce the same MAX_STRIPE_COUNT upper bound that build()
    // enforces so that reflective bypass still yields IllegalArgumentException
    // rather than NegativeArraySizeException (or silently produces a negative
    // stripeCount/stripeMask).
    // Fix location: StripedBlockCache.<init>(Builder), lines 47-83
    // Regression watch: normal Builder-driven construction with stripeCount in [1,
    // MAX_STRIPE_COUNT]
    // must continue to succeed; the positive-stripeCount branch already handled
    // by the existing guard must not regress.
    @Test
    void test_StripedBlockCache_init_stripeCountUpperBoundReflectiveBypass() throws Exception {
        // Build a Builder normally, then reflectively overwrite stripeCount with a huge value
        // that bypasses Builder.stripeCount() and build()'s MAX_STRIPE_COUNT check. The value
        // (1 << 30) + 1 is chosen because Integer.highestOneBit returns (1 << 30), and
        // (1 << 30) << 1 == Integer.MIN_VALUE, driving roundUpToPowerOfTwo to return a negative
        // effective stripeCount.
        StripedBlockCache.Builder builder = StripedBlockCache.builder().expectedMinimumBlockSize(1L)
                .byteBudget(1L << 40);

        Field stripeCountField = StripedBlockCache.Builder.class.getDeclaredField("stripeCount");
        stripeCountField.setAccessible(true);
        stripeCountField.setInt(builder, (1 << 30) + 1);

        Constructor<StripedBlockCache> ctor = StripedBlockCache.class
                .getDeclaredConstructor(StripedBlockCache.Builder.class);
        ctor.setAccessible(true);

        // The constructor must reject the out-of-bounds stripeCount with IllegalArgumentException
        // rather than allowing the overflow to surface as NegativeArraySizeException (or silently
        // producing a corrupt stripeMask).
        var thrown = assertThrows(Exception.class, () -> {
            try {
                ctor.newInstance(builder);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                throw (Exception) ite.getCause();
            }
        });
        assertInstanceOf(IllegalArgumentException.class, thrown,
                "constructor must throw IllegalArgumentException for stripeCount > "
                        + "MAX_STRIPE_COUNT, got: " + thrown.getClass().getName() + ": "
                        + thrown.getMessage());
    }
}
