package jlsm.sstable;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.bloom.BloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import jlsm.core.sstable.SSTableWriter;
import jlsm.sstable.internal.CompressionMap;
import jlsm.sstable.internal.DataBlock;
import jlsm.sstable.internal.EntryCodec;
import jlsm.sstable.internal.SSTableFormat;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes entries to a new SSTable file using a trie-based key index.
 *
 * <p>
 * Supports two file formats:
 * <ul>
 * <li><b>v1</b> — no compression (constructors without {@link CompressionCodec})</li>
 * <li><b>v2</b> — per-block compression with a compression offset map (constructor with
 * {@link CompressionCodec})</li>
 * </ul>
 *
 * <p>
 * State machine: {@code OPEN → FINISHED → CLOSED} and {@code OPEN → CLOSED}. Calling
 * {@link #close()} without {@link #finish()} deletes the partial file.
 */
public final class TrieSSTableWriter implements SSTableWriter {

    private enum State {
        OPEN, FINISHED, CLOSED
    }

    private final long id;
    private final Level level;
    private final Path outputPath;
    private final SeekableByteChannel channel;

    // Data block accumulation
    private DataBlock currentBlock = new DataBlock();
    private long writePosition = 0L;

    // v1: parallel lists of keys (raw byte[]) + absolute file offsets
    // v2: parallel lists of keys + packed (blockIndex << 32 | intraBlockOffset)
    private final List<byte[]> indexKeys = new ArrayList<>();
    private final List<Long> indexOffsets = new ArrayList<>();

    // Bloom filter
    private BloomFilter bloomFilter;
    private final BloomFilter.Factory bloomFactory;

    // Compression (v2 only — null for v1)
    private final CompressionCodec codec;
    private final List<CompressionMap.Entry> compressionMapEntries = new ArrayList<>();
    private int blockCount = 0;

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
     * Creates a v1 writer (no compression) with a {@link BlockedBloomFilter} at 1% FPR.
     *
     * @param id unique SSTable identifier
     * @param level LSM level for this SSTable
     * @param outputPath path to the output file; must not exist
     * @throws IOException if the file cannot be opened
     */
    public TrieSSTableWriter(long id, Level level, Path outputPath) throws IOException {
        this(id, level, outputPath, n -> new BlockedBloomFilter(n, 0.01), null);
    }

    /**
     * Creates a v1 writer (no compression) with a custom bloom filter factory.
     *
     * @param id unique SSTable identifier
     * @param level LSM level for this SSTable
     * @param outputPath path to the output file; must not exist
     * @param bloomFactory factory used to create the bloom filter; must not be null
     * @throws IOException if the file cannot be opened
     */
    public TrieSSTableWriter(long id, Level level, Path outputPath,
            BloomFilter.Factory bloomFactory) throws IOException {
        this(id, level, outputPath, bloomFactory, null);
    }

    /**
     * Creates a v2 writer with per-block compression using the given codec.
     *
     * <p>
     * Pass {@code null} for the codec to produce a v1 (uncompressed) file.
     *
     * @param id unique SSTable identifier
     * @param level LSM level for this SSTable
     * @param outputPath path to the output file; must not exist
     * @param bloomFactory factory used to create the bloom filter; must not be null
     * @param codec compression codec, or null for v1 format
     * @throws IOException if the file cannot be opened
     */
    public TrieSSTableWriter(long id, Level level, Path outputPath,
            BloomFilter.Factory bloomFactory, CompressionCodec codec) throws IOException {
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        Objects.requireNonNull(bloomFactory, "bloomFactory must not be null");
        this.id = id;
        this.level = level;
        this.outputPath = outputPath;
        this.bloomFactory = bloomFactory;
        this.codec = codec;
        this.channel = Files.newByteChannel(outputPath, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
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

        // Encode using the already-extracted key bytes to avoid a redundant toArray
        byte[] encoded = EntryCodec.encode(entry, keyBytes);

        if (codec != null) {
            // v2: store (blockIndex, intraBlockOffset) packed into a long
            int intraBlockOffset = currentBlock.byteSize();
            long packed = ((long) blockCount << 32) | (intraBlockOffset & 0xFFFFFFFFL);
            indexKeys.add(keyBytes);
            indexOffsets.add(packed);
        } else {
            // v1: store absolute file offset
            long entryAbsOffset = writePosition + currentBlock.byteSize();
            indexKeys.add(keyBytes);
            indexOffsets.add(entryAbsOffset);
        }

        currentBlock.add(encoded);
        approximateSizeBytes += encoded.length;

        // Update stats — reuse keyBytes; clone only for smallest (first entry)
        SequenceNumber seq = entry.sequenceNumber();
        if (smallestKey == null)
            smallestKey = MemorySegment.ofArray(keyBytes.clone());
        largestKey = MemorySegment.ofArray(keyBytes);

        if (minSequence == null || seq.compareTo(minSequence) < 0)
            minSequence = seq;
        if (maxSequence == null || seq.compareTo(maxSequence) > 0)
            maxSequence = seq;

        lastKeyBytes = keyBytes;
        entryCount++;

        // Flush block if it exceeds the target size
        if (currentBlock.byteSize() >= SSTableFormat.DEFAULT_BLOCK_SIZE) {
            flushCurrentBlock();
        }
    }

    private void flushCurrentBlock() throws IOException {
        if (currentBlock.count() == 0)
            return;
        byte[] blockBytes = currentBlock.serialize();

        if (codec != null) {
            // v2: compress the block
            byte[] compressed = codec.compress(blockBytes, 0, blockBytes.length);
            byte actualCodecId;
            byte[] dataToWrite;
            if (compressed.length >= blockBytes.length) {
                // Incompressible — store raw with NONE codec ID
                dataToWrite = blockBytes;
                actualCodecId = CompressionCodec.none().codecId();
            } else {
                dataToWrite = compressed;
                actualCodecId = codec.codecId();
            }
            compressionMapEntries.add(new CompressionMap.Entry(writePosition, dataToWrite.length,
                    blockBytes.length, actualCodecId));
            writeBytes(dataToWrite);
        } else {
            // v1: write raw block
            writeBytes(blockBytes);
        }
        blockCount++;
        currentBlock = new DataBlock();
    }

    private void writeBytes(byte[] bytes) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) {
            channel.write(buf);
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
        bloomFilter = bloomFactory.create((int) Math.max(1, entryCount));
        for (byte[] key : indexKeys) {
            bloomFilter.add(MemorySegment.ofArray(key));
        }

        if (codec != null) {
            // v2 layout: [data blocks][compression map][key index][bloom filter][footer 64]
            long mapOffset = writePosition;
            CompressionMap compressionMap = new CompressionMap(compressionMapEntries);
            writeBytes(compressionMap.serialize());
            long mapLength = writePosition - mapOffset;

            long indexOffset = writePosition;
            writeKeyIndexV2();
            long indexLength = writePosition - indexOffset;

            long filterOffset = writePosition;
            MemorySegment filterBytes = bloomFilter.serialize();
            byte[] filterArray = filterBytes.toArray(ValueLayout.JAVA_BYTE);
            writeBytes(filterArray);
            long filterLength = writePosition - filterOffset;

            writeFooterV2(mapOffset, mapLength, indexOffset, indexLength, filterOffset,
                    filterLength);
        } else {
            // v1 layout: [data blocks][key index][bloom filter][footer 48]
            long indexOffset = writePosition;
            writeKeyIndexV1();
            long indexLength = writePosition - indexOffset;

            long filterOffset = writePosition;
            MemorySegment filterBytes = bloomFilter.serialize();
            byte[] filterArray = filterBytes.toArray(ValueLayout.JAVA_BYTE);
            writeBytes(filterArray);
            long filterLength = writePosition - filterOffset;

            writeFooterV1(indexOffset, indexLength, filterOffset, filterLength);
        }

        // fsync if supported (e.g., local FileChannel; skipped for object-storage channels)
        if (channel instanceof FileChannel fc)
            fc.force(true);

        long sizeBytes = writePosition;

        state = State.FINISHED;

        return new SSTableMetadata(id, outputPath, level, smallestKey, largestKey, minSequence,
                maxSequence, sizeBytes, entryCount);
    }

    /** Writes v1 key index: [numKeys(4)][per key: keyLen(4) + key + fileOffset(8)]. */
    private void writeKeyIndexV1() throws IOException {
        int numKeys = indexKeys.size();
        int indexSize = 4;
        for (byte[] k : indexKeys) {
            indexSize += 4 + k.length + 8;
        }
        byte[] buf = new byte[indexSize];
        int off = 0;
        off = writeInt(buf, off, numKeys);
        for (int i = 0; i < numKeys; i++) {
            byte[] keyBytes = indexKeys.get(i);
            off = writeInt(buf, off, keyBytes.length);
            System.arraycopy(keyBytes, 0, buf, off, keyBytes.length);
            off += keyBytes.length;
            off = writeLong(buf, off, indexOffsets.get(i));
        }
        writeBytes(buf);
    }

    /**
     * Writes v2 key index: [numKeys(4)][per key: keyLen(4) + key + blockIndex(4) + intraOff(4)].
     */
    private void writeKeyIndexV2() throws IOException {
        int numKeys = indexKeys.size();
        int indexSize = 4;
        for (byte[] k : indexKeys) {
            indexSize += 4 + k.length + 4 + 4;
        }
        byte[] buf = new byte[indexSize];
        int off = 0;
        off = writeInt(buf, off, numKeys);
        for (int i = 0; i < numKeys; i++) {
            byte[] keyBytes = indexKeys.get(i);
            off = writeInt(buf, off, keyBytes.length);
            System.arraycopy(keyBytes, 0, buf, off, keyBytes.length);
            off += keyBytes.length;
            long packed = indexOffsets.get(i);
            int blockIndex = (int) (packed >>> 32);
            int intraBlockOffset = (int) packed;
            off = writeInt(buf, off, blockIndex);
            off = writeInt(buf, off, intraBlockOffset);
        }
        writeBytes(buf);
    }

    private void writeFooterV1(long idxOffset, long idxLength, long fltOffset, long fltLength)
            throws IOException {
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

    private void writeFooterV2(long mapOffset, long mapLength, long idxOffset, long idxLength,
            long fltOffset, long fltLength) throws IOException {
        byte[] buf = new byte[SSTableFormat.FOOTER_SIZE_V2];
        int off = 0;
        off = writeLong(buf, off, mapOffset);
        off = writeLong(buf, off, mapLength);
        off = writeLong(buf, off, idxOffset);
        off = writeLong(buf, off, idxLength);
        off = writeLong(buf, off, fltOffset);
        off = writeLong(buf, off, fltLength);
        off = writeLong(buf, off, entryCount);
        writeLong(buf, off, SSTableFormat.MAGIC_V2);
        writeBytes(buf);
    }

    private static int writeInt(byte[] buf, int off, int v) {
        buf[off++] = (byte) (v >>> 24);
        buf[off++] = (byte) (v >>> 16);
        buf[off++] = (byte) (v >>> 8);
        buf[off++] = (byte) v;
        return off;
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
        if (state == State.CLOSED)
            return;
        boolean shouldDelete = (state == State.OPEN);
        state = State.CLOSED;
        IOException channelEx = null;
        try {
            channel.close();
        } catch (IOException e) {
            channelEx = e;
        } finally {
            if (shouldDelete) {
                try {
                    Files.deleteIfExists(outputPath);
                } catch (IOException deleteEx) {
                    if (channelEx != null) {
                        channelEx.addSuppressed(deleteEx);
                    } else {
                        channelEx = deleteEx;
                    }
                }
            }
        }
        if (channelEx != null)
            throw channelEx;
    }

    private static int compareUnsigned(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int cmp = Byte.compareUnsigned(a[i], b[i]);
            if (cmp != 0)
                return cmp;
        }
        return Integer.compare(a.length, b.length);
    }
}
