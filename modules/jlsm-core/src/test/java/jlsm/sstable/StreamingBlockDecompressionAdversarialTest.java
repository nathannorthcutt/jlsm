package jlsm.sstable;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for streaming block decompression in v2 compressed SSTables.
 *
 * <p>
 * Targets findings from spec-analysis.md round 1.
 */
class StreamingBlockDecompressionAdversarialTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    private static String str(MemorySegment s) {
        return new String(s.toArray(ValueLayout.JAVA_BYTE));
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private static Entry.Delete delete(String key, long seq) {
        return new Entry.Delete(seg(key), new SequenceNumber(seq));
    }

    private Path writeV2(Path dir, String name, List<Entry> entries) throws IOException {
        Path path = dir.resolve(name);
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }
        return path;
    }

    private TrieSSTableReader openV2(Path path) throws IOException {
        return TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null,
                CompressionCodec.none(), CompressionCodec.deflate());
    }

    private TrieSSTableReader openV2Lazy(Path path) throws IOException {
        return TrieSSTableReader.openLazy(path, BlockedBloomFilter.deserializer(), null,
                CompressionCodec.none(), CompressionCodec.deflate());
    }

    /**
     * Builds entries large enough to span multiple blocks (~100 bytes each, 4096-byte target
     * block).
     */
    private List<Entry> multiBlockEntries(int count) {
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = "key-%06d".formatted(i);
            String value = "value-padding-for-block-spanning-%06d".formatted(i);
            entries.add(put(key, value, i + 1));
        }
        return entries;
    }

    // ---- CG-1: No closed-state check during iterator advance ----

    /**
     * CG-1: CompressedBlockIterator on EAGER v2 reader silently continues after close.
     *
     * <p>
     * The brief states "existing checkNotClosed() guard applies" during iteration, but
     * CompressedBlockIterator.advance() never checks the closed flag. For eager readers, the data
     * is still in memory so iteration succeeds silently — violating close semantics.
     */
    // @spec F08.R19 — closed eager reader detected, throws IllegalStateException
    @Test
    void testEagerScanIteratorFailsAfterClose(@TempDir Path dir) throws IOException {
        List<Entry> entries = multiBlockEntries(500);
        Path path = writeV2(dir, "eager-close.sst", entries);

        TrieSSTableReader r = openV2(path);
        Iterator<Entry> iter = r.scan();

        // Consume a few entries
        for (int i = 0; i < 10; i++) {
            assertTrue(iter.hasNext());
            iter.next();
        }

        // Close the reader
        r.close();

        // Iteration should fail with IllegalStateException, not silently continue
        assertThrows(IllegalStateException.class, iter::next,
                "CompressedBlockIterator must check closed state during advance");
    }

    /**
     * CG-1: CompressedBlockIterator on LAZY v2 reader throws wrong exception after close.
     *
     * <p>
     * After close, the lazy channel is closed. readAndDecompressBlockNoCache throws
     * ClosedChannelException wrapped in UncheckedIOException instead of IllegalStateException. This
     * test requires advancing past the current block to trigger a channel read.
     */
    // @spec F08.R19,R29 — closed lazy reader throws IllegalStateException (v2 streaming divergence)
    @Test
    void testLazyScanIteratorFailsAfterClose(@TempDir Path dir) throws IOException {
        List<Entry> entries = multiBlockEntries(500);
        Path path = writeV2(dir, "lazy-close.sst", entries);

        TrieSSTableReader r = openV2Lazy(path);
        Iterator<Entry> iter = r.scan();

        // Consume entries until we've exhausted at least 2 blocks worth
        // (~40 entries per block at ~100 bytes each in a 4096-byte block)
        for (int i = 0; i < 100; i++) {
            assertTrue(iter.hasNext());
            iter.next();
        }

        // Close the reader
        r.close();

        // Should throw IllegalStateException, not UncheckedIOException(ClosedChannelException)
        assertThrows(IllegalStateException.class, iter::next,
                "CompressedBlockIterator must throw IllegalStateException on closed reader");
    }

    /**
     * CG-1: IndexRangeIterator on EAGER v2 reader silently continues after close.
     *
     * <p>
     * Same bug as CompressedBlockIterator — IndexRangeIterator.advance() doesn't check the closed
     * flag. For eager readers, readAndDecompressBlockNoCache reads from eagerData (still in memory)
     * so iteration silently succeeds.
     */
    // @spec F08.R20 — closed reader detected by range-scan iterator
    @Test
    void testEagerRangeScanIteratorFailsAfterClose(@TempDir Path dir) throws IOException {
        List<Entry> entries = multiBlockEntries(500);
        Path path = writeV2(dir, "eager-range-close.sst", entries);

        TrieSSTableReader r = openV2(path);
        Iterator<Entry> iter = r.scan(seg("key-000000"), seg("key-000499"));

        // Consume a few entries
        for (int i = 0; i < 5; i++) {
            assertTrue(iter.hasNext());
            iter.next();
        }

        // Close the reader
        r.close();

        // Should throw IllegalStateException
        assertThrows(IllegalStateException.class, iter::next,
                "IndexRangeIterator must check closed state during advance");
    }

    // ---- CG-2: Lazy v2 range scan not tested ----

    /**
     * CG-2: Lazy v2 range scan within a single block — exercises the block cache reuse path on a
     * lazy reader (readAndDecompressBlockNoCache via lazyChannel).
     */
    // @spec F08.R5,R23,R27 — lazy reader range scan reuses cached block within single block
    @Test
    void testLazyReaderRangeScanSameBlock(@TempDir Path dir) throws IOException {
        List<Entry> entries = List.of(put("aaa", "v1", 1), put("aab", "v2", 2), put("aac", "v3", 3),
                put("aad", "v4", 4), put("aae", "v5", 5), put("zzz", "end", 6));
        Path path = writeV2(dir, "lazy-range-same.sst", entries);

        try (TrieSSTableReader r = openV2Lazy(path)) {
            Iterator<Entry> iter = r.scan(seg("aab"), seg("aae"));
            List<String> keys = new ArrayList<>();
            while (iter.hasNext()) {
                keys.add(str(iter.next().key()));
            }
            assertEquals(List.of("aab", "aac", "aad"), keys,
                    "lazy reader range scan must return correct entries");
        }
    }

    /**
     * CG-2: Lazy v2 range scan spanning multiple blocks — exercises block cache transitions on a
     * lazy reader.
     */
    // @spec F08.R6,R15 — lazy reader cross-block range scan replaces cached block
    @Test
    void testLazyReaderRangeScanCrossBlock(@TempDir Path dir) throws IOException {
        List<Entry> entries = multiBlockEntries(500);
        Path path = writeV2(dir, "lazy-range-cross.sst", entries);

        try (TrieSSTableReader r = openV2Lazy(path)) {
            Iterator<Entry> iter = r.scan(seg("key-000100"), seg("key-000400"));
            List<String> keys = new ArrayList<>();
            while (iter.hasNext()) {
                keys.add(str(iter.next().key()));
            }
            assertEquals(300, keys.size(), "lazy cross-block range scan must return correct count");
            assertEquals("key-000100", keys.getFirst());
            assertEquals("key-000399", keys.getLast());

            // Verify ordering
            for (int i = 1; i < keys.size(); i++) {
                assertTrue(keys.get(i).compareTo(keys.get(i - 1)) > 0,
                        "keys must be ascending in range scan");
            }
        }
    }

    // ---- CG-3: Delete entries through streaming scan ----

    /**
     * CG-3: Delete entries must be correctly decoded by CompressedBlockIterator.
     *
     * <p>
     * All existing tests use only Entry.Put. Entry.Delete has a different encoding (type=1, no
     * value bytes). Verifies the streaming scan handles mixed entry types.
     */
    // @spec F08.R4,R14 — scan decodes all entry types including deletes, preserves order
    @Test
    void testStreamingScanWithDeleteEntries(@TempDir Path dir) throws IOException {
        List<Entry> entries = List.of(put("alpha", "one", 1), delete("beta", 2),
                put("delta", "three", 3), delete("epsilon", 4), put("gamma", "five", 5));
        Path path = writeV2(dir, "mixed-types.sst", entries);

        try (TrieSSTableReader r = openV2(path)) {
            Iterator<Entry> iter = r.scan();
            List<Entry> scanned = new ArrayList<>();
            while (iter.hasNext()) {
                scanned.add(iter.next());
            }

            assertEquals(5, scanned.size(), "scan must return all entries including deletes");

            // Verify types and keys (sorted order: alpha, beta, delta, epsilon, gamma)
            assertInstanceOf(Entry.Put.class, scanned.get(0));
            assertEquals("alpha", str(scanned.get(0).key()));

            assertInstanceOf(Entry.Delete.class, scanned.get(1));
            assertEquals("beta", str(scanned.get(1).key()));

            assertInstanceOf(Entry.Put.class, scanned.get(2));
            assertEquals("delta", str(scanned.get(2).key()));

            assertInstanceOf(Entry.Delete.class, scanned.get(3));
            assertEquals("epsilon", str(scanned.get(3).key()));

            assertInstanceOf(Entry.Put.class, scanned.get(4));
            assertEquals("gamma", str(scanned.get(4).key()));
        }
    }

    /**
     * CG-3: Delete entries through IndexRangeIterator v2 path with block caching.
     */
    // @spec F08.R5,R15 — range scan handles delete entries correctly
    @Test
    void testRangeScanWithDeleteEntries(@TempDir Path dir) throws IOException {
        List<Entry> entries = List.of(put("aaa", "one", 1), delete("bbb", 2),
                put("ccc", "three", 3), delete("ddd", 4), put("eee", "five", 5));
        Path path = writeV2(dir, "range-mixed.sst", entries);

        try (TrieSSTableReader r = openV2(path)) {
            Iterator<Entry> iter = r.scan(seg("bbb"), seg("eee"));
            List<Entry> scanned = new ArrayList<>();
            while (iter.hasNext()) {
                scanned.add(iter.next());
            }

            // Range [bbb, eee) should include bbb, ccc, ddd
            assertEquals(3, scanned.size());

            assertInstanceOf(Entry.Delete.class, scanned.get(0));
            assertEquals("bbb", str(scanned.get(0).key()));

            assertInstanceOf(Entry.Put.class, scanned.get(1));
            assertEquals("ccc", str(scanned.get(1).key()));

            assertInstanceOf(Entry.Delete.class, scanned.get(2));
            assertEquals("ddd", str(scanned.get(2).key()));

            // Verify Delete entries have correct sequence numbers
            assertEquals(new SequenceNumber(2), scanned.get(0).sequenceNumber());
            assertEquals(new SequenceNumber(4), scanned.get(2).sequenceNumber());
        }
    }

    // ---- CG-4: Value and sequence number verification ----

    /**
     * CG-4: Streaming scan must return correct values and sequence numbers, not just keys.
     *
     * <p>
     * Existing test only verifies keys via mismatch(). A misaligned decode could produce correct
     * keys but wrong values. This test cross-checks all three fields.
     */
    // @spec F08.R4,R14 — scan preserves values and sequence numbers per entry
    @Test
    void testStreamingScanVerifiesValuesAndSequenceNumbers(@TempDir Path dir) throws IOException {
        List<Entry> entries = multiBlockEntries(200);
        Path path = writeV2(dir, "full-verify.sst", entries);

        try (TrieSSTableReader r = openV2(path)) {
            Iterator<Entry> iter = r.scan();
            for (Entry expected : entries) {
                assertTrue(iter.hasNext());
                Entry scanned = iter.next();

                // Key
                assertEquals(-1L, expected.key().mismatch(scanned.key()), "key must match");

                // Sequence number
                assertEquals(expected.sequenceNumber(), scanned.sequenceNumber(),
                        "sequence number must match for key " + str(expected.key()));

                // Value (both are Put entries)
                assertInstanceOf(Entry.Put.class, scanned);
                Entry.Put expectedPut = (Entry.Put) expected;
                Entry.Put scannedPut = (Entry.Put) scanned;
                assertEquals(-1L, expectedPut.value().mismatch(scannedPut.value()),
                        "value must match for key " + str(expected.key()));
            }
            assertFalse(iter.hasNext());
        }
    }

    // ---- Additional coverage: multiple independent iterators ----

    /**
     * Two independent scan() iterators on the same eager v2 reader must produce identical results
     * without interfering with each other.
     */
    // @spec F08.R26 — multiple independent iterators maintain separate block state
    @Test
    void testMultipleIndependentScanIterators(@TempDir Path dir) throws IOException {
        List<Entry> entries = multiBlockEntries(100);
        Path path = writeV2(dir, "multi-iter.sst", entries);

        try (TrieSSTableReader r = openV2(path)) {
            Iterator<Entry> iter1 = r.scan();
            Iterator<Entry> iter2 = r.scan();

            // Interleave reads from both iterators
            List<String> keys1 = new ArrayList<>();
            List<String> keys2 = new ArrayList<>();

            while (iter1.hasNext() || iter2.hasNext()) {
                if (iter1.hasNext()) {
                    keys1.add(str(iter1.next().key()));
                }
                if (iter2.hasNext()) {
                    keys2.add(str(iter2.next().key()));
                }
            }

            assertEquals(100, keys1.size(), "first iterator must return all entries");
            assertEquals(100, keys2.size(), "second iterator must return all entries");
            assertEquals(keys1, keys2, "both iterators must produce identical results");
        }
    }
}
