package jlsm.sstable;

import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TrieSSTableWriterTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private static Entry.Delete del(String key, long seq) {
        return new Entry.Delete(seg(key), new SequenceNumber(seq));
    }

    @Test
    void initialEntryCountIsZero(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out)) {
            assertEquals(0L, w.entryCount());
        }
    }

    @Test
    void entryCountIncrementsAfterAppend(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out)) {
            w.append(put("a", "va", 1));
            assertEquals(1L, w.entryCount());
            w.append(put("b", "vb", 2));
            assertEquals(2L, w.entryCount());
        }
    }

    @Test
    void approximateSizeGrowsAfterAppend(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out)) {
            long size0 = w.approximateSizeBytes();
            w.append(put("a", "va", 1));
            long size1 = w.approximateSizeBytes();
            assertTrue(size1 > size0);
            w.append(put("b", "vb", 2));
            assertTrue(w.approximateSizeBytes() > size1);
        }
    }

    @Test
    void finishOnEmptyThrows(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out)) {
            assertThrows(IllegalStateException.class, w::finish);
        }
    }

    @Test
    void finishReturnsCorrectMetadata(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.sst");
        TrieSSTableWriter w = new TrieSSTableWriter(42L, Level.L0, out);
        w.append(put("a", "va", 1));
        w.append(put("b", "vb", 3));
        w.append(put("c", "vc", 2));
        SSTableMetadata meta = w.finish();
        w.close();

        assertEquals(42L, meta.id());
        assertEquals(new Level(0), meta.level());
        assertEquals(3L, meta.entryCount());
        assertTrue(meta.sizeBytes() > 0);
        assertEquals(-1L, seg("a").mismatch(meta.smallestKey()));
        assertEquals(-1L, seg("c").mismatch(meta.largestKey()));
        assertEquals(new SequenceNumber(1L), meta.minSequence());
        assertEquals(new SequenceNumber(3L), meta.maxSequence());
    }

    @Test
    void doubleFinishThrows(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.sst");
        TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out);
        w.append(put("a", "va", 1));
        w.finish();
        assertThrows(IllegalStateException.class, w::finish);
        w.close();
    }

    @Test
    void appendAfterFinishThrows(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.sst");
        TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out);
        w.append(put("a", "va", 1));
        w.finish();
        assertThrows(IllegalStateException.class, () -> w.append(put("b", "vb", 2)));
        w.close();
    }

    @Test
    void appendNullThrows(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out)) {
            assertThrows(NullPointerException.class, () -> w.append(null));
        }
    }

    @Test
    void appendOutOfOrderThrows(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out)) {
            w.append(put("b", "vb", 1));
            assertThrows(IllegalArgumentException.class, () -> w.append(put("a", "va", 2)));
        }
    }

    @Test
    void appendSameKeyThrows(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out)) {
            w.append(put("a", "v1", 1));
            assertThrows(IllegalArgumentException.class, () -> w.append(put("a", "v2", 2)));
        }
    }

    @Test
    void closeWithoutFinishDeletesFile(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.sst");
        TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out);
        w.append(put("a", "va", 1));
        w.close(); // no finish
        assertFalse(Files.exists(out), "partial file should be deleted on close without finish");
    }

    @Test
    void finishCreatesFile(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("test.sst");
        TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out);
        w.append(put("key1", "val1", 1));
        w.finish();
        w.close();
        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);
    }
}
