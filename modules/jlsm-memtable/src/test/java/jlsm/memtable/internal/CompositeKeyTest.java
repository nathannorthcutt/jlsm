package jlsm.memtable.internal;

import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

class CompositeKeyTest {

    private static final Comparator<CompositeKey> CMP = new KeyComparator();

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static MemorySegment seg(byte... bytes) {
        return MemorySegment.ofArray(bytes);
    }

    private static CompositeKey key(String logicalKey, long seqNum) {
        return new CompositeKey(seg(logicalKey), new SequenceNumber(seqNum));
    }

    // -----------------------------------------------------------------------
    // Equality
    // -----------------------------------------------------------------------

    @Test
    void comparatorReturnsZeroForEqualCompositeKeys() {
        CompositeKey a = key("foo", 42L);
        CompositeKey b = key("foo", 42L);
        assertEquals(0, CMP.compare(a, b));
    }

    // -----------------------------------------------------------------------
    // Sequence number ordering (descending within same logical key)
    // -----------------------------------------------------------------------

    @Test
    void higherSeqNumSortsBeforeLowerSeqNumForSameLogicalKey() {
        CompositeKey higher = key("foo", 100L);
        CompositeKey lower  = key("foo", 50L);
        assertTrue(CMP.compare(higher, lower) < 0,
                "higher seqnum should sort BEFORE lower seqnum");
    }

    @Test
    void lowerSeqNumSortsAfterHigherSeqNumForSameLogicalKey() {
        CompositeKey higher = key("foo", 100L);
        CompositeKey lower  = key("foo", 50L);
        assertTrue(CMP.compare(lower, higher) > 0,
                "lower seqnum should sort AFTER higher seqnum");
    }

    // -----------------------------------------------------------------------
    // Logical key ordering (ascending)
    // -----------------------------------------------------------------------

    @Test
    void ascendingLexicographicOrderAcrossDifferentLogicalKeys() {
        CompositeKey a = key("apple", 1L);
        CompositeKey b = key("banana", 1L);
        assertTrue(CMP.compare(a, b) < 0, "'apple' should sort before 'banana'");
        assertTrue(CMP.compare(b, a) > 0, "'banana' should sort after 'apple'");
    }

    @Test
    void shorterKeyWithSamePrefixSortsBeforeLongerKey() {
        CompositeKey shorter = key("ab", 1L);
        CompositeKey longer  = key("abc", 1L);
        assertTrue(CMP.compare(shorter, longer) < 0,
                "'ab' should sort before 'abc'");
    }

    @Test
    void emptyKeySortsBeforeAnyNonEmptyKey() {
        CompositeKey empty    = new CompositeKey(seg(new byte[0]), new SequenceNumber(1L));
        CompositeKey nonEmpty = key("a", 1L);
        assertTrue(CMP.compare(empty, nonEmpty) < 0,
                "empty key should sort before any non-empty key");
    }

    @Test
    void unsignedByteOrdering_0xFF_greaterThan_0x01() {
        CompositeKey ff = new CompositeKey(seg((byte) 0xFF), new SequenceNumber(1L));
        CompositeKey one = new CompositeKey(seg((byte) 0x01), new SequenceNumber(1L));
        assertTrue(CMP.compare(ff, one) > 0,
                "0xFF should sort after 0x01 (unsigned comparison)");
        assertTrue(CMP.compare(one, ff) < 0,
                "0x01 should sort before 0xFF (unsigned comparison)");
    }

    @Test
    void unsignedByteOrdering_0x80_greaterThan_0x7F() {
        CompositeKey high = new CompositeKey(seg((byte) 0x80), new SequenceNumber(1L));
        CompositeKey low  = new CompositeKey(seg((byte) 0x7F), new SequenceNumber(1L));
        assertTrue(CMP.compare(high, low) > 0,
                "0x80 should sort after 0x7F (unsigned comparison)");
    }

    // -----------------------------------------------------------------------
    // Long.MAX_VALUE probe sorts before all real entries for the same key
    // -----------------------------------------------------------------------

    @Test
    void maxSeqNumProbeSortsBeforeAllRealEntriesForSameKey() {
        CompositeKey probe = new CompositeKey(seg("k"), new SequenceNumber(Long.MAX_VALUE));
        CompositeKey real  = new CompositeKey(seg("k"), new SequenceNumber(999L));
        assertTrue(CMP.compare(probe, real) < 0,
                "Long.MAX_VALUE probe should sort BEFORE a real entry for the same key");
    }
}
