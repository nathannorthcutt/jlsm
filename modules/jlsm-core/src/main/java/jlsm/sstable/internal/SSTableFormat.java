package jlsm.sstable.internal;

/**
 * Constants describing the SSTable binary file format.
 *
 * <p>
 * As of the SSTable v1–v4 collapse (per the {@code pre-ga-format-deprecation-policy}), only v5 is
 * emitted and parsed. Higher format versions (v6+) will be added by their respective specs;
 * historical magics v1–v4 have been retired pre-GA and are no longer recognised.
 *
 * @see V5Footer
 */
public final class SSTableFormat {

    /**
     * Magic number for SSTable v5 files (data + metadata + footer each CRC-protected; footer is
     * self-checksummed and includes magic in its checksum scope; VarInt-prefixed data blocks).
     *
     * @spec sstable.end-to-end-integrity.R12
     * @spec sstable.end-to-end-integrity.R34
     */
    public static final long MAGIC_V5 = 0x4A4C534D53535405L;

    /**
     * Magic number for SSTable v6 files (v5 layout extended with a fixed-position scope section
     * appended after the v5 sections; the scope section carries TableScope identifiers and the
     * DEK-version set, covered by a CRC32C generalised to variable-length payloads). v6 is emitted
     * for encrypted tables; v5 remains the format for plaintext tables.
     *
     * @spec sstable.footer-encryption-scope.R1
     */
    public static final long MAGIC_V6 = 0x4A4C534D53535406L;

    /**
     * Byte size of the SSTable v5 footer. See {@code sstable.end-to-end-integrity.R11} for the
     * 17-field layout.
     *
     * @spec sstable.end-to-end-integrity.R11
     */
    public static final int FOOTER_SIZE_V5 = 112;

    /** Target data block size in bytes; new block starts when current exceeds this threshold. */
    public static final int DEFAULT_BLOCK_SIZE = 4096;

    /** Block size optimised for huge-page TLB alignment (2 MiB). */
    public static final int HUGE_PAGE_BLOCK_SIZE = 2_097_152;

    /** Block size optimised for remote/object-storage backends (8 MiB). */
    public static final int REMOTE_BLOCK_SIZE = 8_388_608;

    /** Maximum allowed block size (32 MiB). */
    public static final int MAX_BLOCK_SIZE = 33_554_432;

    /** Minimum allowed block size (1 KiB). */
    public static final int MIN_BLOCK_SIZE = 1024;

    /**
     * Validates that a block size is within the allowed range and is a power of two.
     *
     * @param blockSize the block size to validate
     * @throws IllegalArgumentException if the block size is invalid
     */
    public static void validateBlockSize(int blockSize) {
        if (blockSize < MIN_BLOCK_SIZE) {
            throw new IllegalArgumentException(
                    "blockSize %d is below minimum %d".formatted(blockSize, MIN_BLOCK_SIZE));
        }
        if (blockSize > MAX_BLOCK_SIZE) {
            throw new IllegalArgumentException(
                    "blockSize %d exceeds maximum %d".formatted(blockSize, MAX_BLOCK_SIZE));
        }
        if ((blockSize & (blockSize - 1)) != 0) {
            throw new IllegalArgumentException(
                    "blockSize %d is not a power of two".formatted(blockSize));
        }
    }

    private SSTableFormat() {
    }
}
