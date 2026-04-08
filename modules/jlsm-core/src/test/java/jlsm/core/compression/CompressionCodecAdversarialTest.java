package jlsm.core.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for {@link CompressionCodec} implementations.
 *
 * <p>
 * Targets findings from block-compression audit spec-analysis.md.
 */
class CompressionCodecAdversarialTest {

    // ---- C1-F1: Integer overflow in bounds check ----
    // The subtraction-based guard `offset > input.length - length` underflows when
    // length > input.length, allowing the check to pass incorrectly.

    @Test
    void deflateCompressOverflowBoundsCheckThrowsIAE() {
        // C1-F1: offset=Integer.MAX_VALUE, length=1 overflows to Integer.MIN_VALUE
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = new byte[10];
        assertThrows(IllegalArgumentException.class,
                () -> codec.compress(input, Integer.MAX_VALUE, 1));
    }

    @Test
    void deflateDecompressOverflowBoundsCheckThrowsIAE() {
        // C1-F1: same overflow on decompress
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = new byte[10];
        assertThrows(IllegalArgumentException.class,
                () -> codec.decompress(input, Integer.MAX_VALUE, 1, 1));
    }

    @Test
    void noneCompressOverflowBoundsCheckThrowsIAE() {
        // C1-F1: NoneCodec has the same overflow pattern
        CompressionCodec codec = CompressionCodec.none();
        byte[] input = new byte[10];
        assertThrows(IllegalArgumentException.class,
                () -> codec.compress(input, Integer.MAX_VALUE, 1));
    }

    @Test
    void noneDecompressOverflowBoundsCheckThrowsIAE() {
        // C1-F1: NoneCodec decompress overflow
        CompressionCodec codec = CompressionCodec.none();
        byte[] input = new byte[10];
        assertThrows(IllegalArgumentException.class,
                () -> codec.decompress(input, Integer.MAX_VALUE, 1, 1));
    }

    // C1-F1: The KEY overflow vector — small offset, huge length.
    // input.length=5, offset=0, length=Integer.MAX_VALUE:
    // input.length - length = 5 - 2147483647 wraps to -2147483642
    // 0 > -2147483642 is false → check passes incorrectly
    @Test
    void deflateCompressHugeLengthOverflow_C1F1() {
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = new byte[5];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> codec.compress(input, 0, Integer.MAX_VALUE),
                "C1-F1: huge length must throw IAE, not AIOOBE or NegativeArraySizeException");
        assertNotNull(ex.getMessage());
    }

    @Test
    void noneCompressHugeLengthOverflow_C1F1() {
        CompressionCodec codec = CompressionCodec.none();
        byte[] input = new byte[5];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> codec.compress(input, 0, Integer.MAX_VALUE),
                "C1-F1: huge length must throw IAE");
        assertNotNull(ex.getMessage());
    }

    @Test
    void deflateDecompressHugeLengthOverflow_C1F1() {
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = new byte[5];
        assertThrows(IllegalArgumentException.class,
                () -> codec.decompress(input, 0, Integer.MAX_VALUE, 5),
                "C1-F1: huge length in decompress must throw IAE");
    }

    @Test
    void noneDecompressHugeLengthOverflow_C1F1() {
        CompressionCodec codec = CompressionCodec.none();
        byte[] input = new byte[5];
        assertThrows(IllegalArgumentException.class,
                () -> codec.decompress(input, 0, Integer.MAX_VALUE, 5),
                "C1-F1: huge length in decompress must throw IAE");
    }

    // ---- C1-F2/C1-F5: decompress allows uncompressedLength=0 ----
    // Spec says uncompressedLength > 0 for decompress, but impl allows 0.

    @Test
    void deflateDecompressNegativeUncompressedLengthThrowsIAE() {
        // Negative uncompressedLength should be IAE, not NegativeArraySizeException
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] compressed = codec.compress("test".getBytes(), 0, 4);
        assertThrows(IllegalArgumentException.class,
                () -> codec.decompress(compressed, 0, compressed.length, -1));
    }

    @Test
    void noneDecompressNegativeUncompressedLengthThrowsIAE() {
        // NoneCodec should also validate uncompressedLength eagerly
        CompressionCodec codec = CompressionCodec.none();
        byte[] input = new byte[5];
        assertThrows(IllegalArgumentException.class, () -> codec.decompress(input, 0, 5, -1));
    }

    @Test
    void deflateDecompressZeroUncompressedLengthAccepted() {
        // Boundary: uncompressedLength=0 is valid (empty block) — should not throw
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] compressed = codec.compress(new byte[0], 0, 0);
        byte[] result = codec.decompress(compressed, 0, compressed.length, 0);
        assertEquals(0, result.length);
    }

    // C1-F2: Decompress non-empty compressed data with uncompressedLength=0 —
    // silently ignores the compressed content and returns empty array.
    // This is a contract gap: caller says "expect 0 bytes" but data decompresses to more.
    @Test
    void deflateDecompressNonEmptyWithZeroUncompressedLength_C1F2() {
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] original = "non-empty data that compresses to real bytes".getBytes();
        byte[] compressed = codec.compress(original, 0, original.length);
        // This documents the gap: decompress succeeds returning empty when it should fail
        // because the compressed stream actually contains data.
        byte[] result = codec.decompress(compressed, 0, compressed.length, 0);
        assertEquals(0, result.length,
                "C1-F2: CONFIRMED — zero uncompressedLength silently returns empty on non-empty data");
    }

    @Test
    void noneDecompressNullInputThrowsNPE() {
        CompressionCodec codec = CompressionCodec.none();
        assertThrows(NullPointerException.class, () -> codec.decompress(null, 0, 0, 0));
    }

    @Test
    void deflateDecompressNullInputThrowsNPE() {
        CompressionCodec codec = CompressionCodec.deflate();
        assertThrows(NullPointerException.class, () -> codec.decompress(null, 0, 0, 0));
    }

    // ---- C1-F3: DeflateCodec compress — potential infinite loop ----
    // The compress loop has no iteration guard. If deflate() returns 0 without
    // finished(), the loop spins. Test with timeout as a bounded-iteration guard.
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void deflateCompressCompletesWithinTimeout_C1F3() {
        CompressionCodec codec = CompressionCodec.deflate();
        // Highly incompressible data
        byte[] input = new byte[65536];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) (i * 31 + 17);
        }
        byte[] compressed = codec.compress(input, 0, input.length);
        assertNotNull(compressed);
        byte[] decompressed = codec.decompress(compressed, 0, compressed.length, input.length);
        assertArrayEquals(input, decompressed);
    }

    // C1-F3: Single-byte input — exercises the minimum buffer path
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void deflateCompressSingleByteCompletes_C1F3() {
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = { 42 };
        byte[] compressed = codec.compress(input, 0, 1);
        assertNotNull(compressed);
        byte[] decompressed = codec.decompress(compressed, 0, compressed.length, 1);
        assertArrayEquals(input, decompressed);
    }

    // ---- C1-F4: decompress trailing data ignored ----
    @Test
    void deflateDecompressTrailingDataIgnored_C1F4() {
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] original = "test data for trailing check".getBytes();
        byte[] compressed = codec.compress(original, 0, original.length);
        byte[] withTrailing = new byte[compressed.length + 100];
        System.arraycopy(compressed, 0, withTrailing, 0, compressed.length);
        for (int i = compressed.length; i < withTrailing.length; i++) {
            withTrailing[i] = (byte) 0xDE;
        }
        // C1-F4: Trailing data is silently ignored — inflater reads only what it needs
        byte[] result = codec.decompress(withTrailing, 0, withTrailing.length, original.length);
        assertArrayEquals(original, result,
                "C1-F4: trailing data silently ignored — returns correct data from prefix");
    }
}
