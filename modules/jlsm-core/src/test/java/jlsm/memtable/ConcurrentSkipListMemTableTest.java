package jlsm.memtable;

import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentSkipListMemTableTest {

    private ConcurrentSkipListMemTable table;

    @BeforeEach
    void setUp() {
        table = new ConcurrentSkipListMemTable();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry.Put put(String key, String value, long seqNum) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seqNum));
    }

    private static Entry.Delete delete(String key, long seqNum) {
        return new Entry.Delete(seg(key), new SequenceNumber(seqNum));
    }

    private static List<Entry> drain(Iterator<Entry> it) {
        List<Entry> result = new ArrayList<>();
        it.forEachRemaining(result::add);
        return result;
    }

    /** Returns true if the entry's key content matches the given string. */
    private static boolean keyEquals(Entry entry, String expected) {
        MemorySegment k = entry.key();
        MemorySegment e = seg(expected);
        return k.byteSize() == e.byteSize() && k.mismatch(e) == -1L;
    }

    /** Returns true if two MemorySegments have the same byte content. */
    private static boolean contentEquals(MemorySegment a, MemorySegment b) {
        return a.byteSize() == b.byteSize() && a.mismatch(b) == -1L;
    }

    // -----------------------------------------------------------------------
    // Phase B: Basic state — empty table
    // -----------------------------------------------------------------------

    @Test
    void isEmpty_trueOnNewInstance() {
        assertTrue(table.isEmpty());
    }

    @Test
    void approximateSizeBytes_zeroOnNewInstance() {
        assertEquals(0L, table.approximateSizeBytes());
    }

    @Test
    void scan_emptyOnNewInstance() {
        assertFalse(table.scan().hasNext());
    }

    @Test
    void get_emptyOnNewInstance() {
        assertEquals(Optional.empty(), table.get(seg("any")));
    }

    // -----------------------------------------------------------------------
    // Phase C: Single-entry operations
    // -----------------------------------------------------------------------

    @Test
    void isEmpty_falseAfterApply() {
        table.apply(put("k", "v", 1L));
        assertFalse(table.isEmpty());
    }

    @Test
    void applyPutThenGetReturnsThatEntry() {
        Entry.Put p = put("hello", "world", 1L);
        table.apply(p);
        Optional<Entry> result = table.get(seg("hello"));
        assertTrue(result.isPresent());
        assertTrue(keyEquals(result.get(), "hello"));
        assertInstanceOf(Entry.Put.class, result.get());
        assertTrue(contentEquals(((Entry.Put) result.get()).value(), seg("world")));
    }

    @Test
    void applyDeleteThenGetReturnsTombstone() {
        table.apply(delete("gone", 1L));
        Optional<Entry> result = table.get(seg("gone"));
        assertTrue(result.isPresent());
        assertInstanceOf(Entry.Delete.class, result.get());
    }

    @Test
    void getMissingKeyReturnsEmpty() {
        table.apply(put("a", "1", 1L));
        assertEquals(Optional.empty(), table.get(seg("z")));
    }

    @Test
    void applyNull_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> table.apply(null));
    }

    @Test
    void getNull_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> table.get(null));
    }

    // -----------------------------------------------------------------------
    // Phase D: Multi-version — get returns highest seqnum
    // -----------------------------------------------------------------------

    @Test
    void twoPutsForSameKey_getReturnsHighestSeqNum() {
        table.apply(put("k", "v1", 1L));
        table.apply(put("k", "v2", 2L));
        Optional<Entry> result = table.get(seg("k"));
        assertTrue(result.isPresent());
        assertInstanceOf(Entry.Put.class, result.get());
        assertTrue(contentEquals(((Entry.Put) result.get()).value(), seg("v2")));
    }

    @Test
    void insertOrderDoesNotMatter_highestSeqNumAlwaysReturned() {
        // Insert in reverse sequence order
        table.apply(put("k", "v3", 3L));
        table.apply(put("k", "v1", 1L));
        table.apply(put("k", "v2", 2L));
        Optional<Entry> result = table.get(seg("k"));
        assertTrue(result.isPresent());
        assertTrue(contentEquals(((Entry.Put) result.get()).value(), seg("v3")));
    }

    @Test
    void deleteAtHigherSeqNumOverridesPriorPut() {
        table.apply(put("k", "v", 1L));
        table.apply(delete("k", 2L));
        Optional<Entry> result = table.get(seg("k"));
        assertTrue(result.isPresent());
        assertInstanceOf(Entry.Delete.class, result.get());
        assertEquals(2L, result.get().sequenceNumber().value());
    }

    @Test
    void putAtHigherSeqNumOverridesPriorDelete() {
        table.apply(delete("k", 1L));
        table.apply(put("k", "alive", 2L));
        Optional<Entry> result = table.get(seg("k"));
        assertTrue(result.isPresent());
        assertInstanceOf(Entry.Put.class, result.get());
        assertTrue(contentEquals(((Entry.Put) result.get()).value(), seg("alive")));
    }

    @Test
    void tenVersions_getReturnsMaxSeqNum() {
        for (long i = 1; i <= 10; i++) {
            table.apply(put("k", "v" + i, i));
        }
        Optional<Entry> result = table.get(seg("k"));
        assertTrue(result.isPresent());
        assertEquals(10L, result.get().sequenceNumber().value());
    }

    // -----------------------------------------------------------------------
    // Phase E: Scan ordering
    // -----------------------------------------------------------------------

    @Test
    void scan_multipleKeys_ascendingLexOrder() {
        table.apply(put("c", "3", 1L));
        table.apply(put("a", "1", 1L));
        table.apply(put("b", "2", 1L));
        List<Entry> entries = drain(table.scan());
        assertEquals(3, entries.size());
        assertTrue(keyEquals(entries.get(0), "a"));
        assertTrue(keyEquals(entries.get(1), "b"));
        assertTrue(keyEquals(entries.get(2), "c"));
    }

    @Test
    void scan_sameKeyMultipleSeqNums_higherSeqNumFirst() {
        table.apply(put("k", "v1", 1L));
        table.apply(put("k", "v2", 2L));
        table.apply(put("k", "v3", 3L));
        List<Entry> entries = drain(table.scan());
        assertEquals(3, entries.size());
        assertEquals(3L, entries.get(0).sequenceNumber().value());
        assertEquals(2L, entries.get(1).sequenceNumber().value());
        assertEquals(1L, entries.get(2).sequenceNumber().value());
    }

    @Test
    void scan_interleavedKeysAndVersions_correctJointOrdering() {
        // Insert out of order; expect: a@2, a@1, b@1, c@3, c@1
        table.apply(put("c", "c1", 1L));
        table.apply(put("a", "a1", 1L));
        table.apply(put("c", "c3", 3L));
        table.apply(put("b", "b1", 1L));
        table.apply(put("a", "a2", 2L));
        List<Entry> entries = drain(table.scan());
        assertEquals(5, entries.size());
        assertTrue(keyEquals(entries.get(0), "a"));
        assertEquals(2L, entries.get(0).sequenceNumber().value());
        assertTrue(keyEquals(entries.get(1), "a"));
        assertEquals(1L, entries.get(1).sequenceNumber().value());
        assertTrue(keyEquals(entries.get(2), "b"));
        assertEquals(1L, entries.get(2).sequenceNumber().value());
        assertTrue(keyEquals(entries.get(3), "c"));
        assertEquals(3L, entries.get(3).sequenceNumber().value());
        assertTrue(keyEquals(entries.get(4), "c"));
        assertEquals(1L, entries.get(4).sequenceNumber().value());
    }

    @Test
    void scan_tombstonesAppearInScanOutput() {
        table.apply(put("a", "v", 1L));
        table.apply(delete("b", 2L));
        List<Entry> entries = drain(table.scan());
        assertEquals(2, entries.size());
        assertTrue(keyEquals(entries.get(0), "a"));
        assertTrue(keyEquals(entries.get(1), "b"));
        assertInstanceOf(Entry.Delete.class, entries.get(1));
    }

    // -----------------------------------------------------------------------
    // Phase F: Range scan [fromKey, toKey)
    // -----------------------------------------------------------------------

    @Test
    void rangeScan_fromKeyIsInclusive() {
        table.apply(put("a", "1", 1L));
        table.apply(put("b", "2", 1L));
        table.apply(put("c", "3", 1L));
        List<Entry> entries = drain(table.scan(seg("b"), seg("d")));
        assertEquals(2, entries.size());
        assertTrue(keyEquals(entries.get(0), "b"));
        assertTrue(keyEquals(entries.get(1), "c"));
    }

    @Test
    void rangeScan_toKeyIsExclusive() {
        table.apply(put("a", "1", 1L));
        table.apply(put("b", "2", 1L));
        table.apply(put("c", "3", 1L));
        List<Entry> entries = drain(table.scan(seg("a"), seg("c")));
        assertEquals(2, entries.size());
        assertTrue(keyEquals(entries.get(0), "a"));
        assertTrue(keyEquals(entries.get(1), "b"));
    }

    @Test
    void rangeScan_emptyRangeReturnsEmptyIterator() {
        table.apply(put("z", "1", 1L));
        List<Entry> entries = drain(table.scan(seg("a"), seg("b")));
        assertTrue(entries.isEmpty());
    }

    @Test
    void rangeScan_multipleVersionsOfInRangeKey_allAppearDescendingSeqNum() {
        table.apply(put("b", "b1", 1L));
        table.apply(put("b", "b3", 3L));
        table.apply(put("b", "b2", 2L));
        List<Entry> entries = drain(table.scan(seg("a"), seg("c")));
        assertEquals(3, entries.size());
        assertEquals(3L, entries.get(0).sequenceNumber().value());
        assertEquals(2L, entries.get(1).sequenceNumber().value());
        assertEquals(1L, entries.get(2).sequenceNumber().value());
    }

    @Test
    void rangeScan_nullFromKey_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> table.scan(null, seg("z")));
    }

    @Test
    void rangeScan_nullToKey_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> table.scan(seg("a"), null));
    }

    @Test
    void rangeScan_prefixBoundary_abInRange_a_to_b() {
        // "ab" should appear in [a, b) because "ab" < "b" lexicographically
        table.apply(put("a", "1", 1L));
        table.apply(put("ab", "2", 1L));
        table.apply(put("b", "3", 1L));
        List<Entry> entries = drain(table.scan(seg("a"), seg("b")));
        assertEquals(2, entries.size());
        assertTrue(keyEquals(entries.get(0), "a"));
        assertTrue(keyEquals(entries.get(1), "ab"));
    }

    // -----------------------------------------------------------------------
    // Phase G: Size estimation
    // -----------------------------------------------------------------------

    @Test
    void sizeIncreasesAfterPut() {
        long before = table.approximateSizeBytes();
        table.apply(put("key", "value", 1L));
        assertTrue(table.approximateSizeBytes() > before);
    }

    @Test
    void sizeIncreasesAfterDelete() {
        long before = table.approximateSizeBytes();
        table.apply(delete("key", 1L));
        assertTrue(table.approximateSizeBytes() > before);
    }

    @Test
    void sizeReflectsKeyAndValueByteLengths() {
        table.apply(put("k", "v", 1L));
        // key=1, value=1, seqnum=8 bytes
        long expected = 1L + 1L + Long.BYTES;
        assertEquals(expected, table.approximateSizeBytes());
    }

    @Test
    void sizeReflectsKeyByteLengthForDelete() {
        table.apply(delete("key", 1L));
        // key=3, seqnum=8 bytes
        long expected = 3L + Long.BYTES;
        assertEquals(expected, table.approximateSizeBytes());
    }

    @Test
    void multipleVersionsAccumulateSize() {
        table.apply(put("k", "v", 1L));
        table.apply(put("k", "v", 2L));
        // Each put: key=1, value=1, seqnum=8 → 10 bytes; two puts = 20
        long expected = 2L * (1L + 1L + Long.BYTES);
        assertEquals(expected, table.approximateSizeBytes());
    }
}
