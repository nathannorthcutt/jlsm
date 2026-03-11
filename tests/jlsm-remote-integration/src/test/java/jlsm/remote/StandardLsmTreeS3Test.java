package jlsm.remote;

import jlsm.bloom.PassthroughBloomFilter;
import jlsm.core.model.Entry;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.tree.StandardLsmTree;
import jlsm.wal.remote.RemoteWriteAheadLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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

/**
 * Integration tests for {@link StandardLsmTree} with all storage components backed by S3. Uses
 * {@link PassthroughBloomFilter} to avoid alignment issues with the deserialized bloom filter; uses
 * eager {@link TrieSSTableReader#open} to avoid lazy-channel thread-safety concerns.
 */
@ExtendWith(S3Fixture.class)
class StandardLsmTreeS3Test {

    private Path dir;
    private final AtomicLong idCounter = new AtomicLong(0);

    @BeforeEach
    void setup(S3Fixture fixture) {
        dir = fixture.newTestDirectory();
        idCounter.set(0);
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

    private StandardLsmTree openTree(long flushThresholdBytes) throws IOException {
        return StandardLsmTree.builder().wal(RemoteWriteAheadLog.builder().directory(dir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path,
                        PassthroughBloomFilter.factory()))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, PassthroughBloomFilter.deserializer()))
                .bloomDeserializer(PassthroughBloomFilter.deserializer())
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> dir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(flushThresholdBytes).build();
    }

    // ---- basic put/get ----

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
            assertFalse(tree.get(seg("missing")).isPresent());
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

    // ---- delete / tombstone ----

    @Test
    void deletedKeyReturnsEmpty() throws IOException {
        try (StandardLsmTree tree = openTree(Long.MAX_VALUE)) {
            tree.put(seg("key"), seg("value"));
            tree.delete(seg("key"));
            assertFalse(tree.get(seg("key")).isPresent());
        }
    }

    // ---- flush to SSTable ----

    @Test
    void getAfterFlushReadsFromSSTable() throws IOException {
        // tiny threshold → flush on second write
        try (StandardLsmTree tree = openTree(1L)) {
            tree.put(seg("alpha"), seg("beta"));
            tree.put(seg("gamma"), seg("delta"));

            Optional<MemorySegment> result = tree.get(seg("alpha"));
            assertTrue(result.isPresent(), "alpha should be readable after flush");
            assertEquals("beta", str(result.get()));
        }
    }

    @Test
    void deleteTombstoneHidesOlderSSTableValue() throws IOException {
        try (StandardLsmTree tree = openTree(1L)) {
            tree.put(seg("key"), seg("value")); // triggers flush
            tree.delete(seg("key"));
            assertFalse(tree.get(seg("key")).isPresent(),
                    "deleted key must return empty even when SSTable has a Put");
        }
    }

    // ---- WAL recovery ----

    @Test
    void walRecovery() throws IOException {
        try (StandardLsmTree tree = openTree(Long.MAX_VALUE)) {
            tree.put(seg("persistent"), seg("data"));
        }

        // Reopen with WAL recovery
        try (StandardLsmTree tree = StandardLsmTree.builder()
                .wal(RemoteWriteAheadLog.builder().directory(dir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path,
                        PassthroughBloomFilter.factory()))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, PassthroughBloomFilter.deserializer()))
                .bloomDeserializer(PassthroughBloomFilter.deserializer())
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> dir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .recoverFromWal(true).build()) {

            Optional<MemorySegment> result = tree.get(seg("persistent"));
            assertTrue(result.isPresent(), "Data written before close must survive WAL recovery");
            assertEquals("data", str(result.get()));
        }
    }

    // ---- scan ----

    @Test
    void rangeScanReturnsCorrectSubset() throws IOException {
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

    // ---- compaction ----

    @Test
    void compactionKeepsAllKeysReadable() throws IOException {
        try (StandardLsmTree tree = openTree(1L)) {
            int keyCount = 10;
            for (int i = 0; i < keyCount; i++) {
                tree.put(seg("key-" + i), seg("val-" + i));
            }
            for (int i = 0; i < keyCount; i++) {
                Optional<MemorySegment> result = tree.get(seg("key-" + i));
                assertTrue(result.isPresent(), "key-" + i + " should be readable after compaction");
                assertEquals("val-" + i, str(result.get()));
            }
        }
    }

    // ---- latency baseline (informational only — always passes) ----

    @Test
    void latencyBaseline_hundredWritesWithFlush() throws IOException {
        int count = 100;
        try (StandardLsmTree tree = openTree(512L)) {
            long start = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                tree.put(seg("key-" + i), seg("value-" + i));
            }
            long elapsed = System.currentTimeMillis() - start;
            System.out.printf(
                    "[S3 LsmTree latency] %d writes (with flushes) in %d ms (%.1f ms/op)%n", count,
                    elapsed, (double) elapsed / count);
        }
    }
}
