package jlsm.sstable.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.Test;

import jlsm.sstable.CorruptSectionException;

/**
 * TDD tests for {@link V5Footer}.
 *
 * <p>
 * All tests are expected to fail because the static methods throw
 * {@link UnsupportedOperationException}. Once implemented, these tests define the contract: a
 * 112-byte big-endian footer, a 104-byte checksum scope (first 100 bytes concatenated with the
 * final 8 bytes of magic), R37 tight-packing validation, and R15 dictionary-sentinel consistency.
 * </p>
 *
 * @spec sstable.end-to-end-integrity.R11
 * @spec sstable.end-to-end-integrity.R12
 * @spec sstable.end-to-end-integrity.R15
 * @spec sstable.end-to-end-integrity.R16
 * @spec sstable.end-to-end-integrity.R37
 */
class V5FooterTest {

    private static V5Footer typicalFooter() {
        return new V5Footer(/* mapOffset */ 4096L, /* mapLength */ 1024L, /* dictOffset */ 0L,
                /* dictLength */ 0L, /* idxOffset */ 5120L, /* idxLength */ 512L,
                /* fltOffset */ 5632L, /* fltLength */ 256L, /* entryCount */ 42L,
                /* blockSize */ 1024L, /* blockCount */ 3, /* mapChecksum */ 0xCAFEBABE,
                /* dictChecksum */ 0, /* idxChecksum */ 0xDEADBEEF, /* fltChecksum */ 0x11223344,
                /* footerChecksum */ 0, /* magic */ SSTableFormat.MAGIC_V5);
    }

    @Test
    void footerSizeIs112() {
        assertEquals(112, V5Footer.FOOTER_SIZE);
    }

    @Test
    void checksumScopeSizeIs104() {
        assertEquals(104, V5Footer.CHECKSUM_SCOPE_SIZE);
    }

    @Test
    void encodeWritesExactly112Bytes() {
        byte[] dst = new byte[128];
        Arrays.fill(dst, (byte) 0xFF);
        V5Footer.encode(typicalFooter(), dst, 8);
        for (int i = 0; i < 8; i++) {
            assertEquals((byte) 0xFF, dst[i],
                    "byte at offset %d before footer must not be written".formatted(i));
        }
        for (int i = 120; i < 128; i++) {
            assertEquals((byte) 0xFF, dst[i],
                    "byte at offset %d after footer must not be written".formatted(i));
        }
        boolean anyWritten = false;
        for (int i = 8; i < 120; i++) {
            if (dst[i] != (byte) 0xFF) {
                anyWritten = true;
                break;
            }
        }
        assertTrue(anyWritten, "encode must write bytes in the [8, 120) window");
    }

    @Test
    void encodeFieldsUseBigEndianLayout() {
        V5Footer footer = typicalFooter();
        byte[] dst = new byte[112];
        V5Footer.encode(footer, dst, 0);

        ByteBuffer bb = ByteBuffer.wrap(dst).order(ByteOrder.BIG_ENDIAN);
        assertEquals(footer.mapOffset(), bb.getLong(0));
        assertEquals(footer.mapLength(), bb.getLong(8));
        assertEquals(footer.dictOffset(), bb.getLong(16));
        assertEquals(footer.dictLength(), bb.getLong(24));
        assertEquals(footer.idxOffset(), bb.getLong(32));
        assertEquals(footer.idxLength(), bb.getLong(40));
        assertEquals(footer.fltOffset(), bb.getLong(48));
        assertEquals(footer.fltLength(), bb.getLong(56));
        assertEquals(footer.entryCount(), bb.getLong(64));
        assertEquals(footer.blockSize(), bb.getLong(72));
        assertEquals(footer.blockCount(), bb.getInt(80));
        assertEquals(footer.mapChecksum(), bb.getInt(84));
        assertEquals(footer.dictChecksum(), bb.getInt(88));
        assertEquals(footer.idxChecksum(), bb.getInt(92));
        assertEquals(footer.fltChecksum(), bb.getInt(96));
        assertEquals(footer.footerChecksum(), bb.getInt(100));
        assertEquals(footer.magic(), bb.getLong(104));
    }

    @Test
    void roundtripPreservesAllFields() {
        V5Footer original = typicalFooter();
        byte[] dst = new byte[112];
        V5Footer.encode(original, dst, 0);
        V5Footer decoded = V5Footer.decode(dst, 0);
        assertEquals(original, decoded);
    }

    @Test
    void roundtripZeroFooter() {
        V5Footer zero = new V5Footer(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0, 0, 0, 0, 0,
                SSTableFormat.MAGIC_V5);
        byte[] dst = new byte[112];
        V5Footer.encode(zero, dst, 0);
        V5Footer decoded = V5Footer.decode(dst, 0);
        assertEquals(zero, decoded);
    }

    @Test
    void roundtripMaxValues() {
        V5Footer maxValues = new V5Footer(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                SSTableFormat.MAGIC_V5);
        byte[] dst = new byte[112];
        V5Footer.encode(maxValues, dst, 0);
        V5Footer decoded = V5Footer.decode(dst, 0);
        assertEquals(maxValues, decoded);
    }

    @Test
    void checksumExcludesFooterChecksumField() {
        byte[] dst = new byte[112];
        V5Footer.encode(typicalFooter(), dst, 0);
        int before = V5Footer.computeFooterChecksum(dst);

        // Mutate bytes [100, 104) — the footerChecksum field itself.
        dst[100] = (byte) 0xAA;
        dst[101] = (byte) 0xBB;
        dst[102] = (byte) 0xCC;
        dst[103] = (byte) 0xDD;

        int after = V5Footer.computeFooterChecksum(dst);
        assertEquals(before, after,
                "checksum scope must exclude the footerChecksum field at bytes [100, 104)");
    }

    @Test
    void checksumIncludesMagic() {
        byte[] dst = new byte[112];
        V5Footer.encode(typicalFooter(), dst, 0);
        int before = V5Footer.computeFooterChecksum(dst);

        // Flip one bit in the magic region (bytes [104, 112)).
        dst[110] = (byte) (dst[110] ^ 0x01);

        int after = V5Footer.computeFooterChecksum(dst);
        assertNotEquals(before, after, "checksum scope must include the magic bytes [104, 112)");
    }

    @Test
    void checksumIncludesFirst100Bytes() {
        byte[] dst = new byte[112];
        V5Footer.encode(typicalFooter(), dst, 0);
        int before = V5Footer.computeFooterChecksum(dst);

        dst[0] = (byte) (dst[0] ^ 0x01);

        int after = V5Footer.computeFooterChecksum(dst);
        assertNotEquals(before, after, "checksum scope must include the first 100 bytes");
    }

    @Test
    void checksumOverKnownScopeMatches_ReferenceImplementation() {
        byte[] dst = new byte[112];
        V5Footer.encode(typicalFooter(), dst, 0);

        CRC32C crc = new CRC32C();
        crc.update(dst, 0, 100);
        crc.update(dst, 104, 8);
        int reference = (int) crc.getValue();

        int computed = V5Footer.computeFooterChecksum(dst);
        assertEquals(reference, computed,
                "computeFooterChecksum must equal CRC32C over [0,100) + [104,112)");
    }

    @Test
    void tightPackingValidNoDict_returnsNull() {
        assertNull(V5Footer.validateTightPacking(typicalFooter()));
    }

    @Test
    void tightPackingValidWithDict_returnsNull() {
        V5Footer footer = new V5Footer(/* mapOffset */ 4096L, /* mapLength */ 1024L,
                /* dictOffset */ 5120L, /* dictLength */ 256L, /* idxOffset */ 5376L,
                /* idxLength */ 512L, /* fltOffset */ 5888L, /* fltLength */ 256L, 42L, 1024L, 3, 0,
                0, 0, 0, 0, SSTableFormat.MAGIC_V5);
        assertNull(V5Footer.validateTightPacking(footer));
    }

    @Test
    void tightPackingGapBetweenSections_returnsSectionName() {
        // map: [4096, 5120), idx begins at 5220 — 100-byte gap.
        V5Footer footer = new V5Footer(/* mapOffset */ 4096L, /* mapLength */ 1024L,
                /* dictOffset */ 0L, /* dictLength */ 0L, /* idxOffset */ 5220L,
                /* idxLength */ 512L, /* fltOffset */ 5732L, /* fltLength */ 256L, 42L, 1024L, 3, 0,
                0, 0, 0, 0, SSTableFormat.MAGIC_V5);
        String violating = V5Footer.validateTightPacking(footer);
        assertNotNull(violating, "gap between sections must produce a non-null section name");
    }

    @Test
    void tightPackingOverlapBetweenSections_returnsSectionName() {
        // map: [4096, 5120), idx begins at 5000 — overlap.
        V5Footer footer = new V5Footer(/* mapOffset */ 4096L, /* mapLength */ 1024L,
                /* dictOffset */ 0L, /* dictLength */ 0L, /* idxOffset */ 5000L,
                /* idxLength */ 512L, /* fltOffset */ 5512L, /* fltLength */ 256L, 42L, 1024L, 3, 0,
                0, 0, 0, 0, SSTableFormat.MAGIC_V5);
        String violating = V5Footer.validateTightPacking(footer);
        assertNotNull(violating, "overlap between sections must produce a non-null section name");
    }

    @Test
    void tightPackingZeroLengthDictSkipped() {
        // Other sections tightly packed; dict is the zero-length sentinel.
        V5Footer footer = typicalFooter();
        assertNull(V5Footer.validateTightPacking(footer));
    }

    @Test
    void dictionarySentinelAllZero_isConsistent() {
        V5Footer footer = new V5Footer(4096L, 1024L, 0L, 0L, 5120L, 512L, 5632L, 256L, 42L, 1024L,
                3, 0, 0, 0, 0, 0, SSTableFormat.MAGIC_V5);
        assertTrue(V5Footer.isDictionarySentinelConsistent(footer));
    }

    @Test
    void dictionarySentinelLengthOnly_isInconsistent() {
        V5Footer footer = new V5Footer(4096L, 1024L, 0L, 100L, 5120L, 512L, 5632L, 256L, 42L, 1024L,
                3, 0, 0, 0, 0, 0, SSTableFormat.MAGIC_V5);
        assertFalse(V5Footer.isDictionarySentinelConsistent(footer));
    }

    @Test
    void dictionarySentinelOffsetOnly_isInconsistent() {
        V5Footer footer = new V5Footer(4096L, 1024L, 100L, 0L, 5120L, 512L, 5632L, 256L, 42L, 1024L,
                3, 0, 0, 0, 0, 0, SSTableFormat.MAGIC_V5);
        assertFalse(V5Footer.isDictionarySentinelConsistent(footer));
    }

    @Test
    void dictionarySentinelAllPositive_isConsistent() {
        V5Footer footer = new V5Footer(4096L, 1024L, 1000L, 500L, 5120L, 512L, 5632L, 256L, 42L,
                1024L, 3, 0, 0xABCDEF00, 0, 0, 0, SSTableFormat.MAGIC_V5);
        assertTrue(V5Footer.isDictionarySentinelConsistent(footer));
    }

    @Test
    void dictionarySentinelLengthZeroOffsetNonzero_isInconsistent() {
        V5Footer footer = new V5Footer(4096L, 1024L, 1000L, 0L, 5120L, 512L, 5632L, 256L, 42L,
                1024L, 3, 0, 0, 0, 0, 0, SSTableFormat.MAGIC_V5);
        assertFalse(V5Footer.isDictionarySentinelConsistent(footer));
    }

    @Test
    void dictionarySentinelChecksumOnly_isInconsistent() {
        // Symmetric gap to lengthOnly / offsetOnly: a non-zero dictChecksum while both
        // dictOffset and dictLength are zero is an inconsistent sentinel per R15.
        V5Footer footer = new V5Footer(4096L, 1024L, 0L, 0L, 5120L, 512L, 5632L, 256L, 42L, 1024L,
                3, 0, 0xABCDEF00, 0, 0, 0, SSTableFormat.MAGIC_V5);
        assertFalse(V5Footer.isDictionarySentinelConsistent(footer));
    }

    // Ensure CorruptSectionException is referenced so the import isn't elided when sectionName
    // strings are exercised by walkthrough validation in future refactors.
    @Test
    void tightPackingReturnsKnownSectionNameOnViolation() {
        V5Footer footer = new V5Footer(4096L, 1024L, 0L, 0L, 5220L, 512L, 5732L, 256L, 42L, 1024L,
                3, 0, 0, 0, 0, 0, SSTableFormat.MAGIC_V5);
        String violating = V5Footer.validateTightPacking(footer);
        assertNotNull(violating);
        assertTrue(
                violating.equals(CorruptSectionException.SECTION_COMPRESSION_MAP)
                        || violating.equals(CorruptSectionException.SECTION_DICTIONARY)
                        || violating.equals(CorruptSectionException.SECTION_KEY_INDEX)
                        || violating.equals(CorruptSectionException.SECTION_BLOOM_FILTER),
                "violating section name must be one of the SECTION_* constants: " + violating);
    }

    // ===== Hardening (adversarial, Cycle 1) =====

    // Finding: H-DT-8
    // Bug: encode into a dst byte[] too small (< 112 bytes remaining) silently under-writes,
    // producing a malformed footer on disk.
    // Correct behavior: throw IndexOutOfBoundsException at the earliest point. No partial write.
    // Fix location: V5Footer.encode(V5Footer, byte[], int)
    // Regression watch: encode with byte[112] at offset 0 must still succeed.
    @Test
    void encodeDstTooSmallThrowsIndexOutOfBounds() {
        byte[] tooSmall = new byte[111]; // one byte short
        assertThrows(IndexOutOfBoundsException.class,
                () -> V5Footer.encode(typicalFooter(), tooSmall, 0));
    }

    // Finding: H-DT-8b — same bug variant via offset positioning
    // Bug: encode into dst with dstOffset + 112 > dst.length may silently under-write.
    // Correct behavior: throw IndexOutOfBoundsException when dstOffset + FOOTER_SIZE > dst.length.
    @Test
    void encodeDstOffsetOutOfRangeThrowsIndexOutOfBounds() {
        byte[] dst = new byte[200];
        // dstOffset = 100 leaves only 100 bytes at the tail — less than FOOTER_SIZE (112).
        assertThrows(IndexOutOfBoundsException.class,
                () -> V5Footer.encode(typicalFooter(), dst, 100));
    }

    // Finding: H-DT-9
    // Bug: decode from src byte[] too small (< 112 bytes remaining from srcOffset) may read
    // past the array end or parse garbage values, producing a misleading footer.
    // Correct behavior: throw IndexOutOfBoundsException at the earliest point.
    // Fix location: V5Footer.decode(byte[], int)
    // Regression watch: decode with byte[112] at offset 0 must still succeed.
    @Test
    void decodeSrcTooSmallThrowsIndexOutOfBounds() {
        byte[] tooSmall = new byte[111];
        assertThrows(IndexOutOfBoundsException.class, () -> V5Footer.decode(tooSmall, 0));
    }

    // Finding: H-DT-10
    // Bug: computeFooterChecksum with input shorter than FOOTER_SIZE reads past the end of the
    // input array, producing a misleading CRC or ArrayIndexOutOfBoundsException.
    // Correct behavior: throw IllegalArgumentException (or IndexOutOfBoundsException) immediately
    // when input.length < FOOTER_SIZE.
    // Fix location: V5Footer.computeFooterChecksum(byte[])
    // Regression watch: input of exactly FOOTER_SIZE bytes must still produce a checksum.
    @Test
    void computeFooterChecksumInputTooShortThrows() {
        byte[] tooShort = new byte[111];
        assertThrows(RuntimeException.class, () -> V5Footer.computeFooterChecksum(tooShort),
                "computeFooterChecksum must reject inputs shorter than FOOTER_SIZE");
    }

    // Finding: H-DT-11
    // Bug: decode(byte[], int) with negative srcOffset reads from a negative index, producing
    // ArrayIndexOutOfBoundsException with a confusing message or silent misparse.
    // Correct behavior: throw IndexOutOfBoundsException deterministically on any negative offset.
    // Fix location: V5Footer.decode(byte[], int)
    // Regression watch: srcOffset == 0 must continue to succeed.
    @Test
    void decodeNegativeSrcOffsetThrowsIndexOutOfBounds() {
        byte[] src = new byte[200];
        assertThrows(IndexOutOfBoundsException.class, () -> V5Footer.decode(src, -1));
    }
}
