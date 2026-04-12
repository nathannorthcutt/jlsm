package jlsm.core.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CompressionCodec}, {@link NoneCodec}, and {@link DeflateCodec}.
 *
 * <p>
 * Tests are written against the public {@link CompressionCodec} interface and static factory
 * methods. Package-private implementations are tested through the interface.
 *
 * <p>
 * Updated from byte[] API to MemorySegment API per F17.R1-R3.
 */
class CompressionCodecTest {

    // ---- Helper: allocate a MemorySegment from byte[] data ----

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

    // ---- NoneCodec: identity and round-trip ----

    @Test
    void testNoneCodecCodecId() {
        CompressionCodec codec = CompressionCodec.none();
        assertEquals((byte) 0x00, codec.codecId());
    }

    @Test
    void testNoneCodecRoundTrip() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            byte[] input = "Hello, world!".getBytes();
            MemorySegment src = toSegment(arena, input);

            MemorySegment dst = arena.allocate(codec.maxCompressedLength(input.length));
            MemorySegment compressed = codec.compress(src, dst);

            MemorySegment decompDst = arena.allocate(input.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, input.length);
            assertArrayEquals(input, toByteArray(decompressed));
        }
    }

    @Test
    void testNoneCompressIsContentCopy() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            byte[] input = { 1, 2, 3, 4, 5, 6, 7, 8 };
            MemorySegment src = toSegment(arena, input);

            MemorySegment dst = arena.allocate(input.length);
            MemorySegment compressed = codec.compress(src, dst);
            assertArrayEquals(input, toByteArray(compressed));
        }
    }

    @Test
    void testNoneCodecDecompressSizeMismatch() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            byte[] input = { 1, 2, 3 };
            MemorySegment src = toSegment(arena, input);

            MemorySegment dst = arena.allocate(input.length);
            MemorySegment compressed = codec.compress(src, dst);

            MemorySegment decompDst = arena.allocate(999);
            assertThrows(UncheckedIOException.class,
                    () -> codec.decompress(compressed, decompDst, 999));
        }
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
        try (Arena arena = Arena.ofConfined()) {
            byte[] input = "The quick brown fox jumps over the lazy dog. ".repeat(10).getBytes();
            MemorySegment src = toSegment(arena, input);

            int maxLen = codec.maxCompressedLength(input.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            assertTrue(compressed.byteSize() < input.length,
                    "compressed size (%d) should be less than input size (%d)"
                            .formatted(compressed.byteSize(), input.length));

            MemorySegment decompDst = arena.allocate(input.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, input.length);
            assertArrayEquals(input, toByteArray(decompressed));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 })
    void testDeflateCodecAllLevels(int level) {
        CompressionCodec codec = CompressionCodec.deflate(level);
        assertEquals((byte) 0x02, codec.codecId());
        try (Arena arena = Arena.ofConfined()) {
            byte[] input = "Repeated content for compression. ".repeat(20).getBytes();
            MemorySegment src = toSegment(arena, input);

            int maxLen = codec.maxCompressedLength(input.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            MemorySegment decompDst = arena.allocate(input.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, input.length);
            assertArrayEquals(input, toByteArray(decompressed));
        }
    }

    @Test
    void testDeflateCodecLargeInput() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            byte[] input = new byte[128 * 1024];
            Random rng = new Random(42);
            rng.nextBytes(input);
            System.arraycopy(input, 0, input, 64 * 1024, 32 * 1024);

            MemorySegment src = toSegment(arena, input);
            int maxLen = codec.maxCompressedLength(input.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            MemorySegment decompDst = arena.allocate(input.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, input.length);
            assertArrayEquals(input, toByteArray(decompressed));
        }
    }

    @Test
    void testDeflateCodecEmptyInput() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(0);
            MemorySegment dst = arena.allocate(64);
            MemorySegment compressed = codec.compress(src, dst);
            assertEquals(0, compressed.byteSize());

            MemorySegment decompDst = arena.allocate(64);
            MemorySegment decompressed = codec.decompress(arena.allocate(0), decompDst, 0);
            assertEquals(0, decompressed.byteSize());
        }
    }

    @Test
    void testDecompressSizeMismatch() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            byte[] input = "test data".getBytes();
            MemorySegment src = toSegment(arena, input);

            int maxLen = codec.maxCompressedLength(input.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            MemorySegment decompDst = arena.allocate(input.length + 100);
            assertThrows(UncheckedIOException.class,
                    () -> codec.decompress(compressed, decompDst, input.length + 100));
        }
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
    }

    @Test
    void testStaticFactoryDeflateWithLevel() {
        CompressionCodec codec = CompressionCodec.deflate(3);
        assertEquals((byte) 0x02, codec.codecId());
        try (Arena arena = Arena.ofConfined()) {
            byte[] input = "test data for level 3".getBytes();
            MemorySegment src = toSegment(arena, input);

            int maxLen = codec.maxCompressedLength(input.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            MemorySegment decompDst = arena.allocate(input.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, input.length);
            assertArrayEquals(input, toByteArray(decompressed));
        }
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
        CompressionCodec codec = CompressionCodec.deflate();
        Random rng = new Random(42);
        try (Arena arena = Arena.ofConfined()) {
            for (int size : new int[]{ 0, 1, 10, 100, 4096, 65536 }) {
                byte[] input = new byte[size];
                rng.nextBytes(input);
                int bound = codec.maxCompressedLength(size);

                MemorySegment src = toSegment(arena, input);
                MemorySegment dst = arena.allocate(bound);
                MemorySegment compressed = codec.compress(src, dst);
                assertTrue(compressed.byteSize() <= bound,
                        "compressed length (%d) exceeds maxCompressedLength(%d) = %d"
                                .formatted(compressed.byteSize(), size, bound));
            }
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
    void testCompressNullSrc() {
        CompressionCodec codec = CompressionCodec.deflate();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(64);
            assertThrows(NullPointerException.class, () -> codec.compress(null, dst));
        }
    }

    @Test
    void testNoneCompressNullSrc() {
        CompressionCodec codec = CompressionCodec.none();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(64);
            assertThrows(NullPointerException.class, () -> codec.compress(null, dst));
        }
    }
}
