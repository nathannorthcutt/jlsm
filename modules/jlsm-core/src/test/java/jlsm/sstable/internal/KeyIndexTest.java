package jlsm.sstable.internal;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
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

    // -------------------------------------------------------------------------
    // Iterative DFS stress tests (Violation 1)
    // These tests exercise traversal depth that would cause StackOverflowError
    // under an unbounded recursive DFS implementation.
    // -------------------------------------------------------------------------

    /**
     * Builds a trie where every key shares a very long common prefix, forcing the traversal to
     * descend 5000 trie nodes deep before reaching any leaf. A recursive DFS with no depth limit
     * would overflow the stack; an iterative DFS must handle this without error.
     */
    @Test
    void deepTrieIterationDoesNotOverflow() {
        // All keys share a 5000-byte prefix followed by a single distinguishing byte.
        // This forces 5001 levels of trie depth per key.
        int prefixLen = 5000;
        byte[] prefix = new byte[prefixLen];
        Arrays.fill(prefix, (byte) 'a');

        int numKeys = 3;
        List<MemorySegment> keys = new ArrayList<>(numKeys);
        List<Long> offsets = new ArrayList<>(numKeys);
        for (int i = 0; i < numKeys; i++) {
            byte[] key = Arrays.copyOf(prefix, prefixLen + 1);
            key[prefixLen] = (byte) ('a' + i);
            keys.add(MemorySegment.ofArray(key));
            offsets.add((long) (i * 100));
        }

        KeyIndex idx = new KeyIndex(keys, offsets);

        // iterator() must complete without StackOverflowError
        List<KeyIndex.Entry> entries = new ArrayList<>();
        assertDoesNotThrow(() -> idx.iterator().forEachRemaining(entries::add),
                "iterator() must not overflow the stack on a deeply nested trie");
        assertEquals(numKeys, entries.size(),
                "all entries must be returned from a deeply nested trie");
    }

    /**
     * Verifies that iterator() returns all 200 entries in sorted (lexicographic) order for a
     * moderately large trie with many distinct keys sharing partial common prefixes.
     */
    @Test
    void largeTrieIterationReturnsSortedEntries() {
        int numEntries = 200;
        List<MemorySegment> keys = new ArrayList<>(numEntries);
        List<Long> offsets = new ArrayList<>(numEntries);

        // Keys: "key-000" through "key-199". These share the prefix "key-" and have
        // variable digit suffixes, creating a moderately complex trie structure.
        for (int i = 0; i < numEntries; i++) {
            String keyStr = String.format("key-%03d", i);
            keys.add(segOf(keyStr));
            offsets.add((long) i);
        }

        KeyIndex idx = new KeyIndex(keys, offsets);

        List<KeyIndex.Entry> result = new ArrayList<>();
        idx.iterator().forEachRemaining(result::add);

        assertEquals(numEntries, result.size(),
                "iterator must return all " + numEntries + " entries");

        // Verify strictly ascending lexicographic order
        for (int i = 1; i < result.size(); i++) {
            byte[] prev = result.get(i - 1).key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            byte[] curr = result.get(i).key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            assertTrue(KeyIndex.compareUnsigned(prev, curr) < 0,
                    "entry " + i + " must be strictly greater than entry " + (i - 1));
        }

        // Verify offsets match the original parallel list
        for (int i = 0; i < result.size(); i++) {
            String expectedKey = String.format("key-%03d", i);
            byte[] expectedBytes = expectedKey.getBytes();
            assertEquals(-1L, MemorySegment.ofArray(expectedBytes).mismatch(result.get(i).key()),
                    "entry " + i + " key must match expected key " + expectedKey);
            assertEquals((long) i, result.get(i).fileOffset(),
                    "entry " + i + " offset must match expected offset");
        }
    }

    /**
     * Verifies that rangeIterator() correctly filters entries from a large trie (200 entries),
     * returning only those within the specified [from, to) range.
     */
    @Test
    void largeTrieRangeIteratorFiltersCorrectly() {
        int numEntries = 200;
        List<MemorySegment> keys = new ArrayList<>(numEntries);
        List<Long> offsets = new ArrayList<>(numEntries);

        for (int i = 0; i < numEntries; i++) {
            String keyStr = String.format("key-%03d", i);
            keys.add(segOf(keyStr));
            offsets.add((long) i);
        }

        KeyIndex idx = new KeyIndex(keys, offsets);

        // Range ["key-050", "key-100") should return entries 50 through 99 inclusive.
        MemorySegment from = segOf("key-050");
        MemorySegment to = segOf("key-100");

        List<KeyIndex.Entry> result = new ArrayList<>();
        idx.rangeIterator(from, to).forEachRemaining(result::add);

        assertEquals(50, result.size(), "range [key-050, key-100) must return exactly 50 entries");

        // First entry must be "key-050"
        assertEquals(-1L, from.mismatch(result.get(0).key()), "first entry must be key-050");

        // Last entry must be "key-099"
        MemorySegment expectedLast = segOf("key-099");
        assertEquals(-1L, expectedLast.mismatch(result.get(result.size() - 1).key()),
                "last entry must be key-099");

        // All returned entries must be within range
        for (KeyIndex.Entry e : result) {
            byte[] keyBytes = e.key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            byte[] fromBytes = from.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            byte[] toBytes = to.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            assertTrue(KeyIndex.compareUnsigned(keyBytes, fromBytes) >= 0,
                    "entry must be >= from bound");
            assertTrue(KeyIndex.compareUnsigned(keyBytes, toBytes) < 0, "entry must be < to bound");
        }
    }

    /**
     * Builds an extreme trie with a single key 10,000 bytes long (one 'a' character repeated). This
     * creates a trie with a 10,000-node chain, far exceeding typical JVM default stack depth. An
     * iterative DFS must handle this; a recursive DFS with even moderate key lengths will
     * StackOverflow.
     */
    @Test
    void extremelyDeepSingleKeyLookupAndIteration() {
        int keyLen = 10_000;
        byte[] keyBytes = new byte[keyLen];
        Arrays.fill(keyBytes, (byte) 'x');
        MemorySegment key = MemorySegment.ofArray(keyBytes);

        KeyIndex idx = new KeyIndex(List.of(key), List.of(99L));

        // lookup must not overflow
        assertDoesNotThrow(() -> {
            long offset = idx.lookup(key).orElseThrow();
            assertEquals(99L, offset);
        }, "lookup() must not overflow on a 10,000-byte key");

        // iterator must not overflow
        assertDoesNotThrow(() -> {
            List<KeyIndex.Entry> entries = new ArrayList<>();
            idx.iterator().forEachRemaining(entries::add);
            assertEquals(1, entries.size());
            assertEquals(-1L, key.mismatch(entries.get(0).key()));
        }, "iterator() must not overflow on a 10,000-byte key");
    }
}
