package jlsm.sstable.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for SSTable v4 format constants in {@link SSTableFormat}.
 *
 * <p>
 * The v4 format adds a dictionary meta-block between the compression map and the key index, with an
 * 88-byte footer containing dictOffset and dictLength fields.
 */
// @spec compression.zstd-dictionary.R19
class SSTableFormatV4Test {

    @Test
    void v4MagicConstantHasCorrectValue() {
        // ASCII "JLSMSST\x04" as big-endian long
        assertEquals(0x4A4C534D53535404L, SSTableFormat.MAGIC_V4,
                "MAGIC_V4 should be 'JLSMSST\\x04' packed as big-endian long");
    }

    @Test
    void v4FooterSizeConstantHasCorrectValue() {
        // 11 fields x 8 bytes = 88 bytes
        assertEquals(88, SSTableFormat.FOOTER_SIZE_V4,
                "v4 footer has 11 fields (mapOffset, mapLength, dictOffset, dictLength, "
                        + "idxOffset, idxLength, fltOffset, fltLength, entryCount, blockSize, magic) "
                        + "= 88 bytes");
    }

    @Test
    void v4FooterSizeIsLargerThanV3() {
        // v4 adds dictOffset (8) + dictLength (8) = 16 bytes over v3's 72 bytes
        assertEquals(SSTableFormat.FOOTER_SIZE_V3 + 16, SSTableFormat.FOOTER_SIZE_V4,
                "v4 footer adds dictOffset and dictLength (16 bytes) over v3");
    }

    @Test
    void v4MagicDiffersFromAllPriorVersions() {
        assertNotEquals(SSTableFormat.MAGIC, SSTableFormat.MAGIC_V4);
        assertNotEquals(SSTableFormat.MAGIC_V2, SSTableFormat.MAGIC_V4);
        assertNotEquals(SSTableFormat.MAGIC_V3, SSTableFormat.MAGIC_V4);
    }
}
