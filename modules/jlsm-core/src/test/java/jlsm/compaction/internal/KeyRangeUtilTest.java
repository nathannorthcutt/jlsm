package jlsm.compaction.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import org.junit.jupiter.api.Test;

class KeyRangeUtilTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static MemorySegment seg(byte[] bytes) {
        return MemorySegment.ofArray(bytes);
    }

    private static SSTableMetadata meta(String smallest, String largest) {
        return new SSTableMetadata(0L, Path.of("/tmp/fake.sst"), Level.L0, seg(smallest),
                seg(largest), SequenceNumber.ZERO, SequenceNumber.ZERO, 100L, 1L);
    }

    // -------------------------------------------------------------------------
    // compareUnsigned
    // -------------------------------------------------------------------------

    @Test
    void compareUnsignedEqualSegments() {
        MemorySegment a = seg("abc");
        MemorySegment b = seg("abc");
        assertEquals(0, KeyRangeUtil.compareUnsigned(a, b));
    }

    @Test
    void compareUnsignedALessThanB() {
        MemorySegment a = seg("abc");
        MemorySegment b = seg("abd");
        assertTrue(KeyRangeUtil.compareUnsigned(a, b) < 0);
    }

    @Test
    void compareUnsignedAGreaterThanB() {
        MemorySegment a = seg("abd");
        MemorySegment b = seg("abc");
        assertTrue(KeyRangeUtil.compareUnsigned(a, b) > 0);
    }

    @Test
    void compareUnsignedAPrefixOfB() {
        MemorySegment a = seg("ab");
        MemorySegment b = seg("abc");
        assertTrue(KeyRangeUtil.compareUnsigned(a, b) < 0);
    }

    @Test
    void compareUnsignedBPrefixOfA() {
        MemorySegment a = seg("abc");
        MemorySegment b = seg("ab");
        assertTrue(KeyRangeUtil.compareUnsigned(a, b) > 0);
    }

    @Test
    void compareUnsignedHighByteValues() {
        // 0xFF > 0x01 as unsigned
        MemorySegment a = seg(new byte[]{ (byte) 0xFF });
        MemorySegment b = seg(new byte[]{ (byte) 0x01 });
        assertTrue(KeyRangeUtil.compareUnsigned(a, b) > 0);
    }

    // -------------------------------------------------------------------------
    // keysEqual
    // -------------------------------------------------------------------------

    @Test
    void keysEqualSameContent() {
        assertTrue(KeyRangeUtil.keysEqual(seg("hello"), seg("hello")));
    }

    @Test
    void keysEqualDifferentContent() {
        assertFalse(KeyRangeUtil.keysEqual(seg("hello"), seg("world")));
    }

    @Test
    void keysEqualDifferentLength() {
        assertFalse(KeyRangeUtil.keysEqual(seg("ab"), seg("abc")));
    }

    // -------------------------------------------------------------------------
    // overlaps
    // -------------------------------------------------------------------------

    @Test
    void noOverlapABeforeB() {
        SSTableMetadata a = meta("a", "c");
        SSTableMetadata b = meta("d", "f");
        assertFalse(KeyRangeUtil.overlaps(a, b));
        assertFalse(KeyRangeUtil.overlaps(b, a));
    }

    @Test
    void noOverlapBBeforeA() {
        SSTableMetadata a = meta("d", "f");
        SSTableMetadata b = meta("a", "c");
        assertFalse(KeyRangeUtil.overlaps(a, b));
    }

    @Test
    void overlapsAdjacentTouchingBoundary() {
        // [a,c] and [c,e] share key "c" — they overlap
        SSTableMetadata a = meta("a", "c");
        SSTableMetadata b = meta("c", "e");
        assertTrue(KeyRangeUtil.overlaps(a, b));
        assertTrue(KeyRangeUtil.overlaps(b, a));
    }

    @Test
    void overlapsPartialOverlap() {
        SSTableMetadata a = meta("a", "d");
        SSTableMetadata b = meta("c", "f");
        assertTrue(KeyRangeUtil.overlaps(a, b));
        assertTrue(KeyRangeUtil.overlaps(b, a));
    }

    @Test
    void overlapsOneContainsOther() {
        SSTableMetadata outer = meta("a", "z");
        SSTableMetadata inner = meta("c", "e");
        assertTrue(KeyRangeUtil.overlaps(outer, inner));
        assertTrue(KeyRangeUtil.overlaps(inner, outer));
    }

    @Test
    void overlapsIdenticalRange() {
        SSTableMetadata a = meta("b", "d");
        SSTableMetadata b = meta("b", "d");
        assertTrue(KeyRangeUtil.overlaps(a, b));
    }
}
