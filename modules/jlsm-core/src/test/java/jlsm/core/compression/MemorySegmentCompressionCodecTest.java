package jlsm.core.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the MemorySegment-based {@link CompressionCodec} API (F17.R1-R17).
 *
 * <p>
 * All tests use {@link Arena#ofConfined()} for MemorySegment allocation. These tests target the new
 * MemorySegment compress/decompress signatures and verify the old byte[] methods are removed.
 */
class MemorySegmentCompressionCodecTest {

    // ---- R3: byte[] methods removed ----

    @Test
    void byteArrayCompressMethodRemoved_R3() {
        // The old byte[] compress(byte[], int, int) must no longer exist on CompressionCodec
        Method[] methods = CompressionCodec.class.getMethods();
        for (Method m : methods) {
            if ("compress".equals(m.getName())) {
                Class<?>[] params = m.getParameterTypes();
                assertFalse(
                        params.length == 3 && params[0] == byte[].class && params[1] == int.class
                                && params[2] == int.class,
                        "byte[] compress(byte[], int, int) must be removed (F17.R3)");
            }
        }
    }

    @Test
    void byteArrayDecompressMethodRemoved_R3() {
        // The old byte[] decompress(byte[], int, int, int) must no longer exist on CompressionCodec
        Method[] methods = CompressionCodec.class.getMethods();
        for (Method m : methods) {
            if ("decompress".equals(m.getName())) {
                Class<?>[] params = m.getParameterTypes();
                assertFalse(
                        params.length == 4 && params[0] == byte[].class && params[1] == int.class
                                && params[2] == int.class && params[3] == int.class,
                        "byte[] decompress(byte[], int, int, int) must be removed (F17.R3)");
            }
        }
    }

    // ---- R1/R2: MemorySegment compress/decompress signatures exist ----

    @Test
    void compressMethodAcceptsMemorySegments_R1() throws NoSuchMethodException {
        // Verify the method signature exists on the interface
        Method m = CompressionCodec.class.getMethod("compress", MemorySegment.class,
                MemorySegment.class);
        assertEquals(MemorySegment.class, m.getReturnType());
    }

    @Test
    void decompressMethodAcceptsMemorySegments_R2() throws NoSuchMethodException {
        Method m = CompressionCodec.class.getMethod("decompress", MemorySegment.class,
                MemorySegment.class, int.class);
        assertEquals(MemorySegment.class, m.getReturnType());
    }

    // ---- R4: codecId() and maxCompressedLength(int) retained ----

    @Test
    void codecIdRetained_R4() {
        assertEquals((byte) 0x00, CompressionCodec.none().codecId());
        assertEquals((byte) 0x02, CompressionCodec.deflate().codecId());
    }

    @Test
    void maxCompressedLengthRetained_R4() {
        CompressionCodec none = CompressionCodec.none();
        assertEquals(100, none.maxCompressedLength(100));

        CompressionCodec deflate = CompressionCodec.deflate();
        assertTrue(deflate.maxCompressedLength(100) >= 100);
    }

    // ---- R6: null segment rejection ----

    @Test
    void compressRejectsNullSrc_R6() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(256);
            assertThrows(NullPointerException.class, () -> codec.compress(null, dst));
        }
    }

    @Test
    void compressRejectsNullDst_R6() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(10);
            assertThrows(NullPointerException.class, () -> codec.compress(src, null));
        }
    }

    @Test
    void decompressRejectsNullSrc_R6() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(256);
            assertThrows(NullPointerException.class, () -> codec.decompress(null, dst, 10));
        }
    }

    @Test
    void decompressRejectsNullDst_R6() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(10);
            assertThrows(NullPointerException.class, () -> codec.decompress(src, null, 10));
        }
    }

    @Test
    void noneCompressRejectsNullSrc_R6() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(256);
            assertThrows(NullPointerException.class, () -> codec.compress(null, dst));
        }
    }

    @Test
    void noneDecompressRejectsNullDst_R6() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(10);
            assertThrows(NullPointerException.class, () -> codec.decompress(src, null, 10));
        }
    }

    // ---- R7: empty source compress -> zero-length slice ----

    @Test
    void deflateCompressEmptySourceReturnsZeroLengthSlice_R7() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(0);
            MemorySegment dst = arena.allocate(64);
            MemorySegment result = codec.compress(src, dst);
            assertEquals(0, result.byteSize());
        }
    }

    @Test
    void noneCompressEmptySourceReturnsZeroLengthSlice_R7() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(0);
            MemorySegment dst = arena.allocate(64);
            MemorySegment result = codec.compress(src, dst);
            assertEquals(0, result.byteSize());
        }
    }

    // ---- R8: empty decompress (0 src, 0 len) -> zero-length slice ----

    @Test
    void deflateDecompressEmptySrcZeroLen_R8() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(0);
            MemorySegment dst = arena.allocate(64);
            MemorySegment result = codec.decompress(src, dst, 0);
            assertEquals(0, result.byteSize());
        }
    }

    @Test
    void noneDecompressEmptySrcZeroLen_R8() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(0);
            MemorySegment dst = arena.allocate(64);
            MemorySegment result = codec.decompress(src, dst, 0);
            assertEquals(0, result.byteSize());
        }
    }

    // ---- R9: empty decompress (>0 src, 0 len) -> UncheckedIOException ----

    @Test
    void deflateDecompressNonEmptySrcZeroLen_R9() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(10);
            MemorySegment dst = arena.allocate(64);
            assertThrows(UncheckedIOException.class, () -> codec.decompress(src, dst, 0));
        }
    }

    @Test
    void noneDecompressNonEmptySrcZeroLen_R9() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(10);
            MemorySegment dst = arena.allocate(64);
            assertThrows(UncheckedIOException.class, () -> codec.decompress(src, dst, 0));
        }
    }

    // ---- R10: negative uncompressedLength -> IllegalArgumentException ----

    @Test
    void deflateDecompressNegativeUncompressedLength_R10() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(10);
            MemorySegment dst = arena.allocate(64);
            assertThrows(IllegalArgumentException.class, () -> codec.decompress(src, dst, -1));
        }
    }

    @Test
    void noneDecompressNegativeUncompressedLength_R10() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(10);
            MemorySegment dst = arena.allocate(64);
            assertThrows(IllegalArgumentException.class, () -> codec.decompress(src, dst, -1));
        }
    }

    @Test
    void deflateDecompressNegativeMaxInt_R10() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(10);
            MemorySegment dst = arena.allocate(64);
            assertThrows(IllegalArgumentException.class,
                    () -> codec.decompress(src, dst, Integer.MIN_VALUE));
        }
    }

    // ---- R11: undersized dst -> IllegalStateException ----

    @Test
    void deflateCompressUndersizedDst_R11() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(100);
            // dst is smaller than maxCompressedLength(100)
            MemorySegment dst = arena.allocate(1);
            assertThrows(IllegalStateException.class, () -> codec.compress(src, dst));
        }
    }

    @Test
    void noneCompressUndersizedDst_R11() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(100);
            MemorySegment dst = arena.allocate(50);
            assertThrows(IllegalStateException.class, () -> codec.compress(src, dst));
        }
    }

    // ---- R1/R12/R13: NoneCodec MemorySegment round-trip ----

    @Test
    void noneCodecRoundTrip_R12_R13() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            byte[] data = "Hello, MemorySegment world!".getBytes();
            MemorySegment src = arena.allocate(data.length);
            src.copyFrom(MemorySegment.ofArray(data));

            int maxLen = codec.maxCompressedLength((int) src.byteSize());
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            assertEquals(data.length, compressed.byteSize(),
                    "NoneCodec compressed size must equal source size");

            MemorySegment decompDst = arena.allocate(data.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, data.length);

            assertEquals(data.length, decompressed.byteSize());
            byte[] result = new byte[data.length];
            MemorySegment.copy(decompressed, 0, MemorySegment.ofArray(result), 0, data.length);
            assertArrayEquals(data, result);
        }
    }

    @Test
    void noneCodecDecompressSizeMismatch_R13() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(10);
            MemorySegment dst = arena.allocate(20);
            // src.byteSize()=10 but uncompressedLength=5 -> mismatch
            assertThrows(UncheckedIOException.class, () -> codec.decompress(src, dst, 5));
        }
    }

    // ---- R1/R14/R15/R16: DeflateCodec MemorySegment round-trip ----

    @Test
    void deflateCodecRoundTrip_R14_R15() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            byte[] data = "The quick brown fox jumps over the lazy dog. ".repeat(10).getBytes();
            MemorySegment src = arena.allocate(data.length);
            src.copyFrom(MemorySegment.ofArray(data));

            int maxLen = codec.maxCompressedLength(data.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            assertTrue(compressed.byteSize() < data.length,
                    "Deflate should compress repeated text");

            MemorySegment decompDst = arena.allocate(data.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, data.length);

            assertEquals(data.length, decompressed.byteSize());
            byte[] result = new byte[data.length];
            MemorySegment.copy(decompressed, 0, MemorySegment.ofArray(result), 0, data.length);
            assertArrayEquals(data, result);
        }
    }

    @Test
    void deflateCodecZeroCopyPath_R14() {
        // Verify that Arena-allocated segments produce direct ByteBuffers
        // (the zero-copy path through Deflater.setInput(ByteBuffer))
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(64);
            ByteBuffer bb = seg.asByteBuffer();
            assertTrue(bb.isDirect(),
                    "Arena-allocated MemorySegment.asByteBuffer() must return direct ByteBuffer");

            // Round-trip to confirm the zero-copy path works end-to-end
            byte[] data = "zero-copy test data for deflate".getBytes();
            MemorySegment src = arena.allocate(data.length);
            src.copyFrom(MemorySegment.ofArray(data));

            int maxLen = codec.maxCompressedLength(data.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);
            assertNotNull(compressed);
            assertTrue(compressed.byteSize() > 0);

            MemorySegment decompDst = arena.allocate(data.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, data.length);
            byte[] result = new byte[data.length];
            MemorySegment.copy(decompressed, 0, MemorySegment.ofArray(result), 0, data.length);
            assertArrayEquals(data, result);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 })
    void deflateAllLevelsMemorySegment_R17(int level) {
        CompressionCodec codec = CompressionCodec.deflate(level);
        try (Arena arena = Arena.ofConfined()) {
            byte[] data = "Repeated content for compression. ".repeat(20).getBytes();
            MemorySegment src = arena.allocate(data.length);
            src.copyFrom(MemorySegment.ofArray(data));

            int maxLen = codec.maxCompressedLength(data.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            MemorySegment decompDst = arena.allocate(data.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, data.length);

            byte[] result = new byte[data.length];
            MemorySegment.copy(decompressed, 0, MemorySegment.ofArray(result), 0, data.length);
            assertArrayEquals(data, result);
        }
    }

    @Test
    void deflateLargeInput_R14_R15() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            // 128 KiB of pseudo-random data with some repetition
            byte[] data = new byte[128 * 1024];
            Random rng = new Random(42);
            rng.nextBytes(data);
            System.arraycopy(data, 0, data, 64 * 1024, 32 * 1024);

            MemorySegment src = arena.allocate(data.length);
            src.copyFrom(MemorySegment.ofArray(data));

            int maxLen = codec.maxCompressedLength(data.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            MemorySegment decompDst = arena.allocate(data.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, data.length);

            byte[] result = new byte[data.length];
            MemorySegment.copy(decompressed, 0, MemorySegment.ofArray(result), 0, data.length);
            assertArrayEquals(data, result);
        }
    }

    @Test
    void deflateDecompressSizeMismatch_R2() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            byte[] data = "test data".getBytes();
            MemorySegment src = arena.allocate(data.length);
            src.copyFrom(MemorySegment.ofArray(data));

            int maxLen = codec.maxCompressedLength(data.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            // Request wrong uncompressed length
            MemorySegment decompDst = arena.allocate(data.length + 100);
            assertThrows(UncheckedIOException.class,
                    () -> codec.decompress(compressed, decompDst, data.length + 100));
        }
    }

    // ---- R5: Thread safety ----

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void deflateThreadSafety_R5() throws InterruptedException {
        CompressionCodec codec = CompressionCodec.deflate();
        int threadCount = 4;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            Thread.ofVirtual().start(() -> {
                try (Arena arena = Arena.ofConfined()) {
                    byte[] data = ("Thread %d data repeated ".formatted(threadId)).repeat(20)
                            .getBytes();
                    MemorySegment src = arena.allocate(data.length);
                    src.copyFrom(MemorySegment.ofArray(data));

                    int maxLen = codec.maxCompressedLength(data.length);
                    MemorySegment dst = arena.allocate(maxLen);
                    MemorySegment compressed = codec.compress(src, dst);

                    MemorySegment decompDst = arena.allocate(data.length);
                    MemorySegment decompressed = codec.decompress(compressed, decompDst,
                            data.length);

                    byte[] result = new byte[data.length];
                    MemorySegment.copy(decompressed, 0, MemorySegment.ofArray(result), 0,
                            data.length);
                    assertArrayEquals(data, result);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        assertNull(failure.get(), () -> "Thread safety failure: " + failure.get().getMessage());
    }

    // ---- R1: compress returns slice of dst ----

    @Test
    void compressReturnIsSliceOfDst_R1() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            byte[] data = "slice test".getBytes();
            MemorySegment src = arena.allocate(data.length);
            src.copyFrom(MemorySegment.ofArray(data));

            int maxLen = codec.maxCompressedLength(data.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment result = codec.compress(src, dst);

            // The result must be a slice of dst — verify by reading the same bytes
            // from dst at offset 0
            assertTrue(result.byteSize() > 0);
            assertTrue(result.byteSize() <= dst.byteSize());
            for (long i = 0; i < result.byteSize(); i++) {
                assertEquals(dst.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i),
                        result.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i));
            }
        }
    }

    @Test
    void decompressReturnIsSliceOfDst_R2() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            byte[] data = "slice decompress test".getBytes();
            MemorySegment src = arena.allocate(data.length);
            src.copyFrom(MemorySegment.ofArray(data));

            MemorySegment compressDst = arena.allocate(data.length);
            MemorySegment compressed = codec.compress(src, compressDst);

            MemorySegment decompDst = arena.allocate(data.length + 100);
            MemorySegment result = codec.decompress(compressed, decompDst, data.length);

            assertEquals(data.length, result.byteSize());
            for (long i = 0; i < result.byteSize(); i++) {
                assertEquals(decompDst.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i),
                        result.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i));
            }
        }
    }

    // ---- R1: compress does not mutate src ----

    @Test
    void compressDoesNotMutateSrc_R1() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            byte[] data = "immutable source test".getBytes();
            MemorySegment src = arena.allocate(data.length);
            src.copyFrom(MemorySegment.ofArray(data));

            // Snapshot src contents before compress
            byte[] before = new byte[data.length];
            MemorySegment.copy(src, 0, MemorySegment.ofArray(before), 0, data.length);

            int maxLen = codec.maxCompressedLength(data.length);
            MemorySegment dst = arena.allocate(maxLen);
            codec.compress(src, dst);

            // Verify src not modified
            byte[] after = new byte[data.length];
            MemorySegment.copy(src, 0, MemorySegment.ofArray(after), 0, data.length);
            assertArrayEquals(before, after);
        }
    }

    // ---- R11: zero-length src bypasses dst size check ----

    @Test
    void compressEmptySrcBypassesDstSizeCheck_R7_R11() {
        // R7 says empty src returns zero-length slice BEFORE R11 dst size check
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(0);
            MemorySegment dst = arena.allocate(0); // dst is technically too small for non-empty
            // Should NOT throw — R7 check happens first
            MemorySegment result = codec.compress(src, dst);
            assertEquals(0, result.byteSize());
        }
    }

    // ---- R17: invalid deflate levels ----

    @Test
    void deflateInvalidLevelNegative_R17() {
        assertThrows(IllegalArgumentException.class, () -> CompressionCodec.deflate(-1));
    }

    @Test
    void deflateInvalidLevelTooHigh_R17() {
        assertThrows(IllegalArgumentException.class, () -> CompressionCodec.deflate(10));
    }

    // ---- R12: NoneCodec uses MemorySegment.copy() — verify content ----

    @Test
    void noneCompressCopiesContentCorrectly_R12() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            byte[] data = { 1, 2, 3, 4, 5, 6, 7, 8 };
            MemorySegment src = arena.allocate(data.length);
            src.copyFrom(MemorySegment.ofArray(data));

            MemorySegment dst = arena.allocate(data.length);
            MemorySegment compressed = codec.compress(src, dst);

            assertEquals(data.length, compressed.byteSize());
            byte[] result = new byte[data.length];
            MemorySegment.copy(compressed, 0, MemorySegment.ofArray(result), 0, data.length);
            assertArrayEquals(data, result);
        }
    }

    // ---- Default maxCompressedLength for custom codecs ----

    @Test
    void defaultMaxCompressedLengthConservative_R4() {
        // A custom codec using the default implementation should return a conservative bound
        CompressionCodec custom = new CompressionCodec() {
            @Override
            public byte codecId() {
                return (byte) 0xFF;
            }

            @Override
            public MemorySegment compress(MemorySegment src, MemorySegment dst) {
                return dst.asSlice(0, 0);
            }

            @Override
            public MemorySegment decompress(MemorySegment src, MemorySegment dst,
                    int uncompressedLength) {
                return dst.asSlice(0, 0);
            }
        };
        int bound = custom.maxCompressedLength(100);
        assertTrue(bound >= 100, "default bound must be >= input");
    }

    // ---- Static factory smoke tests ----

    @Test
    void staticFactoryNoneSingleton() {
        CompressionCodec a = CompressionCodec.none();
        CompressionCodec b = CompressionCodec.none();
        assertSame(a, b, "none() must return the same singleton instance");
    }

    @Test
    void staticFactoryDeflateDefault() {
        CompressionCodec codec = CompressionCodec.deflate();
        assertEquals((byte) 0x02, codec.codecId());
    }

    // ---- maxCompressedLength bound holds for MemorySegment path ----

    @Test
    void deflateMaxCompressedLengthBoundHoldsForMemorySegment() {
        CompressionCodec codec = CompressionCodec.deflate();
        Random rng = new Random(42);
        try (Arena arena = Arena.ofConfined()) {
            for (int size : new int[]{ 0, 1, 10, 100, 4096, 65536 }) {
                byte[] data = new byte[size];
                rng.nextBytes(data);
                int bound = codec.maxCompressedLength(size);

                MemorySegment src = arena.allocate(size);
                src.copyFrom(MemorySegment.ofArray(data));
                MemorySegment dst = arena.allocate(bound);
                MemorySegment compressed = codec.compress(src, dst);

                assertTrue(compressed.byteSize() <= bound,
                        "compressed length (%d) exceeds maxCompressedLength(%d) = %d"
                                .formatted(compressed.byteSize(), size, bound));
            }
        }
    }

    // ---- Single byte round-trip ----

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void deflateSingleByteRoundTrip() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(1);
            src.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 42);

            int maxLen = codec.maxCompressedLength(1);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            MemorySegment decompDst = arena.allocate(1);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, 1);

            assertEquals(1, decompressed.byteSize());
            assertEquals((byte) 42, decompressed.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0));
        }
    }
}
