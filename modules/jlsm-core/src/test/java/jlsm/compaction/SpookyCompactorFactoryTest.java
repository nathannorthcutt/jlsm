package jlsm.compaction;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compaction.CompactionTask;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.tree.SSTableWriterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that SpookyCompactor uses an SSTableWriterFactory instead of hardcoded TrieSSTableWriter
 * constructors, enabling per-level codec policy during compaction.
 */
class SpookyCompactorFactoryTest {

    @TempDir
    Path tempDir;

    private AtomicLong idCounter;

    @BeforeEach
    void setUp() {
        idCounter = new AtomicLong(100L);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static MemorySegment key(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static MemorySegment val(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String keyStr(Entry e) {
        return new String(e.key().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private SSTableMetadata writeSSTable(Level level, Entry... entries) throws IOException {
        long id = idCounter.getAndIncrement();
        Path path = tempDir.resolve("src-" + id + ".sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(id, level, path)) {
            for (Entry e : entries)
                w.append(e);
            return w.finish();
        }
    }

    private List<Entry> readAll(SSTableMetadata meta) throws IOException {
        return readAll(meta, new CompressionCodec[0]);
    }

    private List<Entry> readAll(SSTableMetadata meta, CompressionCodec... codecs)
            throws IOException {
        try (TrieSSTableReader r = TrieSSTableReader.open(meta.path(),
                BlockedBloomFilter.deserializer(), null, codecs)) {
            Iterator<Entry> it = r.scan();
            List<Entry> result = new ArrayList<>();
            while (it.hasNext())
                result.add(it.next());
            return result;
        }
    }

    // -----------------------------------------------------------------------
    // writerFactory builder method exists and is accepted
    // -----------------------------------------------------------------------

    @Test
    void builderAcceptsWriterFactory() {
        // The builder should accept an SSTableWriterFactory
        SSTableWriterFactory factory = (id, level, path) -> new TrieSSTableWriter(id, level, path);

        SpookyCompactor compactor = SpookyCompactor.builder().idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .writerFactory(factory).build();

        assertNotNull(compactor);
    }

    // -----------------------------------------------------------------------
    // writerFactory null rejected
    // -----------------------------------------------------------------------

    @Test
    void builderRejectsNullWriterFactory() {
        assertThrows(NullPointerException.class,
                () -> SpookyCompactor.builder().writerFactory(null));
    }

    // -----------------------------------------------------------------------
    // Compactor uses writerFactory for output (not hardcoded constructor)
    // -----------------------------------------------------------------------

    @Test
    void compactorUsesWriterFactory() throws IOException {
        List<Level> capturedLevels = new ArrayList<>();

        SSTableWriterFactory factory = (id, level, path) -> {
            capturedLevels.add(level);
            return new TrieSSTableWriter(id, level, path);
        };

        SpookyCompactor compactor = SpookyCompactor.builder().idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .writerFactory(factory).build();

        SSTableMetadata src = writeSSTable(Level.L0,
                new Entry.Put(key("a"), val("1"), new SequenceNumber(1)),
                new Entry.Put(key("b"), val("2"), new SequenceNumber(2)));

        CompactionTask task = new CompactionTask(List.of(src), Level.L0, new Level(1));
        List<SSTableMetadata> out = compactor.compact(task);

        // The factory should have been called at least once
        assertFalse(capturedLevels.isEmpty(),
                "writerFactory should have been invoked during compaction");
        // The captured level should be the target level (L1)
        assertEquals(new Level(1), capturedLevels.getFirst());

        // Output should still be valid
        assertFalse(out.isEmpty());
        List<Entry> entries = readAll(out.getFirst());
        assertEquals(2, entries.size());
    }

    // -----------------------------------------------------------------------
    // Compactor with codec-aware factory produces compressed output
    // -----------------------------------------------------------------------

    @Test
    void compactorWithCodecAwareFactoryProducesReadableOutput() throws IOException {
        CompressionCodec deflate = CompressionCodec.deflate();

        // Factory that creates writers with deflate compression
        SSTableWriterFactory factory = (id, level, path) -> new TrieSSTableWriter(id, level, path,
                n -> new BlockedBloomFilter(n, 0.01), deflate);

        SpookyCompactor compactor = SpookyCompactor.builder().idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .writerFactory(factory).build();

        SSTableMetadata src = writeSSTable(Level.L0,
                new Entry.Put(key("a"), val("1"), new SequenceNumber(1)),
                new Entry.Put(key("b"), val("2"), new SequenceNumber(2)),
                new Entry.Put(key("c"), val("3"), new SequenceNumber(3)));

        CompactionTask task = new CompactionTask(List.of(src), Level.L0, new Level(1));
        List<SSTableMetadata> out = compactor.compact(task);

        // Compressed output should still be readable (pass deflate codec to the reader)
        assertFalse(out.isEmpty());
        List<Entry> entries = readAll(out.getFirst(), deflate);
        assertEquals(3, entries.size());
        assertEquals("a", keyStr(entries.get(0)));
        assertEquals("b", keyStr(entries.get(1)));
        assertEquals("c", keyStr(entries.get(2)));
    }

    // -----------------------------------------------------------------------
    // Default behavior: no writerFactory set → uses default (backward compatible)
    // -----------------------------------------------------------------------

    @Test
    void defaultBehaviorWithoutWriterFactory() throws IOException {
        // When no writerFactory is set, the compactor should still work
        // (backward compatible with hardcoded TrieSSTableWriter)
        SpookyCompactor compactor = SpookyCompactor.builder().idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .build();

        SSTableMetadata src = writeSSTable(Level.L0,
                new Entry.Put(key("x"), val("1"), new SequenceNumber(1)));

        CompactionTask task = new CompactionTask(List.of(src), Level.L0, new Level(1));
        List<SSTableMetadata> out = compactor.compact(task);

        assertFalse(out.isEmpty());
        List<Entry> entries = readAll(out.getFirst());
        assertEquals(1, entries.size());
        assertEquals("x", keyStr(entries.getFirst()));
    }

    // -----------------------------------------------------------------------
    // Factory receives correct target level for compaction output
    // -----------------------------------------------------------------------

    @Test
    void factoryReceivesCorrectTargetLevel() throws IOException {
        List<Level> capturedLevels = new ArrayList<>();

        SSTableWriterFactory factory = (id, level, path) -> {
            capturedLevels.add(level);
            return new TrieSSTableWriter(id, level, path);
        };

        Level targetLevel = new Level(3);
        SpookyCompactor compactor = SpookyCompactor.builder().idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .writerFactory(factory).build();

        SSTableMetadata src = writeSSTable(Level.L0,
                new Entry.Put(key("a"), val("1"), new SequenceNumber(1)));

        CompactionTask task = new CompactionTask(List.of(src), Level.L0, targetLevel);
        List<SSTableMetadata> out = compactor.compact(task);

        assertFalse(capturedLevels.isEmpty());
        assertEquals(targetLevel, capturedLevels.getFirst());
    }
}
