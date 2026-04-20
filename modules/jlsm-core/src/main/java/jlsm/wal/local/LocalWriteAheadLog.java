package jlsm.wal.local;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import jlsm.core.wal.WriteAheadLog;
import jlsm.core.io.ArenaBufferPool;
import jlsm.wal.internal.SegmentFile;
import jlsm.wal.internal.WalRecord;

/**
 * WAL implementation for local filesystems using memory-mapped I/O.
 *
 * <p>
 * Each segment file is pre-allocated to {@code segmentSize} bytes and mapped READ_WRITE. Records
 * are appended at {@code writePosition} and forced to disk after every write. When a segment is
 * full the file is truncated to the valid write position and a new segment is created.
 */
public final class LocalWriteAheadLog implements WriteAheadLog {

    private static final long DEFAULT_SEGMENT_SIZE = 64L * 1024 * 1024; // 64 MiB
    private static final int DEFAULT_POOL_SIZE = 4;
    private static final long DEFAULT_BUFFER_SIZE = 1024 * 1024L; // 1 MiB
    private static final long DEFAULT_ACQUIRE_TIMEOUT_MILLIS = 5_000L;
    private static final int DEFAULT_COMPRESSION_MIN_SIZE = 64;
    private static final int DEFAULT_MAX_CONSECUTIVE_SKIPS = 10;

    private final Path directory;
    private final long segmentSize;
    private final ArenaBufferPool bufferPool;
    private final CompressionCodec codec;
    private final int compressionMinSize;
    private final int maxConsecutiveSkips;
    private final Map<Byte, CompressionCodec> codecMap;

    // Write path — serialized by writeLock
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong nextSequence = new AtomicLong(1L);
    private long activeSegmentNumber;
    private MappedByteBuffer mappedBuffer;
    private FileChannel activeChannel;
    private int writePosition;

    // segmentNumber → first sequence value in that segment
    private final NavigableMap<Long, Long> segmentIndex = new ConcurrentSkipListMap<>();
    private final ReentrantReadWriteLock indexLock = new ReentrantReadWriteLock();

    private LocalWriteAheadLog(Path directory, long segmentSize, ArenaBufferPool bufferPool,
            CompressionCodec codec, int compressionMinSize, int maxConsecutiveSkips,
            Map<Byte, CompressionCodec> codecMap) throws IOException {
        assert directory != null : "directory must not be null";
        assert segmentSize > 0 : "segmentSize must be positive";
        assert bufferPool != null : "bufferPool must not be null";
        assert codec != null : "codec must not be null";
        assert compressionMinSize >= 0 : "compressionMinSize must be non-negative";
        assert maxConsecutiveSkips > 0 : "maxConsecutiveSkips must be positive";

        this.directory = directory;
        this.segmentSize = segmentSize;
        this.bufferPool = bufferPool;
        this.codec = codec;
        this.compressionMinSize = compressionMinSize;
        this.maxConsecutiveSkips = maxConsecutiveSkips;
        this.codecMap = codecMap;

        recover();
    }

    // -------------------------------------------------------------------------
    // Recovery
    // -------------------------------------------------------------------------

    private void recover() throws IOException {
        List<Path> segments = SegmentFile.listSorted(directory);

        if (segments.isEmpty()) {
            // Brand new WAL — create segment 1
            activeSegmentNumber = 1L;
            nextSequence.set(1L);
            openNewSegment(activeSegmentNumber);
            return;
        }

        // Scan each segment for valid records; build segmentIndex
        long lastValidSeq = 0L;

        for (Path segPath : segments) {
            long segNum = SegmentFile.parseNumber(segPath.getFileName().toString());
            assert segNum >= 0 : "invalid segment file in recovery: " + segPath;

            long[] scanResult = scanSegment(segPath);
            long firstSeq = scanResult[0]; // -1 if empty
            long lastSeq = scanResult[1]; // -1 if empty
            int validBytes = (int) scanResult[2];

            if (firstSeq >= 0) {
                segmentIndex.put(segNum, firstSeq);
                if (lastSeq > lastValidSeq)
                    lastValidSeq = lastSeq;
            }

            boolean isLast = segPath.equals(segments.get(segments.size() - 1));
            if (isLast) {
                activeSegmentNumber = segNum;
                // Open the last segment for writing; ensure it is at segmentSize
                ensureSegmentSize(segPath);
                activeChannel = FileChannel.open(segPath, StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
                mappedBuffer = activeChannel.map(FileChannel.MapMode.READ_WRITE, 0, segmentSize);
                writePosition = validBytes;
            }
        }

        nextSequence.set(lastValidSeq + 1L);
    }

    /**
     * Scans a segment file and returns {firstSeq, lastSeq, validByteCount}. firstSeq/lastSeq = -1
     * if no valid records found.
     */
    private long[] scanSegment(Path segPath) throws IOException {
        long fileSize = Files.size(segPath);
        if (fileSize == 0)
            return new long[]{ -1, -1, 0 };

        long mapSize = Math.min(fileSize, segmentSize);
        long firstSeq = -1L;
        long lastSeq = -1L;
        int validBytes = 0;
        int consecutiveSkips = 0;

        try (FileChannel fc = FileChannel.open(segPath, StandardOpenOption.READ)) {
            MappedByteBuffer mbb = fc.map(FileChannel.MapMode.READ_ONLY, 0, mapSize);
            MemorySegment seg = MemorySegment.ofBuffer(mbb);
            long pos = 0;
            while (pos < mapSize) {
                Entry entry;
                try {
                    entry = WalRecord.decode(seg, pos, mapSize - pos, codecMap);
                } catch (IOException | UncheckedIOException e) {
                    // CRC mismatch or decompression failure — skip record (R33)
                    int frameContentLen = readFrameContentLen(seg, pos);
                    if (frameContentLen < 2) {
                        break; // Can't advance meaningfully
                    }
                    long advance = 4L + frameContentLen;
                    if (pos + advance > mapSize) {
                        break; // Partial record
                    }
                    pos += advance;
                    consecutiveSkips++;
                    if (consecutiveSkips > maxConsecutiveSkips) {
                        throw new IOException(
                                "systematic codec failure: %d consecutive records skipped in %s"
                                        .formatted(consecutiveSkips, segPath));
                    }
                    continue;
                }
                if (entry == null)
                    break;

                consecutiveSkips = 0; // Reset on successful decode

                long seqVal = entry.sequenceNumber().value();
                if (firstSeq < 0)
                    firstSeq = seqVal;
                if (seqVal > lastSeq)
                    lastSeq = seqVal;

                // Advance by frame size: 4 (frame length field) + frameContentLen
                int frameContentLen = readFrameContentLen(seg, pos);
                pos += 4 + frameContentLen;
                validBytes = (int) pos;
            }
        }

        return new long[]{ firstSeq, lastSeq, validBytes };
    }

    private static final ValueLayout.OfInt INT_BE_UNALIGNED = ValueLayout.JAVA_INT
            .withOrder(java.nio.ByteOrder.BIG_ENDIAN).withByteAlignment(1);

    private int readFrameContentLen(MemorySegment seg, long pos) {
        return seg.get(INT_BE_UNALIGNED, pos);
    }

    private void ensureSegmentSize(Path path) throws IOException {
        long size = Files.size(path);
        if (size < segmentSize) {
            try (FileChannel fc = FileChannel.open(path, StandardOpenOption.WRITE)) {
                fc.write(ByteBuffer.allocate(1), segmentSize - 1);
            }
        }
    }

    private void openNewSegment(long segNum) throws IOException {
        Path path = directory.resolve(SegmentFile.toFileName(segNum));
        // Pre-allocate
        try (FileChannel fc = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            fc.write(ByteBuffer.allocate(1), segmentSize - 1);
        }
        activeChannel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        mappedBuffer = activeChannel.map(FileChannel.MapMode.READ_WRITE, 0, segmentSize);
        writePosition = 0;
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
            // Re-wrap the entry with the assigned sequence number
            Entry stamped = stamp(entry, new SequenceNumber(seq));

            MemorySegment buf = bufferPool.acquire();
            try {
                int n = encodeWithCompression(stamped, buf);

                if (n > segmentSize - writePosition) {
                    rollSegment(seq);
                }

                MemorySegment mapped = MemorySegment.ofBuffer(mappedBuffer);
                MemorySegment.copy(buf, 0, mapped, writePosition, n);
                writePosition += n;
                mappedBuffer.force(writePosition - n, n);
            } finally {
                bufferPool.release(buf);
            }

            return new SequenceNumber(seq);
        } finally {
            writeLock.unlock();
        }
    }

    /** Dummy segment used as compression buffer when pool is exhausted (R36). Never written to. */
    private static final MemorySegment DUMMY_COMPRESSION_BUF = MemorySegment.ofArray(new byte[0]);

    // @spec F17.R25 — payload below threshold written uncompressed
    // @spec F17.R26 — compressed+5 >= uncompressed -> write uncompressed
    // @spec F17.R29 — same format semantics as RemoteWriteAheadLog
    // @spec F17.R36 — pool exhaustion falls back to uncompressed
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

    private void rollSegment(long firstSeqInNewSegment) throws IOException {
        assert writeLock.isHeldByCurrentThread() : "writeLock must be held during rollover";

        // Flush and close current segment
        mappedBuffer.force();
        activeChannel.truncate(writePosition);
        activeChannel.close();
        mappedBuffer = null;
        activeChannel = null;

        // Create next segment
        activeSegmentNumber++;
        openNewSegment(activeSegmentNumber);

        // Update segment index
        indexLock.writeLock().lock();
        try {
            segmentIndex.put(activeSegmentNumber, firstSeqInNewSegment);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    private static Entry stamp(Entry entry, SequenceNumber seq) {
        return switch (entry) {
            case Entry.Put put -> new Entry.Put(put.key(), put.value(), seq);
            case Entry.Delete del -> new Entry.Delete(del.key(), seq);
        };
    }

    @Override
    public Iterator<Entry> replay(SequenceNumber from) throws IOException {
        Objects.requireNonNull(from, "from must not be null");

        List<Path> allSegments = SegmentFile.listSorted(directory);
        if (allSegments.isEmpty())
            return Collections.emptyIterator();

        // Find the first segment to include: last segment whose firstSeq <= from,
        // or the very first segment if all firstSeqs > from.
        List<Path> toScan = segmentsForReplay(allSegments, from);
        return new ReplayIterator(toScan, from, segmentSize, codecMap, maxConsecutiveSkips);
    }

    private List<Path> segmentsForReplay(List<Path> allSegments, SequenceNumber from) {
        indexLock.readLock().lock();
        try {
            if (segmentIndex.isEmpty())
                return new ArrayList<>(allSegments);

            // Find the start: last segment number with firstSeq <= from.value()
            // Since segmentIndex maps segNum -> firstSeq, iterate to find the floor by value.
            long startSegNum = -1L;
            for (var entry : segmentIndex.entrySet()) {
                if (entry.getValue() <= from.value()) {
                    startSegNum = entry.getKey();
                }
            }

            final long finalStart = startSegNum;
            if (finalStart < 0) {
                // All segments have firstSeq > from — include all
                return new ArrayList<>(allSegments);
            }

            List<Path> result = new ArrayList<>();
            for (Path p : allSegments) {
                long segNum = SegmentFile.parseNumber(p.getFileName().toString());
                if (segNum >= finalStart)
                    result.add(p);
            }
            return result;
        } finally {
            indexLock.readLock().unlock();
        }
    }

    @Override
    public void truncateBefore(SequenceNumber upTo) throws IOException {
        Objects.requireNonNull(upTo, "upTo must not be null");

        List<Path> allSegments = SegmentFile.listSorted(directory);

        indexLock.writeLock().lock();
        try {
            // A segment is fully obsolete when the NEXT segment's firstSeq <= upTo.value(),
            // meaning every record in this segment is strictly < upTo.
            for (int i = 0; i < allSegments.size() - 1; i++) {
                Path current = allSegments.get(i);
                Path next = allSegments.get(i + 1);
                long nextSegNum = SegmentFile.parseNumber(next.getFileName().toString());
                Long nextFirstSeq = segmentIndex.get(nextSegNum);

                if (nextFirstSeq != null && nextFirstSeq <= upTo.value()) {
                    long curSegNum = SegmentFile.parseNumber(current.getFileName().toString());
                    Files.deleteIfExists(current);
                    segmentIndex.remove(curSegNum);
                }
            }
        } finally {
            indexLock.writeLock().unlock();
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
        writeLock.lock();
        IOException deferred = null;
        try {
            if (mappedBuffer != null) {
                try {
                    mappedBuffer.force();
                } catch (UncheckedIOException e) {
                    deferred = e.getCause() != null ? e.getCause() : new IOException(e);
                }
                mappedBuffer = null;
            }
            if (activeChannel != null) {
                try {
                    activeChannel.close();
                } catch (IOException e) {
                    if (deferred != null) {
                        deferred.addSuppressed(e);
                    } else {
                        deferred = e;
                    }
                }
                activeChannel = null;
            }
        } finally {
            writeLock.unlock();
        }
        bufferPool.close();
        if (deferred != null)
            throw deferred;
    }

    // -------------------------------------------------------------------------
    // Replay iterator
    // -------------------------------------------------------------------------

    private static final class ReplayIterator implements Iterator<Entry> {

        private final List<Path> segments;
        private final SequenceNumber from;
        private final long segmentSize;
        private final Map<Byte, CompressionCodec> codecMap;
        private final int maxConsecutiveSkips;
        private int segIdx = 0;
        private Entry next;
        // Per-segment state
        private MemorySegment currentSeg;
        private long segAvailable;
        private long segPos;
        private int consecutiveSkips;

        ReplayIterator(List<Path> segments, SequenceNumber from, long segmentSize,
                Map<Byte, CompressionCodec> codecMap, int maxConsecutiveSkips) {
            this.segments = segments;
            this.from = from;
            this.segmentSize = segmentSize;
            this.codecMap = codecMap;
            this.maxConsecutiveSkips = maxConsecutiveSkips;
            advance();
        }

        private void advance() {
            next = null;
            while (next == null) {
                if (currentSeg == null) {
                    if (segIdx >= segments.size())
                        return;
                    loadSegment(segments.get(segIdx++));
                    if (currentSeg == null)
                        continue;
                }
                Entry candidate = readNext();
                if (candidate == null) {
                    currentSeg = null;
                    continue;
                }
                if (candidate.sequenceNumber().compareTo(from) >= 0) {
                    next = candidate;
                }
                // else skip and keep scanning
            }
        }

        private void loadSegment(Path path) {
            try {
                long fileSize = Files.size(path);
                if (fileSize == 0)
                    return;
                long mapSize = Math.min(fileSize, segmentSize);
                FileChannel fc = FileChannel.open(path, StandardOpenOption.READ);
                MappedByteBuffer mbb = fc.map(FileChannel.MapMode.READ_ONLY, 0, mapSize);
                fc.close();
                currentSeg = MemorySegment.ofBuffer(mbb);
                segAvailable = mapSize;
                segPos = 0;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private Entry readNext() {
            while (segPos < segAvailable) {
                Entry entry;
                try {
                    entry = WalRecord.decode(currentSeg, segPos, segAvailable - segPos, codecMap);
                } catch (IOException | UncheckedIOException e) {
                    // CRC mismatch or decompression failure — skip record (R33)
                    int frameContentLen = currentSeg.get(INT_BE_UNALIGNED, segPos);
                    if (frameContentLen < 2) {
                        return null; // Can't advance
                    }
                    long advance = 4L + frameContentLen;
                    if (segPos + advance > segAvailable) {
                        return null; // Partial record
                    }
                    segPos += advance;
                    consecutiveSkips++;
                    if (consecutiveSkips > maxConsecutiveSkips) {
                        throw new UncheckedIOException(new IOException(
                                "systematic codec failure: %d consecutive records skipped"
                                        .formatted(consecutiveSkips)));
                    }
                    continue;
                }
                if (entry == null)
                    return null;

                consecutiveSkips = 0; // Reset on success

                int frameContentLen = currentSeg.get(INT_BE_UNALIGNED, segPos);
                segPos += 4L + frameContentLen;
                return entry;
            }
            return null;
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
        private long segmentSize = DEFAULT_SEGMENT_SIZE;
        private int poolSize = DEFAULT_POOL_SIZE;
        private long bufferSize = DEFAULT_BUFFER_SIZE;
        private long acquireTimeoutMillis = DEFAULT_ACQUIRE_TIMEOUT_MILLIS;
        // @spec F17.R27 — default DEFLATE level 6 if no codec provided
        private CompressionCodec codec = CompressionCodec.deflate();
        // @spec F17.R28 — default threshold 64 bytes
        private int compressionMinSize = DEFAULT_COMPRESSION_MIN_SIZE;
        // @spec F17.R35 — 10 consecutive skips threshold (configurable)
        private int maxConsecutiveSkips = DEFAULT_MAX_CONSECUTIVE_SKIPS;
        private CompressionCodec[] recoveryCodecs;

        public Builder directory(Path directory) {
            Objects.requireNonNull(directory, "directory must not be null");
            this.directory = directory;
            return this;
        }

        public Builder segmentSize(long segmentSize) {
            if (segmentSize <= 0) {
                throw new IllegalArgumentException(
                        "segmentSize must be positive, got: " + segmentSize);
            }
            this.segmentSize = segmentSize;
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

        public LocalWriteAheadLog build() throws IOException {
            Objects.requireNonNull(directory, "directory must not be null");
            ArenaBufferPool pool = ArenaBufferPool.builder().poolSize(poolSize)
                    .bufferSize(bufferSize).acquireTimeoutMillis(acquireTimeoutMillis).build();

            // Build codec map for recovery (R30)
            Map<Byte, CompressionCodec> cm = buildCodecMap();

            return new LocalWriteAheadLog(directory, segmentSize, pool, codec, compressionMinSize,
                    maxConsecutiveSkips, cm);
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
