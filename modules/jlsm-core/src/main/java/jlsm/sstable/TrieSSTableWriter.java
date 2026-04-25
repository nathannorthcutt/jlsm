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
import jlsm.encryption.TableScope;
import jlsm.sstable.internal.CompressionMap;
import jlsm.sstable.internal.DataBlock;
import jlsm.sstable.internal.EntryCodec;
import jlsm.sstable.internal.SSTableFormat;
import jlsm.sstable.internal.V6Footer;

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
 * Writes entries to a new SSTable file using a trie-based key index. All writers emit the v5 format
 * (per the pre-GA SSTable v1–v4 collapse). The simple constructors normalise a missing codec to
 * {@link CompressionCodec#none()} so every writer follows the same v5 layout, including the
 * partial-path + atomic-commit lifecycle.
 *
 * <p>
 * State machine: {@code OPEN → FINISHED → CLOSED} and {@code OPEN → CLOSED}. Calling
 * {@link #close()} without {@link #finish()} deletes the partial file.
 */
// @spec sstable.writer.R1 — single codec per file
// @spec sstable.writer.R2 — if compressed >= uncompressed, store as NONE
// @spec sstable.writer.R3 — map built during write, serialized after all blocks
public final class TrieSSTableWriter implements SSTableWriter {

    private enum State {
        OPEN, FINISHED, FAILED, CLOSED
    }

    private final long id;
    private final Level level;
    private final Path outputPath;
    /** Where bytes are actually written: outputPath + ".partial." + writerId. */
    private final Path workingPath;
    private final SeekableByteChannel channel;

    // Data block accumulation
    private DataBlock currentBlock = new DataBlock();
    private long writePosition = 0L;

    // Parallel lists of keys + packed (blockIndex << 32 | intraBlockOffset).
    private final List<byte[]> indexKeys = new ArrayList<>();
    private final List<Long> indexOffsets = new ArrayList<>();

    // Bloom filter
    private BloomFilter bloomFilter;
    private final BloomFilter.Factory bloomFactory;

    // Compression — never null post-collapse; defaults to NoneCodec when no compression requested.
    private final CompressionCodec codec;
    private final List<CompressionMap.Entry> compressionMapEntries = new ArrayList<>();
    private int blockCount = 0;

    // Block size (configurable via Builder; otherwise DEFAULT_BLOCK_SIZE)
    private final int blockSize;

    /** Listener invoked when fsync/force() is skipped for non-FileChannel outputs (R23). */
    private final FsyncSkipListener fsyncSkipListener;

    /**
     * Optional encryption scope; when present, the writer emits a v6 footer with a scope section
     * after the v5 footer body and {@link SSTableFormat#MAGIC_V6} as the trailing magic.
     *
     * @spec sstable.footer-encryption-scope.R10
     */
    private final TableScope scope;

    /**
     * Sorted ascending DEK versions referenced by this SSTable. Empty array is permitted (R3c).
     *
     * @spec sstable.footer-encryption-scope.R3
     */
    private final int[] dekVersions;

    /**
     * Optional R10c commit hook. When present, finish() acquires the lease, compares the fresh
     * scope to construction-time, and only commits under the held lease.
     *
     * @spec sstable.footer-encryption-scope.R10c
     */
    private final WriterCommitHook commitHook;

    /**
     * Logical table name passed to {@link WriterCommitHook#acquire(String)} during finish().
     *
     * @spec sstable.footer-encryption-scope.R10c
     */
    private final String tableNameForLock;

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
     * Creates a writer with a {@link BlockedBloomFilter} at 1% FPR and no codec configured (v5 with
     * NoneCodec entries).
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
     * Creates a writer with a custom bloom filter factory and no codec configured.
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
     * Creates a writer with per-block compression using the given codec. Pass {@code null} for the
     * codec to fall back to {@link CompressionCodec#none()}.
     *
     * @param id unique SSTable identifier
     * @param level LSM level for this SSTable
     * @param outputPath path to the output file; must not exist
     * @param bloomFactory factory used to create the bloom filter; must not be null
     * @param codec compression codec, or null for NoneCodec
     * @throws IOException if the file cannot be opened
     */
    public TrieSSTableWriter(long id, Level level, Path outputPath,
            BloomFilter.Factory bloomFactory, CompressionCodec codec) throws IOException {
        this(id, level, outputPath, bloomFactory, codec, SSTableFormat.DEFAULT_BLOCK_SIZE, null,
                null, null, null, null);
    }

    /**
     * Internal constructor used by the public constructors and the {@link Builder}. Emits v5 by
     * default; if {@code scope} is non-null, emits v6 with a scope section appended after the v5
     * footer body and {@link SSTableFormat#MAGIC_V6} as the trailing magic. A missing codec is
     * normalised to {@link CompressionCodec#none()}.
     *
     * @spec sstable.footer-encryption-scope.R1
     * @spec sstable.footer-encryption-scope.R10
     */
    private TrieSSTableWriter(long id, Level level, Path outputPath,
            BloomFilter.Factory bloomFactory, CompressionCodec codec, int blockSize,
            FsyncSkipListener fsyncSkipListener, TableScope scope, int[] dekVersions,
            WriterCommitHook commitHook, String tableNameForLock) throws IOException {
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
        this.codec = (codec != null) ? codec : CompressionCodec.none();
        this.blockSize = blockSize;
        this.fsyncSkipListener = fsyncSkipListener;
        // v6 wiring: scope is opt-in; dekVersions defaults to an empty array (R3c — empty set
        // permitted). When scope is null, this writer emits v5 unchanged.
        this.scope = scope;
        this.dekVersions = (dekVersions != null) ? dekVersions.clone() : new int[0];
        this.commitHook = commitHook;
        this.tableNameForLock = tableNameForLock;
        if (this.scope != null) {
            // R10: writer constructed for v6 must validate dek-version array up-front; runtime
            // conditional, not assert.
            for (int i = 0; i < this.dekVersions.length; i++) {
                if (this.dekVersions[i] <= 0) {
                    throw new IllegalArgumentException("dekVersions must be positive (R3b); got "
                            + this.dekVersions[i] + " at index " + i);
                }
                if (i > 0 && this.dekVersions[i] <= this.dekVersions[i - 1]) {
                    throw new IllegalArgumentException(
                            "dekVersions must be strictly ascending (R3a) at index " + i);
                }
            }
        }

        // v5 always uses a per-writer-unique partial path, then atomically renames to outputPath
        // at finish().
        String writerId = UUID.randomUUID().toString();
        Path parent = outputPath.getParent();
        String basename = outputPath.getFileName().toString();
        Path partial = parent == null ? Path.of(basename + ".partial." + writerId)
                : parent.resolve(basename + ".partial." + writerId);
        this.workingPath = partial;
        this.channel = Files.newByteChannel(partial, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
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

        // Store (blockIndex, intraBlockOffset) packed into a long.
        int intraBlockOffset = currentBlock.byteSize();
        long packed = ((long) blockCount << 32) | (intraBlockOffset & 0xFFFFFFFFL);
        indexKeys.add(keyBytes);
        indexOffsets.add(packed);

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

        if (currentBlock.byteSize() >= blockSize) {
            flushCurrentBlock();
        }
    }

    private void flushCurrentBlock() throws IOException {
        if (currentBlock.count() == 0)
            return;
        byte[] blockBytes = currentBlock.serialize();
        compressAndWriteBlock(blockBytes);
        if (blockCount == Integer.MAX_VALUE) {
            throw new IOException("block count overflow: cannot exceed " + Integer.MAX_VALUE);
        }
        blockCount++;
        currentBlock = new DataBlock();
    }

    // @spec sstable.writer.R11 — MemorySegment compress, no byte[] conversion
    // @spec sstable.end-to-end-integrity.R1,R3 — write VarInt length prefix before payload;
    // record blockOffset at the first byte AFTER the VarInt (payload start). R3 atomicity:
    // VarInt write + blockOffset record + payload write + map entry append form a unit; any
    // partial failure transitions writer to FAILED.
    // @spec sstable.end-to-end-integrity.R47 — per-block CRC32C is unconditional in v5.
    /**
     * Compresses a single serialized block and writes it to the channel as a v5 length-prefixed
     * payload, recording a compression map entry with a CRC32C of the on-disk bytes.
     */
    private void compressAndWriteBlock(byte[] blockBytes) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment blockSeg = arena.allocate(blockBytes.length);
            MemorySegment.copy(blockBytes, 0, blockSeg, ValueLayout.JAVA_BYTE, 0,
                    blockBytes.length);

            int maxLen = codec.maxCompressedLength(blockBytes.length);
            MemorySegment compDst = arena.allocate(maxLen);
            MemorySegment compSlice = codec.compress(blockSeg, compDst);
            int compressedLen = (int) compSlice.byteSize();

            byte actualCodecId;
            byte[] dataToWrite;
            if (compressedLen >= blockBytes.length) {
                // Incompressible — store raw with NONE codec ID
                dataToWrite = blockBytes;
                actualCodecId = CompressionCodec.none().codecId();
            } else {
                dataToWrite = compSlice.toArray(ValueLayout.JAVA_BYTE);
                actualCodecId = codec.codecId();
            }

            CRC32C crc = new CRC32C();
            crc.update(dataToWrite, 0, dataToWrite.length);
            int checksum = (int) crc.getValue();

            // R1/R3: write VarInt of on-disk payload length, then payload. blockOffset points
            // at the payload start (post-VarInt).
            byte[] prefix = new byte[4];
            int prefixLen = jlsm.sstable.internal.VarInt.encode(dataToWrite.length, prefix, 0);
            writeBytes(prefix, prefixLen);
            compressionMapEntries.add(new CompressionMap.Entry(writePosition, dataToWrite.length,
                    blockBytes.length, actualCodecId, checksum));
            writeBytes(dataToWrite);
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
            // @spec sstable.end-to-end-integrity.R17 — v5 forbids empty SSTables.
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

            // R10c step 1 — pre-lock heavy work: emit v5 layout + fsync data + metadata. This
            // happens BEFORE the catalog lock is acquired, so long-running I/O is fenced out of
            // the critical section.
            finishV5Layout();

            // R10c steps 2-7 — acquire the lease, re-read the fresh scope, compare to
            // construction-time, and either commit the v6 scope section + magic under the lease
            // or transition to FAILED.
            if (commitHook != null) {
                finishUnderCommitHook();
            } else if (scope != null) {
                // Legacy path (no hook supplied): emit v6 with construction-time scope. Engine
                // integration always supplies a hook; this branch exists for unit tests that
                // exercise the byte-layer in isolation.
                finishV6ScopeSection();
            }

            long sizeBytes = writePosition;

            // Atomic commit: partial path → final path.
            closeChannelQuietly();
            commitFromPartial();

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
     * R10c steps 2-7: acquire the {@link WriterCommitHook} lease, perform a fresh catalog read,
     * compare the freshly-read encryption scope to construction-time, and either emit the v6 scope
     * section + magic under the held lease or FAIL the commit.
     *
     * @spec sstable.footer-encryption-scope.R10c
     */
    private void finishUnderCommitHook() throws IOException {
        if (tableNameForLock == null) {
            throw new IllegalStateException(
                    "commitHook supplied without tableNameForLock — engine integration error");
        }
        // R10c step 2 — acquire the per-table exclusive lease.
        try (final WriterCommitHook.Lease lease = commitHook.acquire(tableNameForLock)) {
            // R10c step 3 — fresh catalog read of the encryption scope.
            final java.util.Optional<TableScope> freshScope = lease.freshScope();
            // R10c step 4 — compare construction-time scope to freshly-read scope.
            final boolean wasEncrypted = (scope != null);
            final boolean isEncryptedNow = freshScope.isPresent();
            // R10c step 5 — encryption transitioned mid-write; refuse the commit. R12 — error
            // message must NOT reveal scope identifiers.
            if (wasEncrypted != isEncryptedNow
                    || (wasEncrypted && isEncryptedNow && !scope.equals(freshScope.get()))) {
                state = State.FAILED;
                throw new IOException(
                        "encryption state changed between writer construction and finish; "
                                + "refusing to commit (R10c)");
            }
            // R10c step 6 — encryption state is consistent: emit scope section + magic + double
            // fsync (when applicable).
            if (isEncryptedNow) {
                finishV6ScopeSection();
            }
            // R10c step 7 — release happens via try-with-resources at block exit.
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

    /**
     * Append the v6 scope section after the v5 footer, then overwrite the trailing v5 magic with
     * the v6 magic. Per R10d, perform a pre-magic fsync to push the scope-section bytes durable
     * before magic, then a post-magic fsync to make the commit durable. The v5 footer's 112 bytes
     * (including its own MAGIC_V5) remain intact above the scope section — dispatch is by the
     * file's trailing magic only.
     *
     * @spec sstable.footer-encryption-scope.R1
     * @spec sstable.footer-encryption-scope.R2
     * @spec sstable.footer-encryption-scope.R2a
     * @spec sstable.footer-encryption-scope.R10d
     */
    private void finishV6ScopeSection() throws IOException {
        // Encode the scope section bytes [bodyLen:u32 BE][body][crc32c:u32 BE].
        final byte[] scopeBytes = V6Footer.encodeScopeSection(scope, dekVersions);
        // Append the scope section after the v5 footer body.
        writeBytes(scopeBytes);
        // Append a 4-byte u32 BE: scope-section-total-size (= scopeBytes.length). This enables
        // the reader to walk back from the trailing v6 magic.
        final byte[] sizeBytes = new byte[4];
        sizeBytes[0] = (byte) (scopeBytes.length >>> 24);
        sizeBytes[1] = (byte) (scopeBytes.length >>> 16);
        sizeBytes[2] = (byte) (scopeBytes.length >>> 8);
        sizeBytes[3] = (byte) scopeBytes.length;
        writeBytes(sizeBytes);
        // R10d: pre-magic fsync — push scope bytes durable before the magic flips.
        forceOrSkip("v6-pre-magic");
        // Append the v6 magic (8 bytes, big-endian).
        final byte[] magicBytes = new byte[8];
        long magic = SSTableFormat.MAGIC_V6;
        magicBytes[0] = (byte) (magic >>> 56);
        magicBytes[1] = (byte) (magic >>> 48);
        magicBytes[2] = (byte) (magic >>> 40);
        magicBytes[3] = (byte) (magic >>> 32);
        magicBytes[4] = (byte) (magic >>> 24);
        magicBytes[5] = (byte) (magic >>> 16);
        magicBytes[6] = (byte) (magic >>> 8);
        magicBytes[7] = (byte) magic;
        writeBytes(magicBytes);
        // R10d: post-magic fsync — make the commit durable.
        forceOrSkip("v6-post-magic");
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
        private FsyncSkipListener fsyncSkipListener = null;
        // v6 footer scope-aware emit (sstable.footer-encryption-scope R1, R10).
        // When `scope` is non-null at build time, the writer emits a v6 footer with the
        // identifier triple + DEK-version-set scope section; otherwise, it emits v5.
        private TableScope scope = null;
        private int[] dekVersions = null;
        // R10c integration hooks (WU-3). Engine integration supplies the commit hook so the
        // writer can take the catalog's exclusive lock + perform the fresh re-read at finish().
        private WriterCommitHook commitHook = null;
        private String tableNameForLock = null;

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
         * Configures the writer to emit a v6 footer with a scope section bound to {@code scope}.
         * Calling this method causes the writer to emit {@link SSTableFormat#MAGIC_V6} as the
         * trailing magic instead of {@link SSTableFormat#MAGIC_V5}. When this method is not called,
         * the writer emits v5 unchanged.
         *
         * <p>
         * R10 invariant: a writer constructed with a scope must own a corresponding {@link Table}
         * whose {@code TableMetadata.encryption} is present at finish-time. WU-2 implements the
         * construction-time wiring; WU-3 adds the R10c finish-time fresh-catalog re-check.
         *
         * @param scope the table scope this writer's SSTable belongs to; never null
         * @return this builder
         * @throws NullPointerException if {@code scope} is null
         * @spec sstable.footer-encryption-scope.R1
         * @spec sstable.footer-encryption-scope.R10
         */
        public Builder scope(TableScope scope) {
            this.scope = Objects.requireNonNull(scope, "scope must not be null");
            return this;
        }

        /**
         * Sets the strictly-ascending positive DEK version set referenced by this SSTable's
         * ciphertext. Empty array is permitted (R3c — applies to v6 SSTables that persist no
         * encrypted entries).
         *
         * @param dekVersions strictly ascending, positive int[]; never null
         * @return this builder
         * @throws NullPointerException if {@code dekVersions} is null
         * @throws IllegalArgumentException if any version is non-positive or pairs are not strictly
         *             ascending
         * @spec sstable.footer-encryption-scope.R3
         * @spec sstable.footer-encryption-scope.R3a
         * @spec sstable.footer-encryption-scope.R3b
         * @spec sstable.footer-encryption-scope.R3c
         */
        /**
         * Configures the per-table commit hook the writer's {@code finish()} acquires during the
         * R10c protocol. The hook supplies an exclusive lease + a fresh-read encryption scope
         * accessor; the writer compares the fresh scope to the construction-time scope and either
         * commits under the held lease or transitions to FAILED.
         *
         * <p>
         * Stub: the wiring lands in WU-3 of WD-02. Calling this method on the current build records
         * the hook reference for future resolution.
         *
         * @param commitHook non-null commit hook
         * @return this builder
         * @throws NullPointerException if {@code commitHook} is null
         * @spec sstable.footer-encryption-scope.R10c
         */
        public Builder commitHook(WriterCommitHook commitHook) {
            this.commitHook = Objects.requireNonNull(commitHook, "commitHook must not be null");
            return this;
        }

        /**
         * Sets the logical table name passed to the {@link WriterCommitHook#acquire(String)} call
         * at finish-time.
         *
         * @param tableNameForLock non-null table name
         * @return this builder
         * @throws NullPointerException if {@code tableNameForLock} is null
         * @spec sstable.footer-encryption-scope.R10c
         */
        public Builder tableNameForLock(String tableNameForLock) {
            this.tableNameForLock = Objects.requireNonNull(tableNameForLock,
                    "tableNameForLock must not be null");
            return this;
        }

        public Builder dekVersions(int[] dekVersions) {
            Objects.requireNonNull(dekVersions, "dekVersions must not be null");
            for (int i = 0; i < dekVersions.length; i++) {
                if (dekVersions[i] <= 0) {
                    throw new IllegalArgumentException("dekVersions must be positive (R3b); got "
                            + dekVersions[i] + " at index " + i);
                }
                if (i > 0 && dekVersions[i] <= dekVersions[i - 1]) {
                    throw new IllegalArgumentException(
                            "dekVersions must be strictly ascending (R3a) at index " + i);
                }
            }
            this.dekVersions = dekVersions.clone();
            return this;
        }

        /**
         * Builds and returns a new writer.
         *
         * @return a new writer
         * @throws IOException if the output file cannot be opened
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
            // source, assign the candidate validated by R7a at pool() call time.
            // @spec sstable.pool-aware-block-size.R11 — explicit blockSize(int) wins; the
            // pool-derived value is discarded.
            // @spec sstable.pool-aware-block-size.R13 — default path: neither pool nor explicit →
            // blockSize retains its DEFAULT_BLOCK_SIZE initializer value.
            int effectiveBlockSize = this.blockSize;
            if (pool != null && !blockSizeExplicit) {
                effectiveBlockSize = (int) derivedBlockSizeCandidate;
            }
            // Default bloomFactory is resolved into a local only — never written back to
            // this.bloomFactory — so a failed build() (at any gate above or below) cannot leak
            // a silent default into the Builder. This preserves the R11a atomicity pattern
            // followed by every other setter on this builder.
            final BloomFilter.Factory effectiveBloomFactory = (bloomFactory != null) ? bloomFactory
                    : n -> new BlockedBloomFilter(n, 0.01);
            return new TrieSSTableWriter(id, level, path, effectiveBloomFactory, codec,
                    effectiveBlockSize, fsyncSkipListener, scope, dekVersions, commitHook,
                    tableNameForLock);
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
                // Delete the partial working file.
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
