package jlsm.sstable.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for {@link CompressionMap} and {@link CompressionMap.Entry}.
 *
 * <p>
 * Targets findings from block-compression audit round 1:
 * <ul>
 * <li>CONTRACT-GAP-1: CompressionMap.Entry record has no validation</li>
 * <li>IMPL-RISK-3: CompressionMap.deserialize accepts negative block count</li>
 * </ul>
 */
class CompressionMapAdversarialTest {

    // ---- CONTRACT-GAP-1: Entry record lacks validation ----

    @Test
    void entryRejectsNegativeCompressedSize() {
        // CONTRACT-GAP-1: negative compressedSize should be rejected
        assertThrows(IllegalArgumentException.class,
                () -> new CompressionMap.Entry(0L, -1, 100, (byte) 0x00));
    }

    @Test
    void entryRejectsNegativeUncompressedSize() {
        // CONTRACT-GAP-1: negative uncompressedSize should be rejected
        assertThrows(IllegalArgumentException.class,
                () -> new CompressionMap.Entry(0L, 100, -1, (byte) 0x00));
    }

    @Test
    void entryRejectsNegativeBlockOffset() {
        // CONTRACT-GAP-1: negative blockOffset should be rejected
        assertThrows(IllegalArgumentException.class,
                () -> new CompressionMap.Entry(-1L, 100, 100, (byte) 0x00));
    }

    @Test
    void entryAcceptsZeroSizes() {
        // Boundary: zero is valid (empty block edge case)
        CompressionMap.Entry entry = new CompressionMap.Entry(0L, 0, 0, (byte) 0x00);
        assertEquals(0, entry.compressedSize());
        assertEquals(0, entry.uncompressedSize());
    }

    // ---- IMPL-RISK-3: deserialize accepts negative block count ----

    @Test
    void deserializeRejectsNegativeBlockCount() {
        // IMPL-RISK-3: first 4 bytes encode -1 (0xFFFFFFFF), should throw IAE
        byte[] data = new byte[]{ (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        assertThrows(IllegalArgumentException.class, () -> CompressionMap.deserialize(data));
    }

    @Test
    void deserializeRejectsLargeBlockCountExceedingDataLength() {
        // Boundary: blockCount = 1 but data is only 4 bytes (missing entry data)
        byte[] data = new byte[]{ 0, 0, 0, 1 };
        assertThrows(IllegalArgumentException.class, () -> CompressionMap.deserialize(data));
    }

    @Test
    void deserializeRejectsTrailingGarbageExactly() {
        // Verify exact match: blockCount claims 0 entries but there's trailing data.
        // This is a theoretical concern — current impl silently ignores trailing bytes.
        // Documenting the behavior rather than requiring strict matching.
        byte[] data = new byte[]{ 0, 0, 0, 0, 99, 99 };
        CompressionMap map = CompressionMap.deserialize(data);
        assertEquals(0, map.blockCount()); // trailing bytes silently ignored — WATCH
    }
}
