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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SSTable v2 format with block-level compression.
 *
 * <p>
 * Covers the TrieSSTableWriter and TrieSSTableReader extensions for compression, backward
 * compatibility with v1 files, and the CompressionCodec integration.
 */
class SSTableCompressionTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private static Entry.Delete del(String key, long seq) {
        return new Entry.Delete(seg(key), new SequenceNumber(seq));
    }

    /**
     * Writes a v1 SSTable (no compression) using the original constructor.
     */
    private Path writeV1SSTable(Path dir, String name, List<Entry> entries) throws IOException {
        Path path = dir.resolve(name);
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path)) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }
        return path;
    }

    /**
     * Writes a v2 SSTable with the given compression codec.
     */
    private Path writeV2SSTable(Path dir, String name, List<Entry> entries, CompressionCodec codec)
            throws IOException {
        Path path = dir.resolve(name);
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), codec)) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }
        return path;
    }

    private List<Entry> basicEntries() {
        return List.of(put("apple", "red", 1), put("banana", "yellow", 2), del("cherry", 3),
                put("date", "brown", 4), put("elderberry", "purple", 5));
    }

    // ---- v2 with NoneCodec ----

    @Test
    void testWriteAndReadV2WithNoneCodec(@TempDir Path dir) throws IOException {
        List<Entry> entries = basicEntries();
        Path path = writeV2SSTable(dir, "none.sst", entries, CompressionCodec.none());

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none())) {
            assertEquals(5L, r.metadata().entryCount());

            // Verify point lookup
            Optional<Entry> result = r.get(seg("apple"));
            assertTrue(result.isPresent());
            assertInstanceOf(Entry.Put.class, result.get());
            assertEquals(-1L, seg("red").mismatch(((Entry.Put) result.get()).value()));
        }
    }

    // ---- v2 with DeflateCodec ----

    @Test
    void testWriteAndReadV2WithDeflateCodec(@TempDir Path dir) throws IOException {
        List<Entry> entries = basicEntries();
        Path path = writeV2SSTable(dir, "deflate.sst", entries, CompressionCodec.deflate());

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            assertEquals(5L, r.metadata().entryCount());

            // Verify all entries via full scan
            Iterator<Entry> iter = r.scan();
            List<Entry> scanned = new ArrayList<>();
            while (iter.hasNext()) {
                scanned.add(iter.next());
            }
            assertEquals(5, scanned.size());

            // Verify first entry
            assertInstanceOf(Entry.Put.class, scanned.get(0));
            assertEquals(-1L, seg("apple").mismatch(scanned.get(0).key()));
        }
    }

    // ---- Deflate produces smaller file ----

    @Test
    void testDeflateProducesSmallerFile(@TempDir Path dir) throws IOException {
        // Write many entries with repetitive values to make compression effective
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String key = "key-%05d".formatted(i);
            String value = "value-data-repeated-content-for-compression-".repeat(5) + i;
            entries.add(put(key, value, i + 1));
        }

        Path nonePath = writeV2SSTable(dir, "none.sst", entries, CompressionCodec.none());
        Path deflatePath = writeV2SSTable(dir, "deflate.sst", entries, CompressionCodec.deflate());

        long noneSize = Files.size(nonePath);
        long deflateSize = Files.size(deflatePath);
        assertTrue(deflateSize < noneSize, "deflate file (%d) should be smaller than none file (%d)"
                .formatted(deflateSize, noneSize));
    }

    // ---- Backward compatibility: v2 reader reads v1 file ----

    @Test
    void testV2ReaderReadsV1File(@TempDir Path dir) throws IOException {
        List<Entry> entries = basicEntries();
        Path v1Path = writeV1SSTable(dir, "v1.sst", entries);

        // v2 reader with codecs should still read v1 files
        try (TrieSSTableReader r = TrieSSTableReader.open(v1Path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            assertEquals(5L, r.metadata().entryCount());

            Optional<Entry> result = r.get(seg("banana"));
            assertTrue(result.isPresent());
            assertInstanceOf(Entry.Put.class, result.get());
            assertEquals(-1L, seg("yellow").mismatch(((Entry.Put) result.get()).value()));
        }
    }

    // ---- Point lookup with compression ----

    @Test
    void testV2PointLookupWithCompression(@TempDir Path dir) throws IOException {
        List<Entry> entries = basicEntries();
        Path path = writeV2SSTable(dir, "lookup.sst", entries, CompressionCodec.deflate());

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            // Existing key
            Optional<Entry> result = r.get(seg("date"));
            assertTrue(result.isPresent());
            assertEquals(-1L, seg("brown").mismatch(((Entry.Put) result.get()).value()));

            // Missing key
            assertTrue(r.get(seg("fig")).isEmpty());

            // Deleted key
            Optional<Entry> deleted = r.get(seg("cherry"));
            assertTrue(deleted.isPresent());
            assertInstanceOf(Entry.Delete.class, deleted.get());
        }
    }

    // ---- Range scan with compression ----

    @Test
    void testV2RangeScanWithCompression(@TempDir Path dir) throws IOException {
        List<Entry> entries = basicEntries();
        Path path = writeV2SSTable(dir, "range.sst", entries, CompressionCodec.deflate());

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            // Range [banana, elderberry) should return banana, cherry, date
            Iterator<Entry> iter = r.scan(seg("banana"), seg("elderberry"));
            List<Entry> results = new ArrayList<>();
            while (iter.hasNext()) {
                results.add(iter.next());
            }
            assertEquals(3, results.size());
            assertEquals(-1L, seg("banana").mismatch(results.get(0).key()));
            assertEquals(-1L, seg("cherry").mismatch(results.get(1).key()));
            assertEquals(-1L, seg("date").mismatch(results.get(2).key()));
        }
    }

    // ---- Full scan with compression ----

    @Test
    void testV2FullScanWithCompression(@TempDir Path dir) throws IOException {
        List<Entry> entries = basicEntries();
        Path path = writeV2SSTable(dir, "scan.sst", entries, CompressionCodec.deflate());

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            Iterator<Entry> iter = r.scan();
            List<String> keys = new ArrayList<>();
            while (iter.hasNext()) {
                keys.add(new String(
                        iter.next().key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)));
            }
            assertEquals(List.of("apple", "banana", "cherry", "date", "elderberry"), keys);
        }
    }

    // ---- Incompressible block stored as NONE ----

    @Test
    void testIncompressibleBlockStoredAsNone(@TempDir Path dir) throws IOException {
        // Random data is incompressible — blocks should fall back to NONE codec
        Random rng = new Random(42);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String key = "key-%05d".formatted(i);
            byte[] randomValue = new byte[200];
            rng.nextBytes(randomValue);
            entries.add(new Entry.Put(seg(key), MemorySegment.ofArray(randomValue),
                    new SequenceNumber(i + 1)));
        }

        Path path = writeV2SSTable(dir, "random.sst", entries, CompressionCodec.deflate());

        // Should still be readable — incompressible blocks stored as NONE
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            assertEquals(50, r.metadata().entryCount());
            Iterator<Entry> iter = r.scan();
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            assertEquals(50, count);
        }
    }

    // ---- Unknown codec ID → IOException ----

    @Test
    void testUnknownCodecIdThrowsIOException(@TempDir Path dir) throws IOException {
        List<Entry> entries = basicEntries();
        // Write with deflate codec
        Path path = writeV2SSTable(dir, "deflate.sst", entries, CompressionCodec.deflate());

        // Open with only NONE codec — deflate blocks should trigger IOException
        assertThrows(IOException.class, () -> {
            try (TrieSSTableReader r = TrieSSTableReader.open(path,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.none())) {
                // Force reading — might be lazy
                r.scan();
            }
        });
    }

    // ---- Multiple blocks with compression ----

    @Test
    void testMultipleBlocksWithCompression(@TempDir Path dir) throws IOException {
        // Write enough entries to span multiple 4KB blocks
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String key = "key-%06d".formatted(i);
            String value = "value-content-for-block-spanning-test-%06d".formatted(i);
            entries.add(put(key, value, i + 1));
        }

        Path path = writeV2SSTable(dir, "multiblock.sst", entries, CompressionCodec.deflate());

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            assertEquals(500, r.metadata().entryCount());

            // Verify scan returns all entries in order
            Iterator<Entry> iter = r.scan();
            int count = 0;
            String prevKey = "";
            while (iter.hasNext()) {
                Entry e = iter.next();
                String key = new String(e.key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
                assertTrue(key.compareTo(prevKey) > 0,
                        "keys must be ascending: '%s' after '%s'".formatted(key, prevKey));
                prevKey = key;
                count++;
            }
            assertEquals(500, count);

            // Verify point lookups on entries from different blocks
            assertTrue(r.get(seg("key-000000")).isPresent());
            assertTrue(r.get(seg("key-000250")).isPresent());
            assertTrue(r.get(seg("key-000499")).isPresent());
            assertTrue(r.get(seg("key-999999")).isEmpty());
        }
    }

    // ---- Lazy reader with compression ----

    @Test
    void testLazyReaderWithCompression(@TempDir Path dir) throws IOException {
        List<Entry> entries = basicEntries();
        Path path = writeV2SSTable(dir, "lazy.sst", entries, CompressionCodec.deflate());

        try (TrieSSTableReader r = TrieSSTableReader.openLazy(path,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                CompressionCodec.deflate())) {
            assertEquals(5L, r.metadata().entryCount());

            // Point lookup
            Optional<Entry> result = r.get(seg("elderberry"));
            assertTrue(result.isPresent());
            assertEquals(-1L, seg("purple").mismatch(((Entry.Put) result.get()).value()));

            // Full scan
            Iterator<Entry> iter = r.scan();
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            assertEquals(5, count);
        }
    }
}
