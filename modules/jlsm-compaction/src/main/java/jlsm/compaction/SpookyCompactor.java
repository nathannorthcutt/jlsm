package jlsm.compaction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.compaction.internal.KeyRangeUtil;
import jlsm.compaction.internal.MergeIterator;
import jlsm.core.compaction.CompactionTask;
import jlsm.core.compaction.Compactor;
import jlsm.core.io.ArenaBufferPool;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.sstable.SSTableMetadata;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;

/**
 * SPOOKY compaction strategy (Dayan et al., VLDB 2022).
 *
 * <p>Merges one group of perfectly overlapping files at a time, using the bottom level's file
 * boundaries to define groups across all levels. Achieves ~2x lower space amplification than Full
 * Merge and ~2x lower write amplification than Partial Merge simultaneously.
 */
public final class SpookyCompactor implements Compactor, AutoCloseable {

    private final LongSupplier idSupplier;
    private final BiFunction<Long, Level, Path> pathFn;
    private final long targetBottomFileSizeBytes;
    private final ArenaBufferPool bufferPool;
    private final jlsm.core.bloom.BloomFilter.Deserializer bloomDeserializer;

    private SpookyCompactor(Builder builder) {
        this.idSupplier = builder.idSupplier;
        this.pathFn = builder.pathFn;
        this.targetBottomFileSizeBytes = builder.targetBottomFileSizeBytes;
        this.bloomDeserializer = builder.bloomDeserializer;
        this.bufferPool = ArenaBufferPool.builder()
                .poolSize(builder.poolSize)
                .bufferSize(builder.poolBufferSizeBytes)
                .acquireTimeoutMillis(builder.acquireTimeoutMillis)
                .build();
    }

    // -------------------------------------------------------------------------
    // selectCompaction
    // -------------------------------------------------------------------------

    @Override
    public Optional<CompactionTask> selectCompaction(List<List<SSTableMetadata>> levelMetadata) {
        Objects.requireNonNull(levelMetadata, "levelMetadata must not be null");

        // Find the bottom-most non-empty level
        int bottomLevelIdx = -1;
        for (int i = levelMetadata.size() - 1; i >= 0; i--) {
            if (!levelMetadata.get(i).isEmpty()) {
                bottomLevelIdx = i;
                break;
            }
        }

        if (bottomLevelIdx < 0) return Optional.empty();

        Level bottomLevel = new Level(bottomLevelIdx);

        // Bootstrap: only L0 is populated → compact all L0 files into L1
        if (bottomLevelIdx == 0) {
            List<SSTableMetadata> l0 = levelMetadata.get(0);
            return Optional.of(new CompactionTask(new ArrayList<>(l0), Level.L0, new Level(1)));
        }

        // For each bottom-level file, build a candidate group and score it
        List<SSTableMetadata> bottomFiles = levelMetadata.get(bottomLevelIdx);

        List<SSTableMetadata> bestGroup = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (SSTableMetadata bottomFile : bottomFiles) {
            List<SSTableMetadata> group = new ArrayList<>();
            group.add(bottomFile);
            long upperSizeBytes = 0L;

            // Collect overlapping files from all upper levels
            for (int lvl = bottomLevelIdx - 1; lvl >= 0; lvl--) {
                List<SSTableMetadata> levelFiles = levelMetadata.get(lvl);
                for (SSTableMetadata upperFile : levelFiles) {
                    if (KeyRangeUtil.overlaps(upperFile, bottomFile)) {
                        group.add(upperFile);
                        upperSizeBytes += upperFile.sizeBytes();
                    }
                }
            }

            double score = (double) upperSizeBytes / Math.max(1L, bottomFile.sizeBytes());

            if (group.size() > 1 && score > bestScore) {
                bestScore = score;
                bestGroup = group;
            }
        }

        if (bestGroup == null) return Optional.empty();

        // sourceLevel = lowest level index among group files
        int minLevelIdx = Integer.MAX_VALUE;
        for (SSTableMetadata m : bestGroup) {
            if (m.level().index() < minLevelIdx) {
                minLevelIdx = m.level().index();
            }
        }

        return Optional.of(new CompactionTask(bestGroup, new Level(minLevelIdx), bottomLevel));
    }

    // -------------------------------------------------------------------------
    // compact
    // -------------------------------------------------------------------------

    @Override
    public List<SSTableMetadata> compact(CompactionTask task) throws IOException {
        Objects.requireNonNull(task, "task must not be null");

        List<SSTableMetadata> sources = task.sourceSSTables();
        Level targetLevel = task.targetLevel();

        // Determine if this is a bottom-level compaction (tombstones can be dropped)
        int maxSourceLevelIdx = 0;
        for (SSTableMetadata m : sources) {
            if (m.level().index() > maxSourceLevelIdx) {
                maxSourceLevelIdx = m.level().index();
            }
        }
        boolean isBottomLevel = (targetLevel.index() == maxSourceLevelIdx);

        // Open all source SSTables (eager: one big read per file)
        List<TrieSSTableReader> readers = new ArrayList<>(sources.size());
        try {
            for (SSTableMetadata meta : sources) {
                readers.add(TrieSSTableReader.open(meta.path(), bloomDeserializer));
            }

            // Build merge iterator
            List<Iterator<Entry>> iters = new ArrayList<>(readers.size());
            for (TrieSSTableReader reader : readers) {
                iters.add(reader.scan());
            }
            MergeIterator merge = new MergeIterator(iters);

            return writeMergedOutput(merge, targetLevel, isBottomLevel);

        } finally {
            for (TrieSSTableReader r : readers) {
                try { r.close(); } catch (IOException ignored) {}
            }
        }
    }

    private List<SSTableMetadata> writeMergedOutput(MergeIterator merge, Level targetLevel,
            boolean isBottomLevel) throws IOException {
        List<SSTableMetadata> output = new ArrayList<>();
        TrieSSTableWriter writer = null;
        java.lang.foreign.MemorySegment scratchBuf = null;

        try {
            scratchBuf = bufferPool.acquire();

            Entry prev = null;

            while (merge.hasNext()) {
                Entry entry = merge.next();

                // Dedup: skip lower-seqnum versions of the same key
                if (prev != null && KeyRangeUtil.keysEqual(entry.key(), prev.key())) {
                    continue;
                }

                // Drop tombstones at the bottom level
                if (isBottomLevel && entry instanceof Entry.Delete) {
                    prev = entry;
                    continue;
                }

                // Open a new writer if needed
                if (writer == null) {
                    long id = idSupplier.getAsLong();
                    Path outputPath = pathFn.apply(id, targetLevel);
                    writer = new TrieSSTableWriter(id, targetLevel, outputPath);
                }

                writer.append(entry);
                prev = entry;

                // Split output file if it has reached the target size
                if (writer.approximateSizeBytes() >= targetBottomFileSizeBytes) {
                    output.add(writer.finish());
                    writer.close();
                    writer = null;
                }
            }

            // Finish any remaining in-progress writer
            if (writer != null && writer.entryCount() > 0) {
                output.add(writer.finish());
                writer.close();
                writer = null;
            }

        } catch (IOException | RuntimeException e) {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) {}
                writer = null;
            }
            throw (e instanceof IOException ioe) ? ioe : new IOException("merge failed", e);
        } finally {
            if (scratchBuf != null) {
                bufferPool.release(scratchBuf);
            }
        }

        return output;
    }

    @Override
    public void close() {
        bufferPool.close();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LongSupplier idSupplier;
        private BiFunction<Long, Level, Path> pathFn;
        private long targetBottomFileSizeBytes = 64L * 1024 * 1024;
        private int poolSize = 4;
        private long poolBufferSizeBytes = 1024 * 1024L;
        private long acquireTimeoutMillis = 30_000L;
        private jlsm.core.bloom.BloomFilter.Deserializer bloomDeserializer =
                BlockedBloomFilter.deserializer();

        public Builder idSupplier(LongSupplier idSupplier) {
            Objects.requireNonNull(idSupplier, "idSupplier must not be null");
            this.idSupplier = idSupplier;
            return this;
        }

        public Builder pathFn(BiFunction<Long, Level, Path> pathFn) {
            Objects.requireNonNull(pathFn, "pathFn must not be null");
            this.pathFn = pathFn;
            return this;
        }

        public Builder targetBottomFileSizeBytes(long bytes) {
            if (bytes < 1) throw new IllegalArgumentException("targetBottomFileSizeBytes must be >= 1");
            this.targetBottomFileSizeBytes = bytes;
            return this;
        }

        public Builder poolSize(int poolSize) {
            if (poolSize < 1) throw new IllegalArgumentException("poolSize must be >= 1");
            this.poolSize = poolSize;
            return this;
        }

        public Builder poolBufferSizeBytes(long bytes) {
            if (bytes < 1) throw new IllegalArgumentException("poolBufferSizeBytes must be >= 1");
            this.poolBufferSizeBytes = bytes;
            return this;
        }

        public Builder acquireTimeoutMillis(long millis) {
            if (millis <= 0) throw new IllegalArgumentException("acquireTimeoutMillis must be > 0");
            this.acquireTimeoutMillis = millis;
            return this;
        }

        /**
         * Overrides the {@link jlsm.core.bloom.BloomFilter.Deserializer} used to open source
         * SSTables during compaction. Defaults to {@code BlockedBloomFilter.deserializer()}.
         * Must match the deserializer used when those SSTables were originally written.
         */
        public Builder bloomDeserializer(jlsm.core.bloom.BloomFilter.Deserializer deserializer) {
            this.bloomDeserializer = Objects.requireNonNull(deserializer,
                    "bloomDeserializer must not be null");
            return this;
        }

        public SpookyCompactor build() {
            if (idSupplier == null) throw new IllegalStateException("idSupplier must be configured");
            if (pathFn == null) throw new IllegalStateException("pathFn must be configured");
            return new SpookyCompactor(this);
        }
    }
}
