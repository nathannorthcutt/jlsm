package jlsm.tree;

import jlsm.compaction.SpookyCompactor;
import jlsm.core.compaction.CompactionTask;
import jlsm.core.compaction.Compactor;
import jlsm.core.memtable.MemTable;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import jlsm.core.sstable.SSTableReader;
import jlsm.core.tree.LsmTree;
import jlsm.core.wal.WriteAheadLog;
import jlsm.tree.internal.MergeIterator;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Standard LSM-Tree implementation that composes a WAL, MemTable, SSTableWriter, and SSTableReader.
 *
 * <p>
 * <b>Write path</b>: every mutation is first appended to the WAL (assigned a sequence number), then
 * applied to the active MemTable. When {@code approximateSizeBytes() >= flushThresholdBytes}, the
 * MemTable is rotated and flushed to an L0 SSTable.
 *
 * <p>
 * <b>Read path</b>: the active MemTable is checked first, then each SSTable reader in newest-first
 * order (index 0 = most recently flushed).
 *
 * <p>
 * <b>Threading</b>: writes and flushes are serialised by a single {@link ReentrantLock}; reads
 * ({@link #get}, {@link #scan}) are lock-free because {@link CopyOnWriteArrayList} provides a
 * consistent snapshot of the SSTable reader list.
 */
public final class StandardLsmTree implements LsmTree {

    private static final long DEFAULT_FLUSH_THRESHOLD = 64L * 1024 * 1024; // 64 MiB

    private final WriteAheadLog wal;
    private final Supplier<MemTable> memTableFactory;
    private final SSTableWriterFactory writerFactory;
    private final SSTableReaderFactory readerFactory;
    private final LongSupplier idSupplier;
    private final BiFunction<Long, Level, Path> pathFn;
    private final long flushThresholdBytes;
    private final Compactor compactor;

    /** The MemTable currently accepting writes. Rotated under {@link #writeLock}. */
    private volatile MemTable activeMemTable;

    /**
     * SSTable readers in newest-first order (index 0 = most recently flushed L0 file). Reads are
     * lock-free; flushes prepend under {@link #writeLock}.
     */
    private final CopyOnWriteArrayList<SSTableReader> sstableReaders;

    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed = false;

    private StandardLsmTree(Builder builder, MemTable initialMemTable,
            CopyOnWriteArrayList<SSTableReader> existingReaders) {
        this.wal = builder.wal;
        this.memTableFactory = builder.memTableFactory;
        this.writerFactory = builder.writerFactory;
        this.readerFactory = builder.readerFactory;
        this.idSupplier = builder.idSupplier;
        this.pathFn = builder.pathFn;
        this.flushThresholdBytes = builder.flushThresholdBytes;
        this.compactor = builder.compactor;
        this.activeMemTable = initialMemTable;
        this.sstableReaders = existingReaders;
    }

    // -----------------------------------------------------------------------
    // Write path
    // -----------------------------------------------------------------------

    @Override
    public void put(MemorySegment key, MemorySegment value) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        checkNotClosed();

        writeLock.lock();
        try {
            // WAL assigns the sequence number; pass ZERO as sentinel for the initial entry
            SequenceNumber seq = wal.append(new Entry.Put(key, value, SequenceNumber.ZERO));
            activeMemTable.apply(new Entry.Put(key, value, seq));
            maybeFlush();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void delete(MemorySegment key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();

        writeLock.lock();
        try {
            SequenceNumber seq = wal.append(new Entry.Delete(key, SequenceNumber.ZERO));
            activeMemTable.apply(new Entry.Delete(key, seq));
            maybeFlush();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Flushes if the active MemTable has grown past the threshold. Must hold {@link #writeLock}.
     */
    private void maybeFlush() throws IOException {
        if (activeMemTable.approximateSizeBytes() >= flushThresholdBytes) {
            flush();
            maybeCompact();
        }
    }

    /**
     * Runs one round of compaction if the compactor selects a task. Must hold {@link #writeLock}.
     */
    private void maybeCompact() throws IOException {
        List<List<SSTableMetadata>> levelMetadata = buildLevelMetadata();
        Optional<CompactionTask> task = compactor.selectCompaction(levelMetadata);
        if (task.isPresent()) {
            List<SSTableMetadata> outputs = compactor.compact(task.get());
            applyCompactionResult(task.get(), outputs);
        }
    }

    private List<List<SSTableMetadata>> buildLevelMetadata() {
        int maxLevel = 0;
        for (SSTableReader reader : sstableReaders) {
            maxLevel = Math.max(maxLevel, reader.metadata().level().index());
        }
        List<List<SSTableMetadata>> result = new ArrayList<>();
        for (int i = 0; i <= maxLevel; i++) {
            result.add(new ArrayList<>());
        }
        for (SSTableReader reader : sstableReaders) {
            result.get(reader.metadata().level().index()).add(reader.metadata());
        }
        return result;
    }

    private void applyCompactionResult(CompactionTask task, List<SSTableMetadata> outputs)
            throws IOException {
        Set<Long> sourceIds = new HashSet<>();
        for (SSTableMetadata m : task.sourceSSTables()) {
            sourceIds.add(m.id());
        }
        List<SSTableReader> toClose = new ArrayList<>();
        sstableReaders.removeIf(r -> {
            if (sourceIds.contains(r.metadata().id())) {
                toClose.add(r);
                return true;
            }
            return false;
        });
        for (SSTableMetadata out : outputs) {
            sstableReaders.add(readerFactory.open(out.path()));
        }
        for (SSTableReader r : toClose) {
            try {
                r.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Rotates the active MemTable and writes its contents to a new L0 SSTable. Must hold
     * {@link #writeLock}.
     */
    private void flush() throws IOException {
        if (activeMemTable.isEmpty())
            return;

        MemTable immutable = activeMemTable;
        activeMemTable = memTableFactory.get();

        long id = idSupplier.getAsLong();
        Path path = pathFn.apply(id, Level.L0);

        SequenceNumber maxSeq = SequenceNumber.ZERO;

        try (var writer = writerFactory.create(id, Level.L0, path)) {
            MemorySegment lastKey = null;
            Iterator<Entry> it = immutable.scan();
            while (it.hasNext()) {
                Entry entry = it.next();
                // MemTable.scan() yields multiple seqNums per key (DESC); skip older versions
                if (lastKey != null && MergeIterator.keysEqual(lastKey, entry.key())) {
                    continue;
                }
                lastKey = entry.key();
                writer.append(entry);
                if (entry.sequenceNumber().compareTo(maxSeq) > 0) {
                    maxSeq = entry.sequenceNumber();
                }
            }
            writer.finish();
        }

        SSTableReader reader = readerFactory.open(path);
        sstableReaders.add(0, reader); // prepend: newest at index 0

        // Truncate WAL entries that are now safely persisted in the SSTable
        wal.truncateBefore(maxSeq.next());
    }

    // -----------------------------------------------------------------------
    // Read path
    // -----------------------------------------------------------------------

    @Override
    public Optional<MemorySegment> get(MemorySegment key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();

        // Check active MemTable first (lock-free: ConcurrentSkipListMemTable is thread-safe)
        Optional<Entry> memResult = activeMemTable.get(key);
        if (memResult.isPresent()) {
            return mapEntry(memResult.get());
        }

        // Fall through to SSTables, newest first
        for (SSTableReader reader : sstableReaders) {
            Optional<Entry> sstResult = reader.get(key);
            if (sstResult.isPresent()) {
                return mapEntry(sstResult.get());
            }
        }

        return Optional.empty();
    }

    @Override
    public Iterator<Entry> scan() throws IOException {
        checkNotClosed();

        List<Iterator<Entry>> sources = new ArrayList<>();
        sources.add(activeMemTable.scan());
        for (SSTableReader reader : sstableReaders) {
            sources.add(reader.scan());
        }
        return new MergeIterator(sources);
    }

    @Override
    public Iterator<Entry> scan(MemorySegment from, MemorySegment to) throws IOException {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        checkNotClosed();

        List<Iterator<Entry>> sources = new ArrayList<>();
        sources.add(activeMemTable.scan(from, to));
        for (SSTableReader reader : sstableReaders) {
            sources.add(reader.scan(from, to));
        }
        return new MergeIterator(sources, from, to);
    }

    /** Maps an Entry to an Optional value: Put → value present, Delete → empty. */
    private static Optional<MemorySegment> mapEntry(Entry entry) {
        return switch (entry) {
            case Entry.Put put -> Optional.of(put.value());
            case Entry.Delete _ -> Optional.empty();
        };
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void close() throws IOException {
        closed = true;
        IOException deferred = null;

        try {
            wal.close();
        } catch (IOException e) {
            deferred = e;
        }

        for (SSTableReader reader : sstableReaders) {
            try {
                reader.close();
            } catch (IOException e) {
                if (deferred == null)
                    deferred = e;
            }
        }

        if (compactor instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception ignored) {
            }
        }

        if (deferred != null)
            throw deferred;
    }

    private void checkNotClosed() {
        if (closed)
            throw new IllegalStateException("LsmTree is closed");
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private WriteAheadLog wal;
        private Supplier<MemTable> memTableFactory;
        private SSTableWriterFactory writerFactory;
        private SSTableReaderFactory readerFactory;
        private LongSupplier idSupplier;
        private BiFunction<Long, Level, Path> pathFn;
        private long flushThresholdBytes = DEFAULT_FLUSH_THRESHOLD;
        private boolean recoverFromWal = true;
        private List<SSTableReader> existingSSTables = List.of();
        private Compactor compactor;
        private jlsm.core.bloom.BloomFilter.Deserializer bloomDeserializer;

        public Builder wal(WriteAheadLog wal) {
            this.wal = Objects.requireNonNull(wal, "wal must not be null");
            return this;
        }

        public Builder memTableFactory(Supplier<MemTable> memTableFactory) {
            this.memTableFactory = Objects.requireNonNull(memTableFactory,
                    "memTableFactory must not be null");
            return this;
        }

        public Builder sstableWriterFactory(SSTableWriterFactory writerFactory) {
            this.writerFactory = Objects.requireNonNull(writerFactory,
                    "writerFactory must not be null");
            return this;
        }

        public Builder sstableReaderFactory(SSTableReaderFactory readerFactory) {
            this.readerFactory = Objects.requireNonNull(readerFactory,
                    "readerFactory must not be null");
            return this;
        }

        public Builder idSupplier(LongSupplier idSupplier) {
            this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier must not be null");
            return this;
        }

        public Builder pathFn(BiFunction<Long, Level, Path> pathFn) {
            this.pathFn = Objects.requireNonNull(pathFn, "pathFn must not be null");
            return this;
        }

        public Builder memTableFlushThresholdBytes(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException(
                        "flushThresholdBytes must be positive, got: " + bytes);
            }
            this.flushThresholdBytes = bytes;
            return this;
        }

        public Builder recoverFromWal(boolean recover) {
            this.recoverFromWal = recover;
            return this;
        }

        public Builder existingSSTables(List<SSTableReader> readers) {
            this.existingSSTables = Objects.requireNonNull(readers,
                    "existingSSTables must not be null");
            return this;
        }

        public Builder compactor(Compactor compactor) {
            this.compactor = Objects.requireNonNull(compactor, "compactor must not be null");
            return this;
        }

        /**
         * Overrides the bloom-filter deserializer passed to the default {@link SpookyCompactor}
         * when no explicit {@link #compactor} is set. Must match the filter used when writing the
         * SSTables this tree will compact. If not set, defaults to
         * {@code BlockedBloomFilter.deserializer()}.
         */
        public Builder bloomDeserializer(jlsm.core.bloom.BloomFilter.Deserializer deserializer) {
            this.bloomDeserializer = Objects.requireNonNull(deserializer,
                    "bloomDeserializer must not be null");
            return this;
        }

        public StandardLsmTree build() throws IOException {
            Objects.requireNonNull(wal, "wal must not be null");
            Objects.requireNonNull(memTableFactory, "memTableFactory must not be null");
            Objects.requireNonNull(writerFactory, "writerFactory must not be null");
            Objects.requireNonNull(readerFactory, "readerFactory must not be null");
            Objects.requireNonNull(idSupplier, "idSupplier must not be null");
            Objects.requireNonNull(pathFn, "pathFn must not be null");

            if (compactor == null) {
                SpookyCompactor.Builder compactorBuilder = SpookyCompactor.builder()
                        .idSupplier(idSupplier).pathFn(pathFn);
                if (bloomDeserializer != null) {
                    compactorBuilder.bloomDeserializer(bloomDeserializer);
                }
                compactor = compactorBuilder.build();
            }

            MemTable initialMemTable = memTableFactory.get();

            if (recoverFromWal) {
                Iterator<Entry> replayed = wal.replay(SequenceNumber.ZERO);
                replayed.forEachRemaining(initialMemTable::apply);
            }

            return new StandardLsmTree(this, initialMemTable,
                    new CopyOnWriteArrayList<>(existingSSTables));
        }
    }
}
