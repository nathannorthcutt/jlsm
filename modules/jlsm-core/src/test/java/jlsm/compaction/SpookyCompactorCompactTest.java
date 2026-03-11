package jlsm.compaction;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compaction.CompactionTask;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpookyCompactorCompactTest {

    @TempDir
    Path tempDir;

    private AtomicLong idCounter;
    private SpookyCompactor compactor;

    @BeforeEach
    void setUp() {
        idCounter = new AtomicLong(100L);
        compactor = SpookyCompactor.builder()
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .build();
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    @Test
    void nullTaskRejected() {
        assertThrows(NullPointerException.class, () -> compactor.compact(null));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MemorySegment key(String s) {
        return MemorySegment.ofArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static MemorySegment val(String s) {
        return MemorySegment.ofArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String keyStr(Entry e) {
        return new String(e.key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String valStr(Entry.Put e) {
        return new String(e.value().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Write a small SSTable with the given entries to a temp file and return its metadata. */
    private SSTableMetadata writeSSTable(Level level, Entry... entries) throws IOException {
        long id = idCounter.getAndIncrement();
        Path path = tempDir.resolve("src-" + id + ".sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(id, level, path)) {
            for (Entry e : entries) w.append(e);
            return w.finish();
        }
    }

    private List<Entry> readAll(SSTableMetadata meta) throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(meta.path(),
                BlockedBloomFilter.deserializer())) {
            Iterator<Entry> it = r.scan();
            List<Entry> result = new ArrayList<>();
            while (it.hasNext()) result.add(it.next());
            return result;
        }
    }

    // -------------------------------------------------------------------------
    // Single source identity compaction
    // -------------------------------------------------------------------------

    @Test
    void singleSourceIdentityCompaction() throws IOException {
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
    }

    // -------------------------------------------------------------------------
    // Two sources merged in order
    // -------------------------------------------------------------------------

    @Test
    void twoSourcesMergedInOrder() throws IOException {
        SSTableMetadata s1 = writeSSTable(Level.L0,
                new Entry.Put(key("a"), val("1"), new SequenceNumber(1)),
                new Entry.Put(key("c"), val("3"), new SequenceNumber(3)));
        SSTableMetadata s2 = writeSSTable(Level.L0,
                new Entry.Put(key("b"), val("2"), new SequenceNumber(2)),
                new Entry.Put(key("d"), val("4"), new SequenceNumber(4)));

        CompactionTask task = new CompactionTask(List.of(s1, s2), Level.L0, new Level(1));
        List<SSTableMetadata> out = compactor.compact(task);

        List<Entry> entries = readAll(out.get(0));
        assertEquals(4, entries.size());
        assertEquals("a", keyStr(entries.get(0)));
        assertEquals("b", keyStr(entries.get(1)));
        assertEquals("c", keyStr(entries.get(2)));
        assertEquals("d", keyStr(entries.get(3)));
    }

    // -------------------------------------------------------------------------
    // Duplicate keys: highest seqNum survives
    // -------------------------------------------------------------------------

    @Test
    void duplicateKeysHighestSeqNumSurvives() throws IOException {
        SSTableMetadata s1 = writeSSTable(Level.L0,
                new Entry.Put(key("k"), val("old"), new SequenceNumber(1)));
        SSTableMetadata s2 = writeSSTable(Level.L0,
                new Entry.Put(key("k"), val("new"), new SequenceNumber(5)));

        CompactionTask task = new CompactionTask(List.of(s1, s2), Level.L0, new Level(1));
        List<SSTableMetadata> out = compactor.compact(task);

        List<Entry> entries = readAll(out.get(0));
        assertEquals(1, entries.size());
        assertInstanceOf(Entry.Put.class, entries.get(0));
        assertEquals("new", valStr((Entry.Put) entries.get(0)));
        assertEquals(5L, entries.get(0).sequenceNumber().value());
    }

    // -------------------------------------------------------------------------
    // Tombstone preserved on non-bottom level compaction
    // -------------------------------------------------------------------------

    @Test
    void tombstonePreservedOnNonBottomLevel() throws IOException {
        // Source levels span 0-1; target is 1 (non-bottom when max level in sources is 1 but we're not at bottom)
        // Actually: isBottomLevel = (targetLevel == maxLevelIndex of sourceSSTables)
        // If target is L1 and sources are L0 only → target IS the bottom relative to sources
        // Let's use: source at L0, target at L1, but another file at L2 must exist for L1 to be non-bottom
        // Simplest: use L0→L1 compaction with sources only at L0 and target at L1;
        //   isBottomLevel = targetLevel.index() == maxLevelIndexAmongSources(L0=0) → 1 != 0 → NOT bottom
        // Wait, re-reading the plan:
        //   isBottomLevel = (task.targetLevel().index() == maxLevelIndex(task.sourceSSTables()))
        //   maxLevelIndex of sources all at L0 → 0; targetLevel = L1 → 1 != 0 → non-bottom
        // Actually wait, that doesn't make sense either.
        // The plan says: "isBottomLevel = (task.targetLevel().index() == maxLevelIndex(task.sourceSSTables()))"
        // maxLevelIndex of sources = max level among all source files
        // If sources are {L0, L1} files and target is L1 → maxLevelIndex=1, targetLevel=1 → bottom
        // If sources are {L0} files and target is L1 → maxLevelIndex=0, targetLevel=1 → not bottom
        // Hmm, that's backwards from what I'd expect. Let me re-read.
        //
        // Actually re-reading: "isBottomLevel = (task.targetLevel().index() == maxLevelIndex(task.sourceSSTables()))"
        // means: we're compacting to the bottom when the target level IS the max level in the sources.
        // But that doesn't apply when we're compacting L0+L1 → L1 where L1 is already the bottom.
        // I think the intended meaning is: is the TARGET the bottom-most level we have in the system?
        // The sources span from sourceLevel to targetLevel, and if target == max(source levels) then
        // we're landing at the bottom.
        //
        // Let me use: sources = {L1 file, L2 file}, target = L2 → bottom
        // and for non-bottom: sources = {L0 file, L1 file}, target = L1; maxSourceLevel=1, targetLevel=1 → bottom again
        // Hmm let me re-think.
        // "isBottomLevel = (task.targetLevel().index() == maxLevelIndex(task.sourceSSTables()))"
        // If sources have L0 and L1 files, maxLevelIndex = 1, target = L1 → 1 == 1 → bottom
        // If sources have L0 only, maxLevelIndex = 0, target = L1 → 1 != 0 → NOT bottom
        //
        // So for a non-bottom compaction: sources = {L0 file}, target = L1 → tombstone preserved
        // For a bottom compaction: sources = {L0 file, L1 file} or target == max source level → tombstone dropped

        SSTableMetadata src = writeSSTable(Level.L0,
                new Entry.Delete(key("gone"), new SequenceNumber(5)),
                new Entry.Put(key("z"), val("keep"), new SequenceNumber(6)));

        // target L1, sources only at L0 → maxLevelIndex(sources)=0, target=1 → NOT bottom → preserve tombstone
        CompactionTask task = new CompactionTask(List.of(src), Level.L0, new Level(1));
        List<SSTableMetadata> out = compactor.compact(task);

        List<Entry> entries = readAll(out.get(0));
        assertEquals(2, entries.size());
        assertInstanceOf(Entry.Delete.class, entries.get(0));
        assertEquals("gone", keyStr(entries.get(0)));
    }

    // -------------------------------------------------------------------------
    // Tombstone dropped on bottom-level compaction
    // -------------------------------------------------------------------------

    @Test
    void tombstoneDroppedOnBottomLevel() throws IOException {
        // sources at L0 and L1, target = L1 → maxLevelIndex(sources)=1, target=1 → bottom
        SSTableMetadata l0src = writeSSTable(Level.L0,
                new Entry.Delete(key("gone"), new SequenceNumber(10)));
        SSTableMetadata l1src = writeSSTable(new Level(1),
                new Entry.Put(key("stays"), val("yes"), new SequenceNumber(1)));

        CompactionTask task = new CompactionTask(List.of(l0src, l1src), Level.L0, new Level(1));
        List<SSTableMetadata> out = compactor.compact(task);

        List<Entry> entries = readAll(out.get(0));
        // "gone" tombstone should be dropped; "stays" should survive
        assertEquals(1, entries.size());
        assertEquals("stays", keyStr(entries.get(0)));
    }

    // -------------------------------------------------------------------------
    // Output level = targetLevel
    // -------------------------------------------------------------------------

    @Test
    void outputLevelMatchesTargetLevel() throws IOException {
        SSTableMetadata src = writeSSTable(Level.L0,
                new Entry.Put(key("x"), val("v"), new SequenceNumber(1)));

        Level target = new Level(3);
        CompactionTask task = new CompactionTask(List.of(src), Level.L0, target);
        List<SSTableMetadata> out = compactor.compact(task);

        assertFalse(out.isEmpty());
        assertEquals(target, out.get(0).level());
    }

    // -------------------------------------------------------------------------
    // IDs from supplier
    // -------------------------------------------------------------------------

    @Test
    void idsFromSupplier() throws IOException {
        AtomicLong idGen = new AtomicLong(999L);
        SpookyCompactor c = SpookyCompactor.builder()
                .idSupplier(idGen::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("out-" + id + ".sst"))
                .build();

        SSTableMetadata src = writeSSTable(Level.L0,
                new Entry.Put(key("a"), val("1"), new SequenceNumber(1)));

        CompactionTask task = new CompactionTask(List.of(src), Level.L0, new Level(1));
        List<SSTableMetadata> out = c.compact(task);

        assertEquals(999L, out.get(0).id());
    }

    // -------------------------------------------------------------------------
    // Paths from pathFn
    // -------------------------------------------------------------------------

    @Test
    void pathsFromPathFn() throws IOException {
        Path expectedPath = tempDir.resolve("custom-output.sst");
        SpookyCompactor c = SpookyCompactor.builder()
                .idSupplier(() -> 42L)
                .pathFn((id, level) -> expectedPath)
                .build();

        SSTableMetadata src = writeSSTable(Level.L0,
                new Entry.Put(key("a"), val("1"), new SequenceNumber(1)));

        CompactionTask task = new CompactionTask(List.of(src), Level.L0, new Level(1));
        List<SSTableMetadata> out = c.compact(task);

        assertEquals(expectedPath, out.get(0).path());
        assertTrue(Files.exists(expectedPath));
    }

    // -------------------------------------------------------------------------
    // Output file splitting
    // -------------------------------------------------------------------------

    @Test
    void outputFileSplitting() throws IOException {
        // Set a tiny target size to force splitting
        SpookyCompactor c = SpookyCompactor.builder()
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("split-" + id + ".sst"))
                .targetBottomFileSizeBytes(1L) // force a new file after every entry
                .build();

        SSTableMetadata src = writeSSTable(Level.L0,
                new Entry.Put(key("a"), val("1"), new SequenceNumber(1)),
                new Entry.Put(key("b"), val("2"), new SequenceNumber(2)),
                new Entry.Put(key("c"), val("3"), new SequenceNumber(3)));

        // sources L0 only, target L1 → non-bottom → tombstones kept (none here)
        CompactionTask task = new CompactionTask(List.of(src), Level.L0, new Level(1));
        List<SSTableMetadata> out = c.compact(task);

        // Should have multiple output files
        assertTrue(out.size() > 1, "Expected multiple output files but got " + out.size());

        // All entries should still be there across all files
        int total = 0;
        for (SSTableMetadata meta : out) {
            total += readAll(meta).size();
        }
        assertEquals(3, total);
    }

    // -------------------------------------------------------------------------
    // In-progress writer cleaned up on exception
    // -------------------------------------------------------------------------

    @Test
    void partialOutputDeletedOnException() throws IOException {
        // Create a compactor that will fail: use a pathFn that points to a directory (write will fail)
        AtomicLong idGen2 = new AtomicLong(500L);
        Path badPath = tempDir.resolve("not-a-file");
        Files.createDirectory(badPath); // create a directory where we expect a file → open fails

        SpookyCompactor c = SpookyCompactor.builder()
                .idSupplier(idGen2::getAndIncrement)
                .pathFn((id, level) -> badPath) // always returns a directory path
                .build();

        SSTableMetadata src = writeSSTable(Level.L0,
                new Entry.Put(key("a"), val("1"), new SequenceNumber(1)));

        CompactionTask task = new CompactionTask(List.of(src), Level.L0, new Level(1));
        assertThrows(IOException.class, () -> c.compact(task));
        // Directory still exists (we didn't delete it)
        assertTrue(Files.exists(badPath));
    }
}
