package jlsm.indexing;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.indexing.FullTextIndex;
import jlsm.core.io.MemorySerializer;
import jlsm.core.tree.LsmTree;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.tree.SSTableReaderFactory;
import jlsm.tree.SSTableWriterFactory;
import jlsm.tree.StandardLsmTree;
import jlsm.wal.local.LocalWriteAheadLog;

/**
 * {@link FullTextIndex.Factory} implementation that produces LSM-backed {@link FullTextIndex}
 * instances whose document IDs are {@link MemorySegment} primary keys.
 *
 * <p>
 * Each {@code (tableName, fieldName)} pair is rooted under
 * {@code <rootDirectory>/<tableName>/<fieldName>/} and backed by its own {@link LsmTree},
 * {@link LsmInvertedIndex.SegmentTermed} inverted index, and {@link LsmFullTextIndex.Impl}
 * tokenising layer. This isolation prevents cross-index contention and makes lifecycle management
 * symmetric — closing one index does not affect others.
 *
 * <p>
 * This factory is the module-boundary bridge between {@code jlsm-indexing} (which owns the
 * implementation) and {@code jlsm-table} (which consumes the interface). Construction of the
 * factory happens outside {@code jlsm-table}; the resulting factory is passed into table builders
 * that register FULL_TEXT indices.
 */
public final class LsmFullTextIndexFactory implements FullTextIndex.Factory {

    /**
     * Identity serializer for {@link MemorySegment} primary keys — each docId is stored as its
     * underlying bytes.
     */
    private static final MemorySerializer<MemorySegment> SEGMENT_SERIALIZER = new MemorySerializer<>() {

        @Override
        public MemorySegment serialize(MemorySegment value) {
            Objects.requireNonNull(value, "value must not be null");
            // Defensive copy: the factory owns the bytes handed off to the inverted index.
            byte[] bytes = value.toArray(ValueLayout.JAVA_BYTE);
            return MemorySegment.ofArray(bytes);
        }

        @Override
        public MemorySegment deserialize(MemorySegment segment) {
            Objects.requireNonNull(segment, "segment must not be null");
            // Return a byte-array-backed segment so consumers own their copy.
            byte[] bytes = segment.toArray(ValueLayout.JAVA_BYTE);
            return MemorySegment.ofArray(bytes);
        }
    };

    private final Path rootDirectory;
    private final long memTableFlushThresholdBytes;

    private LsmFullTextIndexFactory(Builder builder) {
        this.rootDirectory = builder.rootDirectory;
        this.memTableFlushThresholdBytes = builder.memTableFlushThresholdBytes;
    }

    public static Builder builder() {
        return new Builder();
    }

    // @spec query.full-text-index.R1,R3,R4,R5,R6 — factory produces a FullTextIndex<MemorySegment> per
    // (tableName, fieldName); the adapter in FullTextFieldIndex routes mutations through it.
    @Override
    public FullTextIndex<MemorySegment> create(String tableName, String fieldName)
            throws IOException {
        Objects.requireNonNull(tableName, "tableName must not be null");
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        if (tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }

        final Path indexRoot = rootDirectory.resolve(sanitize(tableName))
                .resolve(sanitize(fieldName));
        Files.createDirectories(indexRoot);

        final AtomicLong idCounter = new AtomicLong(0);

        final LsmTree lsmTree = StandardLsmTree.builder()
                .wal(LocalWriteAheadLog.builder().directory(indexRoot).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((SSTableWriterFactory) (id, level,
                        path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory((SSTableReaderFactory) path -> TrieSSTableReader
                        .open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> indexRoot
                        .resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(memTableFlushThresholdBytes).build();

        try {
            return LsmFullTextIndex.<MemorySegment>builder()
                    .invertedIndex(LsmInvertedIndex.<MemorySegment>stringTermedBuilder()
                            .lsmTree(lsmTree).docIdSerializer(SEGMENT_SERIALIZER).build())
                    .build();
        } catch (RuntimeException e) {
            try {
                lsmTree.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * Strips path separators and other illegal filesystem characters so an arbitrary table or field
     * name can be used as a directory name on any platform.
     */
    private static String sanitize(String name) {
        assert name != null && !name.isBlank() : "name validated by caller";
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Builder for {@link LsmFullTextIndexFactory}. */
    public static final class Builder {

        private static final long DEFAULT_FLUSH_THRESHOLD = 64L * 1024L * 1024L; // 64 MiB

        private Path rootDirectory;
        private long memTableFlushThresholdBytes = DEFAULT_FLUSH_THRESHOLD;

        private Builder() {
        }

        /**
         * Sets the root directory under which per-index storage will be created.
         *
         * @param rootDirectory the root directory; must not be null
         * @return this builder
         */
        public Builder rootDirectory(Path rootDirectory) {
            this.rootDirectory = Objects.requireNonNull(rootDirectory,
                    "rootDirectory must not be null");
            return this;
        }

        /**
         * Sets the memtable flush threshold in bytes for each underlying LSM tree.
         *
         * @param memTableFlushThresholdBytes the threshold; must be positive
         * @return this builder
         */
        public Builder memTableFlushThresholdBytes(long memTableFlushThresholdBytes) {
            if (memTableFlushThresholdBytes <= 0) {
                throw new IllegalArgumentException(
                        "memTableFlushThresholdBytes must be positive, got "
                                + memTableFlushThresholdBytes);
            }
            this.memTableFlushThresholdBytes = memTableFlushThresholdBytes;
            return this;
        }

        public LsmFullTextIndexFactory build() {
            Objects.requireNonNull(rootDirectory, "rootDirectory must not be null");
            return new LsmFullTextIndexFactory(this);
        }
    }
}
