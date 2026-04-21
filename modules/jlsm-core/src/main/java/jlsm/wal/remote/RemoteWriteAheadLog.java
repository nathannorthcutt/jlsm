package jlsm.wal.remote;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import jlsm.core.wal.WriteAheadLog;
import jlsm.core.io.ArenaBufferPool;
import jlsm.wal.internal.SegmentFile;
import jlsm.wal.internal.WalRecord;

/**
 * WAL implementation for network/object storage backends.
 *
 * <p>
 * Each {@link #append} writes exactly one record into a brand-new immutable segment file named
 * {@code wal-{seqnum:016d}.log}. Files are written once via a single positioned write and never
 * modified afterwards. No mmap is used for writes.
 *
 * <p>
 * Recovery is O(1): the highest sequence number is inferred from the lexicographically largest file
 * name — no record scanning required.
 */
public final class RemoteWriteAheadLog implements WriteAheadLog {

    private static final long DEFAULT_READ_BUFFER_SIZE = 16L * 1024 * 1024; // 16 MiB
    private static final int DEFAULT_POOL_SIZE = 4;
    private static final long DEFAULT_BUFFER_SIZE = 1024 * 1024L; // 1 MiB for writes
    private static final long DEFAULT_ACQUIRE_TIMEOUT_MILLIS = 10_000L;
    private static final int DEFAULT_COMPRESSION_MIN_SIZE = 64;
    private static final int DEFAULT_MAX_CONSECUTIVE_SKIPS = 10;

    private final Path directory;
    private final long readBufferSize;
    private final ArenaBufferPool bufferPool;
    private final CompressionCodec codec;
    private final int compressionMinSize;
    private final int maxConsecutiveSkips;
    private final Map<Byte, CompressionCodec> codecMap;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong nextSequence = new AtomicLong(1L);

    private RemoteWriteAheadLog(Path directory, long readBufferSize, ArenaBufferPool bufferPool,
            long initialNextSequence, CompressionCodec codec, int compressionMinSize,
            int maxConsecutiveSkips, Map<Byte, CompressionCodec> codecMap) {
        assert directory != null : "directory must not be null";
        assert readBufferSize > 0 : "readBufferSize must be positive";
        assert bufferPool != null : "bufferPool must not be null";
        assert codec != null : "codec must not be null";

        this.directory = directory;
        this.readBufferSize = readBufferSize;
        this.bufferPool = bufferPool;
        this.codec = codec;
        this.compressionMinSize = compressionMinSize;
        this.maxConsecutiveSkips = maxConsecutiveSkips;
        this.codecMap = codecMap;
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
                int n = encodeWithCompression(stamped, buf);
                Path target = directory.resolve(SegmentFile.toFileName(seq));

                try (SeekableByteChannel ch = Files.newByteChannel(target,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer bb = buf.asByteBuffer().limit(n);
                    while (bb.hasRemaining()) {
                        ch.write(bb);
                    }
                    if (ch instanceof java.nio.channels.FileChannel fc)
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

    /** Dummy segment used as compression buffer when pool is exhausted (R36). Never written to. */
    private static final MemorySegment DUMMY_COMPRESSION_BUF = MemorySegment.ofArray(new byte[0]);

    // @spec wal.compression.R8 — payload below threshold written uncompressed
    // @spec wal.compression.R9 — compressed+5 >= uncompressed -> write uncompressed
    // @spec wal.compression.R12 — same format semantics as LocalWriteAheadLog
    // @spec wal.compression.R19 — pool exhaustion falls back to uncompressed
    /**
     * Encodes an entry with compression using the new record format (flags byte). Falls back to
     * uncompressed new-format encoding on buffer acquisition failure (R36). The new format is
     * always used so that recovery with a codec map can read all records consistently.
     */
    private int encodeWithCompression(Entry stamped, MemorySegment buf) throws IOException {
        // Try to acquire a compression buffer; fall back to uncompressed on failure (R36)
        MemorySegment compressionBuf;
        try {
            compressionBuf = bufferPool.acquire();
        } catch (IOException e) {
            // Buffer exhausted — write uncompressed in new format (R36)
            // Use Integer.MAX_VALUE as minSize so compression is never attempted and the
            // compression buffer is never accessed.
            return WalRecord.encode(stamped, buf, codec, Integer.MAX_VALUE, DUMMY_COMPRESSION_BUF);
        }
        try {
            return WalRecord.encode(stamped, buf, codec, compressionMinSize, compressionBuf);
        } finally {
            bufferPool.release(compressionBuf);
        }
    }

    @Override
    public Iterator<Entry> replay(SequenceNumber from) throws IOException {
        Objects.requireNonNull(from, "from must not be null");

        List<Path> all = SegmentFile.listSorted(directory);
        if (all.isEmpty())
            return Collections.emptyIterator();

        // Filter to files with encoded seqnum >= from.value()
        List<Path> filtered = new ArrayList<>();
        for (Path p : all) {
            long seqNum = SegmentFile.parseNumber(p.getFileName().toString());
            if (seqNum >= from.value())
                filtered.add(p);
        }

        return new ReplayIterator(filtered, readBufferSize, codecMap, maxConsecutiveSkips);
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
        if (next <= 1L)
            return SequenceNumber.ZERO;
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
        private final Map<Byte, CompressionCodec> codecMap;
        private final int maxConsecutiveSkips;
        private int segIdx = 0;
        private Entry next;
        private int consecutiveSkips;

        ReplayIterator(List<Path> segments, long readBufferSize,
                Map<Byte, CompressionCodec> codecMap, int maxConsecutiveSkips) {
            this.segments = segments;
            this.readBufferSize = readBufferSize;
            this.codecMap = codecMap;
            this.maxConsecutiveSkips = maxConsecutiveSkips;
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
                if (fileSize == 0)
                    return null;

                try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ)) {
                    long readSize = Math.min(fileSize, readBufferSize);
                    ByteBuffer bb = ByteBuffer.allocate((int) readSize);
                    while (bb.hasRemaining()) {
                        if (ch.read(bb) < 0)
                            break;
                    }
                    bb.flip();
                    MemorySegment seg = MemorySegment.ofBuffer(bb);
                    try {
                        Entry entry = WalRecord.decode(seg, 0, bb.limit(), codecMap);
                        if (entry != null) {
                            consecutiveSkips = 0; // Reset on success
                        }
                        return entry;
                    } catch (IOException e) {
                        // Partial or corrupt record — skip (R33)
                        consecutiveSkips++;
                        if (consecutiveSkips > maxConsecutiveSkips) {
                            throw new UncheckedIOException(new IOException(
                                    "systematic codec failure: %d consecutive records skipped"
                                            .formatted(consecutiveSkips),
                                    e));
                        }
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
            if (next == null)
                throw new NoSuchElementException();
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
        // @spec wal.compression.R10 — default DEFLATE level 6 if no codec provided
        private CompressionCodec codec = CompressionCodec.deflate();
        // @spec wal.compression.R11 — default threshold 64 bytes
        private int compressionMinSize = DEFAULT_COMPRESSION_MIN_SIZE;
        // @spec wal.compression.R18 — 10 consecutive skips threshold (configurable)
        private int maxConsecutiveSkips = DEFAULT_MAX_CONSECUTIVE_SKIPS;
        private CompressionCodec[] recoveryCodecs;

        public Builder directory(Path directory) {
            Objects.requireNonNull(directory, "directory must not be null");
            this.directory = directory;
            return this;
        }

        public Builder readBufferSize(long readBufferSize) {
            if (readBufferSize < 1)
                throw new IllegalArgumentException("readBufferSize must be >= 1");
            this.readBufferSize = readBufferSize;
            return this;
        }

        public Builder poolSize(int poolSize) {
            if (poolSize < 1)
                throw new IllegalArgumentException("poolSize must be >= 1");
            this.poolSize = poolSize;
            return this;
        }

        public Builder bufferSize(long bufferSize) {
            if (bufferSize < 1)
                throw new IllegalArgumentException("bufferSize must be >= 1");
            this.bufferSize = bufferSize;
            return this;
        }

        public Builder acquireTimeoutMillis(long millis) {
            if (millis <= 0)
                throw new IllegalArgumentException("acquireTimeoutMillis must be > 0");
            this.acquireTimeoutMillis = millis;
            return this;
        }

        /** Sets the compression codec. Default is Deflate level 6 (R27). */
        public Builder compression(CompressionCodec codec) {
            Objects.requireNonNull(codec, "codec must not be null");
            this.codec = codec;
            return this;
        }

        /** Sets the minimum payload size for compression. Default is 64 bytes (R28). */
        public Builder compressionMinSize(int minBytes) {
            if (minBytes < 0) {
                throw new IllegalArgumentException(
                        "compressionMinSize must be non-negative, got: " + minBytes);
            }
            this.compressionMinSize = minBytes;
            return this;
        }

        /** Sets the max consecutive corrupt records before aborting recovery (R35). */
        public Builder maxConsecutiveSkips(int max) {
            if (max < 1) {
                throw new IllegalArgumentException("maxConsecutiveSkips must be >= 1, got: " + max);
            }
            this.maxConsecutiveSkips = max;
            return this;
        }

        /** Sets additional codecs for recovery. The write codec is always included (R30). */
        public Builder recoveryCodecs(CompressionCodec... codecs) {
            Objects.requireNonNull(codecs, "codecs must not be null");
            this.recoveryCodecs = codecs;
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

            ArenaBufferPool pool = ArenaBufferPool.builder().poolSize(poolSize)
                    .bufferSize(bufferSize).acquireTimeoutMillis(acquireTimeoutMillis).build();

            // Build codec map for recovery (R30)
            Map<Byte, CompressionCodec> cm = buildCodecMap();

            return new RemoteWriteAheadLog(directory, readBufferSize, pool, initialNext, codec,
                    compressionMinSize, maxConsecutiveSkips, cm);
        }

        private Map<Byte, CompressionCodec> buildCodecMap() {
            Map<Byte, CompressionCodec> map = new HashMap<>();
            // Always include the write codec
            map.put(codec.codecId(), codec);
            // Always include NoneCodec for uncompressed records decoded via new-format path
            map.put(CompressionCodec.none().codecId(), CompressionCodec.none());

            if (recoveryCodecs != null) {
                for (CompressionCodec rc : recoveryCodecs) {
                    Objects.requireNonNull(rc, "recovery codec must not be null");
                    // Allow overwriting the built-in entry — last-writer-wins for the same codec
                    // ID.
                    // R30 rejects only duplicate IDs within the user-provided set.
                    map.put(rc.codecId(), rc);
                }
                // Verify no duplicate IDs in the user-provided array itself
                java.util.Set<Byte> seen = new java.util.HashSet<>();
                for (CompressionCodec rc : recoveryCodecs) {
                    if (!seen.add(rc.codecId())) {
                        throw new IllegalArgumentException(
                                "duplicate codec ID 0x%02x in recovery codecs"
                                        .formatted(rc.codecId()));
                    }
                }
            }
            return Collections.unmodifiableMap(map);
        }
    }
}
