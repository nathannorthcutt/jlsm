package jlsm.remote;

import static org.junit.jupiter.api.Assertions.*;

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
import jlsm.bloom.PassthroughBloomFilter;
import jlsm.compaction.SpookyCompactor;
import jlsm.core.compaction.CompactionTask;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link SpookyCompactor} with SSTables backed by S3. Uses
 * {@link PassthroughBloomFilter} to avoid alignment issues and eager {@link TrieSSTableReader#open}
 * for simplicity.
 */
@ExtendWith(S3Fixture.class)
class SpookyCompactorS3Test {

    private Path dir;
    private final AtomicLong idCounter = new AtomicLong(0);
    private SpookyCompactor compactor;

    @BeforeEach
    void setup(S3Fixture fixture) {
        dir = fixture.newTestDirectory();
        idCounter.set(0);
        compactor = SpookyCompactor.builder().idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> dir.resolve("out-" + id + "-L" + level.index() + ".sst"))
                .bloomDeserializer(PassthroughBloomFilter.deserializer()).build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MemorySegment key(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static MemorySegment val(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String keyStr(Entry e) {
        return new String(e.key().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private static String valStr(Entry.Put e) {
        return new String(e.value().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private SSTableMetadata writeSSTable(Level level, Entry... entries) throws IOException {
        long id = idCounter.getAndIncrement();
        Path path = dir.resolve("src-" + id + ".sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(id, level, path,
                PassthroughBloomFilter.factory())) {
            for (Entry e : entries)
                w.append(e);
            return w.finish();
        }
    }

    private List<Entry> readAll(SSTableMetadata meta) throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(meta.path(),
                PassthroughBloomFilter.deserializer())) {
            Iterator<Entry> it = r.scan();
            List<Entry> result = new ArrayList<>();
            while (it.hasNext())
                result.add(it.next());
            return result;
        }
    }

    // -------------------------------------------------------------------------
    // Bootstrap: L0-only compact to L1
    // -------------------------------------------------------------------------

    @Test
    void bootstrapCompactsL0ToL1() throws IOException {
        SSTableMetadata src = writeSSTable(Level.L0,
                new Entry.Put(key("a"), val("1"), new SequenceNumber(1)),
                new Entry.Put(key("b"), val("2"), new SequenceNumber(2)),
                new Entry.Put(key("c"), val("3"), new SequenceNumber(3)));

        CompactionTask task = new CompactionTask(List.of(src), Level.L0, new Level(1));
        List<SSTableMetadata> out = compactor.compact(task);

        assertFalse(out.isEmpty());
        List<Entry> entries = readAll(out.get(0));
        assertEquals(3, entries.size());
        assertEquals("a", keyStr(entries.get(0)));
        assertEquals("b", keyStr(entries.get(1)));
        assertEquals("c", keyStr(entries.get(2)));
        assertEquals(new Level(1), out.get(0).level());
    }

    // -------------------------------------------------------------------------
    // Merge overlapping L0 and L1 — correct key order preserved
    // -------------------------------------------------------------------------

    @Test
    void mergeOverlappingL0AndL1() throws IOException {
        // L1 has [a, b(seqNum=1)]; L0 has [b(seqNum=5), c] — b's L0 version wins
        SSTableMetadata l1src = writeSSTable(new Level(1),
                new Entry.Put(key("a"), val("from-l1"), new SequenceNumber(1)),
                new Entry.Put(key("b"), val("old-b"), new SequenceNumber(1)));
        SSTableMetadata l0src = writeSSTable(Level.L0,
                new Entry.Put(key("b"), val("new-b"), new SequenceNumber(5)),
                new Entry.Put(key("c"), val("from-l0"), new SequenceNumber(6)));

        CompactionTask task = new CompactionTask(List.of(l0src, l1src), Level.L0, new Level(1));
        List<SSTableMetadata> out = compactor.compact(task);

        List<Entry> entries = readAll(out.get(0));
        assertEquals(3, entries.size());
        assertEquals("a", keyStr(entries.get(0)));
        assertEquals("b", keyStr(entries.get(1)));
        assertEquals("new-b", valStr((Entry.Put) entries.get(1)));
        assertEquals(5L, entries.get(1).sequenceNumber().value());
        assertEquals("c", keyStr(entries.get(2)));
    }

    // -------------------------------------------------------------------------
    // Deduplication: only highest seqNum survives
    // -------------------------------------------------------------------------

    @Test
    void deduplicatesSameKeyAcrossLevels() throws IOException {
        SSTableMetadata l1src = writeSSTable(new Level(1),
                new Entry.Put(key("k"), val("old"), new SequenceNumber(1)));
        SSTableMetadata l0src = writeSSTable(Level.L0,
                new Entry.Put(key("k"), val("new"), new SequenceNumber(2)));

        CompactionTask task = new CompactionTask(List.of(l0src, l1src), Level.L0, new Level(1));
        List<SSTableMetadata> out = compactor.compact(task);

        List<Entry> entries = readAll(out.get(0));
        assertEquals(1, entries.size());
        assertInstanceOf(Entry.Put.class, entries.get(0));
        assertEquals("new", valStr((Entry.Put) entries.get(0)));
        assertEquals(2L, entries.get(0).sequenceNumber().value());
    }

    // -------------------------------------------------------------------------
    // Tombstone preserved at non-bottom level
    // -------------------------------------------------------------------------

    @Test
    void preservesTombstoneAtIntermediateLevel() throws IOException {
        // Sources only at L0 (maxSourceLevel=0), target=L1 (1 != 0 → NOT bottom) → tombstone kept
        SSTableMetadata src = writeSSTable(Level.L0,
                new Entry.Delete(key("gone"), new SequenceNumber(5)),
                new Entry.Put(key("z"), val("keep"), new SequenceNumber(6)));

        CompactionTask task = new CompactionTask(List.of(src), Level.L0, new Level(1));
        List<SSTableMetadata> out = compactor.compact(task);

        List<Entry> entries = readAll(out.get(0));
        assertEquals(2, entries.size());
        assertInstanceOf(Entry.Delete.class, entries.get(0));
        assertEquals("gone", keyStr(entries.get(0)));
        assertEquals("z", keyStr(entries.get(1)));
    }

    // -------------------------------------------------------------------------
    // Tombstone dropped at bottom level
    // -------------------------------------------------------------------------

    @Test
    void dropsTombstoneAtBottomLevel() throws IOException {
        // Sources at L0 and L1 (maxSourceLevel=1), target=L1 (1 == 1 → IS bottom) → tombstone
        // dropped
        SSTableMetadata l1src = writeSSTable(new Level(1),
                new Entry.Put(key("k1"), val("keep"), new SequenceNumber(1)));
        SSTableMetadata l0src = writeSSTable(Level.L0,
                new Entry.Delete(key("k2"), new SequenceNumber(10)));

        CompactionTask task = new CompactionTask(List.of(l0src, l1src), Level.L0, new Level(1));
        List<SSTableMetadata> out = compactor.compact(task);

        List<Entry> entries = readAll(out.get(0));
        assertEquals(1, entries.size());
        assertInstanceOf(Entry.Put.class, entries.get(0));
        assertEquals("k1", keyStr(entries.get(0)));
    }

    // -------------------------------------------------------------------------
    // selectCompaction returns task for overlapping files
    // -------------------------------------------------------------------------

    @Test
    void selectCompactionReturnsTaskForOverlappingFiles() throws IOException {
        SSTableMetadata l1file = writeSSTable(new Level(1),
                new Entry.Put(key("a"), val("1"), new SequenceNumber(1)),
                new Entry.Put(key("b"), val("2"), new SequenceNumber(2)));
        SSTableMetadata l0file = writeSSTable(Level.L0,
                new Entry.Put(key("b"), val("3"), new SequenceNumber(3)),
                new Entry.Put(key("c"), val("4"), new SequenceNumber(4)));

        List<List<SSTableMetadata>> levels = List.of(List.of(l0file), // L0
                List.of(l1file) // L1
        );

        Optional<CompactionTask> result = compactor.selectCompaction(levels);

        assertTrue(result.isPresent(),
                "Expected a compaction task for overlapping L0 and L1 files");
        CompactionTask task = result.get();
        assertEquals(2, task.sourceSSTables().size());
        assertEquals(new Level(1), task.targetLevel());
        assertTrue(task.sourceSSTables().contains(l0file));
        assertTrue(task.sourceSSTables().contains(l1file));
    }
}
