package jlsm.sstable;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.cache.LruBlockCache;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TrieSSTableReaderTest {

    @TempDir
    Path dir;

    Path sstPath;

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private static Entry.Delete del(String key, long seq) {
        return new Entry.Delete(seg(key), new SequenceNumber(seq));
    }

    @BeforeEach
    void writeSSTable() throws IOException {
        sstPath = dir.resolve("test.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, sstPath)) {
            w.append(put("a", "va", 1));
            w.append(del("b", 2));
            w.append(put("c", "vc", 3));
            w.append(put("d", "vd", 4));
            w.finish();
        }
    }

    // ---- Eager mode tests ----

    @Test
    void eagerMetadataEntryCount() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            assertEquals(4L, r.metadata().entryCount());
        }
    }

    @Test
    void eagerMetadataSmallestLargestKey() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            SSTableMetadata meta = r.metadata();
            assertEquals(-1L, seg("a").mismatch(meta.smallestKey()));
            assertEquals(-1L, seg("d").mismatch(meta.largestKey()));
        }
    }

    @Test
    void eagerMetadataFileSize() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            assertTrue(r.metadata().sizeBytes() > 0);
        }
    }

    @Test
    void eagerGetExistingPutEntry() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            Optional<Entry> result = r.get(seg("a"));
            assertTrue(result.isPresent());
            assertInstanceOf(Entry.Put.class, result.get());
            assertEquals(-1L, seg("va").mismatch(((Entry.Put) result.get()).value()));
        }
    }

    @Test
    void eagerGetMissingKeyReturnsEmpty() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            assertTrue(r.get(seg("z")).isEmpty());
        }
    }

    @Test
    void eagerGetDeleteEntry() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            Optional<Entry> result = r.get(seg("b"));
            assertTrue(result.isPresent());
            assertInstanceOf(Entry.Delete.class, result.get());
        }
    }

    @Test
    void eagerScanReturnsAllInOrder() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            List<Entry> entries = toList(r.scan());
            assertEquals(4, entries.size());
            assertEquals(-1L, seg("a").mismatch(entries.get(0).key()));
            assertEquals(-1L, seg("b").mismatch(entries.get(1).key()));
            assertEquals(-1L, seg("c").mismatch(entries.get(2).key()));
            assertEquals(-1L, seg("d").mismatch(entries.get(3).key()));
        }
    }

    @Test
    void eagerScanRangeInclusiveLowerExclusiveUpper() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            List<Entry> entries = toList(r.scan(seg("b"), seg("d")));
            assertEquals(2, entries.size());
            assertEquals(-1L, seg("b").mismatch(entries.get(0).key()));
            assertEquals(-1L, seg("c").mismatch(entries.get(1).key()));
        }
    }

    @Test
    void eagerScanRangeEmptyReturnsNoEntries() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            List<Entry> entries = toList(r.scan(seg("e"), seg("z")));
            assertTrue(entries.isEmpty());
        }
    }

    @Test
    void eagerScanRangePrefixBoundary() throws IOException {
        // write a file with keys "a", "ab", "b"
        Path p = dir.resolve("prefix.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(2L, Level.L0, p)) {
            w.append(put("a", "va", 1));
            w.append(put("ab", "vab", 2));
            w.append(put("b", "vb", 3));
            w.finish();
        }
        try (TrieSSTableReader r = TrieSSTableReader.open(p, BlockedBloomFilter.deserializer())) {
            List<Entry> entries = toList(r.scan(seg("a"), seg("b")));
            assertEquals(2, entries.size());
            assertEquals(-1L, seg("a").mismatch(entries.get(0).key()));
            assertEquals(-1L, seg("ab").mismatch(entries.get(1).key()));
        }
    }

    @Test
    void eagerScanNullFromThrows() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            assertThrows(NullPointerException.class, () -> r.scan(null, seg("z")));
        }
    }

    @Test
    void eagerScanNullToThrows() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            assertThrows(NullPointerException.class, () -> r.scan(seg("a"), null));
        }
    }

    @Test
    void eagerGetNullThrows() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            assertThrows(NullPointerException.class, () -> r.get(null));
        }
    }

    @Test
    void eagerGetMissingKeyBloomMiss() throws IOException {
        // Key "zzz" not in the file; bloom filter will definitely miss
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer())) {
            assertTrue(r.get(seg("zzz")).isEmpty());
        }
    }

    // ---- Lazy mode tests ----

    @Test
    void lazyGetExistingPutEntry() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer())) {
            Optional<Entry> result = r.get(seg("a"));
            assertTrue(result.isPresent());
            assertInstanceOf(Entry.Put.class, result.get());
            assertEquals(-1L, seg("va").mismatch(((Entry.Put) result.get()).value()));
        }
    }

    @Test
    void lazyGetMissingKeyReturnsEmpty() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer())) {
            assertTrue(r.get(seg("z")).isEmpty());
        }
    }

    @Test
    void lazyGetDeleteEntry() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer())) {
            Optional<Entry> result = r.get(seg("b"));
            assertTrue(result.isPresent());
            assertInstanceOf(Entry.Delete.class, result.get());
        }
    }

    @Test
    void lazyScanReturnsAllInOrder() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer())) {
            List<Entry> entries = toList(r.scan());
            assertEquals(4, entries.size());
            assertEquals(-1L, seg("a").mismatch(entries.get(0).key()));
            assertEquals(-1L, seg("d").mismatch(entries.get(3).key()));
        }
    }

    @Test
    void lazyScanRangeInclusiveLowerExclusiveUpper() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer())) {
            List<Entry> entries = toList(r.scan(seg("b"), seg("d")));
            assertEquals(2, entries.size());
            assertEquals(-1L, seg("b").mismatch(entries.get(0).key()));
            assertEquals(-1L, seg("c").mismatch(entries.get(1).key()));
        }
    }

    @Test
    void lazyMetadataEntryCount() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer())) {
            assertEquals(4L, r.metadata().entryCount());
        }
    }

    // ---- Cache-backed reader tests ----

    @Test
    void withCacheEagerGetExistingEntry() throws IOException {
        try (var cache = LruBlockCache.builder().capacity(32).build();
             TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer(), cache)) {
            Optional<Entry> result = r.get(seg("a"));
            assertTrue(result.isPresent());
            assertInstanceOf(Entry.Put.class, result.get());
            assertEquals(-1L, seg("va").mismatch(((Entry.Put) result.get()).value()));
        }
    }

    @Test
    void withCacheEagerGetMissingKeyReturnsEmpty() throws IOException {
        try (var cache = LruBlockCache.builder().capacity(32).build();
             TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer(), cache)) {
            assertTrue(r.get(seg("z")).isEmpty());
        }
    }

    @Test
    void withCacheLazyGetExistingEntry() throws IOException {
        try (var cache = LruBlockCache.builder().capacity(32).build();
             TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer(), cache)) {
            Optional<Entry> result = r.get(seg("c"));
            assertTrue(result.isPresent());
            assertInstanceOf(Entry.Put.class, result.get());
            assertEquals(-1L, seg("vc").mismatch(((Entry.Put) result.get()).value()));
        }
    }

    @Test
    void withCacheLazyGetMissingKeyReturnsEmpty() throws IOException {
        try (var cache = LruBlockCache.builder().capacity(32).build();
             TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer(), cache)) {
            assertTrue(r.get(seg("z")).isEmpty());
        }
    }

    @Test
    void withCacheLazyGetPopulatesCache() throws IOException {
        try (var cache = LruBlockCache.builder().capacity(32).build();
             TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer(), cache)) {
            assertEquals(0, cache.size());
            r.get(seg("a"));
            assertTrue(cache.size() >= 1, "cache should be populated after a get");
        }
    }

    @Test
    void withCacheLazyGetHitDoesNotGrowCache() throws IOException {
        try (var cache = LruBlockCache.builder().capacity(32).build();
             TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer(), cache)) {
            r.get(seg("a"));
            long sizeAfterFirstGet = cache.size();
            r.get(seg("a"));
            assertEquals(sizeAfterFirstGet, cache.size(),
                    "repeated get for the same key should hit cache and not add a new entry");
        }
    }

    @Test
    void withCacheMultipleDistinctGetsPopulateCacheForEachKey() throws IOException {
        try (var cache = LruBlockCache.builder().capacity(32).build();
             TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer(), cache)) {
            r.get(seg("a"));
            r.get(seg("c"));
            assertEquals(2, cache.size(), "each distinct entry offset should occupy a cache slot");
        }
    }

    @Test
    void withCacheLazyScanRangeReturnsCorrectEntries() throws IOException {
        try (var cache = LruBlockCache.builder().capacity(32).build();
             TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer(), cache)) {
            List<Entry> entries = toList(r.scan(seg("b"), seg("d")));
            assertEquals(2, entries.size());
            assertEquals(-1L, seg("b").mismatch(entries.get(0).key()));
            assertEquals(-1L, seg("c").mismatch(entries.get(1).key()));
        }
    }

    @Test
    void withCacheLazyScanRangePopulatesCache() throws IOException {
        try (var cache = LruBlockCache.builder().capacity(32).build();
             TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer(), cache)) {
            toList(r.scan(seg("a"), seg("z")));    // scan all 4 keys
            assertEquals(4, cache.size(), "each entry read during range scan should be cached");
        }
    }

    @Test
    void withNullCacheOpenStillWorks() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(sstPath, BlockedBloomFilter.deserializer(), null)) {
            Optional<Entry> result = r.get(seg("d"));
            assertTrue(result.isPresent());
            assertEquals(-1L, seg("vd").mismatch(((Entry.Put) result.get()).value()));
        }
    }

    @Test
    void withNullCacheOpenLazyStillWorks() throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.openLazy(sstPath, BlockedBloomFilter.deserializer(), null)) {
            Optional<Entry> result = r.get(seg("d"));
            assertTrue(result.isPresent());
            assertEquals(-1L, seg("vd").mismatch(((Entry.Put) result.get()).value()));
        }
    }

    private static List<Entry> toList(Iterator<Entry> it) {
        List<Entry> result = new ArrayList<>();
        it.forEachRemaining(result::add);
        return result;
    }
}
