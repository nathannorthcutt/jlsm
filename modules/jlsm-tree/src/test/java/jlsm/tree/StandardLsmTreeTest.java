package jlsm.tree;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.wal.local.LocalWriteAheadLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StandardLsmTreeTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private StandardLsmTree openTree(long flushThresholdBytes) throws IOException {
        return StandardLsmTree.builder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(flushThresholdBytes)
                .build();
    }

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String str(MemorySegment m) {
        return new String(m.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private static List<Entry> drain(Iterator<Entry> it) {
        List<Entry> result = new ArrayList<>();
        it.forEachRemaining(result::add);
        return result;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void putThenGetReturnsValue() throws IOException {
        try (StandardLsmTree tree = openTree(Long.MAX_VALUE)) {
            tree.put(seg("hello"), seg("world"));
            Optional<MemorySegment> result = tree.get(seg("hello"));
            assertTrue(result.isPresent());
            assertEquals("world", str(result.get()));
        }
    }

    @Test
    void getMissingKeyReturnsEmpty() throws IOException {
        try (StandardLsmTree tree = openTree(Long.MAX_VALUE)) {
            Optional<MemorySegment> result = tree.get(seg("missing"));
            assertFalse(result.isPresent());
        }
    }

    @Test
    void deletedKeyReturnsEmpty() throws IOException {
        try (StandardLsmTree tree = openTree(Long.MAX_VALUE)) {
            tree.put(seg("key"), seg("value"));
            tree.delete(seg("key"));
            Optional<MemorySegment> result = tree.get(seg("key"));
            assertFalse(result.isPresent());
        }
    }

    @Test
    void overwriteReturnsNewestValue() throws IOException {
        try (StandardLsmTree tree = openTree(Long.MAX_VALUE)) {
            tree.put(seg("k"), seg("v1"));
            tree.put(seg("k"), seg("v2"));
            Optional<MemorySegment> result = tree.get(seg("k"));
            assertTrue(result.isPresent());
            assertEquals("v2", str(result.get()));
        }
    }

    @Test
    void getAfterFlushReadsFromSSTable() throws IOException {
        // Force flush by using a tiny threshold (1 byte — any write triggers flush)
        try (StandardLsmTree tree = openTree(1L)) {
            tree.put(seg("alpha"), seg("beta"));
            // Another write to trigger flush if threshold wasn't already exceeded
            tree.put(seg("gamma"), seg("delta"));

            Optional<MemorySegment> result = tree.get(seg("alpha"));
            assertTrue(result.isPresent(), "alpha should still be readable after flush");
            assertEquals("beta", str(result.get()));
        }
    }

    @Test
    void fullScanReturnsAllEntriesOrdered() throws IOException {
        try (StandardLsmTree tree = openTree(Long.MAX_VALUE)) {
            tree.put(seg("c"), seg("C"));
            tree.put(seg("a"), seg("A"));
            tree.put(seg("b"), seg("B"));

            List<Entry> entries = drain(tree.scan());
            assertEquals(3, entries.size());
            assertEquals("a", str(entries.get(0).key()));
            assertEquals("b", str(entries.get(1).key()));
            assertEquals("c", str(entries.get(2).key()));
        }
    }

    @Test
    void rangeScanReturnsHalfOpenSubset() throws IOException {
        try (StandardLsmTree tree = openTree(Long.MAX_VALUE)) {
            tree.put(seg("a"), seg("A"));
            tree.put(seg("b"), seg("B"));
            tree.put(seg("c"), seg("C"));
            tree.put(seg("d"), seg("D"));

            List<Entry> entries = drain(tree.scan(seg("b"), seg("d")));
            assertEquals(2, entries.size());
            assertEquals("b", str(entries.get(0).key()));
            assertEquals("c", str(entries.get(1).key()));
        }
    }

    @Test
    void scanAfterFlushMergesBothSources() throws IOException {
        // Flush after writing first two keys; then write more into MemTable
        try (StandardLsmTree tree = openTree(1L)) {
            tree.put(seg("a"), seg("A"));
            tree.put(seg("c"), seg("C"));
            // Force the tree into a state where flushed SSTables exist,
            // then write more entries that stay in MemTable:
            tree.put(seg("b"), seg("B"));

            List<Entry> entries = drain(tree.scan());
            List<String> keys = entries.stream()
                    .map(e -> str(e.key()))
                    .toList();

            // All three keys must appear in order; exact split between MemTable and SSTable varies
            assertTrue(keys.containsAll(List.of("a", "b", "c")));
            // Also verify ascending order
            for (int i = 1; i < keys.size(); i++) {
                assertTrue(keys.get(i - 1).compareTo(keys.get(i)) < 0,
                        "Expected ascending order but got " + keys);
            }
        }
    }

    @Test
    void memTableDeleteHidesOlderSSTableValue() throws IOException {
        // 1) Write "key" → "value" with a large threshold (stays in MemTable)
        // 2) Manually trigger a flush by lowering threshold, then delete in MemTable
        // Use two separate trees sharing the same tempDir / WAL is separate,
        // instead we simulate it by writing and then deleting within one tree.
        try (StandardLsmTree tree = openTree(1L)) {
            // "key" written and immediately flushed
            tree.put(seg("key"), seg("value"));
            // Now delete should override the SSTable entry
            tree.delete(seg("key"));

            Optional<MemorySegment> result = tree.get(seg("key"));
            assertFalse(result.isPresent(), "Deleted key must return empty even when SSTable has a Put");
        }
    }

    @Test
    void walRecovery() throws IOException {
        // Write some data, close the tree, reopen with recoverFromWal(true)
        // and confirm the data is still readable.
        try (StandardLsmTree tree = openTree(Long.MAX_VALUE)) {
            tree.put(seg("persistent"), seg("data"));
        }

        // Reopen — WAL replay should reconstruct the MemTable
        idCounter.set(0);
        try (StandardLsmTree tree = StandardLsmTree.builder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .recoverFromWal(true)
                .build()) {

            Optional<MemorySegment> result = tree.get(seg("persistent"));
            assertTrue(result.isPresent(), "Data written before close must survive WAL recovery");
            assertEquals("data", str(result.get()));
        }
    }

    // -----------------------------------------------------------------------
    // Builder validation tests
    // -----------------------------------------------------------------------

    @Test
    void builderRequiresWal() {
        assertThrows(NullPointerException.class, () ->
                StandardLsmTree.builder()
                        .memTableFactory(ConcurrentSkipListMemTable::new)
                        .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                        .sstableReaderFactory(path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                        .idSupplier(idCounter::getAndIncrement)
                        .pathFn((id, level) -> tempDir.resolve("sst.sst"))
                        .build());
    }

    @Test
    void builderRequiresMemTableFactory() {
        assertThrows(NullPointerException.class, () ->
                StandardLsmTree.builder()
                        .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                        .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                        .sstableReaderFactory(path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                        .idSupplier(idCounter::getAndIncrement)
                        .pathFn((id, level) -> tempDir.resolve("sst.sst"))
                        .build());
    }

    @Test
    void builderRequiresSstableWriterFactory() {
        assertThrows(NullPointerException.class, () ->
                StandardLsmTree.builder()
                        .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                        .memTableFactory(ConcurrentSkipListMemTable::new)
                        .sstableReaderFactory(path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                        .idSupplier(idCounter::getAndIncrement)
                        .pathFn((id, level) -> tempDir.resolve("sst.sst"))
                        .build());
    }

    @Test
    void builderRequiresSstableReaderFactory() {
        assertThrows(NullPointerException.class, () ->
                StandardLsmTree.builder()
                        .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                        .memTableFactory(ConcurrentSkipListMemTable::new)
                        .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                        .idSupplier(idCounter::getAndIncrement)
                        .pathFn((id, level) -> tempDir.resolve("sst.sst"))
                        .build());
    }

    @Test
    void builderRequiresIdSupplier() {
        assertThrows(NullPointerException.class, () ->
                StandardLsmTree.builder()
                        .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                        .memTableFactory(ConcurrentSkipListMemTable::new)
                        .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                        .sstableReaderFactory(path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                        .pathFn((id, level) -> tempDir.resolve("sst.sst"))
                        .build());
    }

    @Test
    void builderRequiresPathFn() {
        assertThrows(NullPointerException.class, () ->
                StandardLsmTree.builder()
                        .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                        .memTableFactory(ConcurrentSkipListMemTable::new)
                        .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                        .sstableReaderFactory(path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                        .idSupplier(idCounter::getAndIncrement)
                        .build());
    }

    @Test
    void compactionRunsAfterFlushAndAllKeysRemainReadable() throws IOException {
        // Use a tiny flush threshold so every write triggers a flush and then compaction.
        // Write enough entries to produce multiple L0 files, which the SpookyCompactor will
        // bootstrap-compact into L1. After all writes, verify every key is still readable.
        try (StandardLsmTree tree = openTree(1L)) {
            int keyCount = 20;
            for (int i = 0; i < keyCount; i++) {
                tree.put(seg("key-" + i), seg("val-" + i));
            }

            for (int i = 0; i < keyCount; i++) {
                Optional<MemorySegment> result = tree.get(seg("key-" + i));
                assertTrue(result.isPresent(), "key-" + i + " should be readable after compaction");
                assertEquals("val-" + i, str(result.get()),
                        "key-" + i + " should have correct value after compaction");
            }
        }
    }
}
