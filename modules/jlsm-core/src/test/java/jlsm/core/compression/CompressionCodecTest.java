package jlsm.core.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CompressionCodec}, {@link NoneCodec}, and {@link DeflateCodec}.
 *
 * <p>
 * Tests are written against the public {@link CompressionCodec} interface and static factory
 * methods. Package-private implementations are tested through the interface.
 */
class CompressionCodecTest {

    // ---- NoneCodec: identity and round-trip ----

    @Test
    void testNoneCodecCodecId() {
        CompressionCodec codec = CompressionCodec.none();
        assertEquals((byte) 0x00, codec.codecId());
    }

    @Test
    void testNoneCodecRoundTrip() {
        CompressionCodec codec = CompressionCodec.none();
        byte[] input = "Hello, world!".getBytes();
        byte[] compressed = codec.compress(input, 0, input.length);
        byte[] decompressed = codec.decompress(compressed, 0, compressed.length, input.length);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void testNoneCompressIsSliceCopy() {
        CompressionCodec codec = CompressionCodec.none();
        byte[] input = { 1, 2, 3, 4, 5, 6, 7, 8 };
        // Compress a slice from offset 2, length 4
        byte[] compressed = codec.compress(input, 2, 4);
        byte[] expected = { 3, 4, 5, 6 };
        assertArrayEquals(expected, compressed);
        // Must be a copy, not a view into the original array
        assertNotSame(input, compressed);
    }

    @Test
    void testNoneCodecDecompressSizeMismatch() {
        CompressionCodec codec = CompressionCodec.none();
        byte[] input = { 1, 2, 3 };
        byte[] compressed = codec.compress(input, 0, input.length);
        // Request wrong uncompressed length
        assertThrows(UncheckedIOException.class,
                () -> codec.decompress(compressed, 0, compressed.length, 999));
    }

    // ---- DeflateCodec: identity and round-trip ----

    @Test
    void testDeflateCodecCodecId() {
        CompressionCodec codec = CompressionCodec.deflate();
        assertEquals((byte) 0x02, codec.codecId());
    }

    @Test
    void testDeflateCodecRoundTrip() {
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = "The quick brown fox jumps over the lazy dog. ".repeat(10).getBytes();
        byte[] compressed = codec.compress(input, 0, input.length);
        // Deflate should actually compress repeated text
        assertTrue(compressed.length < input.length,
                "compressed size (%d) should be less than input size (%d)"
                        .formatted(compressed.length, input.length));
        byte[] decompressed = codec.decompress(compressed, 0, compressed.length, input.length);
        assertArrayEquals(input, decompressed);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 })
    void testDeflateCodecAllLevels(int level) {
        CompressionCodec codec = CompressionCodec.deflate(level);
        assertEquals((byte) 0x02, codec.codecId());
        byte[] input = "Repeated content for compression. ".repeat(20).getBytes();
        byte[] compressed = codec.compress(input, 0, input.length);
        byte[] decompressed = codec.decompress(compressed, 0, compressed.length, input.length);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void testDeflateCodecLargeInput() {
        CompressionCodec codec = CompressionCodec.deflate();
        // 128 KiB of pseudo-random data with some repetition
        byte[] input = new byte[128 * 1024];
        Random rng = new Random(42);
        rng.nextBytes(input);
        // Add some repeated blocks to make it compressible
        System.arraycopy(input, 0, input, 64 * 1024, 32 * 1024);

        byte[] compressed = codec.compress(input, 0, input.length);
        byte[] decompressed = codec.decompress(compressed, 0, compressed.length, input.length);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void testDeflateCodecEmptyInput() {
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = new byte[0];
        byte[] compressed = codec.compress(input, 0, 0);
        byte[] decompressed = codec.decompress(compressed, 0, compressed.length, 0);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void testDeflateCodecWithOffset() {
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = "XXXX".getBytes();
        byte[] padded = new byte[20];
        System.arraycopy(input, 0, padded, 8, input.length);
        // Compress only the 4 bytes starting at offset 8
        byte[] compressed = codec.compress(padded, 8, input.length);
        byte[] decompressed = codec.decompress(compressed, 0, compressed.length, input.length);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void testDecompressSizeMismatch() {
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = "test data".getBytes();
        byte[] compressed = codec.compress(input, 0, input.length);
        // Request wrong uncompressed length
        assertThrows(UncheckedIOException.class,
                () -> codec.decompress(compressed, 0, compressed.length, input.length + 100));
    }

    // ---- Static factory methods ----

    @Test
    void testStaticFactoryNone() {
        CompressionCodec a = CompressionCodec.none();
        CompressionCodec b = CompressionCodec.none();
        assertSame(a, b, "none() must return the same singleton instance");
        assertEquals((byte) 0x00, a.codecId());
    }

    @Test
    void testStaticFactoryDeflate() {
        CompressionCodec codec = CompressionCodec.deflate();
        assertEquals((byte) 0x02, codec.codecId());
        // Should be a functional codec — can compress
        byte[] input = "test".getBytes();
        byte[] compressed = codec.compress(input, 0, input.length);
        assertNotNull(compressed);
    }

    @Test
    void testStaticFactoryDeflateWithLevel() {
        CompressionCodec codec = CompressionCodec.deflate(3);
        assertEquals((byte) 0x02, codec.codecId());
        byte[] input = "test data for level 3".getBytes();
        byte[] compressed = codec.compress(input, 0, input.length);
        byte[] decompressed = codec.decompress(compressed, 0, compressed.length, input.length);
        assertArrayEquals(input, decompressed);
    }

    // ---- maxCompressedLength ----

    @Test
    void testNoneMaxCompressedLengthEqualsInput() {
        CompressionCodec codec = CompressionCodec.none();
        assertEquals(0, codec.maxCompressedLength(0));
        assertEquals(100, codec.maxCompressedLength(100));
        assertEquals(1024, codec.maxCompressedLength(1024));
    }

    @Test
    void testDeflateMaxCompressedLengthAtLeastInput() {
        CompressionCodec codec = CompressionCodec.deflate();
        for (int size : new int[]{ 0, 1, 100, 4096, 65536, 128 * 1024 }) {
            int bound = codec.maxCompressedLength(size);
            assertTrue(bound >= size,
                    "maxCompressedLength(%d) = %d must be >= input size".formatted(size, bound));
        }
    }

    @Test
    void testDeflateMaxCompressedLengthBoundHolds() {
        // Verify the bound is never exceeded by actual compression
        CompressionCodec codec = CompressionCodec.deflate();
        Random rng = new Random(42);
        for (int size : new int[]{ 0, 1, 10, 100, 4096, 65536 }) {
            byte[] input = new byte[size];
            rng.nextBytes(input);
            int bound = codec.maxCompressedLength(size);
            byte[] compressed = codec.compress(input, 0, input.length);
            assertTrue(compressed.length <= bound,
                    "compressed length (%d) exceeds maxCompressedLength(%d) = %d"
                            .formatted(compressed.length, size, bound));
        }
    }

    @Test
    void testMaxCompressedLengthNegativeInputRejected() {
        CompressionCodec codec = CompressionCodec.deflate();
        assertThrows(IllegalArgumentException.class, () -> codec.maxCompressedLength(-1));
        assertThrows(IllegalArgumentException.class,
                () -> CompressionCodec.none().maxCompressedLength(-1));
    }

    @Test
    void testDefaultMaxCompressedLengthConservative() {
        // A custom codec using the default implementation should return a conservative bound
        CompressionCodec custom = new CompressionCodec() {
            @Override
            public byte codecId() {
                return (byte) 0xFF;
            }

            @Override
            public byte[] compress(byte[] input, int offset, int length) {
                return new byte[0];
            }

            @Override
            public byte[] decompress(byte[] input, int offset, int length, int uncompressedLength) {
                return new byte[0];
            }
        };
        int bound = custom.maxCompressedLength(100);
        assertTrue(bound >= 100, "default bound must be >= input");
    }

    // ---- Invalid argument tests ----

    @Test
    void testDeflateCodecInvalidLevelNegative() {
        assertThrows(IllegalArgumentException.class, () -> CompressionCodec.deflate(-1));
    }

    @Test
    void testDeflateCodecInvalidLevelTooHigh() {
        assertThrows(IllegalArgumentException.class, () -> CompressionCodec.deflate(10));
    }

    @Test
    void testCompressNullInput() {
        CompressionCodec codec = CompressionCodec.deflate();
        assertThrows(NullPointerException.class, () -> codec.compress(null, 0, 0));
    }

    @Test
    void testNoneCompressNullInput() {
        CompressionCodec codec = CompressionCodec.none();
        assertThrows(NullPointerException.class, () -> codec.compress(null, 0, 0));
    }

    @Test
    void testCompressNegativeOffset() {
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = { 1, 2, 3 };
        assertThrows(IllegalArgumentException.class, () -> codec.compress(input, -1, 1));
    }

    @Test
    void testCompressOffsetPlusLengthExceedsInput() {
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = { 1, 2, 3 };
        assertThrows(IllegalArgumentException.class, () -> codec.compress(input, 2, 5));
    }
}
