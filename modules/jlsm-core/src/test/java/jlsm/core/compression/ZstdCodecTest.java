package jlsm.core.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ZstdCodec} compression/decompression, level validation, codec ID, lifecycle, and
 * static factory methods on {@link CompressionCodec}.
 *
 * <p>
 * Tests are designed to work regardless of native libzstd availability. Round-trip tests that
 * require native are guarded by {@link ZstdNativeBindings#isNativeAvailable()}.
 */
// @spec compression.zstd-dictionary.R1,R2,R3,R3a,R7,R7a,R9,R17a,R17b
class ZstdCodecTest {

    // ---- Helper ----

    private static MemorySegment toSegment(Arena arena, byte[] data) {
        MemorySegment seg = arena.allocate(data.length);
        seg.copyFrom(MemorySegment.ofArray(data));
        return seg;
    }

    private static byte[] toByteArray(MemorySegment seg) {
        byte[] result = new byte[(int) seg.byteSize()];
        MemorySegment.copy(seg, 0, MemorySegment.ofArray(result), 0, result.length);
        return result;
    }

    // ---- Level validation (R2) ----

    // @spec compression.zstd-dictionary.R2
    @Test
    void constructorRejectsLevelZero() {
        assertThrows(IllegalArgumentException.class, () -> new ZstdCodec(0, null));
    }

    // @spec compression.zstd-dictionary.R2
    @Test
    void constructorRejectsNegativeLevel() {
        assertThrows(IllegalArgumentException.class, () -> new ZstdCodec(-1, null));
    }

    @Test
    void constructorRejectsLevelAbove22() {
        assertThrows(IllegalArgumentException.class, () -> new ZstdCodec(23, null));
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 3, 11, 22 })
    void constructorAcceptsValidLevels(int level) {
        assertDoesNotThrow(() -> new ZstdCodec(level, null));
    }

    // ---- Codec ID (R7a) ----

    @Test
    void codecIdReturnsCorrectValueForActiveTier() {
        var codec = new ZstdCodec(3, null);
        byte id = codec.codecId();
        if (ZstdNativeBindings.isNativeAvailable()) {
            assertEquals(ZstdCodec.ZSTD_CODEC_ID, id,
                    "codecId must be 0x03 when native is available");
        } else {
            assertEquals(ZstdCodec.DEFLATE_CODEC_ID, id,
                    "codecId must be 0x02 when native is unavailable (fallback)");
        }
    }

    // ---- maxCompressedLength (R3, R3a) ----

    @Test
    void maxCompressedLengthRejectsNegativeInput() {
        var codec = new ZstdCodec(3, null);
        assertThrows(IllegalArgumentException.class, () -> codec.maxCompressedLength(-1));
    }

    @Test
    void maxCompressedLengthReturnsNonNegativeForZero() {
        var codec = new ZstdCodec(3, null);
        int result = codec.maxCompressedLength(0);
        assertTrue(result >= 0, "maxCompressedLength(0) must be >= 0");
    }

    @Test
    void maxCompressedLengthReturnsAtLeastInputLength() {
        var codec = new ZstdCodec(3, null);
        for (int size : new int[]{ 0, 1, 100, 4096, 65536 }) {
            int bound = codec.maxCompressedLength(size);
            assertTrue(bound >= size,
                    "maxCompressedLength(%d) = %d must be >= input size".formatted(size, bound));
        }
    }

    @Test
    void maxCompressedLengthOverflowGuardForLargeInput() {
        // R3a: when the computed bound exceeds int range, throw IAE.
        // Both native ZSTD_compressBound(Integer.MAX_VALUE) and the fallback formula
        // (Integer.MAX_VALUE * 2 + 64) exceed Integer.MAX_VALUE, so both tiers must throw.
        var codec = new ZstdCodec(3, null);
        assertThrows(IllegalArgumentException.class,
                () -> codec.maxCompressedLength(Integer.MAX_VALUE));
        // Verify smaller inputs still work
        assertDoesNotThrow(() -> codec.maxCompressedLength(1_000_000));
    }

    // ---- Static factory methods (R1) ----

    @Test
    void zstdFactoryDefaultReturnsNonNull() {
        CompressionCodec codec = CompressionCodec.zstd();
        assertNotNull(codec, "zstd() must return a non-null codec");
    }

    @Test
    void zstdFactoryWithLevelReturnsNonNull() {
        CompressionCodec codec = CompressionCodec.zstd(10);
        assertNotNull(codec, "zstd(level) must return a non-null codec");
    }

    @Test
    void zstdFactoryWithDictionaryReturnsNonNull() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dict = arena.allocate(256);
            CompressionCodec codec = CompressionCodec.zstd(dict);
            assertNotNull(codec, "zstd(dictionary) must return a non-null codec");
            if (codec instanceof AutoCloseable ac) {
                assertDoesNotThrow(ac::close);
            }
        }
    }

    @Test
    void zstdFactoryWithLevelAndDictionaryReturnsNonNull() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dict = arena.allocate(256);
            CompressionCodec codec = CompressionCodec.zstd(5, dict);
            assertNotNull(codec, "zstd(level, dictionary) must return a non-null codec");
            if (codec instanceof AutoCloseable ac) {
                assertDoesNotThrow(ac::close);
            }
        }
    }

    @Test
    void zstdFactoryInvalidLevelThrows() {
        assertThrows(IllegalArgumentException.class, () -> CompressionCodec.zstd(0));
        assertThrows(IllegalArgumentException.class, () -> CompressionCodec.zstd(23));
    }

    // ---- Close / lifecycle (R17a, R17b) ----

    @Test
    void closedCodecThrowsOnCompress() {
        var codec = new ZstdCodec(3, null);
        codec.close();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = toSegment(arena, "test".getBytes());
            MemorySegment dst = arena.allocate(codec.maxCompressedLength(4));
            assertThrows(IllegalStateException.class, () -> codec.compress(src, dst));
        }
    }

    @Test
    void closedCodecThrowsOnDecompress() {
        var codec = new ZstdCodec(3, null);
        codec.close();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(10);
            MemorySegment dst = arena.allocate(10);
            assertThrows(IllegalStateException.class, () -> codec.decompress(src, dst, 10));
        }
    }

    @Test
    void closeIsIdempotent() {
        var codec = new ZstdCodec(3, null);
        assertDoesNotThrow(() -> {
            codec.close();
            codec.close();
        });
    }

    // ---- Compress / decompress null checks ----

    @Test
    void compressRejectsNullSrc() {
        var codec = new ZstdCodec(3, null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(64);
            assertThrows(NullPointerException.class, () -> codec.compress(null, dst));
        }
    }

    @Test
    void compressRejectsNullDst() {
        var codec = new ZstdCodec(3, null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = toSegment(arena, "test".getBytes());
            assertThrows(NullPointerException.class, () -> codec.compress(src, null));
        }
    }

    @Test
    void decompressRejectsNullSrc() {
        var codec = new ZstdCodec(3, null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(64);
            assertThrows(NullPointerException.class, () -> codec.decompress(null, dst, 10));
        }
    }

    @Test
    void decompressRejectsNegativeUncompressedLength() {
        var codec = new ZstdCodec(3, null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(10);
            MemorySegment dst = arena.allocate(10);
            assertThrows(IllegalArgumentException.class, () -> codec.decompress(src, dst, -1));
        }
    }

    // ---- Empty input handling ----

    @Test
    void compressEmptyInputReturnsZeroLengthSlice() {
        var codec = new ZstdCodec(3, null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(0);
            MemorySegment dst = arena.allocate(64);
            MemorySegment compressed = codec.compress(src, dst);
            assertEquals(0, compressed.byteSize(), "compressed empty input must be zero-length");
        }
    }

    // ---- Round-trip (native only) ----

    @Test
    void nativeRoundTrip() {
        if (!ZstdNativeBindings.isNativeAvailable()) {
            return; // skip when native unavailable
        }
        var codec = new ZstdCodec(3, null);
        try (Arena arena = Arena.ofConfined()) {
            byte[] input = "The quick brown fox jumps over the lazy dog. ".repeat(10).getBytes();
            MemorySegment src = toSegment(arena, input);

            int maxLen = codec.maxCompressedLength(input.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            assertTrue(compressed.byteSize() > 0, "compressed output must not be empty");
            assertTrue(compressed.byteSize() < input.length,
                    "compressed output should be smaller than input for repetitive data");

            MemorySegment decompDst = arena.allocate(input.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, input.length);
            assertArrayEquals(input, toByteArray(decompressed));
        }
    }

    @Test
    void nativeRoundTripMultipleLevels() {
        if (!ZstdNativeBindings.isNativeAvailable()) {
            return;
        }
        byte[] input = "Repeated content for compression testing. ".repeat(20).getBytes();
        for (int level : new int[]{ 1, 3, 10, 22 }) {
            var codec = new ZstdCodec(level, null);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment src = toSegment(arena, input);
                int maxLen = codec.maxCompressedLength(input.length);
                MemorySegment dst = arena.allocate(maxLen);
                MemorySegment compressed = codec.compress(src, dst);

                MemorySegment decompDst = arena.allocate(input.length);
                MemorySegment decompressed = codec.decompress(compressed, decompDst, input.length);
                assertArrayEquals(input, toByteArray(decompressed),
                        "round-trip failed at level " + level);
            }
        }
    }

    @Test
    void fallbackRoundTripUsesDeflate() {
        if (ZstdNativeBindings.isNativeAvailable()) {
            return; // skip when native available — this tests fallback path
        }
        var codec = new ZstdCodec(3, null);
        assertEquals(ZstdCodec.DEFLATE_CODEC_ID, codec.codecId(),
                "fallback codec must use Deflate codec ID");
        try (Arena arena = Arena.ofConfined()) {
            byte[] input = "Fallback deflate test data. ".repeat(10).getBytes();
            MemorySegment src = toSegment(arena, input);

            int maxLen = codec.maxCompressedLength(input.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            MemorySegment decompDst = arena.allocate(input.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, input.length);
            assertArrayEquals(input, toByteArray(decompressed));
        }
    }

    // ---- Undersized dst check ----

    @Test
    void compressUndersizedDstThrows() {
        var codec = new ZstdCodec(3, null);
        try (Arena arena = Arena.ofConfined()) {
            byte[] input = "some data".getBytes();
            MemorySegment src = toSegment(arena, input);
            MemorySegment dst = arena.allocate(1); // way too small
            assertThrows(IllegalStateException.class, () -> codec.compress(src, dst));
        }
    }
}
