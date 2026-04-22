package jlsm.cache;

import jlsm.core.cache.BlockCache;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

class ContractBoundariesAdversarialTest {

    /**
     * Minimal BlockCache that does NOT override getOrLoad, exposing the default implementation for
     * contract-boundary testing.
     */
    private static final class DefaultGetOrLoadCache implements BlockCache {
        private final ConcurrentHashMap<Long, MemorySegment> map = new ConcurrentHashMap<>();

        @Override
        public Optional<MemorySegment> get(long sstableId, long blockOffset) {
            return Optional.ofNullable(map.get(key(sstableId, blockOffset)));
        }

        @Override
        public void put(long sstableId, long blockOffset, MemorySegment block) {
            map.put(key(sstableId, blockOffset), block);
        }

        @Override
        public void evict(long sstableId) {
            map.clear();
        }

        @Override
        public long size() {
            return map.size();
        }

        @Override
        public long capacity() {
            return Long.MAX_VALUE;
        }

        @Override
        public void close() {
            map.clear();
        }

        private static long key(long sstableId, long blockOffset) {
            return sstableId * 31 + blockOffset;
        }
    }

    // Finding: F-R1.cb.1.5
    // Bug: BlockCache.getOrLoad default does not null-check loader result
    // Correct behavior: NullPointerException("loader must not return null") when loader returns
    // null
    // Fix location: BlockCache.getOrLoad default method — add null check after loader.get()
    // Regression watch: ensure non-null loader results still flow through correctly
    @Test
    void test_BlockCache_getOrLoad_default_rejectsNullLoaderResult() {
        var cache = new DefaultGetOrLoadCache();
        var ex = assertThrows(NullPointerException.class, () -> cache.getOrLoad(1L, 0L, () -> null),
                "default getOrLoad must throw NullPointerException when loader returns null");
        assertNotNull(ex.getMessage(),
                "exception must have a descriptive message, not an opaque NPE");
        assertTrue(ex.getMessage().contains("loader"),
                "exception message must mention 'loader' to identify the root cause");
    }

    // Finding: F-R1.cb.1.6
    // Bug: BlockCache.getOrLoad default does not validate loader for null eagerly
    // Correct behavior: NullPointerException("loader must not be null") thrown eagerly,
    // even when the key is already cached (cache hit path)
    // Fix location: BlockCache.getOrLoad default method — add null check for loader parameter
    // Regression watch: ensure non-null loaders still work correctly
    @Test
    void test_BlockCache_getOrLoad_default_rejectsNullLoaderEagerly() {
        var cache = new DefaultGetOrLoadCache();
        // Pre-populate the cache so get() returns a hit — the loader lambda is never invoked
        var segment = Arena.ofAuto().allocate(8);
        cache.put(1L, 0L, segment);
        // A null loader must still be rejected eagerly, not deferred to the miss path
        assertThrows(NullPointerException.class, () -> cache.getOrLoad(1L, 0L, null),
                "default getOrLoad must reject null loader eagerly, "
                        + "even on a cache hit where the loader would not be invoked");
    }

    // Finding: F-R1.cb.1.1
    // Bug: LruBlockCache.size() omits closed-state check, returning 0 after close()
    // instead of throwing IllegalStateException like all other public methods
    // Correct behavior: size() should throw IllegalStateException when cache is closed
    // Fix location: LruBlockCache.size() — add closed-state check before lock acquisition
    // Regression watch: ensure size() still works correctly on open caches
    @Test
    void test_LruBlockCache_size_throwsAfterClose() {
        var cache = LruBlockCache.builder().byteBudget(1_000_000L).build();
        cache.close();
        assertThrows(IllegalStateException.class, cache::size,
                "size() must throw IllegalStateException after close(), "
                        + "consistent with get/put/getOrLoad/evict");
    }

    // Finding: F-R1.cb.1.4
    // Bug: LruBlockCache.capacity() omits closed-state check, returning the stored
    // capacity after close() instead of throwing IllegalStateException like get/put/evict
    // Correct behavior: capacity() should throw IllegalStateException when cache is closed
    // Fix location: LruBlockCache.capacity() — add closed-state check before returning
    // Regression watch: ensure capacity() still returns correct value on open caches
    @Test
    void test_LruBlockCache_capacity_throwsAfterClose() {
        var cache = LruBlockCache.builder().byteBudget(10L).build();
        assertEquals(10L, cache.capacity(), "capacity() must return correct value while open");
        cache.close();
        assertThrows(IllegalStateException.class, cache::capacity,
                "capacity() must throw IllegalStateException after close(), "
                        + "consistent with get/put/getOrLoad/evict");
    }

    // Finding: F-R1.cb.1.7
    // Bug: BlockCache.getOrLoad default is not atomic — get()+put() are separate operations,
    // so concurrent callers can both miss on get() and both invoke the loader, violating
    // the Javadoc contract "called exactly once on a cache miss"
    // Correct behavior: the loader must be invoked at most once per cache miss, even under
    // concurrent access through the default implementation
    // Fix location: BlockCache.getOrLoad default method — add synchronization
    // Regression watch: ensure non-concurrent getOrLoad still works correctly
    @Test
    @Timeout(10)
    void test_BlockCache_getOrLoad_default_loaderCalledExactlyOnce() throws Exception {
        var cache = new DefaultGetOrLoadCache();
        var loaderCount = new AtomicInteger(0);
        var segment = Arena.ofAuto().allocate(8);
        int threadCount = 8;
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    cache.getOrLoad(1L, 0L, () -> {
                        loaderCount.incrementAndGet();
                        return segment;
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        doneLatch.await();

        assertEquals(1, loaderCount.get(),
                "default getOrLoad must invoke the loader exactly once per cache miss, "
                        + "even under concurrent access — Javadoc says 'called exactly once on a cache miss'");
    }

    // Finding: F-R1.cb.1.3
    // Bug: StripedBlockCache.capacity() omits closed-state check, returning the stored
    // capacity after close() instead of throwing IllegalStateException like get/put/evict
    // Correct behavior: capacity() should throw IllegalStateException when cache is closed
    // Fix location: StripedBlockCache.capacity() — add closed-state check before returning
    // Regression watch: ensure capacity() still returns correct value on open caches
    @Test
    void test_StripedBlockCache_capacity_throwsAfterClose() {
        var cache = StripedBlockCache.builder().byteBudget(1_000_000L).build();
        cache.close();
        assertThrows(IllegalStateException.class, cache::capacity,
                "capacity() must throw IllegalStateException after close(), "
                        + "consistent with get/put/getOrLoad/evict");
    }

    // Finding: F-R1.contract_boundaries.1.1
    // Bug: StripedBlockCache.size() lacks the try/catch translation pattern used by
    // get/put/getOrLoad/evict. Under a close() race, a stripe-level IllegalStateException
    // propagates out of size() with a LruBlockCache origin frame, breaking the cross-method
    // translation-pattern guarantee that all striped-level ISEs originate from
    // StripedBlockCache.
    // Correct behavior: size() must translate any delegate ISE observed while striped.closed
    // is true into a StripedBlockCache-level ISE, matching the pattern in get/put/getOrLoad/
    // evict. Stack origin of any ISE thrown from size() must be StripedBlockCache.
    // Fix location: StripedBlockCache.size() — wrap the delegate sum loop in try/catch
    // (IllegalStateException e) { if (closed) throw new IllegalStateException(...); throw e; }
    // Regression watch: ensure non-racing size() still returns the summed stripe count.
    @Test
    @Timeout(10)
    void test_StripedBlockCache_size_toctou_closedCheckRaceExceptionOrigin() throws Exception {
        // Strategy mirrors test_StripedBlockCache_toctou_closedCheckRaceExceptionOrigin in
        // ConcurrencyAdversarialTest: race many size() callers against close() to hit the
        // TOCTOU window where size() has passed the outer closed-check but a stripe has
        // already been closed by close(). Without the translation pattern, the stripe-level
        // ISE propagates with origin LruBlockCache.size.
        final int iterations = 500;
        final int operationThreads = 4;
        var stripeLeakDetected = new AtomicReference<StackTraceElement>();

        for (int iter = 0; iter < iterations && stripeLeakDetected.get() == null; iter++) {
            var cache = StripedBlockCache.builder().stripeCount(2).byteBudget(1_000_000L).build();

            var barrier = new CyclicBarrier(operationThreads + 1);
            var threads = new Thread[operationThreads];

            for (int t = 0; t < operationThreads; t++) {
                threads[t] = Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                        for (int spin = 0; spin < 100; spin++) {
                            try {
                                cache.size();
                            } catch (IllegalStateException e) {
                                StackTraceElement origin = e.getStackTrace()[0];
                                String simpleClass = origin.getClassName()
                                        .substring(origin.getClassName().lastIndexOf('.') + 1);
                                if (!"StripedBlockCache".equals(simpleClass)) {
                                    stripeLeakDetected.compareAndSet(null, origin);
                                    return;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });
            }

            barrier.await();
            cache.close();

            for (var thread : threads) {
                thread.join(2000);
            }
        }

        assertNull(stripeLeakDetected.get(),
                "TOCTOU race in size(): IllegalStateException leaked from stripe level. "
                        + "Expected origin: StripedBlockCache, but got: "
                        + (stripeLeakDetected.get() != null
                                ? stripeLeakDetected.get().getClassName() + "."
                                        + stripeLeakDetected.get().getMethodName()
                                : "n/a"));
    }

    // Finding: F-R1.contract_boundaries.2.2
    // Bug: BlockCache.getOrLoad (default) uses `synchronized(this)` but the Javadoc does not
    // warn third-party implementers or callers about monitor-collision deadlock or unexpected
    // contention on the cache instance's intrinsic monitor. A caller that holds the cache's
    // intrinsic monitor externally (synchronized(cache) { ... }) nests the default's
    // synchronized(this) under its own monitor — an implementer who also guards their own
    // state with synchronized(this) suffers unexpected serialization. Neither behavior is
    // documented at the interface boundary.
    // Correct behavior: the default getOrLoad Javadoc must disclose the locking strategy in
    // enough detail for a third-party implementer or caller to know the cache instance's
    // intrinsic monitor is acquired, and warn that holding that monitor externally risks
    // deadlock or unexpected contention.
    // Fix location: BlockCache.java — augment the default getOrLoad Javadoc with a monitor /
    // locking warning (mention "monitor", "deadlock", or equivalent "must not hold the
    // cache's intrinsic monitor" guidance).
    // Regression watch: keep the existing "synchronises on `this`" + "non-atomic" + R6
    // override directive intact; only add the monitor-collision warning.
    @Test
    void test_BlockCache_getOrLoad_default_javadocDocumentsMonitorCollisionRisk() throws Exception {
        Path source = Path.of("src/main/java/jlsm/core/cache/BlockCache.java");
        assertTrue(Files.isRegularFile(source),
                "BlockCache.java must exist at " + source.toAbsolutePath());
        String text = Files.readString(source);

        // Locate the default getOrLoad Javadoc. The signature spans multiple lines so find
        // the distinctive method-name + default keyword fragment and walk back to /**.
        int signatureIdx = text.indexOf("default MemorySegment getOrLoad(");
        assertTrue(signatureIdx > 0,
                "BlockCache.java must declare `default MemorySegment getOrLoad(...)`");
        int javadocStart = text.lastIndexOf("/**", signatureIdx);
        int javadocEnd = text.indexOf("*/", javadocStart);
        assertTrue(javadocStart > 0 && javadocEnd > javadocStart,
                "default getOrLoad must have a preceding Javadoc block");
        String javadoc = text.substring(javadocStart, javadocEnd);

        // The Javadoc must disclose the monitor-collision risk: either by mentioning the
        // word "monitor" (referring to the instance's intrinsic monitor) OR by mentioning
        // "deadlock" as a risk for callers/implementers. Either keyword signals that the
        // contract boundary addresses the locking strategy beyond "non-atomic."
        String lower = javadoc.toLowerCase();
        boolean discloses = lower.contains("monitor") || lower.contains("deadlock");
        assertTrue(discloses,
                "BlockCache.getOrLoad default Javadoc must document the monitor-collision "
                        + "risk — it must mention 'monitor' or 'deadlock' so third-party "
                        + "implementers and callers know the cache instance's intrinsic "
                        + "monitor is acquired and must not be held externally. "
                        + "Current Javadoc: \n" + javadoc);
    }

    // Finding: F-R1.contract_boundaries.2.1
    // Bug: BlockCache interface Javadoc for close() says "behavior of all other methods is
    // undefined" after close; it does NOT promote the R31 use-after-close ISE contract that
    // every in-tree impl enforces. A third-party implementer reading only the interface is
    // free to implement get/put/getOrLoad/evict/size/capacity with any behavior post-close
    // (silent empties, stale reads, corrupted accounting). Callers of the abstract type
    // cannot rely on ISE-on-reuse because the interface permits the "undefined" contract.
    // Correct behavior: the interface Javadoc must promote R31 — either on close() itself or
    // on each method's @throws — so the contract is binding at the interface boundary, not
    // only at the impl level.
    // Fix location: BlockCache.java — update close() Javadoc to state that subsequent calls
    // must throw IllegalStateException (R31), and/or add @throws IllegalStateException to
    // each method's Javadoc.
    // Regression watch: ensure the phrasing remains consistent with R31 wording in impls
    // ("cache is closed"); keep the default getOrLoad non-atomic note intact.
    @Test
    void test_BlockCache_close_javadocPromotesUseAfterCloseIseContract() throws Exception {
        Path source = Path.of("src/main/java/jlsm/core/cache/BlockCache.java");
        assertTrue(Files.isRegularFile(source),
                "BlockCache.java must exist at " + source.toAbsolutePath());
        String text = Files.readString(source);

        // Locate the close() Javadoc block. The method signature is `void close();` at the
        // end of the interface. Extract the preceding Javadoc comment.
        int closeIdx = text.indexOf("void close();");
        assertTrue(closeIdx > 0, "BlockCache.java must declare `void close();`");
        int javadocStart = text.lastIndexOf("/**", closeIdx);
        int javadocEnd = text.indexOf("*/", javadocStart);
        assertTrue(javadocStart > 0 && javadocEnd > javadocStart,
                "close() must have a preceding Javadoc block");
        String closeJavadoc = text.substring(javadocStart, javadocEnd);

        // The fix must promote R31 to the interface — either (a) the close() Javadoc itself
        // mentions that subsequent calls throw IllegalStateException, or (b) each other
        // public method documents @throws IllegalStateException. Case (a) is the simpler
        // and preferred fix.
        boolean closeDocumentsIse = closeJavadoc.contains("IllegalStateException");
        assertTrue(closeDocumentsIse,
                "BlockCache.close() Javadoc must promote R31 use-after-close contract — "
                        + "it must state that subsequent calls to other methods throw "
                        + "IllegalStateException. Current Javadoc: \n" + closeJavadoc);
    }

    // Finding: F-R1.contract_boundaries.4.2
    // Bug: LruBlockCache.<init>(Builder) emits a different error message than
    // LruBlockCache.Builder.build() when byteBudget is unset. Under reflective bypass
    // (constructing via reflection without calling .byteBudget(n)), the <init>
    // throws "byteBudget must be positive, got: -1" — leaking the sentinel value.
    // The build() path emits "byteBudget not set — call .byteBudget(n) before .build()".
    // Correct behavior: the <init> path, as the last-line-of-defense under -da, must emit
    // the same "byteBudget not set" diagnostic for the sentinel case so the error surface
    // is consistent across the build → <init> contract boundary.
    // Fix location: LruBlockCache.<init>(Builder) — detect the unset sentinel and emit the
    // same "byteBudget not set" message that build() uses.
    // Regression watch: preserve the existing "must be positive" message for the non-sentinel
    // case (positive callers don't exist because build() catches them, but if a future
    // setter path ever permits a positive-then-zeroed field, the positive-guard must
    // still trigger). Do not break existing shared_state.2.4 regression coverage.
    @Test
    void test_LruBlockCache_init_errorMessageMatchesBuildOnUnsetByteBudget() throws Exception {
        // Reflective bypass: instantiate the Builder, do NOT call .byteBudget(n), then
        // invoke the private <init> directly. byteBudget remains at sentinel -1L.
        var builder = LruBlockCache.builder();
        var ctor = LruBlockCache.class.getDeclaredConstructor(LruBlockCache.Builder.class);
        ctor.setAccessible(true);

        var cause = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> ctor.newInstance(builder),
                "<init> must reject unset byteBudget under reflective bypass");
        assertInstanceOf(IllegalArgumentException.class, cause.getCause(),
                "cause must be IllegalArgumentException");
        String msg = cause.getCause().getMessage();
        assertNotNull(msg, "IAE must carry a message");
        assertTrue(msg.contains("not set"),
                "<init> error message for unset byteBudget must match build()'s 'not set' "
                        + "diagnostic rather than leaking the sentinel value. "
                        + "Expected message to contain 'not set'; got: " + msg);
        assertFalse(msg.contains("-1"),
                "<init> error message must not leak the -1 sentinel value; got: " + msg);
    }

    // Finding: F-R1.contract_boundaries.4.2 (StripedBlockCache half)
    // Bug: StripedBlockCache.<init>(Builder) emits a different error message than
    // StripedBlockCache.Builder.build() when byteBudget is unset. Under reflective bypass
    // the <init> throws "byteBudget must be >= effective stripeCount (N), got byteBudget=-1"
    // leaking the sentinel. build() emits "byteBudget not set — call .byteBudget(n) before
    // .build()".
    // Correct behavior: the <init> path must emit the same "byteBudget not set" diagnostic
    // for the sentinel case so the error surface is consistent across the build → <init>
    // contract boundary.
    // Fix location: StripedBlockCache.<init>(Builder) — detect the unset sentinel before
    // the byteBudget < effectiveStripeCount check and emit the same "not set" message.
    // Regression watch: preserve the existing "byteBudget must be >= effective stripeCount"
    // message for legitimate too-small-but-set budgets; preserve shared_state regression.
    @Test
    void test_StripedBlockCache_init_errorMessageMatchesBuildOnUnsetByteBudget() throws Exception {
        // Reflective bypass: instantiate the Builder (default stripeCount is valid via
        // Runtime.availableProcessors), do NOT call .byteBudget(n), then invoke the private
        // <init>. byteBudget remains at sentinel -1L.
        var builder = StripedBlockCache.builder();
        var ctor = StripedBlockCache.class.getDeclaredConstructor(StripedBlockCache.Builder.class);
        ctor.setAccessible(true);

        var cause = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> ctor.newInstance(builder),
                "<init> must reject unset byteBudget under reflective bypass");
        assertInstanceOf(IllegalArgumentException.class, cause.getCause(),
                "cause must be IllegalArgumentException");
        String msg = cause.getCause().getMessage();
        assertNotNull(msg, "IAE must carry a message");
        assertTrue(msg.contains("not set"),
                "<init> error message for unset byteBudget must match build()'s 'not set' "
                        + "diagnostic rather than leaking the sentinel value. "
                        + "Expected message to contain 'not set'; got: " + msg);
        assertFalse(msg.contains("-1"),
                "<init> error message must not leak the -1 sentinel value; got: " + msg);
    }

    // Finding: F-R1.contract_boundaries.4.1
    // Bug: StripedBlockCache.Builder.build() drops R18 (MAX_STRIPE_COUNT) enforcement
    // — the cap exists only in the stripeCount(int) setter, so a reflective bypass
    // that writes the Builder's private stripeCount field directly to a value >
    // MAX_STRIPE_COUNT is accepted by build() and passed to <init>, which also
    // does not re-check the cap.
    // Correct behavior: build() must re-enforce the MAX_STRIPE_COUNT invariant and
    // throw IllegalArgumentException when stripeCount exceeds MAX_STRIPE_COUNT,
    // matching the setter's R18 contract. The <init> path already serves as the
    // last-line-of-defense for other invariants under -da.
    // Fix location: StripedBlockCache.Builder.build() — add MAX_STRIPE_COUNT check
    // Regression watch: ensure stripeCount <= MAX_STRIPE_COUNT still builds; default
    // stripeCount (availableProcessors-derived) still builds cleanly.
    @Test
    void test_StripedBlockCache_build_enforcesMaxStripeCount_onReflectiveBypass() throws Exception {
        var builder = StripedBlockCache.builder().byteBudget(1_000_000_000L);
        // Reflective bypass: write stripeCount past MAX_STRIPE_COUNT without using
        // the setter, simulating a serialization-revival or future Builder method
        // that mutates the field directly.
        Field stripeCountField = builder.getClass().getDeclaredField("stripeCount");
        stripeCountField.setAccessible(true);
        stripeCountField.setInt(builder, 10_000);

        var ex = assertThrows(IllegalArgumentException.class, builder::build,
                "build() must reject stripeCount > MAX_STRIPE_COUNT even when the "
                        + "setter was bypassed; R18 is a build-time invariant and the "
                        + "build → <init> contract must defend it at the last line.");
        assertTrue(ex.getMessage().contains("stripeCount") || ex.getMessage().contains("1024"),
                "exception message must identify the stripeCount cap; got: " + ex.getMessage());
    }

}
