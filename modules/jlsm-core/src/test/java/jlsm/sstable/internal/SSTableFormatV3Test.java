package jlsm.sstable.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for SSTable v3 format constants and validation in {@link SSTableFormat}.
 *
 * <p>
 * These tests reference constants and methods that do not yet exist in {@code SSTableFormat}:
 * {@code MAGIC_V3}, {@code FOOTER_SIZE_V3}, {@code COMPRESSION_MAP_ENTRY_SIZE_V3},
 * {@code HUGE_PAGE_BLOCK_SIZE}, {@code REMOTE_BLOCK_SIZE}, {@code MAX_BLOCK_SIZE},
 * {@code MIN_BLOCK_SIZE}, and {@code validateBlockSize()}. They will fail with compilation errors
 * until the implementation is written.
 * </p>
 */
// @spec sstable.v3-format-upgrade.R11,R13,R14,R17 — constants + validation for v3 format (magic, footer, block sizes)
class SSTableFormatV3Test {

    @Test
    void v3ConstantsHaveCorrectValues() {
        assertEquals(0x4A4C534D53535403L, SSTableFormat.MAGIC_V3,
                "MAGIC_V3 should be 'JLSMSST\\x03' as big-endian long");
        assertEquals(72, SSTableFormat.FOOTER_SIZE_V3,
                "v3 footer adds blockSize field (8 bytes) over v2's 64 bytes");
        assertEquals(21, SSTableFormat.COMPRESSION_MAP_ENTRY_SIZE_V3,
                "v3 compression map entry adds 4-byte CRC32C checksum to v2's 17 bytes");
    }

    @Test
    void blockSizeConstantsCorrect() {
        assertEquals(4096, SSTableFormat.DEFAULT_BLOCK_SIZE);
        assertEquals(2_097_152, SSTableFormat.HUGE_PAGE_BLOCK_SIZE,
                "HUGE_PAGE_BLOCK_SIZE should be 2 MiB");
        assertEquals(8_388_608, SSTableFormat.REMOTE_BLOCK_SIZE,
                "REMOTE_BLOCK_SIZE should be 8 MiB");
    }

    @Test
    void validateBlockSizeAcceptsAllValidSizes() {
        // All valid power-of-2 sizes within [MIN_BLOCK_SIZE, MAX_BLOCK_SIZE]
        assertDoesNotThrow(() -> SSTableFormat.validateBlockSize(1024));
        assertDoesNotThrow(() -> SSTableFormat.validateBlockSize(4096));
        assertDoesNotThrow(() -> SSTableFormat.validateBlockSize(8192));
        assertDoesNotThrow(() -> SSTableFormat.validateBlockSize(2_097_152));
        assertDoesNotThrow(() -> SSTableFormat.validateBlockSize(8_388_608));
        assertDoesNotThrow(() -> SSTableFormat.validateBlockSize(33_554_432));
    }

    @Test
    void validateBlockSizeRejectsBelowMin() {
        assertThrows(IllegalArgumentException.class, () -> SSTableFormat.validateBlockSize(512),
                "512 is below MIN_BLOCK_SIZE (1024)");
    }

    @Test
    void validateBlockSizeRejectsAboveMax() {
        assertThrows(IllegalArgumentException.class,
                () -> SSTableFormat.validateBlockSize(67_108_864),
                "64 MiB is above MAX_BLOCK_SIZE (32 MiB)");
    }

    @Test
    void validateBlockSizeRejectsNonPowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> SSTableFormat.validateBlockSize(4000));
        assertThrows(IllegalArgumentException.class, () -> SSTableFormat.validateBlockSize(6144));
        assertThrows(IllegalArgumentException.class,
                () -> SSTableFormat.validateBlockSize(3_000_000));
    }

    @Test
    void validateBlockSizeRejectsZeroAndNegative() {
        assertThrows(IllegalArgumentException.class, () -> SSTableFormat.validateBlockSize(0));
        assertThrows(IllegalArgumentException.class, () -> SSTableFormat.validateBlockSize(-1));
        assertThrows(IllegalArgumentException.class, () -> SSTableFormat.validateBlockSize(-4096));
    }

    @Test
    void validateBlockSizeAtExactBoundaries() {
        // MIN_BLOCK_SIZE = 1024 should pass
        assertDoesNotThrow(() -> SSTableFormat.validateBlockSize(1024));
        // MAX_BLOCK_SIZE = 33_554_432 (32 MiB) should pass
        assertDoesNotThrow(() -> SSTableFormat.validateBlockSize(33_554_432));
        // One step below MIN should fail
        assertThrows(IllegalArgumentException.class, () -> SSTableFormat.validateBlockSize(512));
        // One step above MAX should fail
        assertThrows(IllegalArgumentException.class,
                () -> SSTableFormat.validateBlockSize(67_108_864));
    }
}
