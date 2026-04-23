package jlsm.sstable;

import java.io.IOException;
import java.util.Objects;

/**
 * Thrown when a metadata section in an SSTable file fails its CRC32C check.
 *
 * <p>
 * A section-level corruption is distinct from a block-level corruption (signalled by
 * {@link CorruptBlockException}) and from a partial-write scenario (signalled by
 * {@link IncompleteSSTableException}). Any of the metadata sections — footer, compression-map,
 * dictionary, key-index, bloom-filter — may raise this exception; the data section raises it only
 * during a recovery scan when the scan itself cannot distinguish the site of the inconsistency.
 * </p>
 *
 * <p>
 * The message is rendered with lower-case hex checksums using the {@code 0x%08X} format so
 * operators can diff the expected and actual values byte-for-byte.
 * </p>
 *
 * @spec sstable.end-to-end-integrity.R26
 * @spec sstable.end-to-end-integrity.R29
 * @spec sstable.end-to-end-integrity.R31
 * @spec sstable.end-to-end-integrity.R32
 * @spec sstable.end-to-end-integrity.R33
 * @spec sstable.end-to-end-integrity.R42
 */
public final class CorruptSectionException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Section-name constant for the v5 footer region.
     *
     * @spec sstable.end-to-end-integrity.R42
     */
    public static final String SECTION_FOOTER = "footer";

    /**
     * Section-name constant for the compression map section.
     *
     * @spec sstable.end-to-end-integrity.R42
     */
    public static final String SECTION_COMPRESSION_MAP = "compression-map";

    /**
     * Section-name constant for the optional ZSTD dictionary section.
     *
     * @spec sstable.end-to-end-integrity.R42
     */
    public static final String SECTION_DICTIONARY = "dictionary";

    /**
     * Section-name constant for the key index section.
     *
     * @spec sstable.end-to-end-integrity.R42
     */
    public static final String SECTION_KEY_INDEX = "key-index";

    /**
     * Section-name constant for the bloom filter section.
     *
     * @spec sstable.end-to-end-integrity.R42
     */
    public static final String SECTION_BLOOM_FILTER = "bloom-filter";

    /**
     * Section-name constant for the data region (used only by the recovery scan when no more
     * specific section can be identified).
     *
     * @spec sstable.end-to-end-integrity.R42
     */
    public static final String SECTION_DATA = "data";

    private final String sectionName;
    private final int expectedChecksum;
    private final int actualChecksum;

    /**
     * Constructs a new {@link CorruptSectionException} for a section that failed its CRC32C check.
     *
     * @param sectionName one of the {@code SECTION_*} constants; must not be {@code null}
     * @param expectedChecksum the CRC32C value read from the footer
     * @param actualChecksum the CRC32C value recomputed from the on-disk bytes
     * @throws NullPointerException if {@code sectionName} is {@code null}
     * @spec sstable.end-to-end-integrity.R31
     * @spec sstable.end-to-end-integrity.R32
     * @spec sstable.end-to-end-integrity.R42
     */
    public CorruptSectionException(String sectionName, int expectedChecksum, int actualChecksum) {
        super("Section %s CRC32C mismatch: expected 0x%08X but computed 0x%08X".formatted(
                Objects.requireNonNull(sectionName, "sectionName"), expectedChecksum,
                actualChecksum));
        this.sectionName = sectionName;
        this.expectedChecksum = expectedChecksum;
        this.actualChecksum = actualChecksum;
    }

    /**
     * Constructs a new {@link CorruptSectionException} for a data-section failure that has no
     * checksum semantics (e.g., VarInt decode rejection). The {@link #expectedChecksum()} and
     * {@link #actualChecksum()} accessors return {@code 0} in this case.
     *
     * @param sectionName one of the {@code SECTION_*} constants; must not be {@code null}
     * @param message the full diagnostic message; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @spec sstable.end-to-end-integrity.R32
     * @spec sstable.end-to-end-integrity.R42
     */
    public CorruptSectionException(String sectionName, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.sectionName = Objects.requireNonNull(sectionName, "sectionName");
        this.expectedChecksum = 0;
        this.actualChecksum = 0;
    }

    /**
     * Returns the name of the section that failed its CRC32C check.
     *
     * @spec sstable.end-to-end-integrity.R32
     * @spec sstable.end-to-end-integrity.R42
     */
    public String sectionName() {
        return sectionName;
    }

    /**
     * Returns the CRC32C value that was read from the footer for this section (the value the writer
     * claimed it stored).
     *
     * @spec sstable.end-to-end-integrity.R32
     */
    public int expectedChecksum() {
        return expectedChecksum;
    }

    /**
     * Returns the CRC32C value that was recomputed from the on-disk section bytes by the reader.
     *
     * @spec sstable.end-to-end-integrity.R32
     */
    public int actualChecksum() {
        return actualChecksum;
    }
}
