package jlsm.wal.remote;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import jlsm.core.wal.WriteAheadLog;
import jlsm.core.io.ArenaBufferPool;
import jlsm.wal.internal.SegmentFile;
import jlsm.wal.internal.WalRecord;

/**
 * WAL implementation for network/object storage backends.
 *
 * <p>Each {@link #append} writes exactly one record into a brand-new immutable segment file named
 * {@code wal-{seqnum:016d}.log}. Files are written once via a single positioned write and never
 * modified afterwards. No mmap is used for writes.
 *
 * <p>Recovery is O(1): the highest sequence number is inferred from the lexicographically largest
 * file name — no record scanning required.
 */
public final class RemoteWriteAheadLog implements WriteAheadLog {

    private static final long DEFAULT_READ_BUFFER_SIZE = 16L * 1024 * 1024; // 16 MiB
    private static final int DEFAULT_POOL_SIZE = 4;
    private static final long DEFAULT_BUFFER_SIZE = 1024 * 1024L; // 1 MiB for writes
    private static final long DEFAULT_ACQUIRE_TIMEOUT_MILLIS = 10_000L;

    private final Path directory;
    private final long readBufferSize;
    private final ArenaBufferPool bufferPool;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong nextSequence = new AtomicLong(1L);

    private RemoteWriteAheadLog(Path directory, long readBufferSize, ArenaBufferPool bufferPool,
            long initialNextSequence) {
        assert directory != null : "directory must not be null";
        assert readBufferSize > 0 : "readBufferSize must be positive";
        assert bufferPool != null : "bufferPool must not be null";

        this.directory = directory;
        this.readBufferSize = readBufferSize;
        this.bufferPool = bufferPool;
        this.nextSequence.set(initialNextSequence);
    }

    // -------------------------------------------------------------------------
    // WriteAheadLog interface
    // -------------------------------------------------------------------------

    @Override
    public SequenceNumber append(Entry entry) throws IOException {
        Objects.requireNonNull(entry, "entry must not be null");

        writeLock.lock();
        try {
            long seq = nextSequence.getAndIncrement();
            Entry stamped = stamp(entry, new SequenceNumber(seq));

            MemorySegment buf = bufferPool.acquire();
            try {
                int n = WalRecord.encode(stamped, buf);
                Path target = directory.resolve(SegmentFile.toFileName(seq));

                try (FileChannel fc = FileChannel.open(target,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer bb = buf.asByteBuffer().limit(n);
                    int written = 0;
                    while (written < n) {
                        written += fc.write(bb, written);
                    }
                    fc.force(false);
                }
                return new SequenceNumber(seq);
            } finally {
                bufferPool.release(buf);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Iterator<Entry> replay(SequenceNumber from) throws IOException {
        Objects.requireNonNull(from, "from must not be null");

        List<Path> all = SegmentFile.listSorted(directory);
        if (all.isEmpty()) return Collections.emptyIterator();

        // Filter to files with encoded seqnum >= from.value()
        List<Path> filtered = new ArrayList<>();
        for (Path p : all) {
            long seqNum = SegmentFile.parseNumber(p.getFileName().toString());
            if (seqNum >= from.value()) filtered.add(p);
        }

        return new ReplayIterator(filtered, readBufferSize);
    }

    @Override
    public void truncateBefore(SequenceNumber upTo) throws IOException {
        Objects.requireNonNull(upTo, "upTo must not be null");

        List<Path> all = SegmentFile.listSorted(directory);
        for (Path p : all) {
            long seqNum = SegmentFile.parseNumber(p.getFileName().toString());
            if (seqNum < upTo.value()) {
                java.nio.file.Files.deleteIfExists(p);
            }
        }
    }

    @Override
    public SequenceNumber lastSequenceNumber() throws IOException {
        long next = nextSequence.get();
        if (next <= 1L) return SequenceNumber.ZERO;
        return new SequenceNumber(next - 1L);
    }

    @Override
    public void close() throws IOException {
        bufferPool.close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Entry stamp(Entry entry, SequenceNumber seq) {
        return switch (entry) {
            case Entry.Put put -> new Entry.Put(put.key(), put.value(), seq);
            case Entry.Delete del -> new Entry.Delete(del.key(), seq);
        };
    }

    // -------------------------------------------------------------------------
    // Replay iterator
    // -------------------------------------------------------------------------

    private static final class ReplayIterator implements Iterator<Entry> {

        private final List<Path> segments;
        private final long readBufferSize;
        private int segIdx = 0;
        private Entry next;

        ReplayIterator(List<Path> segments, long readBufferSize) {
            this.segments = segments;
            this.readBufferSize = readBufferSize;
            advance();
        }

        private void advance() {
            next = null;
            while (next == null && segIdx < segments.size()) {
                Path path = segments.get(segIdx++);
                next = readRecord(path);
            }
        }

        private Entry readRecord(Path path) {
            try {
                long fileSize = java.nio.file.Files.size(path);
                if (fileSize == 0) return null;

                try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
                    // Use mmap for the read (file is small — one record)
                    long mapSize = Math.min(fileSize, readBufferSize);
                    MappedByteBuffer mbb = fc.map(FileChannel.MapMode.READ_ONLY, 0, mapSize);
                    MemorySegment seg = MemorySegment.ofBuffer(mbb);
                    try {
                        return WalRecord.decode(seg, 0, mapSize);
                    } catch (IOException e) {
                        // Partial or corrupt record — skip silently
                        return null;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Entry next() {
            if (next == null) throw new NoSuchElementException();
            Entry result = next;
            advance();
            return result;
        }
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path directory;
        private long readBufferSize = DEFAULT_READ_BUFFER_SIZE;
        private int poolSize = DEFAULT_POOL_SIZE;
        private long bufferSize = DEFAULT_BUFFER_SIZE;
        private long acquireTimeoutMillis = DEFAULT_ACQUIRE_TIMEOUT_MILLIS;

        public Builder directory(Path directory) {
            Objects.requireNonNull(directory, "directory must not be null");
            this.directory = directory;
            return this;
        }

        public Builder readBufferSize(long readBufferSize) {
            if (readBufferSize < 1) throw new IllegalArgumentException("readBufferSize must be >= 1");
            this.readBufferSize = readBufferSize;
            return this;
        }

        public Builder poolSize(int poolSize) {
            if (poolSize < 1) throw new IllegalArgumentException("poolSize must be >= 1");
            this.poolSize = poolSize;
            return this;
        }

        public Builder bufferSize(long bufferSize) {
            if (bufferSize < 1) throw new IllegalArgumentException("bufferSize must be >= 1");
            this.bufferSize = bufferSize;
            return this;
        }

        public Builder acquireTimeoutMillis(long millis) {
            if (millis <= 0) throw new IllegalArgumentException("acquireTimeoutMillis must be > 0");
            this.acquireTimeoutMillis = millis;
            return this;
        }

        public RemoteWriteAheadLog build() throws IOException {
            Objects.requireNonNull(directory, "directory must not be null");

            // Recovery: find max seqnum from file names
            List<Path> existing = SegmentFile.listSorted(directory);
            long initialNext = 1L;
            if (!existing.isEmpty()) {
                Path last = existing.get(existing.size() - 1);
                long maxSeq = SegmentFile.parseNumber(last.getFileName().toString());
                assert maxSeq >= 0 : "invalid segment file found during recovery";
                initialNext = maxSeq + 1L;
            }

            ArenaBufferPool pool = ArenaBufferPool.builder()
                    .poolSize(poolSize)
                    .bufferSize(bufferSize)
                    .acquireTimeoutMillis(acquireTimeoutMillis)
                    .build();
            return new RemoteWriteAheadLog(directory, readBufferSize, pool, initialNext);
        }
    }
}
