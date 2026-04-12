package jlsm.core.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for {@link CompressionCodec} implementations.
 *
 * <p>
 * Updated from byte[] API to MemorySegment API per F17.R1-R3. Targets findings from
 * block-compression audit spec-analysis.md. Some original C1-F1 overflow tests are no longer
 * applicable since the MemorySegment API does not accept offset/length parameters — the segment
 * itself defines bounds.
 */
class CompressionCodecAdversarialTest {

    private static byte[] toByteArray(MemorySegment seg) {
        byte[] result = new byte[(int) seg.byteSize()];
        MemorySegment.copy(seg, 0, MemorySegment.ofArray(result), 0, result.length);
        return result;
    }

    // ---- C1-F2/C1-F5: decompress allows uncompressedLength=0 ----

    @Test
    void deflateDecompressNegativeUncompressedLengthThrowsIAE() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(10);
            MemorySegment dst = arena.allocate(64);
            assertThrows(IllegalArgumentException.class, () -> codec.decompress(src, dst, -1));
        }
    }

    @Test
    void noneDecompressNegativeUncompressedLengthThrowsIAE() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(5);
            MemorySegment dst = arena.allocate(64);
            assertThrows(IllegalArgumentException.class, () -> codec.decompress(src, dst, -1));
        }
    }

    @Test
    void deflateDecompressZeroUncompressedLengthEmptySrcAccepted() {
        // R8: uncompressedLength=0 with empty src is valid — returns zero-length slice
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(0);
            MemorySegment dst = arena.allocate(64);
            MemorySegment result = codec.decompress(src, dst, 0);
            assertEquals(0, result.byteSize());
        }
    }

    // C1-F2: Decompress non-empty compressed data with uncompressedLength=0 —
    // now throws UncheckedIOException per R9 (cannot decompress something into nothing).
    @Test
    void deflateDecompressNonEmptyWithZeroUncompressedLength_C1F2() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            byte[] original = "non-empty data that compresses to real bytes".getBytes();
            MemorySegment src = arena.allocate(original.length);
            src.copyFrom(MemorySegment.ofArray(original));

            int maxLen = codec.maxCompressedLength(original.length);
            MemorySegment compDst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, compDst);

            // R9: non-empty src with uncompressedLength=0 must throw
            MemorySegment decompDst = arena.allocate(64);
            assertThrows(java.io.UncheckedIOException.class,
                    () -> codec.decompress(compressed, decompDst, 0),
                    "R9: non-empty src with zero uncompressedLength must throw UncheckedIOException");
        }
    }

    @Test
    void noneDecompressNullSrcThrowsNPE() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(64);
            assertThrows(NullPointerException.class, () -> codec.decompress(null, dst, 0));
        }
    }

    @Test
    void deflateDecompressNullSrcThrowsNPE() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(64);
            assertThrows(NullPointerException.class, () -> codec.decompress(null, dst, 0));
        }
    }

    // ---- C1-F3: DeflateCodec compress — potential infinite loop ----
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void deflateCompressCompletesWithinTimeout_C1F3() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            // Highly incompressible data
            byte[] data = new byte[65536];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i * 31 + 17);
            }
            MemorySegment src = arena.allocate(data.length);
            src.copyFrom(MemorySegment.ofArray(data));

            int maxLen = codec.maxCompressedLength(data.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);
            assertNotNull(compressed);

            MemorySegment decompDst = arena.allocate(data.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, data.length);
            assertArrayEquals(data, toByteArray(decompressed));
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void deflateCompressSingleByteCompletes_C1F3() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(1);
            src.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 42);

            int maxLen = codec.maxCompressedLength(1);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);
            assertNotNull(compressed);

            MemorySegment decompDst = arena.allocate(1);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, 1);
            assertEquals((byte) 42, decompressed.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0));
        }
    }

    // ---- C1-F4: decompress trailing data ignored ----
    @Test
    void deflateDecompressTrailingDataIgnored_C1F4() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            byte[] original = "test data for trailing check".getBytes();
            MemorySegment src = arena.allocate(original.length);
            src.copyFrom(MemorySegment.ofArray(original));

            int maxLen = codec.maxCompressedLength(original.length);
            MemorySegment compDst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, compDst);

            // Create a larger segment with the compressed data + trailing garbage
            int paddedSize = (int) compressed.byteSize() + 100;
            MemorySegment withTrailing = arena.allocate(paddedSize);
            MemorySegment.copy(compressed, 0, withTrailing, 0, compressed.byteSize());
            for (long i = compressed.byteSize(); i < paddedSize; i++) {
                withTrailing.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte) 0xDE);
            }

            // C1-F4: Trailing data silently ignored — inflater reads only what it needs
            MemorySegment decompDst = arena.allocate(original.length);
            MemorySegment result = codec.decompress(withTrailing, decompDst, original.length);
            assertArrayEquals(original, toByteArray(result),
                    "C1-F4: trailing data silently ignored — returns correct data from prefix");
        }
    }
}
