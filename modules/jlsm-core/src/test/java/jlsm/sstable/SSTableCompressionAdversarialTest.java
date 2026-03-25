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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for SSTable v2 compression integration.
 *
 * <p>
 * Targets findings from block-compression audit round 1:
 * <ul>
 * <li>CONTRACT-GAP-2: V2 reader does not auto-include NoneCodec</li>
 * </ul>
 */
class SSTableCompressionAdversarialTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    // ---- CONTRACT-GAP-2: V2 reader must auto-include NoneCodec ----
    // When the writer falls back to NONE for incompressible blocks, the reader
    // must handle codec ID 0x00 even if only DeflateCodec was passed.

    @Test
    void v2ReaderWithOnlyDeflateCodecHandlesIncompressibleBlocks(@TempDir Path dir)
            throws IOException {
        // Write entries with random (incompressible) data to force NONE fallback
        Random rng = new Random(42);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String key = "key-%05d".formatted(i);
            byte[] randomValue = new byte[200];
            rng.nextBytes(randomValue);
            entries.add(new Entry.Put(seg(key), MemorySegment.ofArray(randomValue),
                    new SequenceNumber(i + 1)));
        }

        Path path = dir.resolve("incompressible.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        // CONTRACT-GAP-2: Open with ONLY deflate — should still work because
        // the reader auto-includes NoneCodec for fallback blocks
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
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

    @Test
    void v2LazyReaderWithOnlyDeflateCodecHandlesIncompressibleBlocks(@TempDir Path dir)
            throws IOException {
        // Same test for lazy reader path
        Random rng = new Random(99);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String key = "lazy-key-%05d".formatted(i);
            byte[] randomValue = new byte[300];
            rng.nextBytes(randomValue);
            entries.add(new Entry.Put(seg(key), MemorySegment.ofArray(randomValue),
                    new SequenceNumber(i + 1)));
        }

        Path path = dir.resolve("lazy-incompressible.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        // CONTRACT-GAP-2: Lazy open with ONLY deflate
        try (TrieSSTableReader r = TrieSSTableReader.openLazy(path,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate())) {
            assertEquals(30, r.metadata().entryCount());
            // Point lookup to verify lazy decompression
            assertTrue(r.get(seg("lazy-key-00000")).isPresent());
        }
    }
}
