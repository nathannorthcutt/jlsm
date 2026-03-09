package jlsm.sstable;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.bloom.BloomFilter;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import jlsm.core.sstable.SSTableWriter;
import jlsm.sstable.internal.DataBlock;
import jlsm.sstable.internal.EntryCodec;
import jlsm.sstable.internal.SSTableFormat;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes entries to a new SSTable file using a trie-based key index.
 *
 * <p>State machine: {@code OPEN → FINISHED → CLOSED} and {@code OPEN → CLOSED}.
 * Calling {@link #close()} without {@link #finish()} deletes the partial file.
 */
public final class TrieSSTableWriter implements SSTableWriter {

    private enum State { OPEN, FINISHED, CLOSED }

    private final long id;
    private final Level level;
    private final Path outputPath;
    private final FileChannel channel;

    // Data block accumulation
    private DataBlock currentBlock = new DataBlock();
    private long writePosition = 0L;

    // Index: parallel lists of keys + file offsets (offset of entry start within the block data)
    private final List<MemorySegment> indexKeys = new ArrayList<>();
    private final List<Long> indexOffsets = new ArrayList<>();

    // Bloom filter
    private BloomFilter bloomFilter;

    // Stats
    private long entryCount = 0L;
    private long approximateSizeBytes = 0L;
    private MemorySegment smallestKey = null;
    private MemorySegment largestKey = null;
    private SequenceNumber minSequence = null;
    private SequenceNumber maxSequence = null;

    // Previous key for ordering check
    private byte[] lastKeyBytes = null;

    private State state = State.OPEN;

    /**
     * Creates a new writer that will write to {@code outputPath}.
     *
     * @param id         unique SSTable identifier
     * @param level      LSM level for this SSTable
     * @param outputPath path to the output file; must not exist
     * @throws IOException if the file cannot be opened
     */
    public TrieSSTableWriter(long id, Level level, Path outputPath) throws IOException {
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        this.id = id;
        this.level = level;
        this.outputPath = outputPath;
        this.channel = FileChannel.open(outputPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ);
    }

    @Override
    public void append(Entry entry) throws IOException {
        Objects.requireNonNull(entry, "entry must not be null");
        if (state != State.OPEN) {
            throw new IllegalStateException("writer is not open (state=" + state + ")");
        }

        byte[] keyBytes = entry.key().toArray(ValueLayout.JAVA_BYTE);

        // Enforce strictly ascending key order
        if (lastKeyBytes != null) {
            int cmp = compareUnsigned(keyBytes, lastKeyBytes);
            if (cmp <= 0) {
                throw new IllegalArgumentException(
                        "keys must be strictly ascending; got key not greater than previous key");
            }
        }

        byte[] encoded = EntryCodec.encode(entry);

        // Absolute file offset of this entry: block start + block's current byte size.
        // currentBlock.byteSize() includes the 4-byte count header plus all previously encoded
        // entries, so this points to the byte immediately after the last written entry.
        long entryAbsOffset = writePosition + currentBlock.byteSize();

        indexKeys.add(MemorySegment.ofArray(keyBytes));
        indexOffsets.add(entryAbsOffset);

        currentBlock.add(encoded);
        approximateSizeBytes += encoded.length;

        // Update stats
        SequenceNumber seq = entry.sequenceNumber();
        if (smallestKey == null) smallestKey = MemorySegment.ofArray(keyBytes.clone());
        largestKey = MemorySegment.ofArray(keyBytes.clone());

        if (minSequence == null || seq.compareTo(minSequence) < 0) minSequence = seq;
        if (maxSequence == null || seq.compareTo(maxSequence) > 0) maxSequence = seq;

        lastKeyBytes = keyBytes;
        entryCount++;

        // Flush block if it exceeds the target size
        if (currentBlock.byteSize() >= SSTableFormat.DEFAULT_BLOCK_SIZE) {
            flushCurrentBlock();
        }
    }

    private void flushCurrentBlock() throws IOException {
        if (currentBlock.count() == 0) return;
        byte[] blockBytes = currentBlock.serialize();
        writeBytes(blockBytes);
        currentBlock = new DataBlock();
    }

    private void writeBytes(byte[] bytes) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) {
            channel.write(buf, writePosition + buf.position());
        }
        writePosition += bytes.length;
    }

    @Override
    public SSTableMetadata finish() throws IOException {
        if (state != State.OPEN) {
            throw new IllegalStateException("finish() already called or writer is closed");
        }
        if (entryCount == 0) {
            throw new IllegalStateException("cannot finish an empty SSTable");
        }

        // Flush remaining block
        flushCurrentBlock();

        // Build bloom filter
        bloomFilter = new BlockedBloomFilter((int) Math.max(1, entryCount), 0.01);
        for (MemorySegment key : indexKeys) {
            bloomFilter.add(key);
        }

        // Write key index
        long indexOffset = writePosition;
        writeKeyIndex();
        long indexLength = writePosition - indexOffset;

        // Write bloom filter
        long filterOffset = writePosition;
        MemorySegment filterBytes = bloomFilter.serialize();
        byte[] filterArray = filterBytes.toArray(ValueLayout.JAVA_BYTE);
        writeBytes(filterArray);
        long filterLength = writePosition - filterOffset;

        // Write footer
        writeFooter(indexOffset, indexLength, filterOffset, filterLength);

        // fsync
        channel.force(true);

        long sizeBytes = writePosition;

        state = State.FINISHED;

        return new SSTableMetadata(id, outputPath, level, smallestKey, largestKey,
                minSequence, maxSequence, sizeBytes, entryCount);
    }

    private void writeKeyIndex() throws IOException {
        int numKeys = indexKeys.size();
        // count: 4 bytes
        // per key: 4 (keyLen) + keyBytes + 8 (offset)
        int indexSize = 4;
        for (MemorySegment k : indexKeys) {
            indexSize += 4 + (int) k.byteSize() + 8;
        }
        byte[] buf = new byte[indexSize];
        int off = 0;
        // numKeys
        buf[off++] = (byte) (numKeys >>> 24);
        buf[off++] = (byte) (numKeys >>> 16);
        buf[off++] = (byte) (numKeys >>> 8);
        buf[off++] = (byte) numKeys;

        for (int i = 0; i < numKeys; i++) {
            byte[] keyBytes = indexKeys.get(i).toArray(ValueLayout.JAVA_BYTE);
            int keyLen = keyBytes.length;
            buf[off++] = (byte) (keyLen >>> 24);
            buf[off++] = (byte) (keyLen >>> 16);
            buf[off++] = (byte) (keyLen >>> 8);
            buf[off++] = (byte) keyLen;
            System.arraycopy(keyBytes, 0, buf, off, keyLen);
            off += keyLen;
            long fileOff = indexOffsets.get(i);
            buf[off++] = (byte) (fileOff >>> 56);
            buf[off++] = (byte) (fileOff >>> 48);
            buf[off++] = (byte) (fileOff >>> 40);
            buf[off++] = (byte) (fileOff >>> 32);
            buf[off++] = (byte) (fileOff >>> 24);
            buf[off++] = (byte) (fileOff >>> 16);
            buf[off++] = (byte) (fileOff >>> 8);
            buf[off++] = (byte) fileOff;
        }
        writeBytes(buf);
    }

    private void writeFooter(long idxOffset, long idxLength,
                              long fltOffset, long fltLength) throws IOException {
        byte[] buf = new byte[SSTableFormat.FOOTER_SIZE];
        int off = 0;
        off = writeLong(buf, off, idxOffset);
        off = writeLong(buf, off, idxLength);
        off = writeLong(buf, off, fltOffset);
        off = writeLong(buf, off, fltLength);
        off = writeLong(buf, off, entryCount);
        writeLong(buf, off, SSTableFormat.MAGIC);
        writeBytes(buf);
    }

    private static int writeLong(byte[] buf, int off, long v) {
        buf[off++] = (byte) (v >>> 56);
        buf[off++] = (byte) (v >>> 48);
        buf[off++] = (byte) (v >>> 40);
        buf[off++] = (byte) (v >>> 32);
        buf[off++] = (byte) (v >>> 24);
        buf[off++] = (byte) (v >>> 16);
        buf[off++] = (byte) (v >>> 8);
        buf[off++] = (byte) v;
        return off;
    }

    @Override
    public long entryCount() {
        return entryCount;
    }

    @Override
    public long approximateSizeBytes() {
        return approximateSizeBytes;
    }

    @Override
    public void close() throws IOException {
        if (state == State.CLOSED) return;
        boolean shouldDelete = (state == State.OPEN);
        state = State.CLOSED;
        try {
            channel.close();
        } finally {
            if (shouldDelete) {
                try { Files.deleteIfExists(outputPath); } catch (IOException ignored) {}
            }
        }
    }

    private static int compareUnsigned(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int cmp = Byte.compareUnsigned(a[i], b[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.length, b.length);
    }
}
