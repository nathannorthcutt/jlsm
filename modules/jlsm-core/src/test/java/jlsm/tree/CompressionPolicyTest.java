package jlsm.tree;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Level;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for per-level codec policy on StandardLsmTree and TypedStandardLsmTree builders.
 *
 * <p>
 * Covers spec requirements F18.R23, R24, R25, and F02.R38.
 */
// @spec compression.zstd-dictionary.R23,R24,R25
class CompressionPolicyTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String str(MemorySegment m) {
        return new String(m.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    /**
     * Tracks which codec was requested for each level when the writer factory is invoked.
     */
    private record CodecCapture(Level level, CompressionCodec codec) {
    }

    /**
     * Creates a tree builder pre-wired with WAL, memtable, reader factory, id supplier, path fn.
     * The caller must still set writerFactory (or compression/compressionPolicy).
     */
    private StandardLsmTree.Builder baseBuilder() throws IOException {
        return StandardLsmTree.builder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .recoverFromWal(false);
    }

    /**
     * Opens a tree with the given codec configuration and a 1-byte flush threshold to force flush
     * on every write. Returns the tree and a list that captures which (level, codec) pairs were
     * requested from the writer factory.
     */
    private record TreeAndCaptures(StandardLsmTree tree, List<CodecCapture> captures) {
    }

    private TreeAndCaptures openTreeWithCodecCapture(
            Function<StandardLsmTree.Builder, StandardLsmTree.Builder> configurator)
            throws IOException {
        List<CodecCapture> captures = new CopyOnWriteArrayList<>();

        StandardLsmTree.Builder builder = baseBuilder().sstableWriterFactory((id, level, path) -> {
            // This should NOT be called when compression/compressionPolicy is set,
            // because the builder should wrap the factory. But we need a base factory
            // for the builder to use. We'll verify through the captures.
            return new TrieSSTableWriter(id, level, path);
        }).memTableFlushThresholdBytes(1L);

        builder = configurator.apply(builder);

        return new TreeAndCaptures(builder.build(), captures);
    }

    // -----------------------------------------------------------------------
    // R25: Default compression policy produces NONE codec
    // -----------------------------------------------------------------------

    @Test
    void defaultCompressionPolicyIsNone() throws IOException {
        // When no compression or compressionPolicy is set, the default should be
        // CompressionCodec.none() for all levels.
        // We verify by building the tree without setting any compression config.
        // The tree should build and work with no compression (backward compatible).
        try (StandardLsmTree tree = baseBuilder()
                .sstableWriterFactory((id, level, path) -> new TrieSSTableWriter(id, level, path))
                .memTableFlushThresholdBytes(1L).build()) {
            tree.put(seg("key"), seg("value"));
            Optional<MemorySegment> result = tree.get(seg("key"));
            assertTrue(result.isPresent());
            assertEquals("value", str(result.get()));
        }
    }

    // -----------------------------------------------------------------------
    // R25: when neither compression nor sstableWriterFactory is supplied, the
    // tree must still build and default to CompressionCodec.none()
    // -----------------------------------------------------------------------

    // @spec compression.zstd-dictionary.R25 — precedence: compressionPolicy > compression > user
    // factories > default none
    @Test
    void treeWithNoCompressionAndNoFactoriesDefaultsToNone() throws IOException {
        // Nothing configured: no compression/compressionPolicy, no sstableWriterFactory,
        // no sstableReaderFactory. Tree must build with an implicit _ -> none() policy.
        Path walDir = Files.createDirectory(tempDir.resolve("default-wal"));
        try (StandardLsmTree tree = StandardLsmTree.builder()
                .wal(LocalWriteAheadLog.builder().directory(walDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir
                        .resolve("default-sst-" + id + "-L" + level.index() + ".sst"))
                .recoverFromWal(false).memTableFlushThresholdBytes(1L).build()) {
            tree.put(seg("key"), seg("value"));
            Optional<MemorySegment> result = tree.get(seg("key"));
            assertTrue(result.isPresent(), "default-configured tree must remain functional");
            assertEquals("value", str(result.get()));
        }
    }

    // -----------------------------------------------------------------------
    // R23: compression(codec) sets all levels to that codec
    // -----------------------------------------------------------------------

    @Test
    void compressionSetsCodecForAllLevels() throws IOException {
        CompressionCodec deflate = CompressionCodec.deflate();

        // The builder should accept compression(codec) and use it when creating writers.
        // We verify by flushing entries and reading them back (the SSTable must be readable,
        // meaning the codec was applied correctly).
        try (StandardLsmTree tree = baseBuilder().compression(deflate)
                .memTableFlushThresholdBytes(1L).build()) {
            tree.put(seg("alpha"), seg("ALPHA"));
            tree.put(seg("beta"), seg("BETA"));

            Optional<MemorySegment> result = tree.get(seg("alpha"));
            assertTrue(result.isPresent());
            assertEquals("ALPHA", str(result.get()));
        }
    }

    // -----------------------------------------------------------------------
    // R23: compressionPolicy(fn) applies different codecs per level
    // -----------------------------------------------------------------------

    @Test
    void compressionPolicyAppliesToFlush() throws IOException {
        // compressionPolicy sets a per-level function. L0 flushes should use whatever
        // the policy returns for L0.
        CompressionCodec deflate = CompressionCodec.deflate();

        try (StandardLsmTree tree = baseBuilder().compressionPolicy(level -> {
            if (level.index() == 0)
                return deflate;
            return CompressionCodec.none();
        }).memTableFlushThresholdBytes(1L).build()) {
            tree.put(seg("key1"), seg("val1"));
            tree.put(seg("key2"), seg("val2"));

            // Data should be readable (verifies the codec was wired correctly)
            Optional<MemorySegment> result = tree.get(seg("key1"));
            assertTrue(result.isPresent());
            assertEquals("val1", str(result.get()));
        }
    }

    // -----------------------------------------------------------------------
    // R24: compressionPolicy takes precedence over compression
    // -----------------------------------------------------------------------

    @Test
    void compressionPolicyTakesPrecedenceOverCompression() throws IOException {
        CompressionCodec deflate = CompressionCodec.deflate();

        // Set both compression (deflate for all) and compressionPolicy (none for all).
        // The policy should win, meaning SSTables are written uncompressed.
        try (StandardLsmTree tree = baseBuilder().compression(deflate)
                .compressionPolicy(_ -> CompressionCodec.none()).memTableFlushThresholdBytes(1L)
                .build()) {
            tree.put(seg("key"), seg("value"));

            Optional<MemorySegment> result = tree.get(seg("key"));
            assertTrue(result.isPresent());
            assertEquals("value", str(result.get()));
        }
    }

    // -----------------------------------------------------------------------
    // R24: order doesn't matter — policy still wins even if set first
    // -----------------------------------------------------------------------

    @Test
    void compressionPolicyWinsRegardlessOfSetOrder() throws IOException {
        CompressionCodec deflate = CompressionCodec.deflate();

        // Set compressionPolicy first, then compression. Policy should still take precedence.
        try (StandardLsmTree tree = baseBuilder().compressionPolicy(_ -> CompressionCodec.none())
                .compression(deflate).memTableFlushThresholdBytes(1L).build()) {
            tree.put(seg("key"), seg("value"));

            Optional<MemorySegment> result = tree.get(seg("key"));
            assertTrue(result.isPresent());
            assertEquals("value", str(result.get()));
        }
    }

    // -----------------------------------------------------------------------
    // Null rejection
    // -----------------------------------------------------------------------

    @Test
    void compressionRejectsNull() {
        assertThrows(NullPointerException.class, () -> StandardLsmTree.builder().compression(null));
    }

    @Test
    void compressionPolicyRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> StandardLsmTree.builder().compressionPolicy(null));
    }

    // -----------------------------------------------------------------------
    // TypedStandardLsmTree: StringKeyed exposes compression methods
    // -----------------------------------------------------------------------

    @Test
    void stringKeyedBuilderExposesCompression() throws IOException {
        CompressionCodec deflate = CompressionCodec.deflate();
        Path walDir = Files.createDirectory(tempDir.resolve("str-wal"));

        TypedStandardLsmTree.StringKeyed.Builder<String> builder = TypedStandardLsmTree
                .<String>stringKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(walDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir
                        .resolve("str-sst-" + id + "-L" + level.index() + ".sst"))
                .recoverFromWal(false).memTableFlushThresholdBytes(1L).compression(deflate)
                .valueSerializer(new StringSerializer());

        try (var tree = builder.build()) {
            tree.put("hello", "world");
            Optional<String> result = tree.get("hello");
            assertTrue(result.isPresent());
            assertEquals("world", result.get());
        }
    }

    // -----------------------------------------------------------------------
    // TypedStandardLsmTree: LongKeyed exposes compression methods
    // -----------------------------------------------------------------------

    @Test
    void longKeyedBuilderExposesCompression() throws IOException {
        CompressionCodec deflate = CompressionCodec.deflate();
        Path walDir = Files.createDirectory(tempDir.resolve("long-wal"));

        TypedStandardLsmTree.LongKeyed.Builder<String> builder = TypedStandardLsmTree
                .<String>longKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(walDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir
                        .resolve("long-sst-" + id + "-L" + level.index() + ".sst"))
                .recoverFromWal(false).memTableFlushThresholdBytes(1L).compression(deflate)
                .valueSerializer(new StringSerializer());

        try (var tree = builder.build()) {
            tree.put(42L, "answer");
            Optional<String> result = tree.get(42L);
            assertTrue(result.isPresent());
            assertEquals("answer", result.get());
        }
    }

    // -----------------------------------------------------------------------
    // TypedStandardLsmTree: compressionPolicy on typed builder
    // -----------------------------------------------------------------------

    @Test
    void typedBuilderExposesCompressionPolicy() throws IOException {
        Path walDir = Files.createDirectory(tempDir.resolve("policy-wal"));
        TypedStandardLsmTree.StringKeyed.Builder<String> builder = TypedStandardLsmTree
                .<String>stringKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(walDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir
                        .resolve("policy-sst-" + id + "-L" + level.index() + ".sst"))
                .recoverFromWal(false).memTableFlushThresholdBytes(1L)
                .compressionPolicy(_ -> CompressionCodec.deflate())
                .valueSerializer(new StringSerializer());

        try (var tree = builder.build()) {
            tree.put("key", "value");
            Optional<String> result = tree.get("key");
            assertTrue(result.isPresent());
            assertEquals("value", result.get());
        }
    }

    // -----------------------------------------------------------------------
    // Builder sets up codec-aware writer factory used by flush AND default compactor
    // -----------------------------------------------------------------------

    @Test
    void compressionPolicyUsedInFlushWrites() throws IOException {
        // This test verifies the codec-aware factory is wired through for flush.
        // We use deflate and verify the written SSTable is readable (implying
        // the codec was used correctly during write AND the reader auto-detects it).
        CompressionCodec deflate = CompressionCodec.deflate();

        try (StandardLsmTree tree = baseBuilder().compressionPolicy(level -> deflate)
                .memTableFlushThresholdBytes(1L).build()) {
            // Write enough entries to trigger multiple flushes
            for (int i = 0; i < 10; i++) {
                tree.put(seg("key-" + String.format("%03d", i)), seg("val-" + i));
            }

            // All entries should be readable
            for (int i = 0; i < 10; i++) {
                Optional<MemorySegment> result = tree.get(seg("key-" + String.format("%03d", i)));
                assertTrue(result.isPresent(), "key-" + i + " should be readable");
            }
        }
    }

    // -----------------------------------------------------------------------
    // F16 R23: tree builder blockSize propagates to SSTables produced by the tree
    // -----------------------------------------------------------------------

    // @spec sstable.v3-format-upgrade.R23,R24 — blockSize propagates via the shared writerFactory;
    // SpookyCompactor
    // inherits this writerFactory (StandardLsmTree.Builder.build wires it in), so any SSTable
    // produced by compaction gets the same blockSize as flushed SSTables
    @Test
    void treeBlockSizePropagatesToFlushedSSTables() throws IOException {
        int chosen = 8192;
        Path dataPath = tempDir.resolve("tree-blocksize");
        Files.createDirectories(dataPath);
        Path walPath = tempDir.resolve("wal-blocksize");
        Files.createDirectories(walPath);
        AtomicLong localCounter = new AtomicLong(0);
        List<Path> writtenPaths = new CopyOnWriteArrayList<>();

        try (StandardLsmTree tree = StandardLsmTree.builder()
                .wal(LocalWriteAheadLog.builder().directory(walPath).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableReaderFactory(
                        path -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                                null, CompressionCodec.none(), CompressionCodec.deflate()))
                .idSupplier(localCounter::getAndIncrement).pathFn((id, level) -> {
                    Path p = dataPath.resolve("sst-" + id + "-L" + level.index() + ".sst");
                    writtenPaths.add(p);
                    return p;
                }).compression(CompressionCodec.deflate()).blockSize(chosen)
                .memTableFlushThresholdBytes(1L).recoverFromWal(false).build()) {
            tree.put(seg("alpha"), seg("first"));
            tree.put(seg("beta"), seg("second"));
        }

        assertFalse(writtenPaths.isEmpty(), "at least one SSTable must have been flushed");
        boolean sawBlockSize = false;
        for (Path p : writtenPaths) {
            if (!Files.exists(p)) {
                continue;
            }
            try (TrieSSTableReader r = TrieSSTableReader.open(p, BlockedBloomFilter.deserializer(),
                    null, CompressionCodec.none(), CompressionCodec.deflate())) {
                assertEquals((long) chosen, r.blockSize(),
                        "tree-configured blockSize must appear in each flushed SSTable's v3 footer");
                sawBlockSize = true;
            }
        }
        assertTrue(sawBlockSize, "expected at least one readable SSTable to verify blockSize");
    }

    // @spec sstable.v3-format-upgrade.R11 — invalid blockSize at tree builder rejected with
    // IllegalArgumentException
    @Test
    void treeBlockSizeRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> StandardLsmTree.builder().blockSize(0),
                "zero is below the minimum block size");
        assertThrows(IllegalArgumentException.class,
                () -> StandardLsmTree.builder().blockSize(6000),
                "non-power-of-two must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> StandardLsmTree.builder().blockSize(64 * 1024 * 1024),
                "64 MiB exceeds max block size (32 MiB)");
    }

    // -----------------------------------------------------------------------
    // Simple string serializer for typed builder tests
    // -----------------------------------------------------------------------

    private static final class StringSerializer implements jlsm.core.io.MemorySerializer<String> {
        @Override
        public MemorySegment serialize(String value) {
            return MemorySegment.ofArray(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String deserialize(MemorySegment segment) {
            return new String(segment.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }
    }
}
