package jlsm.core.compression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for {@link CompressionCodec} implementations.
 *
 * <p>
 * Targets findings from block-compression audit round 1:
 * <ul>
 * <li>IMPL-RISK-1: integer overflow in bounds check</li>
 * <li>IMPL-RISK-2: negative uncompressedLength not validated</li>
 * </ul>
 */
class CompressionCodecAdversarialTest {

    // ---- IMPL-RISK-1: Integer overflow in bounds check ----
    // offset + length can overflow to negative, bypassing the guard.
    // All four methods in both codecs share this pattern.

    @Test
    void deflateCompressOverflowBoundsCheckThrowsIAE() {
        // IMPL-RISK-1: offset=Integer.MAX_VALUE, length=1 overflows to Integer.MIN_VALUE
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = new byte[10];
        assertThrows(IllegalArgumentException.class,
                () -> codec.compress(input, Integer.MAX_VALUE, 1));
    }

    @Test
    void deflateDecompressOverflowBoundsCheckThrowsIAE() {
        // IMPL-RISK-1: same overflow on decompress
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] input = new byte[10];
        assertThrows(IllegalArgumentException.class,
                () -> codec.decompress(input, Integer.MAX_VALUE, 1, 1));
    }

    @Test
    void noneCompressOverflowBoundsCheckThrowsIAE() {
        // IMPL-RISK-1: NoneCodec has the same overflow pattern
        CompressionCodec codec = CompressionCodec.none();
        byte[] input = new byte[10];
        assertThrows(IllegalArgumentException.class,
                () -> codec.compress(input, Integer.MAX_VALUE, 1));
    }

    @Test
    void noneDecompressOverflowBoundsCheckThrowsIAE() {
        // IMPL-RISK-1: NoneCodec decompress overflow
        CompressionCodec codec = CompressionCodec.none();
        byte[] input = new byte[10];
        assertThrows(IllegalArgumentException.class,
                () -> codec.decompress(input, Integer.MAX_VALUE, 1, 1));
    }

    // ---- IMPL-RISK-2: Negative uncompressedLength not validated ----
    // Contract says uncompressedLength > 0; negative values should throw IAE.

    @Test
    void deflateDecompressNegativeUncompressedLengthThrowsIAE() {
        // IMPL-RISK-2: negative uncompressedLength should be IAE, not NegativeArraySizeException
        CompressionCodec codec = CompressionCodec.deflate();
        byte[] compressed = codec.compress("test".getBytes(), 0, 4);
        assertThrows(IllegalArgumentException.class,
                () -> codec.decompress(compressed, 0, compressed.length, -1));
    }

    @Test
    void noneDecompressNegativeUncompressedLengthThrowsIAE() {
        // IMPL-RISK-2: NoneCodec should also validate uncompressedLength eagerly
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

    @Test
    void noneDecompressNullInputThrowsNPE() {
        // Boundary: verify null input detection on decompress path (not just compress)
        CompressionCodec codec = CompressionCodec.none();
        assertThrows(NullPointerException.class, () -> codec.decompress(null, 0, 0, 0));
    }

    @Test
    void deflateDecompressNullInputThrowsNPE() {
        // Boundary: verify null input detection on decompress path
        CompressionCodec codec = CompressionCodec.deflate();
        assertThrows(NullPointerException.class, () -> codec.decompress(null, 0, 0, 0));
    }
}
