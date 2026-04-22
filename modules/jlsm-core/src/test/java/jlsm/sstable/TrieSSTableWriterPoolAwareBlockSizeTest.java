package jlsm.sstable;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.io.ArenaBufferPool;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.sstable.internal.SSTableFormat;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the pool-aware block-size configuration on {@link TrieSSTableWriter.Builder}.
 *
 * <p>
 * Covers spec {@code sstable.pool-aware-block-size} (v4 APPROVED) requirements R3–R16 and the two
 * non-goals that have observable behavior (N1, N4). The pool-side requirements R0–R2 are tested in
 * {@code ArenaBufferPoolTest}.
 */
class TrieSSTableWriterPoolAwareBlockSizeTest {

    // ---------- helpers ----------

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private static ArenaBufferPool pool(long bufferSize) {
        return ArenaBufferPool.builder().poolSize(1).bufferSize(bufferSize)
                .acquireTimeoutMillis(1000).build();
    }

    /**
     * Builds a pool whose Builder.bufferSize(long) accepts any positive value without its own upper
     * bound (per spec N3). Values &gt; Integer.MAX_VALUE will fail on arena allocation in most
     * environments, so this helper uses {@code poolSize(0)}-style trick — we override via a fake.
     * Instead, the R8 tests construct a real pool with a small bufferSize and then separately
     * verify the builder rejects oversized bufferSizes via R8 at pool() time.
     * <p>
     * Because we cannot easily construct an ArenaBufferPool with bufferSize &gt; Integer.MAX_VALUE
     * in practice (allocation fails), R8 is tested via a pool subclass override OR by verifying the
     * builder's overflow check path exists. We use the latter: construct a pool with a valid but
     * near-MAX value, and the R8 message-format test uses a mocked pool path via a custom
     * ArenaBufferPool whose bufferSize() is modifiable.
     * <p>
     * Practical approach: R8 boundary is tested by constructing a pool whose bufferSize sits right
     * above the int→long boundary. The Builder's narrowing gate sits strictly above
     * Integer.MAX_VALUE, so a pool with bufferSize = Integer.MAX_VALUE + 1 hits R8. ArenaBufferPool
     * allows any long &gt;= 1 per the existing builder; the actual arena.allocate call is what
     * would fail for huge values. So we allocate pool with a "reasonable" bufferSize and use the
     * field-setting via reflection only as a last resort.
     */
    private static TrieSSTableWriter.Builder baseBuilder(Path path) {
        return TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01));
    }

    // =========================================================================
    // R3 — Builder exposes pool(ArenaBufferPool)
    // =========================================================================

    @Nested
    class PoolMethodExists {

        // @spec sstable.pool-aware-block-size.R3
        @Test
        void pool_returnsBuilderForChaining(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("r3.sst");
            try (var p = pool(4096)) {
                TrieSSTableWriter.Builder b = baseBuilder(out);
                TrieSSTableWriter.Builder ret = b.pool(p);
                assertSame(b, ret,
                        "R3: pool() must return the Builder instance for fluent chaining");
            }
        }
    }

    // =========================================================================
    // R4, R4a — null rejection (runtime check, not assert-only)
    // =========================================================================

    @Nested
    class NullRejection {

        // @spec sstable.pool-aware-block-size.R4
        @Test
        void pool_throwsNPE_onNull(@TempDir Path dir) {
            Path out = dir.resolve("r4.sst");
            TrieSSTableWriter.Builder b = baseBuilder(out);
            assertThrows(NullPointerException.class, () -> b.pool(null),
                    "R4: pool(null) must throw NullPointerException");
        }

        // @spec sstable.pool-aware-block-size.R4a
        @Test
        void pool_nullMessage_nonEmpty(@TempDir Path dir) {
            Path out = dir.resolve("r4a.sst");
            TrieSSTableWriter.Builder b = baseBuilder(out);
            NullPointerException npe = assertThrows(NullPointerException.class, () -> b.pool(null));
            assertNotNull(npe.getMessage(),
                    "R4a: runtime null check (e.g. Objects.requireNonNull) produces a message; assert-only would not");
            assertFalse(npe.getMessage().isBlank(),
                    "R4a: the NPE message must be non-blank so callers can diagnose the null source");
        }
    }

    // =========================================================================
    // R5 — closed-pool rejection at pool() call time
    // =========================================================================

    @Nested
    class ClosedPoolRejection {

        // @spec sstable.pool-aware-block-size.R5
        @Test
        void pool_throwsISE_whenPoolIsClosedAtCallTime(@TempDir Path dir) {
            Path out = dir.resolve("r5.sst");
            var p = pool(4096);
            p.close();
            TrieSSTableWriter.Builder b = baseBuilder(out);
            assertThrows(IllegalStateException.class, () -> b.pool(p),
                    "R5: pool() must throw ISE when the pool is closed at call time");
        }

        // @spec sstable.pool-aware-block-size.R5
        @Test
        void pool_isePath_messageIdentifiesClosed(@TempDir Path dir) {
            Path out = dir.resolve("r5-msg.sst");
            var p = pool(4096);
            p.close();
            TrieSSTableWriter.Builder b = baseBuilder(out);
            IllegalStateException ise = assertThrows(IllegalStateException.class, () -> b.pool(p));
            assertNotNull(ise.getMessage(), "R5: ISE must carry a non-null message");
            assertTrue(ise.getMessage().toLowerCase().contains("closed"),
                    "R5: ISE message must indicate the pool is closed; got: " + ise.getMessage());
        }
    }

    // =========================================================================
    // R5a — build-time recheck of isClosed(), conditional on active block-size source
    // =========================================================================

    @Nested
    class BuildTimeClosedPoolRecheck {

        // @spec sstable.pool-aware-block-size.R5a
        @Test
        void build_throwsISE_whenPoolClosedBetweenPoolAndBuild(@TempDir Path dir) {
            Path out = dir.resolve("r5a.sst");
            var p = pool(8192);
            TrieSSTableWriter.Builder b = baseBuilder(out).codec(CompressionCodec.deflate(6))
                    .pool(p);
            p.close();
            assertThrows(IllegalStateException.class, b::build,
                    "R5a: build() must throw ISE when the retained pool was closed between pool() and build()");
        }

        // @spec sstable.pool-aware-block-size.R5a
        @Test
        void build_r5aMessageDistinctFromR5(@TempDir Path dir) {
            Path out = dir.resolve("r5a-msg.sst");
            var p = pool(8192);
            TrieSSTableWriter.Builder b = baseBuilder(out).codec(CompressionCodec.deflate(6))
                    .pool(p);
            p.close();
            IllegalStateException ise = assertThrows(IllegalStateException.class, b::build);
            assertNotNull(ise.getMessage(), "R5a: ISE must carry a non-null message");
            // The spec example message: "pool was closed between pool() and build()".
            // We assert on the distinguishing keyword.
            String msg = ise.getMessage().toLowerCase();
            assertTrue(msg.contains("between") || msg.contains("build"),
                    "R5a: message must distinguish this from the R5 case (e.g. mention 'between' or 'build'); got: "
                            + ise.getMessage());
        }

        // @spec sstable.pool-aware-block-size.R5a (scoping — skipped when blockSizeExplicit)
        @Test
        void build_r5aSkipped_whenBlockSizeExplicitCalled(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("r5a-skipped.sst");
            var p = pool(8192);
            TrieSSTableWriter.Builder b = baseBuilder(out).codec(CompressionCodec.deflate(6))
                    .pool(p).blockSize(16384);
            p.close();
            // Explicit blockSize() was called → pool is no longer the active block-size source.
            // R5a is skipped; build() must succeed.
            try (TrieSSTableWriter w = b.build()) {
                w.append(put("k", "v", 1));
                w.finish();
            }
        }
    }

    // =========================================================================
    // R7, R7a — pool derivation happens eagerly at pool() call
    // =========================================================================

    @Nested
    class EagerDerivationAtPool {

        // @spec sstable.pool-aware-block-size.R7a
        @Test
        void pool_eagerlyValidates_whenNoPriorBlockSize(@TempDir Path dir) {
            Path out = dir.resolve("r7a.sst");
            // bufferSize=3000 is not a power of two — fails validateBlockSize.
            // R7a: validation must fire at pool() call time, not deferred to build().
            try (var p = pool(3000)) {
                TrieSSTableWriter.Builder b = baseBuilder(out);
                assertThrows(IllegalArgumentException.class, () -> b.pool(p),
                        "R7a: eager validation must throw at pool() call time when no prior blockSize(int)");
            }
        }

        // @spec sstable.pool-aware-block-size.R7a (conditional — skipped when blockSize called
        // first)
        @Test
        void pool_skipsEagerValidation_whenBlockSizeAlreadyCalled(@TempDir Path dir) {
            Path out = dir.resolve("r7a-skip.sst");
            // Even though the pool's bufferSize would fail validateBlockSize, R7a is skipped
            // because blockSize(int) was explicitly called first → R11 says explicit wins, so
            // eager pool-derivation validation must NOT fire.
            try (var p = pool(3000)) {
                TrieSSTableWriter.Builder b = baseBuilder(out).codec(CompressionCodec.deflate(6))
                        .blockSize(16384);
                assertDoesNotThrow(() -> b.pool(p),
                        "R7a conditional: pool-derivation validation must be skipped when blockSize(int) was called first");
            }
        }

        // @spec sstable.pool-aware-block-size.R7
        @Test
        void build_usesPoolDerivedBlockSize_whenValidPoolAndNoExplicit(@TempDir Path dir)
                throws IOException {
            Path out = dir.resolve("r7-derived.sst");
            int expected = 16384;
            try (var p = pool(expected)) {
                try (TrieSSTableWriter w = baseBuilder(out).codec(CompressionCodec.deflate(6))
                        .pool(p).build()) {
                    w.append(put("k", "v", 1));
                    w.finish();
                }
            }
            try (TrieSSTableReader r = TrieSSTableReader.open(out,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                    CompressionCodec.deflate())) {
                assertEquals((long) expected, r.blockSize(),
                        "R7: the pool-derived block size must be used and written to the footer");
            }
        }
    }

    // =========================================================================
    // R8 — long-space overflow check before narrowing
    // =========================================================================

    @Nested
    class OverflowCheck {

        /**
         * Constructs a fake ArenaBufferPool backed by reflection to set a bufferSize beyond
         * Integer.MAX_VALUE without triggering arena.allocate. Required because the real
         * ArenaBufferPool.Builder will allocate arena memory sized to bufferSize at construction,
         * which is infeasible for values &gt; Integer.MAX_VALUE in a unit test. We validate R8 by
         * constructing a real pool and then reflectively overwriting its bufferSize field.
         */
        private ArenaBufferPool poolWithOversizedBufferSize(long oversized)
                throws ReflectiveOperationException {
            ArenaBufferPool p = pool(4096); // real pool, small allocation
            java.lang.reflect.Field f = ArenaBufferPool.class.getDeclaredField("bufferSize");
            f.setAccessible(true);
            // Defeat final via reflection for the test fixture only. This is legal in Java 25 for
            // instance fields with --add-opens; here we're in the same module so setAccessible is
            // sufficient.
            f.set(p, oversized);
            return p;
        }

        // @spec sstable.pool-aware-block-size.R8
        @Test
        void pool_throwsIAE_whenBufferSizeExceedsIntMaxValue(@TempDir Path dir) throws Exception {
            Path out = dir.resolve("r8.sst");
            long oversized = ((long) Integer.MAX_VALUE) + 1L;
            try (ArenaBufferPool p = poolWithOversizedBufferSize(oversized)) {
                TrieSSTableWriter.Builder b = baseBuilder(out);
                IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                        () -> b.pool(p),
                        "R8: pool.bufferSize() > Integer.MAX_VALUE must throw IAE at pool() call");
                assertNotNull(iae.getMessage(), "R8: IAE must carry a diagnostic message");
            }
        }

        // @spec sstable.pool-aware-block-size.R8 — message includes the actual long value
        @Test
        void pool_iaeMessage_includesLongValue(@TempDir Path dir) throws Exception {
            Path out = dir.resolve("r8-msg.sst");
            long oversized = ((long) Integer.MAX_VALUE) + 1L;
            try (ArenaBufferPool p = poolWithOversizedBufferSize(oversized)) {
                TrieSSTableWriter.Builder b = baseBuilder(out);
                IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                        () -> b.pool(p));
                assertTrue(iae.getMessage().contains(Long.toString(oversized)),
                        "R8: IAE message must include the actual long value " + oversized
                                + "; got: " + iae.getMessage());
            }
        }

        // @spec sstable.pool-aware-block-size.R8 — strict > inequality; Integer.MAX_VALUE is NOT
        // rejected by R8 itself, but falls through to R9 (not power of two, exceeds MAX).
        @Test
        void pool_boundary_IntMaxValue_notRejectedByR8_butByR9(@TempDir Path dir) throws Exception {
            Path out = dir.resolve("r8-boundary.sst");
            try (ArenaBufferPool p = poolWithOversizedBufferSize(Integer.MAX_VALUE)) {
                TrieSSTableWriter.Builder b = baseBuilder(out);
                IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                        () -> b.pool(p),
                        "pool.bufferSize() == Integer.MAX_VALUE must still throw IAE (via R9, not R8)");
                // The R9 path surfaces SSTableFormat.validateBlockSize's message ("exceeds maximum"
                // or "not a power of two"), not R8's "exceeds Integer.MAX_VALUE" message.
                String msg = iae.getMessage() == null ? "" : iae.getMessage().toLowerCase();
                assertFalse(
                        msg.contains("integer.max_value")
                                || msg.contains(Long.toString((long) Integer.MAX_VALUE + 1L)),
                        "R8 strict > : Integer.MAX_VALUE itself must NOT trip R8's overflow message; it should surface R9's validator message. Got: "
                                + iae.getMessage());
            }
        }
    }

    // =========================================================================
    // R9, R10 — validateBlockSize on derived value
    // =========================================================================

    @Nested
    class ValidateBlockSizeDerivation {

        // @spec sstable.pool-aware-block-size.R9 R10 — not power of two
        @Test
        void pool_throwsIAE_whenBufferSizeNotPowerOfTwo(@TempDir Path dir) {
            Path out = dir.resolve("r9-pow2.sst");
            try (var p = pool(3000)) {
                TrieSSTableWriter.Builder b = baseBuilder(out);
                IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                        () -> b.pool(p));
                assertTrue(iae.getMessage().toLowerCase().contains("power of two"),
                        "R10: IAE must surface the validator's 'not a power of two' diagnostic; got: "
                                + iae.getMessage());
            }
        }

        // @spec sstable.pool-aware-block-size.R9 R10 — below minimum
        @Test
        void pool_throwsIAE_whenBufferSizeBelowMin(@TempDir Path dir) {
            Path out = dir.resolve("r9-min.sst");
            // 512 is < MIN_BLOCK_SIZE (1024) but > 0 (passes Builder.bufferSize check).
            // However — 512 is also a power of 2, so it only trips the min-size check.
            try (var p = pool(512)) {
                TrieSSTableWriter.Builder b = baseBuilder(out);
                IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                        () -> b.pool(p));
                assertTrue(iae.getMessage().toLowerCase().contains("below minimum"),
                        "R10: IAE must surface the validator's 'below minimum' diagnostic; got: "
                                + iae.getMessage());
            }
        }

        // @spec sstable.pool-aware-block-size.R9 R10 — above maximum (64 MiB > 32 MiB MAX)
        @Test
        void pool_throwsIAE_whenBufferSizeAboveMax(@TempDir Path dir) {
            Path out = dir.resolve("r9-max.sst");
            long aboveMax = 1L << 26; // 64 MiB, above SSTableFormat.MAX_BLOCK_SIZE (32 MiB)
            try (var p = pool(aboveMax)) {
                TrieSSTableWriter.Builder b = baseBuilder(out);
                IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                        () -> b.pool(p));
                assertTrue(iae.getMessage().toLowerCase().contains("exceeds maximum"),
                        "R10: IAE must surface the validator's 'exceeds maximum' diagnostic; got: "
                                + iae.getMessage());
            }
        }

        // @spec sstable.pool-aware-block-size.R10
        @Test
        void pool_iaeMessage_surfacesValidatorDiagnostic(@TempDir Path dir) {
            Path out = dir.resolve("r10-diag.sst");
            try (var p = pool(3000)) {
                TrieSSTableWriter.Builder b = baseBuilder(out);
                IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                        () -> b.pool(p));
                // The spec says the validator diagnostic must be surfaced. The diagnostic from
                // SSTableFormat.validateBlockSize includes the offending value.
                assertTrue(iae.getMessage().contains("3000"),
                        "R10: IAE message must surface the validator's value-bearing diagnostic; got: "
                                + iae.getMessage());
            }
        }
    }

    // =========================================================================
    // R6, R11, R11a — explicit blockSize overrides pool; last-wins
    // =========================================================================

    @Nested
    class ExplicitOverride {

        // @spec sstable.pool-aware-block-size.R11 — explicit before pool
        @Test
        void explicitBlockSize_winsWhenCalledBeforePool(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("r11-before.sst");
            int explicit = 8192;
            try (var p = pool(16384)) {
                try (TrieSSTableWriter w = baseBuilder(out).codec(CompressionCodec.deflate(6))
                        .blockSize(explicit).pool(p).build()) {
                    w.append(put("k", "v", 1));
                    w.finish();
                }
            }
            try (TrieSSTableReader r = TrieSSTableReader.open(out,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                    CompressionCodec.deflate())) {
                assertEquals((long) explicit, r.blockSize(),
                        "R11: explicit blockSize() before pool() must win — footer reflects explicit, not pool");
            }
        }

        // @spec sstable.pool-aware-block-size.R11 — explicit after pool
        @Test
        void explicitBlockSize_winsWhenCalledAfterPool(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("r11-after.sst");
            int explicit = 8192;
            try (var p = pool(16384)) {
                try (TrieSSTableWriter w = baseBuilder(out).codec(CompressionCodec.deflate(6))
                        .pool(p).blockSize(explicit).build()) {
                    w.append(put("k", "v", 1));
                    w.finish();
                }
            }
            try (TrieSSTableReader r = TrieSSTableReader.open(out,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                    CompressionCodec.deflate())) {
                assertEquals((long) explicit, r.blockSize(),
                        "R11: explicit blockSize() after pool() must win — footer reflects explicit, not pool");
            }
        }

        // @spec sstable.pool-aware-block-size.R6 — flag set true on every blockSize(int) call
        // @spec sstable.pool-aware-block-size.R11a — last-wins across repeated blockSize() calls
        @Test
        void explicitBlockSize_lastWins_whenCalledMultipleTimes(@TempDir Path dir)
                throws IOException {
            Path out = dir.resolve("r11a-lastwins.sst");
            try (TrieSSTableWriter w = baseBuilder(out).codec(CompressionCodec.deflate(6))
                    .blockSize(8192).blockSize(16384).blockSize(32768).build()) {
                w.append(put("k", "v", 1));
                w.finish();
            }
            try (TrieSSTableReader r = TrieSSTableReader.open(out,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                    CompressionCodec.deflate())) {
                assertEquals(32768L, r.blockSize(),
                        "R11a: repeated blockSize() calls use the most recent value");
            }
        }

        // @spec sstable.pool-aware-block-size.R11
        @Test
        void explicitBlockSize_overridesPoolDerivedValue_atBuild(@TempDir Path dir)
                throws IOException {
            Path out = dir.resolve("r11-override.sst");
            try (var p = pool(8192)) {
                try (TrieSSTableWriter w = baseBuilder(out).codec(CompressionCodec.deflate(6))
                        .pool(p).blockSize(16384).build()) {
                    w.append(put("k", "v", 1));
                    w.finish();
                }
            }
            try (TrieSSTableReader r = TrieSSTableReader.open(out,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                    CompressionCodec.deflate())) {
                assertEquals(16384L, r.blockSize(),
                        "R11: explicit blockSize(16384) must override pool-derived 8192");
            }
        }
    }

    // =========================================================================
    // R11a — repeated pool() + atomicity on failed repeat
    // =========================================================================

    @Nested
    class RepeatPoolCalls {

        // @spec sstable.pool-aware-block-size.R11a — pool last-wins
        @Test
        void pool_lastValidPool_wins_whenCalledMultipleTimes(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("r11a-poolwins.sst");
            try (var pA = pool(8192); var pB = pool(16384)) {
                try (TrieSSTableWriter w = baseBuilder(out).codec(CompressionCodec.deflate(6))
                        .pool(pA).pool(pB).build()) {
                    w.append(put("k", "v", 1));
                    w.finish();
                }
            }
            try (TrieSSTableReader r = TrieSSTableReader.open(out,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                    CompressionCodec.deflate())) {
                assertEquals(16384L, r.blockSize(),
                        "R11a: the most recent pool() call's bufferSize is used");
            }
        }

        // @spec sstable.pool-aware-block-size.R11a — atomicity: failed repeat preserves prior pool
        @Test
        void pool_failedRepeat_preservesPriorPoolReference(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("r11a-atomic.sst");
            int validSize = 8192;
            try (var pA = pool(validSize); var pB = pool(3000)) {
                TrieSSTableWriter.Builder b = baseBuilder(out).codec(CompressionCodec.deflate(6))
                        .pool(pA);
                // pool(pB) must throw because 3000 fails R9. R11a atomicity requires pA remains
                // retained.
                assertThrows(IllegalArgumentException.class, () -> b.pool(pB),
                        "R11a: failed repeat pool() must throw and leave builder state unchanged");
                // Now build() should succeed and produce an SSTable with pA's bufferSize (8192).
                try (TrieSSTableWriter w = b.build()) {
                    w.append(put("k", "v", 1));
                    w.finish();
                }
            }
            try (TrieSSTableReader r = TrieSSTableReader.open(out,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                    CompressionCodec.deflate())) {
                assertEquals((long) validSize, r.blockSize(),
                        "R11a atomicity: failed repeat must preserve the prior pool's derived block size");
            }
        }

        // @spec sstable.pool-aware-block-size.R11a — atomicity: failed repeat preserves derived
        // candidate
        @Test
        void pool_failedRepeat_preservesDerivedCandidate(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("r11a-candidate.sst");
            // Set up: pool(valid8192) → caches derived candidate 8192. Then pool(invalidOversized)
            // → throws. The cached derived candidate must still be 8192 on build().
            try (var pA = pool(8192)) {
                TrieSSTableWriter.Builder b = baseBuilder(out).codec(CompressionCodec.deflate(6))
                        .pool(pA);
                // Construct oversized via reflection to trip R8 on the repeat call.
                ArenaBufferPool pB = pool(4096);
                try {
                    java.lang.reflect.Field f = ArenaBufferPool.class
                            .getDeclaredField("bufferSize");
                    f.setAccessible(true);
                    f.set(pB, ((long) Integer.MAX_VALUE) + 1L);
                    assertThrows(IllegalArgumentException.class, () -> b.pool(pB),
                            "R11a: failed repeat (R8 violation) must throw");
                } catch (ReflectiveOperationException e) {
                    fail("reflection setup failed: " + e);
                } finally {
                    pB.close();
                }
                try (TrieSSTableWriter w = b.build()) {
                    w.append(put("k", "v", 1));
                    w.finish();
                }
            }
            try (TrieSSTableReader r = TrieSSTableReader.open(out,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                    CompressionCodec.deflate())) {
                assertEquals(8192L, r.blockSize(),
                        "R11a atomicity: derived candidate from the first successful pool() must survive a failed repeat");
            }
        }

        // @spec sstable.pool-aware-block-size.R11a — atomicity: blockSizeExplicit flag untouched
        @Test
        void pool_failedRepeat_preservesExplicitBlockSizeFlag(@TempDir Path dir)
                throws IOException {
            Path out = dir.resolve("r11a-flag.sst");
            int explicit = 16384;
            try (var pB = pool(3000)) {
                TrieSSTableWriter.Builder b = baseBuilder(out).codec(CompressionCodec.deflate(6))
                        .blockSize(explicit); // sets blockSizeExplicit = true
                // pool(pB) should NOT throw on R7a (that's skipped when blockSizeExplicit).
                // The failed-repeat atomicity invariant is: even if we somehow hit an error path
                // in pool(), the blockSizeExplicit flag must remain unchanged.
                // With bufferSize=3000 and blockSizeExplicit already true, R7a is skipped per
                // `pool_skipsEagerValidation_whenBlockSizeAlreadyCalled`, so this call succeeds.
                // The flag MUST still be true after the call.
                assertDoesNotThrow(() -> b.pool(pB));
                // Build must use the explicit size, not pool-derived.
                try (TrieSSTableWriter w = b.build()) {
                    w.append(put("k", "v", 1));
                    w.finish();
                }
            }
            try (TrieSSTableReader r = TrieSSTableReader.open(out,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                    CompressionCodec.deflate())) {
                assertEquals((long) explicit, r.blockSize(),
                        "R11a: blockSizeExplicit flag must survive pool() regardless of the pool's validity");
            }
        }
    }

    // =========================================================================
    // R13 — default block size when no pool + no explicit
    // =========================================================================

    @Nested
    class DefaultBehavior {

        // @spec sstable.pool-aware-block-size.R13
        @Test
        void build_usesDefaultBlockSize_whenNoPoolAndNoExplicit(@TempDir Path dir)
                throws IOException {
            Path out = dir.resolve("r13.sst");
            try (TrieSSTableWriter w = baseBuilder(out).build()) {
                w.append(put("k", "v", 1));
                w.finish();
            }
            try (TrieSSTableReader r = TrieSSTableReader.open(out,
                    BlockedBloomFilter.deserializer())) {
                assertEquals((long) SSTableFormat.DEFAULT_BLOCK_SIZE, r.blockSize(),
                        "R13: no pool + no explicit blockSize must yield DEFAULT_BLOCK_SIZE");
            }
        }
    }

    // =========================================================================
    // R15 — F16.R16 inheritance (non-default block size requires codec)
    // =========================================================================

    @Nested
    class F16CompatibilityInheritance {

        // @spec sstable.pool-aware-block-size.R15 (via F16.R16)
        @Test
        void build_throwsIAE_whenPoolDerivedNonDefaultBlockSize_andNoCodec(@TempDir Path dir) {
            Path out = dir.resolve("r15-nocodec.sst");
            try (var p = pool(8192)) {
                // No codec + pool-derived non-default block size → F16.R16 fires at build().
                TrieSSTableWriter.Builder b = baseBuilder(out).pool(p);
                assertThrows(IllegalArgumentException.class, b::build,
                        "R15: pool-derived non-default block size without codec must throw IAE at build() (F16.R16)");
            }
        }

        // @spec sstable.pool-aware-block-size.R15 positive
        @Test
        void build_succeeds_whenPoolDerivedNonDefaultBlockSize_andCodecProvided(@TempDir Path dir)
                throws IOException {
            Path out = dir.resolve("r15-codec.sst");
            try (var p = pool(8192)) {
                try (TrieSSTableWriter w = baseBuilder(out).codec(CompressionCodec.deflate(6))
                        .pool(p).build()) {
                    w.append(put("k", "v", 1));
                    w.finish();
                }
            }
            // Reaching here means build() succeeded — R15 positive path.
        }
    }

    // =========================================================================
    // R16 — footer integration
    // =========================================================================

    @Nested
    class FooterIntegration {

        // @spec sstable.pool-aware-block-size.R16
        @Test
        void footer_reflectsPoolDerivedBlockSize(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("r16-footer.sst");
            int bufSize = 32768;
            try (var p = pool(bufSize)) {
                try (TrieSSTableWriter w = baseBuilder(out).codec(CompressionCodec.deflate(6))
                        .pool(p).build()) {
                    w.append(put("k1", "v1", 1));
                    w.append(put("k2", "v2", 2));
                    w.finish();
                }
            }
            try (TrieSSTableReader r = TrieSSTableReader.open(out,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                    CompressionCodec.deflate())) {
                assertEquals((long) bufSize, r.blockSize(),
                        "R16: the pool-derived effective block size must be written to the v3 footer (F16.R15)");
            }
        }
    }

    // =========================================================================
    // Non-goals (observable behavior): N4 silent equivalence, N1 pool not retained
    // =========================================================================

    @Nested
    class NonGoalsObservable {

        // @spec sstable.pool-aware-block-size.N4 — byte-identical output on successful build
        @Test
        void build_byteIdentical_poolDefaultVsNoPool_successPath(@TempDir Path dir)
                throws IOException {
            Path withoutPool = dir.resolve("n4-no-pool.sst");
            Path withPool = dir.resolve("n4-with-pool.sst");

            // Write without pool
            try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0)
                    .path(withoutPool).bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).build()) {
                w.append(put("a", "va", 1));
                w.append(put("b", "vb", 2));
                w.append(put("c", "vc", 3));
                w.finish();
            }

            // Write with pool whose bufferSize == DEFAULT_BLOCK_SIZE
            try (var p = pool(SSTableFormat.DEFAULT_BLOCK_SIZE)) {
                try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0)
                        .path(withPool).bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).pool(p)
                        .build()) {
                    w.append(put("a", "va", 1));
                    w.append(put("b", "vb", 2));
                    w.append(put("c", "vc", 3));
                    w.finish();
                }
            }

            byte[] bytesNoPool = java.nio.file.Files.readAllBytes(withoutPool);
            byte[] bytesWithPool = java.nio.file.Files.readAllBytes(withPool);
            assertArrayEquals(bytesNoPool, bytesWithPool,
                    "N4: pool(bufferSize=DEFAULT_BLOCK_SIZE) on successful build must produce byte-identical output to the no-pool path");
        }

        // @spec sstable.pool-aware-block-size.N1 — writer does not query the pool during finish()
        @Test
        void pool_doesNotRetainRefForRuntimeAcquire(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("n1-nopoolafterbuild.sst");
            var p = pool(8192);
            TrieSSTableWriter w = baseBuilder(out).codec(CompressionCodec.deflate(6)).pool(p)
                    .build();
            // Close the pool AFTER build() has succeeded but before write operations.
            // N1: the writer does not re-query the pool — write ops must succeed regardless of pool
            // state.
            p.close();
            assertDoesNotThrow(() -> {
                w.append(put("k", "v", 1));
                w.finish();
                w.close();
            }, "N1: writer must not re-query the pool at runtime; closing the pool after build() must not affect write operations");
        }
    }
}
