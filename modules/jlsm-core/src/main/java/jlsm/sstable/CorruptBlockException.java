package jlsm.sstable;

import java.io.IOException;

/**
 * Thrown when CRC32C verification fails on an SSTable data block. Carries diagnostic info: block
 * index, expected checksum, actual checksum.
 *
 * @see <a href="../.decisions/per-block-checksums/adr.md">ADR: Per-Block Checksums</a>
 */
// @spec F16.R9 — IOException subclass carrying block index + expected/actual checksums
public final class CorruptBlockException extends IOException {

    private final int blockIndex;
    private final int expectedChecksum;
    private final int actualChecksum;

    /**
     * Creates a new corrupt block exception.
     *
     * @param blockIndex the zero-based index of the corrupt block
     * @param expectedChecksum the CRC32C value stored in the compression map
     * @param actualChecksum the CRC32C value computed from the on-disk bytes
     */
    public CorruptBlockException(int blockIndex, int expectedChecksum, int actualChecksum) {
        super("Block %d CRC32C mismatch: expected 0x%08X but computed 0x%08X".formatted(blockIndex,
                expectedChecksum, actualChecksum));
        this.blockIndex = blockIndex;
        this.expectedChecksum = expectedChecksum;
        this.actualChecksum = actualChecksum;
    }

    public int blockIndex() {
        return blockIndex;
    }

    public int expectedChecksum() {
        return expectedChecksum;
    }

    public int actualChecksum() {
        return actualChecksum;
    }
}
