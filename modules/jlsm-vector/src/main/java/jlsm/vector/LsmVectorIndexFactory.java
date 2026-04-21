package jlsm.vector;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.indexing.VectorPrecision;
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
 * {@link VectorIndex.Factory} implementations backed by {@link LsmVectorIndex}. Two algorithm
 * flavours are exposed:
 * <ul>
 * <li>{@link #ivfFlat()} — produces {@link LsmVectorIndex.IvfFlat} instances</li>
 * <li>{@link #hnsw()} — produces {@link LsmVectorIndex.Hnsw} instances</li>
 * </ul>
 *
 * <p>
 * Each {@code (tableName, fieldName)} pair is rooted under
 * {@code <rootDirectory>/<tableName>/<fieldName>/} and backed by its own {@link LsmTree}. This
 * isolation prevents cross-index contention and makes lifecycle management symmetric — closing one
 * index does not affect others.
 *
 * <p>
 * This factory is the module-boundary bridge between {@code jlsm-vector} (which owns the
 * implementation) and {@code jlsm-table} (which consumes the interface). Construction of the
 * factory happens outside {@code jlsm-table}; the resulting factory is passed into table builders
 * that register VECTOR indices.
 */
// @spec query.vector-index.R1,R2,R3,R4,R5,R6 — factory produces a VectorIndex<MemorySegment> per
// (tableName, fieldName); the adapter in VectorFieldIndex routes mutations through it.
public final class LsmVectorIndexFactory {

    /**
     * Identity serializer for {@link MemorySegment} primary keys — each docId is stored as its
     * underlying bytes.
     */
    private static final MemorySerializer<MemorySegment> SEGMENT_SERIALIZER = new MemorySerializer<>() {

        @Override
        public MemorySegment serialize(MemorySegment value) {
            Objects.requireNonNull(value, "value must not be null");
            // Defensive copy: the factory owns the bytes handed off to the vector index.
            byte[] bytes = value.toArray(ValueLayout.JAVA_BYTE);
            return MemorySegment.ofArray(bytes);
        }

        @Override
        public MemorySegment deserialize(MemorySegment segment) {
            Objects.requireNonNull(segment, "segment must not be null");
            byte[] bytes = segment.toArray(ValueLayout.JAVA_BYTE);
            return MemorySegment.ofArray(bytes);
        }
    };

    private LsmVectorIndexFactory() {
        throw new UnsupportedOperationException("utility class");
    }

    /** Returns a builder for an {@link LsmVectorIndex.IvfFlat}-backed factory. */
    public static IvfFlatBuilder ivfFlat() {
        return new IvfFlatBuilder();
    }

    /** Returns a builder for an {@link LsmVectorIndex.Hnsw}-backed factory. */
    public static HnswBuilder hnsw() {
        return new HnswBuilder();
    }

    // ------------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------------

    private static Path indexRoot(Path rootDirectory, String tableName, String fieldName)
            throws IOException {
        Objects.requireNonNull(tableName, "tableName must not be null");
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        if (tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }
        Path indexRoot = rootDirectory.resolve(sanitize(tableName)).resolve(sanitize(fieldName));
        Files.createDirectories(indexRoot);
        return indexRoot;
    }

    private static LsmTree buildBackingTree(Path indexRoot, long memTableFlushThresholdBytes)
            throws IOException {
        final AtomicLong idCounter = new AtomicLong(0);
        return StandardLsmTree.builder()
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
    }

    private static void validateCreateArgs(int dimensions, VectorPrecision precision,
            SimilarityFunction similarityFunction) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be > 0, got: " + dimensions);
        }
        Objects.requireNonNull(precision, "precision must not be null");
        Objects.requireNonNull(similarityFunction, "similarityFunction must not be null");
    }

    /**
     * Strips path separators and other illegal filesystem characters so an arbitrary table or field
     * name can be used as a directory name on any platform.
     */
    private static String sanitize(String name) {
        assert name != null && !name.isBlank() : "name validated by caller";
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ------------------------------------------------------------------------
    // IvfFlat factory
    // ------------------------------------------------------------------------

    /** Builder for an {@link LsmVectorIndex.IvfFlat}-backed factory. */
    public static final class IvfFlatBuilder {

        private static final long DEFAULT_FLUSH_THRESHOLD = 64L * 1024L * 1024L; // 64 MiB
        private static final int DEFAULT_NUM_CLUSTERS = 256;
        private static final int DEFAULT_NPROBE = 8;

        private Path rootDirectory;
        private long memTableFlushThresholdBytes = DEFAULT_FLUSH_THRESHOLD;
        private int numClusters = DEFAULT_NUM_CLUSTERS;
        private int nprobe = DEFAULT_NPROBE;

        private IvfFlatBuilder() {
        }

        public IvfFlatBuilder rootDirectory(Path rootDirectory) {
            this.rootDirectory = Objects.requireNonNull(rootDirectory,
                    "rootDirectory must not be null");
            return this;
        }

        public IvfFlatBuilder memTableFlushThresholdBytes(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException(
                        "memTableFlushThresholdBytes must be positive, got " + bytes);
            }
            this.memTableFlushThresholdBytes = bytes;
            return this;
        }

        public IvfFlatBuilder numClusters(int n) {
            if (n <= 0) {
                throw new IllegalArgumentException("numClusters must be > 0, got " + n);
            }
            this.numClusters = n;
            return this;
        }

        public IvfFlatBuilder nprobe(int n) {
            if (n <= 0) {
                throw new IllegalArgumentException("nprobe must be > 0, got " + n);
            }
            this.nprobe = n;
            return this;
        }

        public VectorIndex.Factory build() {
            Objects.requireNonNull(rootDirectory, "rootDirectory must not be null");
            final Path root = rootDirectory;
            final long flushBytes = memTableFlushThresholdBytes;
            final int clusters = numClusters;
            final int probes = nprobe;
            return new VectorIndex.Factory() {

                @Override
                public VectorIndex<MemorySegment> create(String tableName, String fieldName,
                        int dimensions, VectorPrecision precision,
                        SimilarityFunction similarityFunction) throws IOException {
                    validateCreateArgs(dimensions, precision, similarityFunction);
                    Path idxRoot = indexRoot(root, tableName, fieldName);
                    LsmTree tree = buildBackingTree(idxRoot, flushBytes);
                    try {
                        return LsmVectorIndex.<MemorySegment>ivfFlatBuilder().lsmTree(tree)
                                .docIdSerializer(SEGMENT_SERIALIZER).dimensions(dimensions)
                                .precision(precision).similarityFunction(similarityFunction)
                                .numClusters(clusters).nprobe(probes).build();
                    } catch (RuntimeException e) {
                        try {
                            tree.close();
                        } catch (IOException suppressed) {
                            e.addSuppressed(suppressed);
                        }
                        throw e;
                    }
                }
            };
        }
    }

    // ------------------------------------------------------------------------
    // Hnsw factory
    // ------------------------------------------------------------------------

    /** Builder for an {@link LsmVectorIndex.Hnsw}-backed factory. */
    public static final class HnswBuilder {

        private static final long DEFAULT_FLUSH_THRESHOLD = 64L * 1024L * 1024L; // 64 MiB
        private static final int DEFAULT_MAX_CONNECTIONS = 16;
        private static final int DEFAULT_EF_CONSTRUCTION = 200;
        private static final int DEFAULT_EF_SEARCH = 50;

        private Path rootDirectory;
        private long memTableFlushThresholdBytes = DEFAULT_FLUSH_THRESHOLD;
        private int maxConnections = DEFAULT_MAX_CONNECTIONS;
        private int efConstruction = DEFAULT_EF_CONSTRUCTION;
        private int efSearch = DEFAULT_EF_SEARCH;

        private HnswBuilder() {
        }

        public HnswBuilder rootDirectory(Path rootDirectory) {
            this.rootDirectory = Objects.requireNonNull(rootDirectory,
                    "rootDirectory must not be null");
            return this;
        }

        public HnswBuilder memTableFlushThresholdBytes(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException(
                        "memTableFlushThresholdBytes must be positive, got " + bytes);
            }
            this.memTableFlushThresholdBytes = bytes;
            return this;
        }

        public HnswBuilder maxConnections(int n) {
            if (n <= 0) {
                throw new IllegalArgumentException("maxConnections must be > 0, got " + n);
            }
            this.maxConnections = n;
            return this;
        }

        public HnswBuilder efConstruction(int n) {
            if (n <= 0) {
                throw new IllegalArgumentException("efConstruction must be > 0, got " + n);
            }
            this.efConstruction = n;
            return this;
        }

        public HnswBuilder efSearch(int n) {
            if (n <= 0) {
                throw new IllegalArgumentException("efSearch must be > 0, got " + n);
            }
            this.efSearch = n;
            return this;
        }

        public VectorIndex.Factory build() {
            Objects.requireNonNull(rootDirectory, "rootDirectory must not be null");
            final Path root = rootDirectory;
            final long flushBytes = memTableFlushThresholdBytes;
            final int mc = maxConnections;
            final int efc = efConstruction;
            final int efs = efSearch;
            return new VectorIndex.Factory() {

                @Override
                public VectorIndex<MemorySegment> create(String tableName, String fieldName,
                        int dimensions, VectorPrecision precision,
                        SimilarityFunction similarityFunction) throws IOException {
                    validateCreateArgs(dimensions, precision, similarityFunction);
                    Path idxRoot = indexRoot(root, tableName, fieldName);
                    LsmTree tree = buildBackingTree(idxRoot, flushBytes);
                    try {
                        return LsmVectorIndex.<MemorySegment>hnswBuilder().lsmTree(tree)
                                .docIdSerializer(SEGMENT_SERIALIZER).dimensions(dimensions)
                                .precision(precision).similarityFunction(similarityFunction)
                                .maxConnections(mc).efConstruction(efc).efSearch(efs).build();
                    } catch (RuntimeException e) {
                        try {
                            tree.close();
                        } catch (IOException suppressed) {
                            e.addSuppressed(suppressed);
                        }
                        throw e;
                    }
                }
            };
        }
    }
}
