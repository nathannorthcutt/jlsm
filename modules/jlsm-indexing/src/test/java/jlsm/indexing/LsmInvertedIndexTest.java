package jlsm.indexing;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.indexing.InvertedIndex;
import jlsm.core.io.MemorySerializer;
import jlsm.core.tree.LsmTree;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.tree.SSTableReaderFactory;
import jlsm.tree.SSTableWriterFactory;
import jlsm.tree.StandardLsmTree;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LsmInvertedIndexTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    // -----------------------------------------------------------------------
    // Serializer helpers
    // -----------------------------------------------------------------------

    private static final MemorySerializer<Long> LONG_DOC_ID_SERIALIZER = new MemorySerializer<>() {
        @Override
        public MemorySegment serialize(Long value) {
            byte[] bytes = new byte[8];
            long v = value;
            for (int i = 7; i >= 0; i--) {
                bytes[i] = (byte) (v & 0xFF);
                v >>>= 8;
            }
            return MemorySegment.ofArray(bytes);
        }

        @Override
        public Long deserialize(MemorySegment segment) {
            byte[] bytes = segment.toArray(ValueLayout.JAVA_BYTE);
            long v = 0L;
            for (byte b : bytes) {
                v = (v << 8) | (b & 0xFFL);
            }
            return v;
        }
    };

    // -----------------------------------------------------------------------
    // Tree factory helper
    // -----------------------------------------------------------------------

    private LsmTree buildTree(long flushThreshold) throws IOException {
        return StandardLsmTree.builder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((SSTableWriterFactory) (id, level,
                        path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory((SSTableReaderFactory) path -> TrieSSTableReader.open(path,
                        BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(flushThreshold).build();
    }

    private static <D> List<D> drain(Iterator<D> it) {
        List<D> result = new ArrayList<>();
        it.forEachRemaining(result::add);
        return result;
    }

    // -----------------------------------------------------------------------
    // StringTermed tests (String terms, Long docIds)
    // -----------------------------------------------------------------------

    @Test
    void stringTermed_indexThenLookupReturnsDocId() throws IOException {
        try (InvertedIndex.StringTermed<Long> index = LsmInvertedIndex.<Long>stringTermedBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .build()) {
            index.index(1L, List.of("hello"));
            List<Long> results = drain(index.lookup("hello"));
            assertEquals(List.of(1L), results);
        }
    }

    @Test
    void stringTermed_lookupMissingTermReturnsEmpty() throws IOException {
        try (InvertedIndex.StringTermed<Long> index = LsmInvertedIndex.<Long>stringTermedBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .build()) {
            List<Long> results = drain(index.lookup("nonexistent"));
            assertTrue(results.isEmpty());
        }
    }

    @Test
    void stringTermed_removeDocRemovesFromPostings() throws IOException {
        try (InvertedIndex.StringTermed<Long> index = LsmInvertedIndex.<Long>stringTermedBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .build()) {
            index.index(1L, List.of("foo"));
            index.remove(1L, List.of("foo"));
            List<Long> results = drain(index.lookup("foo"));
            assertTrue(results.isEmpty());
        }
    }

    @Test
    void stringTermed_multipleDocsForSameTerm() throws IOException {
        try (InvertedIndex.StringTermed<Long> index = LsmInvertedIndex.<Long>stringTermedBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .build()) {
            index.index(1L, List.of("cat"));
            index.index(2L, List.of("cat"));
            index.index(3L, List.of("cat"));
            List<Long> results = drain(index.lookup("cat"));
            assertEquals(3, results.size());
            assertTrue(results.containsAll(List.of(1L, 2L, 3L)));
        }
    }

    @Test
    void stringTermed_multipleTermsForSameDoc() throws IOException {
        try (InvertedIndex.StringTermed<Long> index = LsmInvertedIndex.<Long>stringTermedBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .build()) {
            index.index(42L, List.of("apple", "banana", "cherry"));
            assertEquals(List.of(42L), drain(index.lookup("apple")));
            assertEquals(List.of(42L), drain(index.lookup("banana")));
            assertEquals(List.of(42L), drain(index.lookup("cherry")));
        }
    }

    @Test
    void stringTermed_lookupAfterFlush() throws IOException {
        // tiny flush threshold forces a flush mid-index
        try (InvertedIndex.StringTermed<Long> index = LsmInvertedIndex.<Long>stringTermedBuilder()
                .lsmTree(buildTree(1L)).docIdSerializer(LONG_DOC_ID_SERIALIZER).build()) {
            index.index(10L, List.of("flush-test"));
            index.index(20L, List.of("flush-test"));
            List<Long> results = drain(index.lookup("flush-test"));
            assertEquals(2, results.size());
            assertTrue(results.containsAll(List.of(10L, 20L)));
        }
    }

    @Test
    void stringTermed_builderRequiresLsmTree() {
        assertThrows(NullPointerException.class, () -> LsmInvertedIndex.<Long>stringTermedBuilder()
                .docIdSerializer(LONG_DOC_ID_SERIALIZER).build());
    }

    @Test
    void stringTermed_builderRequiresDocIdSerializer() throws IOException {
        assertThrows(NullPointerException.class, () -> LsmInvertedIndex.<Long>stringTermedBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).build());
    }

    @Test
    void stringTermed_builderNullLsmTreeThrowsAtSetTime() {
        assertThrows(NullPointerException.class,
                () -> LsmInvertedIndex.<Long>stringTermedBuilder().lsmTree(null));
    }

    @Test
    void stringTermed_builderNullDocIdSerializerThrowsAtSetTime() {
        assertThrows(NullPointerException.class,
                () -> LsmInvertedIndex.<Long>stringTermedBuilder().docIdSerializer(null));
    }

    // -----------------------------------------------------------------------
    // LongTermed tests (long terms, Long docIds)
    // -----------------------------------------------------------------------

    @Test
    void longTermed_indexThenLookupReturnsDocId() throws IOException {
        try (InvertedIndex.LongTermed<Long> index = LsmInvertedIndex.<Long>longTermedBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .build()) {
            index.index(1L, List.of(42L));
            List<Long> results = drain(index.lookup(42L));
            assertEquals(List.of(1L), results);
        }
    }

    @Test
    void longTermed_numericTermsSortCorrectly() throws IOException {
        try (InvertedIndex.LongTermed<Long> index = LsmInvertedIndex.<Long>longTermedBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .build()) {
            index.index(10L, List.of(-5L));
            index.index(20L, List.of(5L));
            // negative terms sort before positive in numeric order
            List<Long> negResults = drain(index.lookup(-5L));
            List<Long> posResults = drain(index.lookup(5L));
            assertEquals(List.of(10L), negResults);
            assertEquals(List.of(20L), posResults);
        }
    }

    @Test
    void longTermed_extremeTermValuesRoundTrip() throws IOException {
        try (InvertedIndex.LongTermed<Long> index = LsmInvertedIndex.<Long>longTermedBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .build()) {
            index.index(1L, List.of(Long.MIN_VALUE));
            index.index(2L, List.of(Long.MAX_VALUE));
            assertEquals(List.of(1L), drain(index.lookup(Long.MIN_VALUE)));
            assertEquals(List.of(2L), drain(index.lookup(Long.MAX_VALUE)));
        }
    }

    @Test
    void longTermed_lookupMissingTermReturnsEmpty() throws IOException {
        try (InvertedIndex.LongTermed<Long> index = LsmInvertedIndex.<Long>longTermedBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .build()) {
            List<Long> results = drain(index.lookup(999L));
            assertTrue(results.isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // SegmentTermed tests (MemorySegment terms, Long docIds)
    // -----------------------------------------------------------------------

    @Test
    void segmentTermed_indexThenLookupReturnsDocId() throws IOException {
        MemorySegment term = MemorySegment.ofArray("raw-term".getBytes(StandardCharsets.UTF_8));
        try (InvertedIndex.SegmentTermed<Long> index = LsmInvertedIndex.<Long>segmentTermedBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .build()) {
            index.index(7L, List.of(term));
            List<Long> results = drain(index.lookup(term));
            assertEquals(List.of(7L), results);
        }
    }

    @Test
    void segmentTermed_lookupMissingTermReturnsEmpty() throws IOException {
        MemorySegment term = MemorySegment.ofArray("absent".getBytes(StandardCharsets.UTF_8));
        try (InvertedIndex.SegmentTermed<Long> index = LsmInvertedIndex.<Long>segmentTermedBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .build()) {
            List<Long> results = drain(index.lookup(term));
            assertTrue(results.isEmpty());
        }
    }
}
