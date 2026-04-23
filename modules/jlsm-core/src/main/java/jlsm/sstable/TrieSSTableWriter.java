package jlsm.sstable;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.bloom.BloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.io.ArenaBufferPool;
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
import java.nio.channels.Channel;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
// @spec sstable.writer.R1 — single codec per file
// @spec sstable.writer.R2 — if compressed >= uncompressed, store as NONE
// @spec sstable.writer.R3 — map built during write, serialized after all blocks
// @spec sstable.format-v2.R19 — intra-block offsets may use int
// @spec compression.zstd-dictionary.R10,R11,R12,R13,R13a,R14,R18,R19,R27 — dictionary-aware writer
// lifecycle:
// buffer → train → compress-with-dict → v4 meta-block; graceful degradation paths
public final class TrieSSTableWriter implements SSTableWriter {

    private enum State {
        OPEN, FINISHED, FAILED, CLOSED
    }

    private final long id;
    private final Level level;
    private final Path outputPath;
    /** Where bytes are actually written. For v5, this is outputPath + ".partial." + writerId. */
    private final Path workingPath;
    /** True when {@link #workingPath} differs from {@link #outputPath} (v5 atomic-commit). */
    private final boolean usingPartialPath;
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

    /**
     * Selected output format version. One of 1, 2, 3, 4, 5. v1=no codec; v2=legacy codec path
     * (unused at builder level); v3/v4=legacy; v5=default when codec is configured per R36.
     */
    private final int formatVersion;

    /** Listener invoked when fsync/force() is skipped for non-FileChannel outputs (R23). */
    private final FsyncSkipListener fsyncSkipListener;

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
        // Legacy path: codec → v3; no codec → v1. v5 is only produced via Builder.
        this.v3 = codec != null;
        this.formatVersion = codec != null ? 3 : 1;
        this.fsyncSkipListener = null;
        this.dictionaryTrainingEnabled = false;
        this.dictionaryBlockThreshold = 64;
        this.dictionaryMaxBufferBytes = 256L * 1024 * 1024;
        this.dictionaryMaxSize = 32768;
        this.dictEligible = false;
        // Legacy 2-arg paths write directly to outputPath (no partial commit).
        this.workingPath = outputPath;
        this.usingPartialPath = false;
        this.channel = Files.newByteChannel(outputPath, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    /**
     * Internal constructor used by the Builder for v3/v4/v5 format with configurable block size and
     * optional dictionary training.
     */
    private TrieSSTableWriter(long id, Level level, Path outputPath,
            BloomFilter.Factory bloomFactory, CompressionCodec codec, int blockSize,
            boolean dictionaryTraining, int dictBlockThreshold, long dictMaxBufferBytes,
            int dictMaxSize, int formatVersion, FsyncSkipListener fsyncSkipListener)
            throws IOException {
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        Objects.requireNonNull(bloomFactory, "bloomFactory must not be null");
        if (codec != null && codec.codecId() == 0x00 && codec != CompressionCodec.none()) {
            throw new IllegalArgumentException(
                    "custom codec must not use codecId 0x00 (reserved for NoneCodec)");
        }
        if (formatVersion < 1 || formatVersion > 5) {
            throw new IllegalArgumentException(
                    "formatVersion must be in [1, 5]; got: " + formatVersion);
        }
        this.id = id;
        this.level = level;
        this.outputPath = outputPath;
        this.bloomFactory = bloomFactory;
        this.codec = codec;
        this.blockSize = blockSize;
        this.v3 = true;
        this.formatVersion = formatVersion;
        this.fsyncSkipListener = fsyncSkipListener;
        this.dictionaryTrainingEnabled = dictionaryTraining;
        this.dictionaryBlockThreshold = dictBlockThreshold;
        this.dictionaryMaxBufferBytes = dictMaxBufferBytes;
        this.dictionaryMaxSize = dictMaxSize;

        // Dictionary training is eligible only when: enabled + ZSTD codec (ID 0x03) + native
        // available + not v5 (v5 has no current dictionary-embedding lifecycle).
        boolean eligible = dictionaryTraining && codec != null && codec.codecId() == 0x03
                && ZstdDictionaryTrainer.isAvailable() && formatVersion != 5;
        this.dictEligible = eligible;
        if (eligible) {
            this.dictBufferedBlocks = new ArrayList<>();
        }

        // v5: write to a per-writer-unique partial path, then atomically rename to outputPath
        // at finish(). Pre-v5 formats write directly to outputPath.
        if (formatVersion == 5) {
            String writerId = UUID.randomUUID().toString();
            Path parent = outputPath.getParent();
            String basename = outputPath.getFileName().toString();
            Path partial = parent == null ? Path.of(basename + ".partial." + writerId)
                    : parent.resolve(basename + ".partial." + writerId);
            this.workingPath = partial;
            this.usingPartialPath = true;
            this.channel = Files.newByteChannel(partial, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } else {
            this.workingPath = outputPath;
            this.usingPartialPath = false;
            this.channel = Files.newByteChannel(outputPath, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        }
    }

    /**
     * Appends an entry to the current block. Enforces strictly-ascending keys and the producer-side
     * oversize guard for v5 VarInt-prefixed blocks.
     *
     * @spec sstable.end-to-end-integrity.R46
     */
    @Override
    public void append(Entry entry) throws IOException {
        Objects.requireNonNull(entry, "entry must not be null");
        if (state == State.CLOSED) {
            throw new IllegalStateException("writer is closed");
        }
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

        // Upstream entry-size guard: reject entries that would produce a data block larger
        // than SSTableFormat.MAX_BLOCK_SIZE before the bytes are ever added to the current
        // block. Without this guard, an oversized entry propagates through flushCurrentBlock
        // into compressAndWriteBlock where VarInt.encode(dataToWrite.length) throws the
        // cryptic "VarInt value out of range" IOException from deep inside the pipeline.
        // The block header occupies 4 bytes; adding the encoded entry must keep the block
        // size within MAX_BLOCK_SIZE so that any downstream VarInt-prefixed write is valid.
        long projectedBlockSize = (long) currentBlock.byteSize() + (long) encoded.length;
        if (projectedBlockSize > SSTableFormat.MAX_BLOCK_SIZE) {
            throw new IOException("entry too large: encoded entry size " + encoded.length
                    + " would produce a data block of " + projectedBlockSize
                    + " bytes, which exceeds MAX_BLOCK_SIZE (" + SSTableFormat.MAX_BLOCK_SIZE
                    + ")");
        }

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

        // @spec sstable.v3-format-upgrade.R12 — flush threshold reads configured field, not a
        // hardcoded constant
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
                // @spec compression.zstd-dictionary.R14 — buffer limit exceeded: abandon training,
                // continue streaming
                dictBufferAbandoned = true;
                // Compress and write all previously buffered blocks with plain codec
                for (byte[] buffered : dictBufferedBlocks) {
                    compressAndWriteBlock(buffered);
                }
                dictBufferedBlocks = null;
                // Reset the byte counter alongside releasing the buffered-blocks list so the
                // writer's reported buffered-bytes state stays truthful after abandon. The
                // counter is otherwise unreachable (guarded by dictBufferedBlocks != null), but
                // keeping it accurate removes a latent trap for future refactors that might
                // read the field outside the current guarded block.
                dictBufferedBytes = 0L;
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

    // @spec sstable.writer.R11 — MemorySegment compress, no byte[] conversion
    /**
     * Compresses a single serialized block with the specified codec and writes it to the channel.
     */
    // @spec sstable.v3-format-upgrade.R4,R5 — CRC32C over exact on-disk bytes (compressed or raw
    // post-fallback)
    // @spec sstable.end-to-end-integrity.R1,R3 — v5: write VarInt length prefix before payload;
    // record blockOffset at the first byte AFTER the VarInt (payload start). R3 atomicity:
    // VarInt write + blockOffset record + payload write + map entry append form a unit; any
    // partial failure transitions writer to FAILED.
    // @spec sstable.end-to-end-integrity.R47 — v5 per-block CRC32C is unconditional: the CRC
    // branch triggers on `v3 || formatVersion == 5` so a v5 writer is never observed with
    // v3=false recording checksum=0 into the map entry.
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
            // Per-block CRC32C is required for v3/v4 (per-block-checksums ADR) and v5 writes
            // (end-to-end-integrity R14). The v5 footer-level guarantee depends on every map
            // entry carrying a real checksum, so the CRC must not be gated on the legacy `v3`
            // flag alone — a v5 writer with v3=false would otherwise silently record
            // checksum=0 for every block.
            if (v3 || formatVersion == 5) {
                CRC32C crc = new CRC32C();
                crc.update(dataToWrite, 0, dataToWrite.length);
                checksum = (int) crc.getValue();
            }
            if (formatVersion == 5) {
                // R1/R3: write VarInt of on-disk payload length, then payload. blockOffset
                // points at payload start (post-VarInt).
                byte[] prefix = new byte[4];
                int prefixLen = jlsm.sstable.internal.VarInt.encode(dataToWrite.length, prefix, 0);
                writeBytes(prefix, prefixLen);
                compressionMapEntries.add(new CompressionMap.Entry(writePosition,
                        dataToWrite.length, blockBytes.length, actualCodecId, checksum));
                writeBytes(dataToWrite);
            } else {
                compressionMapEntries.add(new CompressionMap.Entry(writePosition,
                        dataToWrite.length, blockBytes.length, actualCodecId, checksum));
                writeBytes(dataToWrite);
            }
        }
    }

    private static final int MAX_ZERO_PROGRESS_WRITES = 1024;

    private void writeBytes(byte[] bytes) throws IOException {
        writeBytes(bytes, bytes.length);
    }

    private void writeBytes(byte[] bytes, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes, 0, length);
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
        writePosition += length;
    }

    @Override
    public SSTableMetadata finish() throws IOException {
        if (state == State.CLOSED) {
            throw new IllegalStateException("writer is closed");
        }
        if (state != State.OPEN) {
            throw new IllegalStateException("finish() already called or writer failed/closed");
        }
        if (entryCount == 0) {
            // @spec sstable.end-to-end-integrity.R17 — v5 forbids empty SSTables. The legacy
            // paths retain this guard as well to keep invariants uniform.
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

            if (formatVersion == 5) {
                // v5 finish path: emit v5 sections with per-section CRC32C and the 3-fsync
                // discipline (R19-R21) before writing the footer.
                finishV5Layout();
            } else if (dictEligible && !dictBufferAbandoned && dictBufferedBlocks != null) {
                // Dictionary training lifecycle
                finishWithDictionaryTraining();
            } else if (codec != null) {
                // Legacy v3 path (codec but pre-v5).
                assert v3 : "codec-configured writers must always set v3=true";
                if (dictionaryTrainingEnabled && !dictEligible) {
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

            if (formatVersion != 5) {
                // Legacy path: single fsync (if possible).
                if (channel instanceof FileChannel fc) {
                    fc.force(true);
                } else {
                    invokeFsyncSkipListener("non-file-channel");
                }
            }

            long sizeBytes = writePosition;

            // Atomic commit: partial path → final path (v5 only).
            if (usingPartialPath) {
                closeChannelQuietly();
                commitFromPartial();
            }

            state = State.FINISHED;

            return new SSTableMetadata(id, outputPath, level, smallestKey, largestKey, minSequence,
                    maxSequence, sizeBytes, entryCount);
        } catch (ClosedByInterruptException e) {
            // @spec sstable.end-to-end-integrity.R22 — preserve interrupt flag before propagating
            Thread.currentThread().interrupt();
            state = State.FAILED;
            throw e;
        } catch (IOException e) {
            state = State.FAILED;
            throw e;
        }
    }

    /**
     * Close the channel silently, accumulating any IOException into the current control flow. Used
     * in {@code finish()} before the atomic rename so the OS observes a closed file.
     */
    private void closeChannelQuietly() throws IOException {
        if (channel.isOpen()) {
            channel.close();
        }
    }

    /**
     * Atomically commit the partial file at {@link #workingPath} to {@link #outputPath}. Uses
     * {@code StandardCopyOption.ATOMIC_MOVE} when supported; on
     * {@link AtomicMoveNotSupportedException} falls back to a non-atomic move and notifies the
     * FsyncSkipListener per R39(b).
     *
     * <p>
     * If {@link #outputPath} already exists on the underlying filesystem, this method fails fast
     * with {@link FileAlreadyExistsException} <em>before</em> attempting any move. This keeps
     * semantics consistent across filesystems: on POSIX {@code rename(2)} would silently replace
     * the existing file under {@code ATOMIC_MOVE}, potentially destroying a live committed SSTable;
     * on filesystems where {@code ATOMIC_MOVE} is unsupported the non-options {@code Files.move}
     * fallback already throws {@link FileAlreadyExistsException}. The explicit pre-check unifies
     * the two paths and protects pre-existing committed data from silent destruction.
     * </p>
     *
     * @spec sstable.end-to-end-integrity.R39
     */
    private void commitFromPartial() throws IOException {
        // Fail fast if outputPath already exists — the ATOMIC_MOVE path would otherwise silently
        // overwrite a pre-existing committed SSTable on POSIX, while the non-atomic fallback
        // would throw FileAlreadyExistsException. Consistency wins over platform-dependent
        // surprises; callers must pick a fresh outputPath for each commit.
        if (Files.exists(outputPath)) {
            throw new FileAlreadyExistsException(outputPath.toString(), null,
                    "refusing to overwrite pre-existing outputPath during commit");
        }
        try {
            Files.move(workingPath, outputPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            if (fsyncSkipListener != null) {
                try {
                    fsyncSkipListener.onFsyncSkip(outputPath, channel.getClass(),
                            "atomic-rename-unsupported");
                } catch (RuntimeException ignored) {
                    // Listener errors must not obscure the commit itself.
                }
            }
            Files.move(workingPath, outputPath);
        }
    }

    /**
     * v5 finish-path: writes data + compression map + (optional dictionary) + key index + bloom
     * filter + footer, with per-section CRC32C and 3 fsync calls (R19-R21).
     *
     * @spec sstable.end-to-end-integrity.R11
     * @spec sstable.end-to-end-integrity.R13
     * @spec sstable.end-to-end-integrity.R14
     * @spec sstable.end-to-end-integrity.R15
     * @spec sstable.end-to-end-integrity.R19
     * @spec sstable.end-to-end-integrity.R20
     * @spec sstable.end-to-end-integrity.R21
     * @spec sstable.end-to-end-integrity.R37
     */
    private void finishV5Layout() throws IOException {
        // R19: force(true) after all data blocks, before the first metadata section.
        forceOrSkip("post-data");

        // Section 1: compression map
        final long mapOffset = writePosition;
        final byte[] mapBytes = new CompressionMap(compressionMapEntries).serializeV3();
        writeBytes(mapBytes);
        final long mapLength = writePosition - mapOffset;
        final int mapChecksum = crc32cOf(mapBytes);

        // Section 2 (optional): dictionary. The v5 writer currently does not train dictionaries;
        // keep sentinel per R15.
        final long dictOffset = 0L;
        final long dictLength = 0L;
        final int dictChecksum = 0;

        // Section 3: key index
        final long idxOffset = writePosition;
        final byte[] idxBytes = buildKeyIndexV2();
        writeBytes(idxBytes);
        final long idxLength = writePosition - idxOffset;
        final int idxChecksum = crc32cOf(idxBytes);

        // Section 4: bloom filter
        final long fltOffset = writePosition;
        final MemorySegment filterBytes = bloomFilter.serialize();
        final byte[] filterArray = filterBytes.toArray(ValueLayout.JAVA_BYTE);
        writeBytes(filterArray);
        final long fltLength = writePosition - fltOffset;
        final int fltChecksum = crc32cOf(filterArray);

        // R20: force(true) after last metadata, before footer.
        forceOrSkip("post-metadata");

        // Footer
        writeFooterV5(mapOffset, mapLength, dictOffset, dictLength, dictChecksum, idxOffset,
                idxLength, idxChecksum, fltOffset, fltLength, fltChecksum, mapChecksum);

        // R21: force(true) after footer.
        forceOrSkip("post-footer");
    }

    private static int crc32cOf(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    /** Issue a force(true) on the channel if it is a FileChannel; otherwise notify listener. */
    private void forceOrSkip(String site) throws IOException {
        if (channel instanceof FileChannel fc) {
            try {
                fc.force(true);
            } catch (ClosedByInterruptException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        } else {
            invokeFsyncSkipListener("non-file-channel");
        }
    }

    private void invokeFsyncSkipListener(String reason) {
        if (fsyncSkipListener == null) {
            return;
        }
        try {
            Class<? extends Channel> cls = channel.getClass();
            fsyncSkipListener.onFsyncSkip(outputPath, cls, reason);
        } catch (RuntimeException ignored) {
            // Listener failures must not abort the writer; the caller is responsible for
            // ensuring listeners do not throw.
        }
    }

    /**
     * Writes the v5 footer (112 bytes) with CRC32C self-checksum and the v5 magic trailer.
     *
     * @spec sstable.end-to-end-integrity.R55
     */
    private void writeFooterV5(long mapOffset, long mapLength, long dictOffset, long dictLength,
            int dictChecksum, long idxOffset, long idxLength, int idxChecksum, long fltOffset,
            long fltLength, int fltChecksum, int mapChecksum) throws IOException {
        // Producer-side invariant guards (R17/R18 and related). The reader enforces these on
        // ingress, but the producer must never let a malformed footer reach the on-disk surface
        // — a corrupt file could otherwise be uploaded or transferred before the consumer-side
        // integrity check runs. These guards mirror the reader's validation at
        // TrieSSTableReader.readFooter (blockCount >= 1, mapLength >= 1, blockSize power-of-two
        // within [MIN_BLOCK_SIZE, MAX_BLOCK_SIZE]) plus an entryCount >= 1 check for symmetry
        // with the empty-SSTable guard in finish().
        if (blockCount < 1) {
            throw new IllegalStateException(
                    "writeFooterV5 invariant violation: blockCount must be >= 1 (R17); got "
                            + blockCount);
        }
        if (mapLength < 1L) {
            throw new IllegalStateException(
                    "writeFooterV5 invariant violation: mapLength must be >= 1 (R18); got "
                            + mapLength);
        }
        if (entryCount < 1L) {
            throw new IllegalStateException(
                    "writeFooterV5 invariant violation: entryCount must be >= 1; got "
                            + entryCount);
        }
        if (blockSize < SSTableFormat.MIN_BLOCK_SIZE || blockSize > SSTableFormat.MAX_BLOCK_SIZE
                || (blockSize & (blockSize - 1)) != 0) {
            throw new IllegalStateException(
                    "writeFooterV5 invariant violation: blockSize must be a power of two in ["
                            + SSTableFormat.MIN_BLOCK_SIZE + ", " + SSTableFormat.MAX_BLOCK_SIZE
                            + "]; got " + blockSize);
        }

        final jlsm.sstable.internal.V5Footer withZeroChecksum = new jlsm.sstable.internal.V5Footer(
                mapOffset, mapLength, dictOffset, dictLength, idxOffset, idxLength, fltOffset,
                fltLength, entryCount, (long) blockSize, blockCount, mapChecksum, dictChecksum,
                idxChecksum, fltChecksum, 0, SSTableFormat.MAGIC_V5);
        final byte[] buf = new byte[SSTableFormat.FOOTER_SIZE_V5];
        jlsm.sstable.internal.V5Footer.encode(withZeroChecksum, buf, 0);
        final int footerChecksum = jlsm.sstable.internal.V5Footer.computeFooterChecksum(buf);
        final jlsm.sstable.internal.V5Footer finalFooter = new jlsm.sstable.internal.V5Footer(
                mapOffset, mapLength, dictOffset, dictLength, idxOffset, idxLength, fltOffset,
                fltLength, entryCount, (long) blockSize, blockCount, mapChecksum, dictChecksum,
                idxChecksum, fltChecksum, footerChecksum, SSTableFormat.MAGIC_V5);
        jlsm.sstable.internal.V5Footer.encode(finalFooter, buf, 0);
        writeBytes(buf);
    }

    /** Build the v2-format key index (same layout used by v3/v4/v5) into a single byte array. */
    private byte[] buildKeyIndexV2() throws IOException {
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
        return buf;
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

    // @spec sstable.v3-format-upgrade.R14,R15 — 72-byte v3 footer: 9 BE longs ending with MAGIC_V3;
    // blockSize stored
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

    // @spec compression.zstd-dictionary.R11,R11a — train on all buffered blocks, emit v4 with
    // dictionary meta-block
    // @spec compression.zstd-dictionary.R12 — below-threshold: plain codec, v3, no dictionary
    // stored
    // @spec compression.zstd-dictionary.R18 — dictionary written after compression map, before key
    // index
    // @spec compression.zstd-dictionary.R27 — training failure: fall back to plain ZSTD, no
    // SSTable-write failure
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
        private boolean blockSizeExplicit = false;
        private ArenaBufferPool pool = null;
        private long derivedBlockSizeCandidate = 0L;
        private boolean dictTraining;
        private int dictBlockThreshold = 64;
        private long dictMaxBufferBytes = 256L * 1024 * 1024;
        private int dictMaxSize = 32768;
        private FsyncSkipListener fsyncSkipListener = null;
        /**
         * Explicit on-disk format version when set, else {@code 0} meaning "auto-select": codec →
         * v5 (per R36), no codec → v1.
         */
        private int formatVersion = 0;

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

        // @spec sstable.v3-format-upgrade.R10,R11 — Builder.blockSize(int) validates and stores;
        // default DEFAULT_BLOCK_SIZE
        // @spec sstable.pool-aware-block-size.R6 — tracks explicit invocation independently of
        // value
        // @spec sstable.pool-aware-block-size.R11a — last-wins on repeated calls
        public Builder blockSize(int blockSize) {
            SSTableFormat.validateBlockSize(blockSize);
            this.blockSize = blockSize;
            this.blockSizeExplicit = true;
            return this;
        }

        /**
         * Configures the SSTable writer with the given buffer pool. The writer derives its block
         * size from {@link ArenaBufferPool#bufferSize()} unless {@link #blockSize(int)} is also
         * invoked (in which case the explicit value wins per R11).
         *
         * <p>
         * Eager validation: if {@code blockSize(int)} has not been invoked prior to this call, the
         * pool's {@code bufferSize()} is evaluated against R8 and R9 at the time of this call.
         * Validation failures throw immediately, not at {@link #build()}.
         *
         * <p>
         * Atomicity on repeated calls: if this is not the first {@code pool()} call, the candidate
         * pool is validated <em>before</em> replacing the retained reference. A failed repeat
         * {@code pool()} is a no-op with respect to all builder state.
         *
         * @param pool a non-null, open pool
         * @return this builder
         * @throws NullPointerException if {@code pool} is null
         * @throws IllegalStateException if {@code pool.isClosed()} at call time
         * @throws IllegalArgumentException if the pool's bufferSize() exceeds Integer.MAX_VALUE
         *             (R8) or fails {@code SSTableFormat.validateBlockSize} (R9/R10), when eager
         *             validation applies
         * @spec sstable.pool-aware-block-size.R3
         * @spec sstable.pool-aware-block-size.R4
         * @spec sstable.pool-aware-block-size.R4a
         * @spec sstable.pool-aware-block-size.R5
         * @spec sstable.pool-aware-block-size.R7
         * @spec sstable.pool-aware-block-size.R7a
         * @spec sstable.pool-aware-block-size.R8
         * @spec sstable.pool-aware-block-size.R9
         * @spec sstable.pool-aware-block-size.R10
         * @spec sstable.pool-aware-block-size.R11a
         */
        public Builder pool(ArenaBufferPool pool) {
            // @spec sstable.pool-aware-block-size.R4,R4a — runtime null check, not assert-only
            Objects.requireNonNull(pool, "pool must not be null");
            // @spec sstable.pool-aware-block-size.R5,R4a — runtime closed-pool check, not
            // assert-only
            if (pool.isClosed()) {
                throw new IllegalStateException(
                        "pool(ArenaBufferPool) rejected at call time: the supplied pool is closed");
            }
            // @spec sstable.pool-aware-block-size.R7a — eagerly query and validate iff explicit
            // blockSize(int) has not been called. When blockSizeExplicit is true, R11 says the
            // explicit value wins regardless of call order, so derivation and eager validation
            // are skipped for this pool reference.
            if (!this.blockSizeExplicit) {
                long candidate = pool.bufferSize();
                // @spec sstable.pool-aware-block-size.R8 — long comparison BEFORE any narrowing to
                // int.
                // Strict inequality: Integer.MAX_VALUE itself is NOT rejected here; it falls
                // through
                // to R9 via validateBlockSize which will reject it (exceeds max and/or not
                // power-of-two).
                if (candidate > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("pool.bufferSize() (" + candidate
                            + ") exceeds Integer.MAX_VALUE and cannot be used as a block size");
                }
                int derivedInt = (int) candidate;
                // @spec sstable.pool-aware-block-size.R9,R10 — same validator as the explicit path;
                // failure surfaces the validator's diagnostic (min/max/power-of-two) message.
                SSTableFormat.validateBlockSize(derivedInt);
                // @spec sstable.pool-aware-block-size.R11a — atomicity: only after R8 and R9 pass
                // may the candidate replace the retained reference and derived candidate.
                this.derivedBlockSizeCandidate = candidate;
            }
            this.pool = pool;
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
         * Register a listener that is invoked when the writer skips an fsync on a non-FileChannel
         * output (remote/NIO provider). The listener may be invoked multiple times (once per
         * skipped fsync site).
         *
         * @spec sstable.end-to-end-integrity.R23
         */
        public Builder fsyncSkipListener(FsyncSkipListener listener) {
            this.fsyncSkipListener = Objects.requireNonNull(listener, "listener must not be null");
            return this;
        }

        /**
         * Explicitly select the on-disk SSTable format version. Supported values: 3, 4, 5.
         *
         * <p>
         * When not called, the writer auto-selects: codec-configured writers produce v5 per
         * {@code sstable.end-to-end-integrity.R36}; no-codec writers produce v1.
         * </p>
         *
         * <p>
         * This accessor exists to let legacy tests opt back into v3 or v4 format for regression
         * coverage of those on-disk layouts; production code should leave it unset so writers
         * follow the current default (v5).
         * </p>
         *
         * @param version one of {@code 3}, {@code 4}, or {@code 5}
         * @return this builder
         * @throws IllegalArgumentException if version is outside the supported set
         */
        public Builder formatVersion(int version) {
            if (version != 3 && version != 4 && version != 5) {
                throw new IllegalArgumentException(
                        "formatVersion must be one of 3, 4, 5; got: " + version);
            }
            this.formatVersion = version;
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
            // @spec sstable.pool-aware-block-size.R5a — re-check pool closed state ONLY when the
            // pool is still the active block-size source (i.e. no explicit blockSize(int) call).
            // If blockSizeExplicit, the pool is no longer the block-size source per R11 and its
            // lifecycle is not the writer's concern.
            if (pool != null && !blockSizeExplicit && pool.isClosed()) {
                throw new IllegalStateException(
                        "pool was closed between pool() and build(): the retained pool is no longer usable");
            }
            // @spec sstable.pool-aware-block-size.R7 — when the pool is the active block-size
            // source,
            // assign the candidate validated by R7a at pool() call time.
            // @spec sstable.pool-aware-block-size.R11 — explicit blockSize(int) wins; the
            // pool-derived
            // value is discarded.
            // @spec sstable.pool-aware-block-size.R13 — default path: neither pool nor explicit →
            // blockSize retains its DEFAULT_BLOCK_SIZE initializer value.
            int effectiveBlockSize = this.blockSize;
            if (pool != null && !blockSizeExplicit) {
                effectiveBlockSize = (int) derivedBlockSizeCandidate;
            }
            // @spec sstable.v3-format-upgrade.R16 — non-default blockSize without codec rejected at
            // construction. @spec sstable.pool-aware-block-size.R15 — pool-derived non-default
            // block sizes inherit this check identically.
            if (effectiveBlockSize != SSTableFormat.DEFAULT_BLOCK_SIZE && codec == null) {
                throw new IllegalArgumentException(
                        "non-default blockSize requires a compression codec");
            }
            // Default bloomFactory is resolved into a local only — never written back to
            // this.bloomFactory — so a failed build() (at any gate above or below) cannot leak
            // a silent default into the Builder. This preserves the R11a atomicity pattern
            // followed by every other setter on this builder.
            final BloomFilter.Factory effectiveBloomFactory = (bloomFactory != null) ? bloomFactory
                    : n -> new BlockedBloomFilter(n, 0.01);
            // @spec sstable.pool-aware-block-size.R16 — the effective block size (after any pool
            // derivation and validation) is written to the footer via F16.R15 by the constructor.
            //
            // @spec sstable.end-to-end-integrity.R36 — codec-configured writers default to v5
            // unless dictionary training is enabled, in which case the writer stays on v3/v4 so
            // the existing dictionary-embedding lifecycle (which is v4-specific) applies.
            final int resolvedVersion;
            if (formatVersion != 0) {
                resolvedVersion = formatVersion;
            } else if (codec != null && !dictTraining) {
                resolvedVersion = 5;
            } else if (codec != null) {
                resolvedVersion = 3;
            } else {
                resolvedVersion = 1;
            }
            return new TrieSSTableWriter(id, level, path, effectiveBloomFactory, codec,
                    effectiveBlockSize, dictTraining, dictBlockThreshold, dictMaxBufferBytes,
                    dictMaxSize, resolvedVersion, fsyncSkipListener);
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
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (IOException e) {
            channelEx = e;
        } finally {
            if (shouldDelete) {
                // Delete our own working file (partial path for v5, outputPath otherwise).
                try {
                    Files.deleteIfExists(workingPath);
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
    // @spec compression.zstd-dictionary.R13a — observable notification path for training
    // skip/failure events
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
