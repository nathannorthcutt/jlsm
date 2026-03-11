package jlsm.compaction.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;

class MergeIteratorTest {

    private static MemorySegment key(String s) {
        return MemorySegment.ofArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static MemorySegment val(String s) {
        return MemorySegment.ofArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Entry.Put put(String k, long seq) {
        return new Entry.Put(key(k), val("v"), new SequenceNumber(seq));
    }

    private static Entry.Delete del(String k, long seq) {
        return new Entry.Delete(key(k), new SequenceNumber(seq));
    }

    private static Iterator<Entry> iter(Entry... entries) {
        return List.of(entries).iterator();
    }

    private static List<Entry> drain(MergeIterator it) {
        List<Entry> result = new ArrayList<>();
        while (it.hasNext())
            result.add(it.next());
        return result;
    }

    // -------------------------------------------------------------------------
    // Empty inputs
    // -------------------------------------------------------------------------

    @Test
    void nullListRejected() {
        assertThrows(NullPointerException.class, () -> new MergeIterator(null));
    }

    @Test
    void emptyListOfIterators() {
        MergeIterator it = new MergeIterator(List.of());
        assertFalse(it.hasNext());
    }

    @Test
    void singleEmptyIterator() {
        MergeIterator it = new MergeIterator(List.of(iter()));
        assertFalse(it.hasNext());
    }

    @Test
    void allEmptyIterators() {
        MergeIterator it = new MergeIterator(List.of(iter(), iter(), iter()));
        assertFalse(it.hasNext());
    }

    // -------------------------------------------------------------------------
    // Single source
    // -------------------------------------------------------------------------

    @Test
    void singleSourcePassthrough() {
        MergeIterator it = new MergeIterator(List.of(iter(put("a", 1), put("b", 2), put("c", 3))));
        List<Entry> result = drain(it);
        assertEquals(3, result.size());
        assertEquals("a", keyStr(result.get(0)));
        assertEquals("b", keyStr(result.get(1)));
        assertEquals("c", keyStr(result.get(2)));
    }

    // -------------------------------------------------------------------------
    // Two non-overlapping sources
    // -------------------------------------------------------------------------

    @Test
    void twoNonOverlappingSources() {
        MergeIterator it = new MergeIterator(
                List.of(iter(put("a", 1), put("c", 2)), iter(put("b", 3), put("d", 4))));
        List<Entry> result = drain(it);
        assertEquals(4, result.size());
        assertEquals("a", keyStr(result.get(0)));
        assertEquals("b", keyStr(result.get(1)));
        assertEquals("c", keyStr(result.get(2)));
        assertEquals("d", keyStr(result.get(3)));
    }

    // -------------------------------------------------------------------------
    // Equal-key ordering: highest seqNum first
    // -------------------------------------------------------------------------

    @Test
    void equalKeyHighestSeqNumFirst() {
        MergeIterator it = new MergeIterator(
                List.of(iter(put("k", 1)), iter(put("k", 5)), iter(put("k", 3))));
        List<Entry> result = drain(it);
        assertEquals(3, result.size());
        assertEquals(5L, result.get(0).sequenceNumber().value());
        assertEquals(3L, result.get(1).sequenceNumber().value());
        assertEquals(1L, result.get(2).sequenceNumber().value());
    }

    // -------------------------------------------------------------------------
    // N-way merge — globally sorted result
    // -------------------------------------------------------------------------

    @Test
    void nWayMergeGloballySorted() {
        MergeIterator it = new MergeIterator(List.of(iter(put("a", 1), put("d", 4), put("g", 7)),
                iter(put("b", 2), put("e", 5), put("h", 8)),
                iter(put("c", 3), put("f", 6), put("i", 9))));
        List<Entry> result = drain(it);
        assertEquals(9, result.size());
        for (int i = 0; i < result.size(); i++) {
            assertEquals(String.valueOf((char) ('a' + i)), keyStr(result.get(i)));
        }
    }

    // -------------------------------------------------------------------------
    // Tombstones yielded (not filtered)
    // -------------------------------------------------------------------------

    @Test
    void tombstonesYielded() {
        MergeIterator it = new MergeIterator(List.of(iter(del("k", 10), put("z", 1))));
        List<Entry> result = drain(it);
        assertEquals(2, result.size());
        assertInstanceOf(Entry.Delete.class, result.get(0));
        assertEquals("k", keyStr(result.get(0)));
    }

    // -------------------------------------------------------------------------
    // hasNext / next contract
    // -------------------------------------------------------------------------

    @Test
    void hasNextIdempotent() {
        MergeIterator it = new MergeIterator(List.of(iter(put("a", 1))));
        assertTrue(it.hasNext());
        assertTrue(it.hasNext()); // calling twice does not consume
        it.next();
        assertFalse(it.hasNext());
    }

    private static String keyStr(Entry e) {
        return new String(e.key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
