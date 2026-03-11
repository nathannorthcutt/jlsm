package jlsm.tree;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.io.MemorySerializer;
import jlsm.core.model.Entry;
import jlsm.core.tree.TypedLsmTree;
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

class TypedStandardLsmTreeTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    // -----------------------------------------------------------------------
    // Serializer helpers
    // -----------------------------------------------------------------------

    private static final MemorySerializer<String> STRING_SERIALIZER = new MemorySerializer<>() {
        @Override
        public MemorySegment serialize(String value) {
            return MemorySegment.ofArray(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String deserialize(MemorySegment segment) {
            return new String(segment.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }
    };

    // -----------------------------------------------------------------------
    // Tree factory helpers
    // -----------------------------------------------------------------------

    private TypedLsmTree.StringKeyed<String> openStringKeyed(long flushThresholdBytes)
            throws IOException {
        return TypedStandardLsmTree.<String>stringKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(flushThresholdBytes).valueSerializer(STRING_SERIALIZER)
                .build();
    }

    private TypedLsmTree.LongKeyed<String> openLongKeyed(long flushThresholdBytes)
            throws IOException {
        return TypedStandardLsmTree.<String>longKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(flushThresholdBytes).valueSerializer(STRING_SERIALIZER)
                .build();
    }

    private TypedLsmTree.SegmentKeyed<String> openSegmentKeyed(long flushThresholdBytes)
            throws IOException {
        return TypedStandardLsmTree.<String>segmentKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(flushThresholdBytes).valueSerializer(STRING_SERIALIZER)
                .build();
    }

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String str(MemorySegment m) {
        return new String(m.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private static long decodeLong(MemorySegment m) {
        byte[] bytes = m.toArray(ValueLayout.JAVA_BYTE);
        long unsigned = 0L;
        for (byte b : bytes) {
            unsigned = (unsigned << 8) | (b & 0xFFL);
        }
        return unsigned ^ Long.MIN_VALUE;
    }

    private static List<Entry> drain(Iterator<Entry> it) {
        List<Entry> result = new ArrayList<>();
        it.forEachRemaining(result::add);
        return result;
    }

    // -----------------------------------------------------------------------
    // StringKeyed tests
    // -----------------------------------------------------------------------

    @Test
    void stringKeyed_putThenGetReturnsValue() throws IOException {
        try (TypedLsmTree.StringKeyed<String> tree = openStringKeyed(Long.MAX_VALUE)) {
            tree.put("hello", "world");
            Optional<String> result = tree.get("hello");
            assertTrue(result.isPresent());
            assertEquals("world", result.get());
        }
    }

    @Test
    void stringKeyed_getMissingKeyReturnsEmpty() throws IOException {
        try (TypedLsmTree.StringKeyed<String> tree = openStringKeyed(Long.MAX_VALUE)) {
            assertFalse(tree.get("missing").isPresent());
        }
    }

    @Test
    void stringKeyed_deletedKeyReturnsEmpty() throws IOException {
        try (TypedLsmTree.StringKeyed<String> tree = openStringKeyed(Long.MAX_VALUE)) {
            tree.put("key", "value");
            tree.delete("key");
            assertFalse(tree.get("key").isPresent());
        }
    }

    @Test
    void stringKeyed_overwriteReturnsNewestValue() throws IOException {
        try (TypedLsmTree.StringKeyed<String> tree = openStringKeyed(Long.MAX_VALUE)) {
            tree.put("k", "v1");
            tree.put("k", "v2");
            Optional<String> result = tree.get("k");
            assertTrue(result.isPresent());
            assertEquals("v2", result.get());
        }
    }

    @Test
    void stringKeyed_getAfterFlushReadsFromSSTable() throws IOException {
        try (TypedLsmTree.StringKeyed<String> tree = openStringKeyed(1L)) {
            tree.put("alpha", "beta");
            tree.put("gamma", "delta");
            Optional<String> result = tree.get("alpha");
            assertTrue(result.isPresent(), "alpha should still be readable after flush");
            assertEquals("beta", result.get());
        }
    }

    @Test
    void stringKeyed_rangeScanReturnsHalfOpenSubset() throws IOException {
        try (TypedLsmTree.StringKeyed<String> tree = openStringKeyed(Long.MAX_VALUE)) {
            tree.put("a", "A");
            tree.put("b", "B");
            tree.put("c", "C");
            tree.put("d", "D");

            List<Entry> entries = drain(tree.scan("b", "d"));
            assertEquals(2, entries.size());
            assertEquals("b", str(entries.get(0).key()));
            assertEquals("c", str(entries.get(1).key()));
        }
    }

    @Test
    void stringKeyed_fullScanReturnsAllEntriesAscending() throws IOException {
        try (TypedLsmTree.StringKeyed<String> tree = openStringKeyed(Long.MAX_VALUE)) {
            tree.put("c", "C");
            tree.put("a", "A");
            tree.put("b", "B");

            List<Entry> entries = drain(tree.scan());
            assertEquals(3, entries.size());
            assertEquals("a", str(entries.get(0).key()));
            assertEquals("b", str(entries.get(1).key()));
            assertEquals("c", str(entries.get(2).key()));
        }
    }

    // -----------------------------------------------------------------------
    // StringKeyed builder null-guard tests
    // -----------------------------------------------------------------------

    @Test
    void stringKeyed_builderRequiresWal() {
        assertThrows(NullPointerException.class, () -> TypedStandardLsmTree
                .<String>stringKeyedBuilder().memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst.sst"))
                .valueSerializer(STRING_SERIALIZER).build());
    }

    @Test
    void stringKeyed_builderRequiresMemTableFactory() {
        assertThrows(NullPointerException.class, () -> TypedStandardLsmTree
                .<String>stringKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst.sst"))
                .valueSerializer(STRING_SERIALIZER).build());
    }

    @Test
    void stringKeyed_builderRequiresSstableWriterFactory() {
        assertThrows(NullPointerException.class, () -> TypedStandardLsmTree
                .<String>stringKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst.sst"))
                .valueSerializer(STRING_SERIALIZER).build());
    }

    @Test
    void stringKeyed_builderRequiresSstableReaderFactory() {
        assertThrows(NullPointerException.class, () -> TypedStandardLsmTree
                .<String>stringKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst.sst"))
                .valueSerializer(STRING_SERIALIZER).build());
    }

    @Test
    void stringKeyed_builderRequiresIdSupplier() {
        assertThrows(NullPointerException.class, () -> TypedStandardLsmTree
                .<String>stringKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .pathFn((id, level) -> tempDir.resolve("sst.sst"))
                .valueSerializer(STRING_SERIALIZER).build());
    }

    @Test
    void stringKeyed_builderRequiresPathFn() {
        assertThrows(NullPointerException.class, () -> TypedStandardLsmTree
                .<String>stringKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement).valueSerializer(STRING_SERIALIZER).build());
    }

    @Test
    void stringKeyed_builderRequiresValueSerializer() {
        assertThrows(NullPointerException.class, () -> TypedStandardLsmTree
                .<String>stringKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst.sst")).build());
    }

    // -----------------------------------------------------------------------
    // LongKeyed tests
    // -----------------------------------------------------------------------

    @Test
    void longKeyed_putThenGetReturnsValue() throws IOException {
        try (TypedLsmTree.LongKeyed<String> tree = openLongKeyed(Long.MAX_VALUE)) {
            tree.put(42L, "forty-two");
            Optional<String> result = tree.get(42L);
            assertTrue(result.isPresent());
            assertEquals("forty-two", result.get());
        }
    }

    @Test
    void longKeyed_getMissingKeyReturnsEmpty() throws IOException {
        try (TypedLsmTree.LongKeyed<String> tree = openLongKeyed(Long.MAX_VALUE)) {
            assertFalse(tree.get(99L).isPresent());
        }
    }

    @Test
    void longKeyed_deletedKeyReturnsEmpty() throws IOException {
        try (TypedLsmTree.LongKeyed<String> tree = openLongKeyed(Long.MAX_VALUE)) {
            tree.put(7L, "seven");
            tree.delete(7L);
            assertFalse(tree.get(7L).isPresent());
        }
    }

    @Test
    void longKeyed_rangeScanRespectsNumericOrder() throws IOException {
        try (TypedLsmTree.LongKeyed<String> tree = openLongKeyed(Long.MAX_VALUE)) {
            tree.put(0L, "zero");
            tree.put(-1L, "minus-one");
            tree.put(1L, "one");
            tree.put(Long.MIN_VALUE, "min");
            tree.put(Long.MAX_VALUE, "max");

            // scan [-1, 2) → should yield -1, 0, 1 in numeric order
            List<Entry> entries = drain(tree.scan(-1L, 2L));
            assertEquals(3, entries.size());
            assertEquals(-1L, decodeLong(entries.get(0).key()));
            assertEquals(0L, decodeLong(entries.get(1).key()));
            assertEquals(1L, decodeLong(entries.get(2).key()));
        }
    }

    @Test
    void longKeyed_negativeKeysSortBeforePositive() throws IOException {
        try (TypedLsmTree.LongKeyed<String> tree = openLongKeyed(Long.MAX_VALUE)) {
            tree.put(-100L, "neg");
            tree.put(0L, "zero");
            tree.put(100L, "pos");

            List<Entry> entries = drain(tree.scan());
            assertEquals(3, entries.size());
            assertEquals(-100L, decodeLong(entries.get(0).key()));
            assertEquals(0L, decodeLong(entries.get(1).key()));
            assertEquals(100L, decodeLong(entries.get(2).key()));
        }
    }

    @Test
    void longKeyed_extremeValuesRoundTrip() throws IOException {
        try (TypedLsmTree.LongKeyed<String> tree = openLongKeyed(Long.MAX_VALUE)) {
            tree.put(Long.MIN_VALUE, "min");
            tree.put(Long.MAX_VALUE, "max");

            assertEquals("min", tree.get(Long.MIN_VALUE).orElseThrow());
            assertEquals("max", tree.get(Long.MAX_VALUE).orElseThrow());
        }
    }

    // -----------------------------------------------------------------------
    // LongKeyed builder null-guard tests
    // -----------------------------------------------------------------------

    @Test
    void longKeyed_builderRequiresWal() {
        assertThrows(NullPointerException.class, () -> TypedStandardLsmTree
                .<String>longKeyedBuilder().memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst.sst"))
                .valueSerializer(STRING_SERIALIZER).build());
    }

    @Test
    void longKeyed_builderRequiresValueSerializer() {
        assertThrows(NullPointerException.class, () -> TypedStandardLsmTree
                .<String>longKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst.sst")).build());
    }

    // -----------------------------------------------------------------------
    // SegmentKeyed tests
    // -----------------------------------------------------------------------

    @Test
    void segmentKeyed_putThenGetReturnsValue() throws IOException {
        try (TypedLsmTree.SegmentKeyed<String> tree = openSegmentKeyed(Long.MAX_VALUE)) {
            tree.put(seg("raw-key"), "hello");
            Optional<String> result = tree.get(seg("raw-key"));
            assertTrue(result.isPresent());
            assertEquals("hello", result.get());
        }
    }

    @Test
    void segmentKeyed_getMissingKeyReturnsEmpty() throws IOException {
        try (TypedLsmTree.SegmentKeyed<String> tree = openSegmentKeyed(Long.MAX_VALUE)) {
            assertFalse(tree.get(seg("absent")).isPresent());
        }
    }

    @Test
    void segmentKeyed_deletedKeyReturnsEmpty() throws IOException {
        try (TypedLsmTree.SegmentKeyed<String> tree = openSegmentKeyed(Long.MAX_VALUE)) {
            tree.put(seg("k"), "v");
            tree.delete(seg("k"));
            assertFalse(tree.get(seg("k")).isPresent());
        }
    }

    @Test
    void segmentKeyed_rangeScanReturnsHalfOpenSubset() throws IOException {
        try (TypedLsmTree.SegmentKeyed<String> tree = openSegmentKeyed(Long.MAX_VALUE)) {
            tree.put(seg("a"), "A");
            tree.put(seg("b"), "B");
            tree.put(seg("c"), "C");
            tree.put(seg("d"), "D");

            List<Entry> entries = drain(tree.scan(seg("b"), seg("d")));
            assertEquals(2, entries.size());
            assertEquals("b", str(entries.get(0).key()));
            assertEquals("c", str(entries.get(1).key()));
        }
    }

    @Test
    void segmentKeyed_fullScanReturnsAllEntriesAscending() throws IOException {
        try (TypedLsmTree.SegmentKeyed<String> tree = openSegmentKeyed(Long.MAX_VALUE)) {
            tree.put(seg("c"), "C");
            tree.put(seg("a"), "A");
            tree.put(seg("b"), "B");

            List<Entry> entries = drain(tree.scan());
            assertEquals(3, entries.size());
            assertEquals("a", str(entries.get(0).key()));
            assertEquals("b", str(entries.get(1).key()));
            assertEquals("c", str(entries.get(2).key()));
        }
    }

    // -----------------------------------------------------------------------
    // SegmentKeyed builder null-guard tests
    // -----------------------------------------------------------------------

    @Test
    void segmentKeyed_builderRequiresWal() {
        assertThrows(NullPointerException.class, () -> TypedStandardLsmTree
                .<String>segmentKeyedBuilder().memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst.sst"))
                .valueSerializer(STRING_SERIALIZER).build());
    }

    @Test
    void segmentKeyed_builderRequiresValueSerializer() {
        assertThrows(NullPointerException.class, () -> TypedStandardLsmTree
                .<String>segmentKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst.sst")).build());
    }
}
