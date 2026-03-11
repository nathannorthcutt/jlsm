package jlsm.sstable.internal;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeyIndexTest {

    private static MemorySegment seg(byte... bytes) {
        return MemorySegment.ofArray(bytes);
    }

    private static MemorySegment segOf(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    @Test
    void emptyIndexLookupReturnsEmpty() {
        KeyIndex idx = new KeyIndex(List.of(), List.of());
        assertTrue(idx.lookup(segOf("any")).isEmpty());
    }

    @Test
    void emptyIndexIteratorHasNoElements() {
        KeyIndex idx = new KeyIndex(List.of(), List.of());
        assertFalse(idx.iterator().hasNext());
    }

    @Test
    void singleKeyLookupFound() {
        MemorySegment key = segOf("hello");
        KeyIndex idx = new KeyIndex(List.of(key), List.of(42L));
        assertEquals(42L, idx.lookup(key).orElseThrow());
    }

    @Test
    void singleKeyLookupNotFound() {
        MemorySegment key = segOf("hello");
        KeyIndex idx = new KeyIndex(List.of(key), List.of(42L));
        assertTrue(idx.lookup(segOf("world")).isEmpty());
    }

    @Test
    void multipleKeysEachResolvesCorrectly() {
        List<MemorySegment> keys = List.of(segOf("a"), segOf("b"), segOf("c"));
        List<Long> offsets = List.of(100L, 200L, 300L);
        KeyIndex idx = new KeyIndex(keys, offsets);

        assertEquals(100L, idx.lookup(segOf("a")).orElseThrow());
        assertEquals(200L, idx.lookup(segOf("b")).orElseThrow());
        assertEquals(300L, idx.lookup(segOf("c")).orElseThrow());
        assertTrue(idx.lookup(segOf("d")).isEmpty());
    }

    @Test
    void iterationProducesLexicographicOrder() {
        List<MemorySegment> keys = List.of(segOf("apple"), segOf("banana"), segOf("cherry"));
        List<Long> offsets = List.of(1L, 2L, 3L);
        KeyIndex idx = new KeyIndex(keys, offsets);

        List<MemorySegment> result = new ArrayList<>();
        idx.iterator().forEachRemaining(e -> result.add(e.key()));

        assertEquals(3, result.size());
        assertEquals(-1L, segOf("apple").mismatch(result.get(0)));
        assertEquals(-1L, segOf("banana").mismatch(result.get(1)));
        assertEquals(-1L, segOf("cherry").mismatch(result.get(2)));
    }

    @Test
    void prefixKeysIndependentLookup() {
        MemorySegment a = segOf("a");
        MemorySegment ab = segOf("ab");
        KeyIndex idx = new KeyIndex(List.of(a, ab), List.of(10L, 20L));

        assertEquals(10L, idx.lookup(a).orElseThrow());
        assertEquals(20L, idx.lookup(ab).orElseThrow());
    }

    @Test
    void prefixKeysIterationOrder() {
        MemorySegment a = segOf("a");
        MemorySegment ab = segOf("ab");
        KeyIndex idx = new KeyIndex(List.of(a, ab), List.of(10L, 20L));

        List<KeyIndex.Entry> entries = new ArrayList<>();
        idx.iterator().forEachRemaining(entries::add);

        assertEquals(2, entries.size());
        assertEquals(-1L, a.mismatch(entries.get(0).key()));
        assertEquals(-1L, ab.mismatch(entries.get(1).key()));
    }

    @Test
    void rangeIterationInclusiveLower() {
        List<MemorySegment> keys = List.of(segOf("a"), segOf("b"), segOf("c"), segOf("d"));
        List<Long> offsets = List.of(1L, 2L, 3L, 4L);
        KeyIndex idx = new KeyIndex(keys, offsets);

        List<KeyIndex.Entry> results = new ArrayList<>();
        idx.rangeIterator(segOf("b"), segOf("d")).forEachRemaining(results::add);

        assertEquals(2, results.size());
        assertEquals(-1L, segOf("b").mismatch(results.get(0).key()));
        assertEquals(-1L, segOf("c").mismatch(results.get(1).key()));
    }

    @Test
    void rangeIterationEmptyRange() {
        List<MemorySegment> keys = List.of(segOf("a"), segOf("b"), segOf("c"));
        List<Long> offsets = List.of(1L, 2L, 3L);
        KeyIndex idx = new KeyIndex(keys, offsets);

        List<KeyIndex.Entry> results = new ArrayList<>();
        idx.rangeIterator(segOf("d"), segOf("e")).forEachRemaining(results::add);

        assertTrue(results.isEmpty());
    }

    @Test
    void rangeIterationPrefixBoundary() {
        List<MemorySegment> keys = List.of(segOf("a"), segOf("ab"), segOf("b"));
        List<Long> offsets = List.of(1L, 2L, 3L);
        KeyIndex idx = new KeyIndex(keys, offsets);

        // ["a", "b") should include "a" and "ab" but not "b"
        List<KeyIndex.Entry> results = new ArrayList<>();
        idx.rangeIterator(segOf("a"), segOf("b")).forEachRemaining(results::add);

        assertEquals(2, results.size());
        assertEquals(-1L, segOf("a").mismatch(results.get(0).key()));
        assertEquals(-1L, segOf("ab").mismatch(results.get(1).key()));
    }

    @Test
    void unsignedByteOrdering() {
        // 0xFF (signed -1 as byte) should sort after 0x7F
        MemorySegment low = seg((byte) 0x7F);
        MemorySegment high = seg((byte) 0xFF);
        KeyIndex idx = new KeyIndex(List.of(low, high), List.of(1L, 2L));

        List<MemorySegment> result = new ArrayList<>();
        idx.iterator().forEachRemaining(e -> result.add(e.key()));

        assertEquals(2, result.size());
        assertEquals(-1L, low.mismatch(result.get(0)));
        assertEquals(-1L, high.mismatch(result.get(1)));
    }
}
