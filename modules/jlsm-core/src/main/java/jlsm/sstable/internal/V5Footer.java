package jlsm.sstable.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32C;

import jlsm.sstable.CorruptSectionException;

/**
 * Value holder and codec for the SSTable v5 footer.
 *
 * <p>
 * The v5 footer is exactly {@value #FOOTER_SIZE} bytes laid out in big-endian order with
 * per-section CRC32C checksums, a footer-self-checksum over a defined scope, and a trailing 8-byte
 * magic. See {@code .spec/domains/sstable/end-to-end-integrity.md} R11 for the exact byte layout.
 * </p>
 *
 * <p>
 * The record itself is a pure value holder — identity is determined by all 17 footer fields. The
 * static methods provide encode, decode, footer-self-checksum computation, tight-packing validation
 * (R37), and a dictionary-sentinel consistency check (R15).
 * </p>
 *
 * @spec sstable.end-to-end-integrity.R11
 * @spec sstable.end-to-end-integrity.R12
 * @spec sstable.end-to-end-integrity.R15
 * @spec sstable.end-to-end-integrity.R16
 * @spec sstable.end-to-end-integrity.R37
 */
public record V5Footer(long mapOffset, long mapLength, long dictOffset, long dictLength,
        long idxOffset, long idxLength, long fltOffset, long fltLength, long entryCount,
        long blockSize, int blockCount, int mapChecksum, int dictChecksum, int idxChecksum,
        int fltChecksum, int footerChecksum, long magic) {

    /**
     * Total byte size of an on-disk v5 footer.
     *
     * @spec sstable.end-to-end-integrity.R11
     */
    public static final int FOOTER_SIZE = 112;

    /**
     * Number of bytes covered by {@link #footerChecksum()}: the first 100 bytes of the footer plus
     * the final 8 bytes of magic, concatenated.
     *
     * @spec sstable.end-to-end-integrity.R16
     */
    public static final int CHECKSUM_SCOPE_SIZE = 104;

    /**
     * Serialise {@code footer} into exactly {@link #FOOTER_SIZE} big-endian bytes in {@code dst}
     * starting at {@code dstOffset}.
     *
     * @spec sstable.end-to-end-integrity.R11
     */
    public static void encode(V5Footer footer, byte[] dst, int dstOffset) {
        if (dstOffset < 0) {
            throw new IndexOutOfBoundsException("dstOffset must be non-negative: " + dstOffset);
        }
        if (dstOffset > dst.length - FOOTER_SIZE) {
            throw new IndexOutOfBoundsException("dst too small for v5 footer: dst.length="
                    + dst.length + ", dstOffset=" + dstOffset + ", required=" + FOOTER_SIZE);
        }
        final ByteBuffer bb = ByteBuffer.wrap(dst, dstOffset, FOOTER_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        bb.putLong(footer.mapOffset);
        bb.putLong(footer.mapLength);
        bb.putLong(footer.dictOffset);
        bb.putLong(footer.dictLength);
        bb.putLong(footer.idxOffset);
        bb.putLong(footer.idxLength);
        bb.putLong(footer.fltOffset);
        bb.putLong(footer.fltLength);
        bb.putLong(footer.entryCount);
        bb.putLong(footer.blockSize);
        bb.putInt(footer.blockCount);
        bb.putInt(footer.mapChecksum);
        bb.putInt(footer.dictChecksum);
        bb.putInt(footer.idxChecksum);
        bb.putInt(footer.fltChecksum);
        bb.putInt(footer.footerChecksum);
        bb.putLong(footer.magic);
    }

    /**
     * Parse a {@link #FOOTER_SIZE}-byte v5 footer from {@code src} starting at {@code srcOffset}.
     * Does <strong>not</strong> verify the footer-self-checksum or any section checksum — the
     * caller decides whether to verify after parsing.
     *
     * @spec sstable.end-to-end-integrity.R11
     */
    public static V5Footer decode(byte[] src, int srcOffset) {
        if (srcOffset < 0) {
            throw new IndexOutOfBoundsException("srcOffset must be non-negative: " + srcOffset);
        }
        if (srcOffset > src.length - FOOTER_SIZE) {
            throw new IndexOutOfBoundsException("src too small for v5 footer: src.length="
                    + src.length + ", srcOffset=" + srcOffset + ", required=" + FOOTER_SIZE);
        }
        final ByteBuffer bb = ByteBuffer.wrap(src, srcOffset, FOOTER_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        final long mapOffset = bb.getLong();
        final long mapLength = bb.getLong();
        final long dictOffset = bb.getLong();
        final long dictLength = bb.getLong();
        final long idxOffset = bb.getLong();
        final long idxLength = bb.getLong();
        final long fltOffset = bb.getLong();
        final long fltLength = bb.getLong();
        final long entryCount = bb.getLong();
        final long blockSize = bb.getLong();
        final int blockCount = bb.getInt();
        final int mapChecksum = bb.getInt();
        final int dictChecksum = bb.getInt();
        final int idxChecksum = bb.getInt();
        final int fltChecksum = bb.getInt();
        final int footerChecksum = bb.getInt();
        final long magic = bb.getLong();
        return new V5Footer(mapOffset, mapLength, dictOffset, dictLength, idxOffset, idxLength,
                fltOffset, fltLength, entryCount, blockSize, blockCount, mapChecksum, dictChecksum,
                idxChecksum, fltChecksum, footerChecksum, magic);
    }

    /**
     * Compute CRC32C over the footer-checksum scope (offsets {@code [0..100)} and
     * {@code [104..112)}) of a {@link #FOOTER_SIZE}-byte footer array.
     *
     * @return the low 32 bits of the CRC32C as an {@code int}
     * @spec sstable.end-to-end-integrity.R16
     */
    public static int computeFooterChecksum(byte[] footerBytes) {
        if (footerBytes.length < FOOTER_SIZE) {
            throw new IllegalArgumentException("footerBytes shorter than FOOTER_SIZE: length="
                    + footerBytes.length + ", required=" + FOOTER_SIZE);
        }
        final CRC32C crc = new CRC32C();
        crc.update(footerBytes, 0, 100);
        crc.update(footerBytes, 104, 8);
        return (int) crc.getValue();
    }

    /**
     * Validate the tight-packing invariant per R37: present sections (compression-map, optional
     * dictionary, key-index, bloom-filter) must be laid out contiguously with no gaps when sorted
     * by {@code (offset, length)}.
     *
     * <p>
     * Zero-length sections (i.e. the dictionary sentinel) are skipped during walk-through. The
     * returned value is {@code null} on success or the name of the first section that violates
     * tight-packing (one of the {@code CorruptSectionException.SECTION_*} constants).
     * </p>
     *
     * @spec sstable.end-to-end-integrity.R37
     */
    public static String validateTightPacking(V5Footer footer) {
        final List<Section> sections = new ArrayList<>(4);
        if (footer.mapLength > 0) {
            sections.add(new Section(footer.mapOffset, footer.mapLength,
                    CorruptSectionException.SECTION_COMPRESSION_MAP));
        }
        if (footer.dictLength > 0) {
            sections.add(new Section(footer.dictOffset, footer.dictLength,
                    CorruptSectionException.SECTION_DICTIONARY));
        }
        if (footer.idxLength > 0) {
            sections.add(new Section(footer.idxOffset, footer.idxLength,
                    CorruptSectionException.SECTION_KEY_INDEX));
        }
        if (footer.fltLength > 0) {
            sections.add(new Section(footer.fltOffset, footer.fltLength,
                    CorruptSectionException.SECTION_BLOOM_FILTER));
        }
        sections.sort(Comparator.comparingLong(Section::offset));
        // Data-region lower bound (R37): sections must be preceded by a non-empty data region,
        // so the first section cannot start at file offset 0. Without this check a corrupt
        // footer claiming mapOffset=0 would surface as an opaque downstream decoder error
        // instead of a precise tight-packing violation at the producer/consumer boundary.
        if (!sections.isEmpty() && sections.get(0).offset <= 0) {
            return sections.get(0).name;
        }
        for (int i = 1; i < sections.size(); i++) {
            final Section prev = sections.get(i - 1);
            final Section curr = sections.get(i);
            if (prev.offset + prev.length != curr.offset) {
                return curr.name;
            }
        }
        return null;
    }

    /**
     * Validate dictionary sentinel consistency per R15: either the dictionary is absent (all three
     * of {@code dictOffset}, {@code dictLength}, {@code dictChecksum} are {@code 0}) or it is
     * present (none of those three are zero except as allowed for a valid CRC coincidentally equal
     * to zero — the offset and length must both be positive).
     *
     * @return {@code true} when the sentinel is consistent
     * @spec sstable.end-to-end-integrity.R15
     */
    public static boolean isDictionarySentinelConsistent(V5Footer footer) {
        final boolean absent = footer.dictLength == 0 && footer.dictOffset == 0
                && footer.dictChecksum == 0;
        final boolean present = footer.dictLength > 0 && footer.dictOffset > 0;
        return absent || present;
    }

    private record Section(long offset, long length, String name) {
    }
}
