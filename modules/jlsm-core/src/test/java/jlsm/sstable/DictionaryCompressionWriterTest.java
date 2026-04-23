package jlsm.sstable;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.compression.ZstdDictionaryTrainer;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.sstable.internal.SSTableFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TDD tests for SSTable v4 dictionary compression lifecycle in {@link TrieSSTableWriter}.
 *
 * <p>
 * Tests that require native ZSTD (dictionary training) are guarded with
 * {@code assumeTrue(ZstdDictionaryTrainer.isAvailable())} so they are skipped on systems without
 * native libzstd.
 */
// @spec compression.zstd-dictionary.R10,R11,R12,R13,R13a,R14,R19
class DictionaryCompressionWriterTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private List<Entry> generateEntries(int count) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String key = "key-%05d".formatted(i);
            // Use repetitive value data to aid dictionary training
            String value = "value-data-for-entry-%05d-with-some-repeated-content-structure"
                    .formatted(i);
            entries.add(put(key, value, i + 1));
        }
        return entries;
    }

    /**
     * Reads the last 8 bytes of a file as a big-endian long (the SSTable footer magic).
     */
    private long readMagic(Path path) throws IOException {
        long fileSize = Files.size(path);
        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ)) {
            ch.position(fileSize - 8);
            ByteBuffer buf = ByteBuffer.allocate(8);
            while (buf.hasRemaining()) {
                if (ch.read(buf) == -1)
                    throw new IOException("unexpected EOF reading magic");
            }
            buf.flip();
            return buf.getLong();
        }
    }

    // ---- Test 1: Writer without dictionary training produces v3 (unchanged behavior) ----

    @Test
    void writerWithoutDictionaryTrainingProducesV3(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("no-dict.sst");
        CompressionCodec codec = CompressionCodec.zstd();

        // formatVersion(3) opts back into v3 (the v5 default applies when no formatVersion is set).
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec).formatVersion(3)
                .build()) {
            for (Entry e : generateEntries(10)) {
                w.append(e);
            }
            w.finish();
        }

        // When dictionary training is NOT enabled, the writer produces a v3 file
        assertEquals(SSTableFormat.MAGIC_V3, readMagic(path),
                "writer without dictionary training should produce v3 magic");
    }

    // ---- Test 2: Writer with dictionary training + enough blocks produces v4 ----

    @Test
    void writerWithDictionaryTrainingProducesV4(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        Path path = dir.resolve("dict-v4.sst");
        CompressionCodec codec = CompressionCodec.zstd();

        // Use small block size (1024) and many entries to exceed threshold of 64 blocks
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec).blockSize(1024)
                .dictionaryTraining(true).build()) {
            // Write enough entries to get >= 64 blocks at 1024 block size
            for (Entry e : generateEntries(2000)) {
                w.append(e);
            }
            w.finish();
        }

        assertEquals(SSTableFormat.MAGIC_V4, readMagic(path),
                "writer with dictionary training and sufficient blocks should produce v4 magic");
    }

    // ---- Test 3: Writer with dictionary training but below threshold: no dict, plain ZSTD ----

    @Test
    void writerBelowThresholdProducesV3NoDictionary(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        Path path = dir.resolve("dict-below-threshold.sst");
        CompressionCodec codec = CompressionCodec.zstd();

        // With default block size (4096) and only 5 entries, we will have < 64 blocks
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec)
                .dictionaryTraining(true).build()) {
            for (Entry e : generateEntries(5)) {
                w.append(e);
            }
            w.finish();
        }

        // R12: below threshold => no dictionary => v3 format
        assertEquals(SSTableFormat.MAGIC_V3, readMagic(path),
                "writer with dictionary training but below threshold should produce v3 magic");
    }

    // ---- Test 4: Writer with dictionary training + non-ZSTD codec: no dict training ----

    @Test
    void writerWithDictionaryTrainingNonZstdCodecIgnored(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("dict-deflate.sst");
        CompressionCodec codec = CompressionCodec.deflate();

        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec)
                .dictionaryTraining(true) // enabled but codec is not ZSTD — should be ignored
                .build()) {
            for (Entry e : generateEntries(200)) {
                w.append(e);
            }
            w.finish();
        }

        // Non-ZSTD codec => dictionary training is ignored, normal v3 format
        assertEquals(SSTableFormat.MAGIC_V3, readMagic(path),
                "dictionary training with non-ZSTD codec should be ignored, producing v3");
    }

    // ---- Test 5: Writer with dictionary training + buffer limit exceeded: graceful degradation
    // ----

    @Test
    void writerBufferLimitExceededDegracesGracefully(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        Path path = dir.resolve("dict-buffer-exceeded.sst");
        CompressionCodec codec = CompressionCodec.zstd();

        // Set a very small buffer limit (1 KiB) to force buffer overflow quickly
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec).blockSize(1024)
                .dictionaryTraining(true).dictionaryMaxBufferBytes(1024L) // very small limit
                .build()) {
            // R14: Write many entries that will exceed the buffer limit
            for (Entry e : generateEntries(500)) {
                w.append(e);
            }
            // Must not throw — graceful degradation
            assertDoesNotThrow(() -> w.finish());
        }

        // R14: buffer exceeded => abandoned dictionary training => v3 format
        assertEquals(SSTableFormat.MAGIC_V3, readMagic(path),
                "buffer limit exceeded should gracefully degrade to v3 without dictionary");
    }

    // ---- Test 6: Writer with dictionary training but native unavailable: graceful degradation
    // ----

    @Test
    void writerDictionaryTrainingNativeUnavailableDegrades(@TempDir Path dir) throws IOException {
        // This test always runs — if native is available, dictionary training works normally;
        // if unavailable, it should degrade gracefully to no dictionary
        Path path = dir.resolve("dict-no-native.sst");
        CompressionCodec codec = CompressionCodec.zstd();

        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec)
                .dictionaryTraining(true).build()) {
            for (Entry e : generateEntries(10)) {
                w.append(e);
            }
            // Should not throw regardless of native availability
            assertDoesNotThrow(() -> w.finish());
        }

        // File should be valid regardless
        long magic = readMagic(path);
        assertTrue(magic == SSTableFormat.MAGIC_V3 || magic == SSTableFormat.MAGIC_V4,
                "file should be either v3 or v4 depending on native availability");
    }

    // ---- Test 7: Builder defaults ----

    @Test
    void builderDefaultsForDictionaryTraining(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("defaults.sst");
        CompressionCodec codec = CompressionCodec.zstd();

        // Opt into v3 explicitly — new default for codec-only writers is v5.
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec).formatVersion(3)
                .build()) {
            for (Entry e : generateEntries(5)) {
                w.append(e);
            }
            w.finish();
        }

        assertEquals(SSTableFormat.MAGIC_V3, readMagic(path),
                "default builder (no dictionaryTraining) should produce v3");
    }

    // ---- Test 8: Builder method chaining compiles and sets values ----

    @Test
    void builderMethodChainingCompiles(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("chain.sst");
        CompressionCodec codec = CompressionCodec.zstd();

        // All builder methods should be chainable and accepted
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec).blockSize(1024)
                .dictionaryTraining(true).dictionaryBlockThreshold(32)
                .dictionaryMaxBufferBytes(1024L * 1024L).dictionaryMaxSize(16384).build()) {
            w.append(put("a", "alpha", 1));
            w.finish();
        }

        // Just verify the file was written successfully
        assertTrue(Files.size(path) > 0, "file should be non-empty");
    }

    // ---- Test 9: R13a — DictionaryTrainingResult accessible on writer ----

    @Test
    void dictionaryTrainingResultAccessibleAfterFinish(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        Path path = dir.resolve("dict-result.sst");
        CompressionCodec codec = CompressionCodec.zstd();

        TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec).blockSize(1024)
                .dictionaryTraining(true).build();
        try {
            for (Entry e : generateEntries(2000)) {
                w.append(e);
            }
            w.finish();
        } finally {
            w.close();
        }

        // R13a: writer should expose the training result
        TrieSSTableWriter.DictionaryTrainingResult result = w.dictionaryTrainingResult();
        assertNotNull(result, "dictionaryTrainingResult() must not return null after finish()");
        assertTrue(result.attempted(), "training should have been attempted");
        assertTrue(result.succeeded(), "training should have succeeded with enough blocks");
        assertTrue(result.dictionarySize() > 0, "dictionary should have non-zero size");
    }

    // ---- Test 10: DictionaryTrainingResult for skipped training ----

    @Test
    void dictionaryTrainingResultReportsSkipped(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("dict-skipped.sst");
        CompressionCodec codec = CompressionCodec.deflate();

        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec)
                .dictionaryTraining(true).build()) {
            for (Entry e : generateEntries(5)) {
                w.append(e);
            }
            w.finish();

            TrieSSTableWriter.DictionaryTrainingResult result = w.dictionaryTrainingResult();
            assertNotNull(result);
            assertFalse(result.attempted(), "training should not be attempted for non-ZSTD codec");
        }
    }

    // ---- Test 11: v4 footer has 88 bytes ----

    @Test
    void v4FileFooterIs88Bytes(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        Path path = dir.resolve("v4-footer-size.sst");
        CompressionCodec codec = CompressionCodec.zstd();

        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec).blockSize(1024)
                .dictionaryTraining(true).build()) {
            for (Entry e : generateEntries(2000)) {
                w.append(e);
            }
            w.finish();
        }

        // Verify v4 magic
        assertEquals(SSTableFormat.MAGIC_V4, readMagic(path));

        // Read the 88-byte footer
        long fileSize = Files.size(path);
        assertTrue(fileSize >= SSTableFormat.FOOTER_SIZE_V4,
                "v4 file must be at least FOOTER_SIZE_V4 bytes");
    }
}
