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

import jlsm.core.compression.ZstdDictionaryTrainer;

import java.io.IOException;
import java.lang.foreign.Arena;
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
import java.util.zip.CRC32C;

/**
 * Writes entries to a new SSTable file using a trie-based key index.
 *
 * <p>
 * Supports three file formats:
 * <ul>
 * <li><b>v1</b> — no compression (constructors without {@link CompressionCodec})</li>
 * <li><b>v2</b> — per-block compression with a compression offset map (constructor with
 * {@link CompressionCodec})</li>
 * <li><b>v3</b> — per-block compression with CRC32C checksums and configurable block size
 * ({@link Builder})</li>
 * </ul>
 *
 * <p>
 * State machine: {@code OPEN → FINISHED → CLOSED} and {@code OPEN → CLOSED}. Calling
 * {@link #close()} without {@link #finish()} deletes the partial file.
 */
// @spec F02.R22 — single codec per file
// @spec F02.R23 — if compressed >= uncompressed, store as NONE
// @spec F02.R24 — map built during write, serialized after all blocks
// @spec F02.R32 — intra-block offsets may use int
// @spec F18.R10,R11,R12,R13,R13a,R14,R18,R19,R27 — dictionary-aware writer lifecycle:
// buffer → train → compress-with-dict → v4 meta-block; graceful degradation paths
public final class TrieSSTableWriter implements SSTableWriter {

    private enum State {
        OPEN, FINISHED, FAILED, CLOSED
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

    // Compression (v2/v3 — null for v1)
    private final CompressionCodec codec;
    private final List<CompressionMap.Entry> compressionMapEntries = new ArrayList<>();
    private int blockCount = 0;

    // Block size (v3: configurable, v1/v2: DEFAULT_BLOCK_SIZE)
    private final int blockSize;

    // v3 format flag — true only when constructed via Builder
    private final boolean v3;

    // Dictionary training (v4)
    private final boolean dictionaryTrainingEnabled;
    private final int dictionaryBlockThreshold;
    private final long dictionaryMaxBufferBytes;
    private final int dictionaryMaxSize;
    private final boolean dictEligible; // true only when dictionaryTraining + ZSTD + native
    private List<byte[]> dictBufferedBlocks; // null when not buffering
    private long dictBufferedBytes;
    private boolean dictBufferAbandoned;
    private DictionaryTrainingResult trainingResult;

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
        if (codec != null && codec.codecId() == 0x00 && codec != CompressionCodec.none()) {
            throw new IllegalArgumentException(
                    "custom codec must not use codecId 0x00 (reserved for NoneCodec)");
        }
        this.id = id;
        this.level = level;
        this.outputPath = outputPath;
        this.bloomFactory = bloomFactory;
        this.codec = codec;
        this.blockSize = SSTableFormat.DEFAULT_BLOCK_SIZE;
        // @spec F16.R16 — codec-configured writers produce v3 format; no-codec writers stay v1
        this.v3 = codec != null;
        this.dictionaryTrainingEnabled = false;
        this.dictionaryBlockThreshold = 64;
        this.dictionaryMaxBufferBytes = 256L * 1024 * 1024;
        this.dictionaryMaxSize = 32768;
        this.dictEligible = false;
        this.channel = Files.newByteChannel(outputPath, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    /**
     * Internal constructor used by the Builder for v3/v4 format with configurable block size and
     * optional dictionary training.
     */
    private TrieSSTableWriter(long id, Level level, Path outputPath,
            BloomFilter.Factory bloomFactory, CompressionCodec codec, int blockSize,
            boolean dictionaryTraining, int dictBlockThreshold, long dictMaxBufferBytes,
            int dictMaxSize) throws IOException {
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        Objects.requireNonNull(bloomFactory, "bloomFactory must not be null");
        if (codec != null && codec.codecId() == 0x00 && codec != CompressionCodec.none()) {
            throw new IllegalArgumentException(
                    "custom codec must not use codecId 0x00 (reserved for NoneCodec)");
        }
        this.id = id;
        this.level = level;
        this.outputPath = outputPath;
        this.bloomFactory = bloomFactory;
        this.codec = codec;
        this.blockSize = blockSize;
        this.v3 = true;
        this.dictionaryTrainingEnabled = dictionaryTraining;
        this.dictionaryBlockThreshold = dictBlockThreshold;
        this.dictionaryMaxBufferBytes = dictMaxBufferBytes;
        this.dictionaryMaxSize = dictMaxSize;

        // Dictionary training is eligible only when: enabled + ZSTD codec (ID 0x03) + native
        // available
        boolean eligible = dictionaryTraining && codec != null && codec.codecId() == 0x03
                && ZstdDictionaryTrainer.isAvailable();
        this.dictEligible = eligible;
        if (eligible) {
            this.dictBufferedBlocks = new ArrayList<>();
        }

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

        // @spec F16.R12 — flush threshold reads configured field, not a hardcoded constant
        if (currentBlock.byteSize() >= blockSize) {
            flushCurrentBlock();
        }
    }

    private void flushCurrentBlock() throws IOException {
        if (currentBlock.count() == 0)
            return;
        byte[] blockBytes = currentBlock.serialize();

        if (dictEligible && !dictBufferAbandoned && dictBufferedBlocks != null) {
            // Dictionary training mode: buffer the raw block for later compression
            long newTotal = dictBufferedBytes + blockBytes.length;
            if (newTotal > dictionaryMaxBufferBytes) {
                // @spec F18.R14 — buffer limit exceeded: abandon training, continue streaming
                dictBufferAbandoned = true;
                // Compress and write all previously buffered blocks with plain codec
                for (byte[] buffered : dictBufferedBlocks) {
                    compressAndWriteBlock(buffered);
                }
                dictBufferedBlocks = null;
                // Also compress and write this block
                compressAndWriteBlock(blockBytes);
            } else {
                dictBufferedBlocks.add(blockBytes);
                dictBufferedBytes = newTotal;
            }
        } else if (codec != null) {
            compressAndWriteBlock(blockBytes);
        } else {
            // v1: write raw block
            writeBytes(blockBytes);
        }
        if (blockCount == Integer.MAX_VALUE) {
            throw new IOException("block count overflow: cannot exceed " + Integer.MAX_VALUE);
        }
        blockCount++;
        currentBlock = new DataBlock();
    }

    /**
     * Compresses a single serialized block and writes it to the channel, recording a compression
     * map entry.
     */
    private void compressAndWriteBlock(byte[] blockBytes) throws IOException {
        compressAndWriteBlock(blockBytes, codec);
    }

    // @spec F17.R37 — MemorySegment compress, no byte[] conversion
    /**
     * Compresses a single serialized block with the specified codec and writes it to the channel.
     */
    // @spec F16.R4,R5 — CRC32C over exact on-disk bytes (compressed or raw post-fallback)
    private void compressAndWriteBlock(byte[] blockBytes, CompressionCodec useCodec)
            throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment blockSeg = arena.allocate(blockBytes.length);
            MemorySegment.copy(blockBytes, 0, blockSeg, ValueLayout.JAVA_BYTE, 0,
                    blockBytes.length);

            int maxLen = useCodec.maxCompressedLength(blockBytes.length);
            MemorySegment compDst = arena.allocate(maxLen);
            MemorySegment compSlice = useCodec.compress(blockSeg, compDst);
            int compressedLen = (int) compSlice.byteSize();

            byte actualCodecId;
            byte[] dataToWrite;
            if (compressedLen >= blockBytes.length) {
                // Incompressible — store raw with NONE codec ID
                dataToWrite = blockBytes;
                actualCodecId = CompressionCodec.none().codecId();
            } else {
                dataToWrite = compSlice.toArray(ValueLayout.JAVA_BYTE);
                actualCodecId = useCodec.codecId();
            }

            int checksum = 0;
            if (v3) {
                CRC32C crc = new CRC32C();
                crc.update(dataToWrite, 0, dataToWrite.length);
                checksum = (int) crc.getValue();
            }
            compressionMapEntries.add(new CompressionMap.Entry(writePosition, dataToWrite.length,
                    blockBytes.length, actualCodecId, checksum));
            writeBytes(dataToWrite);
        }
    }

    private static final int MAX_ZERO_PROGRESS_WRITES = 1024;

    private void writeBytes(byte[] bytes) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int zeroProgressCount = 0;
        while (buf.hasRemaining()) {
            int written = channel.write(buf);
            if (written == 0) {
                if (++zeroProgressCount >= MAX_ZERO_PROGRESS_WRITES) {
                    throw new IOException("writeBytes made no progress after "
                            + MAX_ZERO_PROGRESS_WRITES + " attempts — channel may be broken");
                }
            } else {
                zeroProgressCount = 0;
            }
        }
        writePosition += bytes.length;
    }

    @Override
    public SSTableMetadata finish() throws IOException {
        if (state != State.OPEN) {
            throw new IllegalStateException("finish() already called or writer failed/closed");
        }
        if (entryCount == 0) {
            throw new IllegalStateException("cannot finish an empty SSTable");
        }

        try {
            // Flush remaining block
            flushCurrentBlock();

            // Build bloom filter — clamp entryCount to avoid negative int from long truncation
            int bloomCapacity = (int) Math.min(Integer.MAX_VALUE, Math.max(1, entryCount));
            bloomFilter = bloomFactory.create(bloomCapacity);
            for (byte[] key : indexKeys) {
                bloomFilter.add(MemorySegment.ofArray(key));
            }

            if (dictEligible && !dictBufferAbandoned && dictBufferedBlocks != null) {
                // Dictionary training lifecycle
                finishWithDictionaryTraining();
            } else if (codec != null) {
                // @spec F16.R16 — codec-configured writers always produce v3 (never v2)
                // v3 layout: [data blocks][compression map v3][key index][bloom filter][footer 72]
                assert v3 : "codec-configured writers must always set v3=true";
                if (dictionaryTrainingEnabled && !dictEligible) {
                    // Training was requested but not eligible (non-ZSTD or native unavailable)
                    trainingResult = new DictionaryTrainingResult(false, false, null, 0);
                } else if (dictBufferAbandoned) {
                    trainingResult = new DictionaryTrainingResult(true, false,
                            "buffer limit exceeded", 0);
                }
                finishV3Layout();
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
        } catch (IOException e) {
            state = State.FAILED;
            throw e;
        }
    }

    /** Writes v1 key index: [numKeys(4)][per key: keyLen(4) + key + fileOffset(8)]. */
    private void writeKeyIndexV1() throws IOException {
        int numKeys = indexKeys.size();
        long indexSize = 4L;
        for (byte[] k : indexKeys) {
            indexSize += 4 + k.length + 8;
        }
        if (indexSize > Integer.MAX_VALUE) {
            throw new IOException(
                    "key index too large: %d bytes exceeds Integer.MAX_VALUE".formatted(indexSize));
        }
        byte[] buf = new byte[(int) indexSize];
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
        long indexSize = 4L;
        for (byte[] k : indexKeys) {
            indexSize += 4 + k.length + 4 + 4;
        }
        if (indexSize > Integer.MAX_VALUE) {
            throw new IOException(
                    "key index too large: %d bytes exceeds Integer.MAX_VALUE".formatted(indexSize));
        }
        byte[] buf = new byte[(int) indexSize];
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

    // @spec F16.R14,R15 — 72-byte v3 footer: 9 BE longs ending with MAGIC_V3; blockSize stored
    private void writeFooterV3(long mapOffset, long mapLength, long idxOffset, long idxLength,
            long fltOffset, long fltLength) throws IOException {
        byte[] buf = new byte[SSTableFormat.FOOTER_SIZE_V3];
        int off = 0;
        off = writeLong(buf, off, mapOffset);
        off = writeLong(buf, off, mapLength);
        off = writeLong(buf, off, idxOffset);
        off = writeLong(buf, off, idxLength);
        off = writeLong(buf, off, fltOffset);
        off = writeLong(buf, off, fltLength);
        off = writeLong(buf, off, entryCount);
        off = writeLong(buf, off, blockSize);
        writeLong(buf, off, SSTableFormat.MAGIC_V3);
        writeBytes(buf);
    }

    private void writeFooterV4(long mapOffset, long mapLength, long dictOffset, long dictLength,
            long idxOffset, long idxLength, long fltOffset, long fltLength) throws IOException {
        byte[] buf = new byte[SSTableFormat.FOOTER_SIZE_V4];
        int off = 0;
        off = writeLong(buf, off, mapOffset);
        off = writeLong(buf, off, mapLength);
        off = writeLong(buf, off, dictOffset);
        off = writeLong(buf, off, dictLength);
        off = writeLong(buf, off, idxOffset);
        off = writeLong(buf, off, idxLength);
        off = writeLong(buf, off, fltOffset);
        off = writeLong(buf, off, fltLength);
        off = writeLong(buf, off, entryCount);
        off = writeLong(buf, off, blockSize);
        writeLong(buf, off, SSTableFormat.MAGIC_V4);
        writeBytes(buf);
    }

    /** Writes the standard v3 layout (data blocks already written). */
    private void finishV3Layout() throws IOException {
        long mapOffset = writePosition;
        CompressionMap compressionMap = new CompressionMap(compressionMapEntries);
        writeBytes(compressionMap.serializeV3());
        long mapLength = writePosition - mapOffset;

        long indexOffset = writePosition;
        writeKeyIndexV2();
        long indexLength = writePosition - indexOffset;

        long filterOffset = writePosition;
        MemorySegment filterBytes = bloomFilter.serialize();
        byte[] filterArray = filterBytes.toArray(ValueLayout.JAVA_BYTE);
        writeBytes(filterArray);
        long filterLength = writePosition - filterOffset;

        writeFooterV3(mapOffset, mapLength, indexOffset, indexLength, filterOffset, filterLength);
    }

    // @spec F18.R11,R11a — train on all buffered blocks, emit v4 with dictionary meta-block
    // @spec F18.R12 — below-threshold: plain codec, v3, no dictionary stored
    // @spec F18.R18 — dictionary written after compression map, before key index
    // @spec F18.R27 — training failure: fall back to plain ZSTD, no SSTable-write failure
    /**
     * Finishes the SSTable with dictionary training. Buffered blocks are either compressed with a
     * trained dictionary (v4 format) or with plain ZSTD (v3 format) depending on block count and
     * training success.
     */
    private void finishWithDictionaryTraining() throws IOException {
        assert dictBufferedBlocks != null : "buffered blocks must not be null";
        List<byte[]> blocks = dictBufferedBlocks;
        dictBufferedBlocks = null; // release reference

        if (blocks.size() < dictionaryBlockThreshold) {
            // R12: below threshold — compress with plain codec, v3 format
            for (byte[] block : blocks) {
                compressAndWriteBlock(block);
            }
            trainingResult = new DictionaryTrainingResult(true, false, "below block threshold", 0);
            finishV3Layout();
            return;
        }

        // Attempt dictionary training
        MemorySegment dictionary;
        try {
            ZstdDictionaryTrainer trainer = new ZstdDictionaryTrainer();
            for (byte[] block : blocks) {
                trainer.addSample(MemorySegment.ofArray(block));
            }
            dictionary = trainer.train(dictionaryMaxSize);
        } catch (Exception e) {
            // R27: training failed — fall back to plain ZSTD, v3 format
            for (byte[] block : blocks) {
                compressAndWriteBlock(block);
            }
            trainingResult = new DictionaryTrainingResult(true, false,
                    "training failed: " + e.getMessage(), 0);
            finishV3Layout();
            return;
        }

        // Training succeeded — create dictionary-bound codec and compress all blocks
        int dictSize = (int) dictionary.byteSize();
        try (var dictCodec = (AutoCloseable) CompressionCodec.zstd(3, dictionary)) {
            CompressionCodec dictBoundCodec = (CompressionCodec) dictCodec;
            for (byte[] block : blocks) {
                compressAndWriteBlock(block, dictBoundCodec);
            }
        } catch (Exception e) {
            throw new IOException("Failed to compress with dictionary codec", e);
        }

        // v4 layout: [data blocks][compression map v3][dictionary][key index][bloom filter][footer
        // 88]
        long mapOffset = writePosition;
        CompressionMap compressionMap = new CompressionMap(compressionMapEntries);
        writeBytes(compressionMap.serializeV3());
        long mapLength = writePosition - mapOffset;

        long dictOffset = writePosition;
        byte[] dictBytes = dictionary.toArray(ValueLayout.JAVA_BYTE);
        writeBytes(dictBytes);
        long dictLength = writePosition - dictOffset;

        long indexOffset = writePosition;
        writeKeyIndexV2();
        long indexLength = writePosition - indexOffset;

        long filterOffset = writePosition;
        MemorySegment filterBytes = bloomFilter.serialize();
        byte[] filterArray = filterBytes.toArray(ValueLayout.JAVA_BYTE);
        writeBytes(filterArray);
        long filterLength = writePosition - filterOffset;

        writeFooterV4(mapOffset, mapLength, dictOffset, dictLength, indexOffset, indexLength,
                filterOffset, filterLength);

        trainingResult = new DictionaryTrainingResult(true, true, null, dictSize);
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

    /**
     * Returns a new builder for constructing a {@code TrieSSTableWriter} with configurable block
     * size and compression.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@code TrieSSTableWriter} supporting v3/v4 format options including dictionary
     * training.
     */
    public static final class Builder {

        private long id;
        private Level level;
        private Path path;
        private BloomFilter.Factory bloomFactory;
        private CompressionCodec codec;
        private int blockSize = SSTableFormat.DEFAULT_BLOCK_SIZE;
        private boolean dictTraining;
        private int dictBlockThreshold = 64;
        private long dictMaxBufferBytes = 256L * 1024 * 1024;
        private int dictMaxSize = 32768;

        private Builder() {
        }

        public Builder id(long id) {
            this.id = id;
            return this;
        }

        public Builder level(Level level) {
            this.level = Objects.requireNonNull(level, "level must not be null");
            return this;
        }

        public Builder path(Path path) {
            this.path = Objects.requireNonNull(path, "path must not be null");
            return this;
        }

        public Builder bloomFactory(BloomFilter.Factory bloomFactory) {
            this.bloomFactory = Objects.requireNonNull(bloomFactory,
                    "bloomFactory must not be null");
            return this;
        }

        public Builder codec(CompressionCodec codec) {
            this.codec = codec;
            return this;
        }

        // @spec F16.R10,R11 — Builder.blockSize(int) validates and stores; default
        // DEFAULT_BLOCK_SIZE
        public Builder blockSize(int blockSize) {
            SSTableFormat.validateBlockSize(blockSize);
            this.blockSize = blockSize;
            return this;
        }

        /**
         * Enables or disables dictionary training for ZSTD compression.
         *
         * <p>
         * When enabled and the codec is ZSTD with native libzstd available, the writer buffers all
         * uncompressed data blocks in memory, trains a dictionary in {@code finish()}, and produces
         * a v4 format file with the dictionary embedded. When the codec is not ZSTD or native is
         * unavailable, this setting is silently ignored.
         *
         * @param enable {@code true} to enable dictionary training; default is {@code false}
         * @return this builder
         */
        public Builder dictionaryTraining(boolean enable) {
            this.dictTraining = enable;
            return this;
        }

        /**
         * Sets the minimum number of blocks required before dictionary training is attempted.
         *
         * @param threshold minimum block count; default is 64
         * @return this builder
         * @throws IllegalArgumentException if threshold is less than 1
         */
        public Builder dictionaryBlockThreshold(int threshold) {
            if (threshold < 1) {
                throw new IllegalArgumentException(
                        "dictionaryBlockThreshold must be >= 1, got: " + threshold);
            }
            this.dictBlockThreshold = threshold;
            return this;
        }

        /**
         * Sets the maximum total bytes of buffered uncompressed blocks for dictionary training.
         *
         * <p>
         * If the total buffered bytes exceed this limit, dictionary training is abandoned and
         * blocks are compressed without a dictionary.
         *
         * @param maxBytes maximum buffer size in bytes; default is 256 MiB
         * @return this builder
         * @throws IllegalArgumentException if maxBytes is less than 1
         */
        public Builder dictionaryMaxBufferBytes(long maxBytes) {
            if (maxBytes < 1) {
                throw new IllegalArgumentException(
                        "dictionaryMaxBufferBytes must be >= 1, got: " + maxBytes);
            }
            this.dictMaxBufferBytes = maxBytes;
            return this;
        }

        /**
         * Sets the maximum size of the trained dictionary in bytes.
         *
         * @param maxDictBytes maximum dictionary size; default is 32768 (32 KB)
         * @return this builder
         * @throws IllegalArgumentException if maxDictBytes is outside ZstdDictionaryTrainer bounds
         */
        public Builder dictionaryMaxSize(int maxDictBytes) {
            if (maxDictBytes < 256 || maxDictBytes > 1_048_576) {
                throw new IllegalArgumentException(
                        "dictionaryMaxSize must be between 256 and 1048576, got: " + maxDictBytes);
            }
            this.dictMaxSize = maxDictBytes;
            return this;
        }

        /**
         * Builds and returns a new writer.
         *
         * @return a new writer
         * @throws IOException if the output file cannot be opened
         * @throws IllegalArgumentException if non-default blockSize is set without a codec
         */
        public TrieSSTableWriter build() throws IOException {
            Objects.requireNonNull(level, "level must be set");
            Objects.requireNonNull(path, "path must be set");
            if (bloomFactory == null) {
                bloomFactory = n -> new BlockedBloomFilter(n, 0.01);
            }
            // @spec F16.R16 — non-default blockSize without codec rejected at construction
            if (blockSize != SSTableFormat.DEFAULT_BLOCK_SIZE && codec == null) {
                throw new IllegalArgumentException(
                        "non-default blockSize requires a compression codec");
            }
            return new TrieSSTableWriter(id, level, path, bloomFactory, codec, blockSize,
                    dictTraining, dictBlockThreshold, dictMaxBufferBytes, dictMaxSize);
        }
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
        boolean shouldDelete = (state == State.OPEN || state == State.FAILED);
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

    /**
     * Returns the result of dictionary training, or {@code null} if dictionary training was not
     * enabled or {@link #finish()} has not been called.
     *
     * @return the training result, or {@code null}
     */
    // @spec F18.R13a — observable notification path for training skip/failure events
    public DictionaryTrainingResult dictionaryTrainingResult() {
        return trainingResult;
    }

    /**
     * Describes the outcome of dictionary training during SSTable writing.
     *
     * @param attempted whether dictionary training was attempted (true even if skipped due to
     *            threshold)
     * @param succeeded whether training succeeded and a dictionary was embedded
     * @param reason reason for failure or skip; {@code null} on success
     * @param dictionarySize size of the trained dictionary in bytes; 0 if not trained
     */
    public record DictionaryTrainingResult(boolean attempted, boolean succeeded, String reason,
            int dictionarySize) {
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
