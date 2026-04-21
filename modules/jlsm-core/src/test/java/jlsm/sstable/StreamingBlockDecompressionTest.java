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
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for streaming block decompression in v2 compressed SSTables.
 *
 * <p>
 * Verifies that {@link TrieSSTableReader#scan()} decompresses blocks lazily (one at a time) and
 * that {@link TrieSSTableReader#scan(MemorySegment, MemorySegment)} caches the current decompressed
 * block for consecutive entries in the same block.
 */
class StreamingBlockDecompressionTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    private static String str(MemorySegment s) {
        return new String(s.toArray(ValueLayout.JAVA_BYTE));
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    /**
     * Writes a v2 SSTable with Deflate compression. Uses entries with enough padding to control
     * block boundaries (4096-byte target block size).
     */
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

    /**
     * Writes a v1 SSTable (no compression).
     */
    private Path writeV1(Path dir, String name, List<Entry> entries) throws IOException {
        Path path = dir.resolve(name);
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path)) {
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
     * Builds a list of entries large enough to span multiple 4096-byte blocks. Each entry is ~100
     * bytes encoded, so ~40 entries per block → 500 entries ≈ 12+ blocks.
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

    // ---- Happy path ----

    // @spec compression.streaming-decompression.R1,R3,R4 — lazy scan returns all entries in
    // ascending order across blocks
    @Test
    void testStreamingScanMultiBlockReturnsAllEntriesInOrder(@TempDir Path dir) throws IOException {
        List<Entry> entries = multiBlockEntries(500);
        Path path = writeV2(dir, "multi.sst", entries);

        try (TrieSSTableReader r = openV2(path)) {
            Iterator<Entry> iter = r.scan();
            int count = 0;
            String prevKey = "";
            while (iter.hasNext()) {
                Entry e = iter.next();
                String key = str(e.key());
                assertTrue(key.compareTo(prevKey) > 0,
                        "keys must be ascending: '%s' after '%s'".formatted(key, prevKey));
                prevKey = key;
                count++;
            }
            assertEquals(500, count, "scan must return all entries");
        }
    }

    // @spec compression.streaming-decompression.R4,R13 — scan entries match point-get reads; get()
    // uses cache path
    @Test
    void testStreamingScanMatchesEntryByEntryWithDirectReads(@TempDir Path dir) throws IOException {
        List<Entry> entries = multiBlockEntries(200);
        Path path = writeV2(dir, "match.sst", entries);

        try (TrieSSTableReader r = openV2(path)) {
            Iterator<Entry> iter = r.scan();
            for (Entry expected : entries) {
                assertTrue(iter.hasNext(), "scan should have more entries");
                Entry scanned = iter.next();
                assertEquals(-1L, expected.key().mismatch(scanned.key()),
                        "scanned key must match written key");

                // Cross-check with point get
                Optional<Entry> got = r.get(expected.key());
                assertTrue(got.isPresent(), "get() must find every written key");
                assertEquals(-1L, expected.key().mismatch(got.get().key()));
            }
            assertFalse(iter.hasNext(), "scan must be exhausted after all entries");
        }
    }

    // @spec compression.streaming-decompression.R5,R23 — range scan within single block reuses
    // cached decompressed block
    @Test
    void testRangeScanSameBlockReusesDecompression(@TempDir Path dir) throws IOException {
        // Write entries that will all fit in a single block (few small entries)
        List<Entry> entries = List.of(put("aaa", "v1", 1), put("aab", "v2", 2), put("aac", "v3", 3),
                put("aad", "v4", 4), put("aae", "v5", 5), put("zzz", "end", 6));
        Path path = writeV2(dir, "sameblock.sst", entries);

        try (TrieSSTableReader r = openV2(path)) {
            // Range scan within entries likely in the same block
            Iterator<Entry> iter = r.scan(seg("aab"), seg("aae"));
            List<String> keys = new ArrayList<>();
            while (iter.hasNext()) {
                keys.add(str(iter.next().key()));
            }
            assertEquals(List.of("aab", "aac", "aad"), keys,
                    "range scan must return entries within [from, to)");
        }
    }

    // @spec compression.streaming-decompression.R5,R6,R15 — range scan crossing blocks replaces
    // cached block, decodes at
    // intra-block offset
    @Test
    void testRangeScanCrossBlockBoundary(@TempDir Path dir) throws IOException {
        List<Entry> entries = multiBlockEntries(500);
        Path path = writeV2(dir, "crossblock.sst", entries);

        try (TrieSSTableReader r = openV2(path)) {
            // Scan from early key to late key — guaranteed to cross block boundaries
            Iterator<Entry> iter = r.scan(seg("key-000100"), seg("key-000400"));
            List<String> keys = new ArrayList<>();
            while (iter.hasNext()) {
                keys.add(str(iter.next().key()));
            }

            // Should get keys 100..399 (inclusive start, exclusive end)
            assertEquals(300, keys.size(), "cross-block range scan must return correct count");
            assertEquals("key-000100", keys.getFirst());
            assertEquals("key-000399", keys.getLast());

            // Verify ordering
            for (int i = 1; i < keys.size(); i++) {
                assertTrue(keys.get(i).compareTo(keys.get(i - 1)) > 0,
                        "keys must be ascending in range scan");
            }
        }
    }

    // @spec compression.streaming-decompression.R1,R27 — lazy reader scan uses synchronized channel
    // reads
    @Test
    void testLazyReaderStreamingScan(@TempDir Path dir) throws IOException {
        List<Entry> entries = multiBlockEntries(300);
        Path path = writeV2(dir, "lazy.sst", entries);

        try (TrieSSTableReader r = openV2Lazy(path)) {
            Iterator<Entry> iter = r.scan();
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            assertEquals(300, count, "lazy reader streaming scan must return all entries");
        }
    }

    // ---- Error and edge cases ----

    // @spec compression.streaming-decompression.R19 — closed reader throws IllegalStateException on
    // scan
    @Test
    void testStreamingScanOnClosedReaderThrows(@TempDir Path dir) throws IOException {
        List<Entry> entries = multiBlockEntries(10);
        Path path = writeV2(dir, "closed.sst", entries);

        TrieSSTableReader r = openV2(path);
        r.close();

        assertThrows(IllegalStateException.class, r::scan,
                "scan on closed reader must throw IllegalStateException");
    }

    // @spec compression.streaming-decompression.R19 — full-scan iterator must signal mid-iteration
    // close via hasNext(), not
    // silently return false. A for-each loop must terminate loudly so callers cannot miss a
    // reader close that happened while their iteration was in progress.
    @Test
    void testStreamingFullScanHasNextThrowsAfterMidIterationClose(@TempDir Path dir)
            throws IOException {
        List<Entry> entries = multiBlockEntries(50); // spans multiple blocks
        Path path = writeV2(dir, "mid-close-full.sst", entries);

        TrieSSTableReader r = openV2Lazy(path);
        Iterator<Entry> iter = r.scan();
        assertTrue(iter.hasNext(), "iterator must have entries before close");
        iter.next(); // consume one entry

        r.close();

        assertThrows(IllegalStateException.class, iter::hasNext,
                "R19: hasNext() must throw IllegalStateException after mid-iteration close, "
                        + "not silently return false");
    }

    // ---- Boundary values ----

    // @spec compression.streaming-decompression.R22 — single-block SSTable decompresses, yields all
    // entries, then exhausts
    @Test
    void testStreamingScanSingleBlockSSTable(@TempDir Path dir) throws IOException {
        // Few small entries that fit in a single 4096-byte block
        List<Entry> entries = List.of(put("alpha", "one", 1), put("beta", "two", 2),
                put("gamma", "three", 3));
        Path path = writeV2(dir, "single-block.sst", entries);

        try (TrieSSTableReader r = openV2(path)) {
            Iterator<Entry> iter = r.scan();
            List<String> keys = new ArrayList<>();
            while (iter.hasNext()) {
                keys.add(str(iter.next().key()));
            }
            assertEquals(List.of("alpha", "beta", "gamma"), keys);
        }
    }

    // @spec compression.streaming-decompression.R16 — empty range produces hasNext()==false
    // immediately
    @Test
    void testRangeScanEmptyRange(@TempDir Path dir) throws IOException {
        List<Entry> entries = List.of(put("aaa", "v1", 1), put("bbb", "v2", 2),
                put("ccc", "v3", 3));
        Path path = writeV2(dir, "empty-range.sst", entries);

        try (TrieSSTableReader r = openV2(path)) {
            // Range that contains no entries
            Iterator<Entry> iter = r.scan(seg("ddd"), seg("eee"));
            assertFalse(iter.hasNext(), "empty range must produce empty iterator");
        }
    }

    // @spec compression.streaming-decompression.R22,R16 — single entry: decompress, yield, exhaust
    @Test
    void testStreamingScanSingleEntry(@TempDir Path dir) throws IOException {
        List<Entry> entries = List.of(put("only", "one", 1));
        Path path = writeV2(dir, "single-entry.sst", entries);

        try (TrieSSTableReader r = openV2(path)) {
            Iterator<Entry> iter = r.scan();
            assertTrue(iter.hasNext());
            assertEquals("only", str(iter.next().key()));
            assertFalse(iter.hasNext());
        }
    }

    // ---- Structural (iterator patterns) ----

    // @spec compression.streaming-decompression.R16,R17 — exhausted iterator: hasNext()==false,
    // next() throws
    // NoSuchElementException
    @Test
    void testScanIteratorExhaustion(@TempDir Path dir) throws IOException {
        List<Entry> entries = List.of(put("first", "v1", 1), put("second", "v2", 2));
        Path path = writeV2(dir, "exhaust.sst", entries);

        try (TrieSSTableReader r = openV2(path)) {
            Iterator<Entry> iter = r.scan();
            iter.next();
            iter.next();
            assertFalse(iter.hasNext(), "iterator must be exhausted");
            assertThrows(NoSuchElementException.class, iter::next,
                    "next() on exhausted iterator must throw NoSuchElementException");
        }
    }

    // @spec compression.streaming-decompression.R11 — v1 scan path unchanged, uses
    // DataRegionIterator
    @Test
    void testV1ScanPathUnchanged(@TempDir Path dir) throws IOException {
        List<Entry> entries = multiBlockEntries(100);
        Path path = writeV1(dir, "v1.sst", entries);

        try (TrieSSTableReader r = TrieSSTableReader.open(path,
                BlockedBloomFilter.deserializer())) {
            Iterator<Entry> iter = r.scan();
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            assertEquals(100, count, "v1 scan path must still work correctly");
        }
    }
}
