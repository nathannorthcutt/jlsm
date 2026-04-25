package jlsm.sstable;

import jlsm.core.bloom.BloomFilter;
import jlsm.core.cache.BlockCache;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import jlsm.core.sstable.SSTableReader;
import jlsm.encryption.ReadContext;
import jlsm.encryption.TableScope;
import jlsm.sstable.internal.CompressionMap;
import jlsm.sstable.internal.EntryCodec;
import jlsm.sstable.internal.KeyIndex;
import jlsm.sstable.internal.SSTableFormat;
import jlsm.sstable.internal.V5Footer;
import jlsm.sstable.internal.V6Footer;
import jlsm.sstable.internal.VarInt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

/**
 * Reads an SSTable file written by {@link TrieSSTableWriter}.
 *
 * <p>
 * Supports two file formats:
 * <ul>
 * <li><b>v1</b> — no compression; factory methods without {@link CompressionCodec}</li>
 * <li><b>v2</b> — per-block compression with a compression offset map; factory methods with
 * {@link CompressionCodec} varargs</li>
 * </ul>
 *
 * <p>
 * Two factory method families:
 * <ul>
 * <li>{@link #open} — eager: loads the entire data region into memory on open</li>
 * <li>{@link #openLazy} — lazy: loads only footer, key index, bloom filter; reads data on
 * demand</li>
 * </ul>
 */
// @spec sstable.writer.R4 — lazy reader synchronizes channel reads
// @spec sstable.writer.R10 — iterator after close is undefined (documented)
// @spec compression.streaming-decompression.R28 — decompressAllBlocks method removed (absent
// behavior)
// @spec compression.zstd-dictionary.R19a — v4-capable reader also reads v1, v2, v3 via magic
// dispatch
// @spec compression.zstd-dictionary.R19b,R19c — v4 section-ordering invariants + v3-style
// compression map
// @spec compression.zstd-dictionary.R20,R20a,R21 — on-disk metadata determines codec for ID 0x03
// (see override methods)
// @spec compression.zstd-dictionary.R22 — v1-only reader on v4/v3/v2 file throws descriptive
// IOException
public final class TrieSSTableReader implements SSTableReader {

    private final SSTableMetadata metadata;
    private final KeyIndex keyIndex;
    private final BloomFilter bloomFilter;
    private final long dataEnd; // file offset just past the last data block

    // Eager mode: all data bytes pre-loaded; lazy mode: null
    private final byte[] eagerData;

    // Lazy mode: open channel; eager mode: null
    private final SeekableByteChannel lazyChannel;

    // Optional block cache; null means no caching
    private final BlockCache blockCache;

    // v2/v3 compression support (null for v1)
    private final CompressionMap compressionMap;
    private final Map<Byte, CompressionCodec> codecMap;

    // v3: per-block CRC32C verification
    private final boolean hasChecksums;

    // @spec sstable.v3-format-upgrade.R15 — block size recorded at write time (v3+) or default
    // (v1/v2)
    private final long blockSize;

    /**
     * On-disk format version detected at open time. One of {@code 1..5}. Used by downstream code
     * that must behave differently for v5 (e.g. varint-prefixed data section, recovery scan).
     */
    private final int formatVersion;

    /**
     * Footer block count (v5 only) — used for recovery scans. {@code 0} for pre-v5 files.
     */
    private final int footerBlockCount;

    /** End of the data region (i.e. mapOffset) — used by v5 recovery scans. */
    private final long dataRegionEnd;

    /**
     * Per-read context produced from the v6 footer scope section. For v5 files (no scope), this is
     * a {@link ReadContext} carrying an empty {@code allowedDekVersions} set — the dispatch R3e
     * gate then trivially rejects every envelope (which is correct: a v5 file has no encrypted
     * entries, so no envelope dispatch should occur).
     *
     * @spec sstable.footer-encryption-scope.R3e
     */
    private final ReadContext readContext;

    /** Reader-level failure surfaced by a lazy first-load failure (R43); null otherwise. */
    // @spec sstable.end-to-end-integrity.R43 — FAILED-state storage: a non-null failureCause
    // represents the originating CorruptSectionException from a post-open lazy first-load;
    // checkNotFailed() wraps it in IllegalStateException for subsequent read rejections while
    // close() remains permitted.
    private volatile IOException failureCause = null;
    private volatile String failureSection = null;

    /**
     * Active reader-operation counter for R38: recovery scans block when any normal read is in
     * progress, and normal reads block when a recovery scan is in progress.
     */
    private final ReentrantLock recoveryLock = new ReentrantLock();
    private final AtomicInteger activeReaderOps = new AtomicInteger();
    private volatile boolean recoveryInProgress = false;

    private volatile boolean closed = false;

    private static final VarHandle CLOSED;

    static {
        try {
            CLOSED = MethodHandles.lookup().findVarHandle(TrieSSTableReader.class, "closed",
                    boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Original 9-parameter constructor — preserved for backward compatibility with tests that use
     * reflection to construct readers directly. Defaults hasChecksums to false.
     */
    private TrieSSTableReader(SSTableMetadata metadata, KeyIndex keyIndex, BloomFilter bloomFilter,
            long dataEnd, byte[] eagerData, SeekableByteChannel lazyChannel, BlockCache blockCache,
            CompressionMap compressionMap, Map<Byte, CompressionCodec> codecMap) {
        this(metadata, keyIndex, bloomFilter, dataEnd, eagerData, lazyChannel, blockCache,
                compressionMap, codecMap, false, SSTableFormat.DEFAULT_BLOCK_SIZE);
    }

    private TrieSSTableReader(SSTableMetadata metadata, KeyIndex keyIndex, BloomFilter bloomFilter,
            long dataEnd, byte[] eagerData, SeekableByteChannel lazyChannel, BlockCache blockCache,
            CompressionMap compressionMap, Map<Byte, CompressionCodec> codecMap,
            boolean hasChecksums) {
        this(metadata, keyIndex, bloomFilter, dataEnd, eagerData, lazyChannel, blockCache,
                compressionMap, codecMap, hasChecksums, SSTableFormat.DEFAULT_BLOCK_SIZE);
    }

    private TrieSSTableReader(SSTableMetadata metadata, KeyIndex keyIndex, BloomFilter bloomFilter,
            long dataEnd, byte[] eagerData, SeekableByteChannel lazyChannel, BlockCache blockCache,
            CompressionMap compressionMap, Map<Byte, CompressionCodec> codecMap,
            boolean hasChecksums, long blockSize) {
        this(metadata, keyIndex, bloomFilter, dataEnd, eagerData, lazyChannel, blockCache,
                compressionMap, codecMap, hasChecksums, blockSize, /* formatVersion */ 1,
                /* footerBlockCount */ 0, /* dataRegionEnd */ dataEnd);
    }

    private TrieSSTableReader(SSTableMetadata metadata, KeyIndex keyIndex, BloomFilter bloomFilter,
            long dataEnd, byte[] eagerData, SeekableByteChannel lazyChannel, BlockCache blockCache,
            CompressionMap compressionMap, Map<Byte, CompressionCodec> codecMap,
            boolean hasChecksums, long blockSize, int formatVersion, int footerBlockCount,
            long dataRegionEnd) {
        this(metadata, keyIndex, bloomFilter, dataEnd, eagerData, lazyChannel, blockCache,
                compressionMap, codecMap, hasChecksums, blockSize, formatVersion, footerBlockCount,
                dataRegionEnd, new ReadContext(java.util.Collections.emptySet()));
    }

    private TrieSSTableReader(SSTableMetadata metadata, KeyIndex keyIndex, BloomFilter bloomFilter,
            long dataEnd, byte[] eagerData, SeekableByteChannel lazyChannel, BlockCache blockCache,
            CompressionMap compressionMap, Map<Byte, CompressionCodec> codecMap,
            boolean hasChecksums, long blockSize, int formatVersion, int footerBlockCount,
            long dataRegionEnd, ReadContext readContext) {
        this.metadata = metadata;
        this.keyIndex = keyIndex;
        this.bloomFilter = bloomFilter;
        this.dataEnd = dataEnd;
        this.eagerData = eagerData;
        this.lazyChannel = lazyChannel;
        this.blockCache = blockCache;
        this.compressionMap = compressionMap;
        this.codecMap = codecMap;
        this.hasChecksums = hasChecksums;
        this.blockSize = blockSize;
        this.formatVersion = formatVersion;
        this.footerBlockCount = footerBlockCount;
        this.dataRegionEnd = dataRegionEnd;
        this.readContext = Objects.requireNonNull(readContext, "readContext must not be null");
    }

    /**
     * Returns the data-block size recorded at write time for this SSTable. For v3+ files this is
     * the value stored in the footer's blockSize field (validated during {@code open}). For v1 and
     * v2 files (which predate the footer blockSize field) this returns
     * {@link SSTableFormat#DEFAULT_BLOCK_SIZE} — the value v2 writers hardcoded.
     */
    /** Block size as recorded in the v5 footer. */
    public long blockSize() {
        return blockSize;
    }

    /**
     * Returns the per-read context produced from the v6 footer scope section. For v5 files (no
     * scope), the context carries an empty {@code allowedDekVersions} set.
     *
     * <p>
     * The same {@link ReadContext} instance is returned across calls so hot-path R3e dispatch
     * checks in {@code FieldEncryptionDispatch} pay zero allocation per envelope.
     *
     * @spec sstable.footer-encryption-scope.R3e
     */
    public ReadContext readContext() {
        return readContext;
    }

    // ---- Convenience factory methods (no codec varargs) ----
    //
    // Post v1-v4 collapse, every SSTable is v5. The simple overloads delegate to the
    // codec-aware path with no extra codecs (the implicit NoneCodec covers files written
    // without compression).

    public static TrieSSTableReader open(Path path, BloomFilter.Deserializer bloomDeserializer)
            throws IOException {
        return open(path, bloomDeserializer, null, new CompressionCodec[0]);
    }

    /**
     * Opens an SSTable with magic-dispatched v5/v6 footer parsing. When the trailing magic is v6,
     * validates the scope section against {@code expectedScope} (R5/R6); the {@link ReadContext}
     * produced from the scope's DEK-version set is exposed via {@link #readContext()} for
     * downstream R3e dispatch gates.
     *
     * <p>
     * Callers must pass a non-null {@code Optional} (use {@link Optional#empty()} for
     * plaintext-only reads); this prevents Java overload resolution from clashing with the existing
     * {@link #open(Path, BloomFilter.Deserializer, BlockCache)} overload on {@code null}.
     *
     * @param path path to the SSTable file
     * @param bloomDeserializer deserializer for the bloom filter
     * @param expectedScope when present, the caller-asserted scope this SSTable should belong to;
     *            when empty, the reader rejects v6 files (R5)
     * @return an opened reader
     * @throws IOException on truncation, CRC mismatch, identifier-rule violation, or unknown magic
     * @throws IllegalStateException on v6-without-scope (R5) or scope mismatch (R6)
     * @spec sstable.footer-encryption-scope.R1a
     * @spec sstable.footer-encryption-scope.R5
     * @spec sstable.footer-encryption-scope.R6
     */
    public static TrieSSTableReader open(Path path, BloomFilter.Deserializer bloomDeserializer,
            Optional<TableScope> expectedScope) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(bloomDeserializer, "bloomDeserializer must not be null");
        Objects.requireNonNull(expectedScope, "expectedScope must not be null");
        return openWithExpectedScope(path, bloomDeserializer, null, expectedScope,
                new CompressionCodec[0]);
    }

    public static TrieSSTableReader open(Path path, BloomFilter.Deserializer bloomDeserializer,
            BlockCache blockCache) throws IOException {
        return open(path, bloomDeserializer, blockCache, new CompressionCodec[0]);
    }

    public static TrieSSTableReader openLazy(Path path, BloomFilter.Deserializer bloomDeserializer)
            throws IOException {
        return openLazy(path, bloomDeserializer, null, new CompressionCodec[0]);
    }

    public static TrieSSTableReader openLazy(Path path, BloomFilter.Deserializer bloomDeserializer,
            BlockCache blockCache) throws IOException {
        return openLazy(path, bloomDeserializer, blockCache, new CompressionCodec[0]);
    }

    /**
     * Magic-dispatching v5/v6 open path. Reads the trailing 8 bytes to decide format. For v6, reads
     * the scope section (and trailing scope-size + magic), validates against {@code expectedScope}
     * (R5/R6), and then opens the file as if its size equalled the v5 footer end (so the existing
     * v5 parsing code applies unchanged).
     *
     * @spec sstable.footer-encryption-scope.R1a
     * @spec sstable.footer-encryption-scope.R2f
     * @spec sstable.footer-encryption-scope.R5
     * @spec sstable.footer-encryption-scope.R6
     */
    private static TrieSSTableReader openWithExpectedScope(Path path,
            BloomFilter.Deserializer bloomDeserializer, BlockCache blockCache,
            Optional<TableScope> expectedScope, CompressionCodec... codecs) throws IOException {
        // First, peek at the trailing 8 bytes to dispatch on magic.
        final long fileSize = Files.size(path);
        if (fileSize < 8) {
            throw new IncompleteSSTableException("no-magic", fileSize, 0,
                    "file too small to contain an SSTable magic number");
        }
        final long magic;
        final long scopeTotalSize;
        final byte[] scopeBytes;
        try (SeekableByteChannel probe = Files.newByteChannel(path, StandardOpenOption.READ)) {
            byte[] magicBuf = readBytes(probe, fileSize - 8, 8);
            magic = readLong(magicBuf, 0);
            if (magic == SSTableFormat.MAGIC_V6) {
                if (expectedScope.isEmpty()) {
                    throw new IllegalStateException(
                            "attempt to decrypt SSTable belonging to a Table without encryption "
                                    + "metadata (R5): the file's trailing magic is v6 but no "
                                    + "expectedScope was supplied");
                }
                if (fileSize < 8 + 4) {
                    throw new IOException(
                            "v6 file too small to contain scope-section-size (R2f) — corrupt "
                                    + "footer");
                }
                byte[] sizeBuf = readBytes(probe, fileSize - 8 - 4, 4);
                long readSize = ((sizeBuf[0] & 0xFFL) << 24) | ((sizeBuf[1] & 0xFFL) << 16)
                        | ((sizeBuf[2] & 0xFFL) << 8) | (sizeBuf[3] & 0xFFL);
                if (readSize <= 0 || readSize > fileSize - 8 - 4 - SSTableFormat.FOOTER_SIZE_V5) {
                    throw new IOException("v6 footer scope-section-size invalid: " + readSize
                            + " (R2d/R2f) — corrupt footer");
                }
                scopeTotalSize = readSize;
                scopeBytes = readBytes(probe, fileSize - 8 - 4 - readSize, (int) readSize);
            } else {
                scopeTotalSize = 0;
                scopeBytes = null;
            }
        }
        if (magic == SSTableFormat.MAGIC_V6) {
            V6Footer.Parsed parsed = V6Footer.parse(scopeBytes, fileSize);
            TableScope expected = expectedScope.get();
            if (!expected.equals(parsed.scope())) {
                throw new IllegalStateException("SSTable scope mismatch (R6): expected scope "
                        + expected + " does not match SSTable's declared scope " + parsed.scope());
            }
            final long v5FileSize = fileSize - scopeTotalSize - 4 - 8;
            if (v5FileSize < SSTableFormat.FOOTER_SIZE_V5) {
                throw new IOException(
                        "v6 file too small to contain a v5 footer below the scope section "
                                + "(R2f) — corrupt footer");
            }
            ReadContext ctx = new ReadContext(parsed.dekVersionSet());
            return openInner(path, bloomDeserializer, blockCache, codecs, v5FileSize, ctx);
        }
        // v5 path: delegate to existing open() with default empty ReadContext.
        return openInner(path, bloomDeserializer, blockCache, codecs, fileSize,
                new ReadContext(java.util.Collections.emptySet()));
    }

    /**
     * Inner open path that reads the v5 footer at {@code virtualFileSize} (which equals
     * {@code Files.size(path)} for v5 files and {@code Files.size(path) - v6Trailer} for v6 files).
     * Builds the reader with the given {@link ReadContext}.
     */
    private static TrieSSTableReader openInner(Path path,
            BloomFilter.Deserializer bloomDeserializer, BlockCache blockCache,
            CompressionCodec[] codecs, long virtualFileSize, ReadContext readContext)
            throws IOException {
        Objects.requireNonNull(codecs, "codecs must not be null");
        SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ);
        try {
            Footer footer = readFooter(ch, virtualFileSize);

            byte[] mapBytes = readBytes(ch, footer.mapOffset, (int) footer.mapLength);
            verifySectionCrc32c(mapBytes, footer.mapChecksum,
                    CorruptSectionException.SECTION_COMPRESSION_MAP);
            CompressionMap compressionMap = CompressionMap.deserialize(mapBytes, 3);
            if (compressionMap.blockCount() != footer.blockCount) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_COMPRESSION_MAP,
                        "v5 footer.blockCount=" + footer.blockCount
                                + " disagrees with compression-map entry count="
                                + compressionMap.blockCount());
            }
            Map<Byte, CompressionCodec> codecMap = buildCodecMap(codecs);
            if (footer.dictLength > 0) {
                codecMap = overrideWithDictionaryCodec(ch, footer, codecMap);
            } else {
                codecMap = overrideWithPlainZstdCodec(codecMap);
            }
            validateCodecMap(compressionMap, codecMap);
            KeyIndex keyIndex = readKeyIndexV2Bytes(ch, footer, compressionMap.blockCount(), true);
            long dataEnd = footer.mapOffset;
            BloomFilter bloom = readBloomFilterWithCrc(ch, footer, bloomDeserializer, true);
            SSTableMetadata meta = buildMetadata(path, virtualFileSize, footer, keyIndex);
            int dataLen = (int) dataEnd;
            byte[] data = readBytes(ch, 0L, dataLen);
            ch.close();
            return new TrieSSTableReader(meta, keyIndex, bloom, dataEnd, data, null, blockCache,
                    compressionMap, codecMap, true, footer.blockSize, footer.version,
                    footer.blockCount, footer.mapOffset, readContext);
        } catch (ClosedByInterruptException e) {
            Thread.currentThread().interrupt();
            try {
                ch.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        } catch (IOException | RuntimeException | Error e) {
            try {
                ch.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        }
    }

    // ---- Magic-dispatch factory methods ----

    /**
     * Opens an SSTable file eagerly, with support for both v1 and v2 (compressed) formats.
     *
     * @param path path to the SSTable file
     * @param bloomDeserializer deserializer for the bloom filter
     * @param blockCache optional block cache, or null
     * @param codecs compression codecs available for decompression; for v2 files, all codec IDs
     *            used in the file must be represented
     * @return a new reader
     * @throws IOException if the file cannot be read or contains an unknown codec ID
     */
    /**
     * Opens an SSTable eagerly and rejects any file whose discovered on-disk format version does
     * not match the caller-supplied {@code expectedVersion}. This overload is the defence-in-depth
     * companion to the magic-dispatch routing in {@link #readFooter}: a caller with external
     * authority (manifest, catalog, level metadata) that binds the file to a known format version
     * can assert that expectation so the reader rejects a mis-versioned file (whether caused by
     * discriminant-byte corruption, a swapped file, or a legacy file delivered in place of the
     * intended target).
     *
     * @param expectedVersion the required format version (1..5); a file whose trailing magic
     *            resolves to any other version is rejected with
     *            {@link CorruptSectionException#SECTION_FOOTER}
     * @throws CorruptSectionException if the on-disk version does not equal {@code expectedVersion}
     */
    // @spec sstable.end-to-end-integrity.R34 — magic vocabulary uniqueness. External-authority
    // cross-check: callers that know the expected format version can require the reader to reject
    // any file whose discovered version disagrees, strengthening the dispatch boundary against
    // silent cross-version reinterpretation.
    // @spec sstable.end-to-end-integrity.R54 — expectedVersion opt-in: callers holding external
    // authority (manifest, catalog, level metadata) may assert the file's expected format version;
    // disagreement surfaces as CorruptSectionException(SECTION_FOOTER).
    public static TrieSSTableReader open(Path path, BloomFilter.Deserializer bloomDeserializer,
            BlockCache blockCache, int expectedVersion, CompressionCodec... codecs)
            throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(bloomDeserializer, "bloomDeserializer must not be null");
        Objects.requireNonNull(codecs, "codecs must not be null");
        if (expectedVersion != 5) {
            throw new IllegalArgumentException(
                    "expectedVersion must be 5 (only v5 is recognised); got: " + expectedVersion);
        }
        verifyExpectedVersion(path, expectedVersion);
        return open(path, bloomDeserializer, blockCache, codecs);
    }

    /**
     * Lazy companion of
     * {@link #open(Path, BloomFilter.Deserializer, BlockCache, int, CompressionCodec...)} —
     * enforces an external-authority version expectation before delegating to the auto-detect
     * {@code openLazy} overload.
     */
    // @spec sstable.end-to-end-integrity.R34 — magic vocabulary uniqueness. Lazy-mode companion to
    // the eager expected-version overload.
    // @spec sstable.end-to-end-integrity.R54 — expectedVersion opt-in (lazy-mode factory).
    public static TrieSSTableReader openLazy(Path path, BloomFilter.Deserializer bloomDeserializer,
            BlockCache blockCache, int expectedVersion, CompressionCodec... codecs)
            throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(bloomDeserializer, "bloomDeserializer must not be null");
        Objects.requireNonNull(codecs, "codecs must not be null");
        if (expectedVersion != 5) {
            throw new IllegalArgumentException(
                    "expectedVersion must be 5 (only v5 is recognised); got: " + expectedVersion);
        }
        verifyExpectedVersion(path, expectedVersion);
        return openLazy(path, bloomDeserializer, blockCache, codecs);
    }

    // Opens a short-lived read-only channel, reads the footer via the shared dispatch path, and
    // throws CorruptSectionException(SECTION_FOOTER) when the discovered version disagrees with
    // expectedVersion. Callers that pass this check proceed to the normal factory, which will
    // re-open the file; the cost of the extra read is paid only by callers that explicitly opted
    // in to the expected-version contract.
    private static void verifyExpectedVersion(Path path, int expectedVersion) throws IOException {
        try (SeekableByteChannel probe = Files.newByteChannel(path, StandardOpenOption.READ)) {
            long fileSize = probe.size();
            Footer footer = readFooter(probe, fileSize);
            if (footer.version != expectedVersion) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "SSTable format version mismatch: expected v" + expectedVersion
                                + " (per caller-supplied expectation), got v" + footer.version
                                + " from trailing magic");
            }
        }
    }

    // @spec sstable.format-v2.R5 — detects v1 by magic, falls back to v1 reading
    // @spec sstable.format-v2.R6 — v1 reader on v2 file throws descriptive IOException
    // @spec sstable.format-v2.R7 — self-describing, no external config needed
    public static TrieSSTableReader open(Path path, BloomFilter.Deserializer bloomDeserializer,
            BlockCache blockCache, CompressionCodec... codecs) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(bloomDeserializer, "bloomDeserializer must not be null");
        Objects.requireNonNull(codecs, "codecs must not be null");

        SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ);
        try {
            long fileSize = ch.size();
            Footer footer = readFooter(ch, fileSize);

            byte[] mapBytes = readBytes(ch, footer.mapOffset, (int) footer.mapLength);
            verifySectionCrc32c(mapBytes, footer.mapChecksum,
                    CorruptSectionException.SECTION_COMPRESSION_MAP);
            CompressionMap compressionMap = CompressionMap.deserialize(mapBytes, 3);
            // @spec sstable.end-to-end-integrity.R18 — only after footer blockCount/mapLength
            // have been validated do we cross-check compression-map entry count vs blockCount
            if (compressionMap.blockCount() != footer.blockCount) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_COMPRESSION_MAP,
                        "v5 footer.blockCount=" + footer.blockCount
                                + " disagrees with compression-map entry count="
                                + compressionMap.blockCount());
            }
            Map<Byte, CompressionCodec> codecMap = buildCodecMap(codecs);

            // @spec sstable.end-to-end-integrity.R30 — dictionary section is loaded (and its
            // bytes fetched) only when dictLength > 0; when dictLength == 0 no dictionary CRC
            // computation occurs. R15 sentinel consistency still applied in readFooter.
            if (footer.dictLength > 0) {
                codecMap = overrideWithDictionaryCodec(ch, footer, codecMap);
            } else {
                codecMap = overrideWithPlainZstdCodec(codecMap);
            }

            validateCodecMap(compressionMap, codecMap);
            KeyIndex keyIndex = readKeyIndexV2Bytes(ch, footer, compressionMap.blockCount(), true);
            long dataEnd = footer.mapOffset;

            BloomFilter bloom = readBloomFilterWithCrc(ch, footer, bloomDeserializer, true);
            SSTableMetadata meta = buildMetadata(path, fileSize, footer, keyIndex);

            int dataLen = (int) dataEnd;
            byte[] data = readBytes(ch, 0L, dataLen);
            ch.close();

            return new TrieSSTableReader(meta, keyIndex, bloom, dataEnd, data, null, blockCache,
                    compressionMap, codecMap, true, footer.blockSize, footer.version,
                    footer.blockCount, footer.mapOffset);
            // @spec sstable.end-to-end-integrity.R41 — if any verification step (CRC mismatch,
            // footer validation, dict/key-index/bloom check) throws, close the channel acquired
            // at the entry of this factory before rethrowing so no file descriptor leaks.
        } catch (ClosedByInterruptException e) {
            Thread.currentThread().interrupt();
            try {
                ch.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        } catch (IOException e) {
            try {
                ch.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        } catch (RuntimeException e) {
            try {
                ch.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        } catch (Error e) {
            try {
                ch.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        }
    }

    /**
     * Opens an SSTable file lazily.
     *
     * @param path path to the SSTable file
     * @param bloomDeserializer deserializer for the bloom filter
     * @param blockCache optional block cache, or null
     * @param codecs compression codecs available for decompression
     * @return a new reader
     * @throws IOException if the file cannot be read or contains an unknown codec ID
     */
    public static TrieSSTableReader openLazy(Path path, BloomFilter.Deserializer bloomDeserializer,
            BlockCache blockCache, CompressionCodec... codecs) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(bloomDeserializer, "bloomDeserializer must not be null");
        Objects.requireNonNull(codecs, "codecs must not be null");

        SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ);
        try {
            long fileSize = ch.size();
            Footer footer = readFooter(ch, fileSize);

            byte[] mapBytes = readBytes(ch, footer.mapOffset, (int) footer.mapLength);
            // @spec sstable.end-to-end-integrity.R28 — lazy mode verifies the compression-map
            // CRC32C before any byte of the section is returned to the caller (eager first-load
            // verification is the permitted atomic verified-or-failed strategy for this section).
            // Key-index and bloom sections follow the same pattern below.
            verifySectionCrc32c(mapBytes, footer.mapChecksum,
                    CorruptSectionException.SECTION_COMPRESSION_MAP);
            CompressionMap compressionMap = CompressionMap.deserialize(mapBytes, 3);
            // @spec sstable.end-to-end-integrity.R18 — compression-map entry-count check only
            // runs after footer blockCount/mapLength have been validated in readFooter
            if (compressionMap.blockCount() != footer.blockCount) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_COMPRESSION_MAP,
                        "v5 footer.blockCount=" + footer.blockCount
                                + " disagrees with compression-map entry count="
                                + compressionMap.blockCount());
            }
            Map<Byte, CompressionCodec> codecMap = buildCodecMap(codecs);

            // @spec sstable.end-to-end-integrity.R30 — dictionary bytes are only read (and its
            // CRC-bearing codec override created) when dictLength > 0; a zero-length dictionary
            // performs no CRC32C computation. R15 sentinel consistency is enforced in readFooter
            // regardless of dictLength.
            if (footer.dictLength > 0) {
                codecMap = overrideWithDictionaryCodec(ch, footer, codecMap);
            } else {
                codecMap = overrideWithPlainZstdCodec(codecMap);
            }

            validateCodecMap(compressionMap, codecMap);
            KeyIndex keyIndex = readKeyIndexV2Bytes(ch, footer, compressionMap.blockCount(), true);
            long dataEnd = footer.mapOffset;

            BloomFilter bloom = readBloomFilterWithCrc(ch, footer, bloomDeserializer, true);
            SSTableMetadata meta = buildMetadata(path, fileSize, footer, keyIndex);

            return new TrieSSTableReader(meta, keyIndex, bloom, dataEnd, null, ch, blockCache,
                    compressionMap, codecMap, true, footer.blockSize, footer.version,
                    footer.blockCount, footer.mapOffset);
            // @spec sstable.end-to-end-integrity.R41 — failure inside the openLazy() factory
            // (verification or otherwise) closes the channel acquired at entry before rethrowing
            // so the descriptor is released rather than leaked to the caller.
        } catch (ClosedByInterruptException e) {
            Thread.currentThread().interrupt();
            try {
                ch.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        } catch (IOException e) {
            try {
                ch.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        } catch (RuntimeException e) {
            try {
                ch.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        } catch (Error e) {
            try {
                ch.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        }
    }

    // ---- SSTableReader interface ----

    @Override
    public SSTableMetadata metadata() {
        return metadata;
    }

    // @spec compression.streaming-decompression.R13 — get() uses readAndDecompressBlock (with
    // BlockCache), not streaming path
    @Override
    public Optional<Entry> get(MemorySegment key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();
        checkNotFailed();
        acquireReaderSlot();
        try {
            if (!bloomFilter.mightContain(key)) {
                return Optional.empty();
            }

            Optional<Long> offsetOpt = keyIndex.lookup(key);
            if (offsetOpt.isEmpty()) {
                return Optional.empty();
            }

            long offset = offsetOpt.get();

            final Entry decoded;
            if (compressionMap != null) {
                // v2: offset is packed (blockIndex << 32 | intraBlockOffset)
                int blockIndex = (int) (offset >>> 32);
                int intraBlockOffset = (int) offset;
                byte[] decompressedBlock = readAndDecompressBlock(blockIndex);
                decoded = EntryCodec.decode(decompressedBlock, intraBlockOffset);
            } else {
                // v1: offset is absolute file position
                byte[] entryBytes = readDataAtV1(offset, SSTableFormat.DEFAULT_BLOCK_SIZE);
                decoded = EntryCodec.decode(entryBytes, 0);
            }

            // Re-check closed after I/O to narrow the TOCTOU window between the
            // pre-read checkNotClosed() and the return. Without this, a concurrent
            // close() between the initial check and here would let get() return
            // data from a logically-closed reader.
            checkNotClosed();

            return Optional.of(decoded);
        } catch (CorruptSectionException e) {
            // R43: post-open lazy first-load detected section-level corruption.
            // Transition reader to FAILED so subsequent calls reject with ISE
            // wrapping this originating cause rather than re-attempting the
            // failing I/O path.
            transitionToFailed(e);
            throw e;
        } finally {
            releaseReaderSlot();
        }
    }

    /**
     * Returns an iterator over every entry in this SSTable in key order.
     *
     * <p>
     * <b>Iterator-after-close is undefined.</b> The returned iterator becomes invalid once this
     * reader is {@linkplain #close() closed}; subsequent {@link Iterator#hasNext()} calls may
     * return {@code true}, {@code false}, or throw an implementation-specific exception. Callers
     * must not rely on post-close iterator state and should drain or discard iterators before
     * closing the reader.
     *
     * @return an iterator over all entries; valid only until {@link #close()} is called
     */
    // @spec sstable.writer.R10 — iterator behavior after close is undefined; documented in public
    // API
    // @spec compression.streaming-decompression.R1 — v2 scan returns lazy iterator, not upfront
    // decompression
    // @spec compression.streaming-decompression.R11 — v1 scan uses existing DataRegionIterator
    // unchanged
    @Override
    public Iterator<Entry> scan() throws IOException {
        checkNotClosed();
        checkNotFailed();
        if (compressionMap != null) {
            // v2: lazy block-by-block decompression — O(single block) memory
            return new CompressedBlockIterator();
        } else {
            // v1: iterate raw data region
            byte[] data = getAllDataV1();
            return new DataRegionIterator(data, dataEnd);
        }
    }

    /**
     * Returns an iterator over entries with keys in the half-open range {@code [fromKey, toKey)}.
     *
     * <p>
     * <b>Iterator-after-close is undefined.</b> The returned iterator becomes invalid once this
     * reader is {@linkplain #close() closed}; see {@link #scan()} for the full contract.
     *
     * @param fromKey inclusive lower bound of the key range
     * @param toKey exclusive upper bound of the key range
     * @return an iterator over entries in the range; valid only until {@link #close()} is called
     */
    // @spec sstable.writer.R10 — iterator behavior after close is undefined; documented in public
    // API
    // @spec compression.streaming-decompression.R12 — v1 uses absolute offsets/readDataAtV1; v2
    // uses IndexRangeIterator with block
    // cache
    @Override
    public Iterator<Entry> scan(MemorySegment fromKey, MemorySegment toKey) throws IOException {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        checkNotClosed();
        checkNotFailed();
        return new IndexRangeIterator(keyIndex.rangeIterator(fromKey, toKey));
    }

    /**
     * Walk the data section sequentially using VarInt block-length prefixes, without requiring the
     * compression map. Terminates after exactly {@code blockCount} blocks have been read,
     * validating {@code currentPos == mapOffset} at the end.
     *
     * <p>
     * Recovery scan is mutually exclusive with normal reads ({@code get}/{@code scan}) on the same
     * reader instance. Concurrent invocation throws {@code IllegalStateException}.
     * </p>
     *
     * @throws java.io.IOException if iteration fails (e.g. CorruptBlockException on a per-block CRC
     *             failure, CorruptSectionException on data-section corruption)
     * @throws IllegalStateException if called concurrently with get/scan
     * @spec sstable.end-to-end-integrity.R7
     * @spec sstable.end-to-end-integrity.R8
     * @spec sstable.end-to-end-integrity.R9
     * @spec sstable.end-to-end-integrity.R10
     * @spec sstable.end-to-end-integrity.R38
     * @spec sstable.end-to-end-integrity.R45
     */
    public java.util.Iterator<Entry> recoveryScan() throws java.io.IOException {
        checkNotClosed();
        checkNotFailed();
        if (formatVersion != 5) {
            throw new UnsupportedOperationException(
                    "recoveryScan() requires v5 format; this reader is v" + formatVersion);
        }
        // R38: mutex — throw ISE if any reader is active OR if another recovery is in progress.
        // Lock first, then check activeReaderOps under the lock: without this, the
        // check-then-act sequence (read activeReaderOps; acquire lock; set flag) races
        // with acquireReaderSlot's (read flag; increment counter) because the two sides
        // read/modify independent variables. Now that acquireReaderSlot also takes the
        // lock around its check-and-increment, the activeReaderOps check here is
        // synchronized against any concurrent reader arrival.
        if (!recoveryLock.tryLock()) {
            throw new IllegalStateException("recovery-scan in progress");
        }
        boolean lockHandedOff = false;
        try {
            if (activeReaderOps.get() > 0) {
                throw new IllegalStateException("reads in progress");
            }
            recoveryInProgress = true;
            // Note: we hold the lock for the lifetime of the returned iterator. The iterator's
            // close/hasNext=false path releases the lock via a finalizer-free wrapper.
            try {
                RecoveryScanIterator it = new RecoveryScanIterator();
                lockHandedOff = true; // ownership transfers to iterator's releaseOnceExhausted
                return it;
            } catch (RuntimeException | Error e) {
                recoveryInProgress = false;
                throw e;
            }
        } finally {
            // F-R1.concurrency.1.7 — unlock only if we still own the lock. When the ctor
            // fails via advance()'s IOException path, releaseOnceExhausted() has already
            // unlocked; a second unlock here would raise IllegalMonitorStateException
            // and mask the originating corruption exception.
            if (!lockHandedOff && recoveryLock.isHeldByCurrentThread()) {
                recoveryLock.unlock();
            }
        }
    }

    /**
     * Sequential VarInt-prefixed walker over the data section. Consumes exactly {@code blockCount}
     * blocks and validates the post-condition currentPos == mapOffset.
     *
     * @spec sstable.end-to-end-integrity.R7
     * @spec sstable.end-to-end-integrity.R8
     * @spec sstable.end-to-end-integrity.R9
     * @spec sstable.end-to-end-integrity.R10
     * @spec sstable.end-to-end-integrity.R44
     * @spec sstable.end-to-end-integrity.R56
     */
    private final class RecoveryScanIterator implements Iterator<Entry>, AutoCloseable {
        private long cursor = 0L;
        private int blocksWalked = 0;
        private List<Entry> bufferedEntries = new ArrayList<>();
        private int bufferedIndex = 0;
        private Entry next;
        private boolean exhausted = false;
        private boolean released = false;

        RecoveryScanIterator() {
            advance();
        }

        private void releaseOnceExhausted() {
            if (released) {
                return;
            }
            released = true;
            recoveryInProgress = false;
            recoveryLock.unlock();
        }

        private void advance() {
            next = null;
            if (exhausted) {
                return;
            }
            while (true) {
                if (bufferedIndex < bufferedEntries.size()) {
                    next = bufferedEntries.get(bufferedIndex++);
                    return;
                }
                if (blocksWalked >= footerBlockCount) {
                    // R8: must end exactly at mapOffset
                    if (cursor != dataRegionEnd) {
                        exhausted = true;
                        releaseOnceExhausted();
                        throw sneakyThrow(
                                new CorruptSectionException(CorruptSectionException.SECTION_DATA,
                                        "recovery scan post-condition failed: expectedEndOffset="
                                                + dataRegionEnd + " actualEndOffset=" + cursor));
                    }
                    exhausted = true;
                    releaseOnceExhausted();
                    return;
                }
                // R9: current cursor must be within the data region to start a new block
                if (cursor >= dataRegionEnd) {
                    exhausted = true;
                    releaseOnceExhausted();
                    throw sneakyThrow(
                            new CorruptSectionException(CorruptSectionException.SECTION_DATA,
                                    "recovery scan underflow: expected " + footerBlockCount
                                            + " blocks, found " + blocksWalked + ", cursor="
                                            + cursor + " reached mapOffset=" + dataRegionEnd));
                }
                try {
                    readBlockAtCursor();
                } catch (IOException ioe) {
                    exhausted = true;
                    releaseOnceExhausted();
                    throw sneakyThrow(ioe);
                } catch (RuntimeException | Error rex) {
                    // F-R1.contract_boundaries.03.01 — the R10 defensive guard throws
                    // IllegalStateException; release the recovery lock before propagating so
                    // callers see the violation without leaking the lock / the iterator state.
                    exhausted = true;
                    releaseOnceExhausted();
                    throw rex;
                }
            }
        }

        private void readBlockAtCursor() throws IOException {
            final long blockStart = cursor;
            final int remaining = (int) Math.min(Integer.MAX_VALUE, dataRegionEnd - cursor);
            if (remaining <= 0) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_DATA,
                        "no bytes left at cursor=" + cursor + " before expected block "
                                + blocksWalked);
            }
            // Read up to 4 bytes for VarInt decoding
            int varIntBytesAvailable = Math.min(4, remaining);
            byte[] varIntBuf = readAbs(blockStart, varIntBytesAvailable);
            VarInt.DecodedVarInt decoded = VarInt.decode(ByteBuffer.wrap(varIntBuf), blockStart);
            int payloadLen = decoded.value();
            int varIntLen = decoded.bytesConsumed();
            long payloadStart = blockStart + varIntLen;
            long payloadEnd = payloadStart + payloadLen;
            if (payloadEnd > dataRegionEnd) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_DATA,
                        "block " + blocksWalked + " declared length " + payloadLen
                                + " extends past mapOffset=" + dataRegionEnd + "; remaining bytes="
                                + (dataRegionEnd - payloadStart));
            }
            byte[] payload = readAbs(payloadStart, payloadLen);
            // R10: per-block CRC32C verification via compressionMap entry for this blockIndex.
            if (compressionMap != null && blocksWalked < compressionMap.blockCount()) {
                CompressionMap.Entry mapEntry = compressionMap.entry(blocksWalked);
                if (hasChecksums) {
                    CRC32C crc = new CRC32C();
                    crc.update(payload, 0, payload.length);
                    int actual = (int) crc.getValue();
                    if (actual != mapEntry.checksum()) {
                        throw new CorruptBlockException(blocksWalked, mapEntry.checksum(), actual);
                    }
                }
                byte[] decompressed;
                CompressionCodec entryCodec = codecMap.get(mapEntry.codecId());
                if (entryCodec == null) {
                    throw new IOException("codec not found for block " + blocksWalked);
                }
                if (mapEntry.codecId() == CompressionCodec.none().codecId()) {
                    decompressed = payload;
                } else {
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment src = arena.allocate(payload.length);
                        MemorySegment.copy(payload, 0, src, ValueLayout.JAVA_BYTE, 0,
                                payload.length);
                        MemorySegment dst = arena.allocate(mapEntry.uncompressedSize());
                        MemorySegment slice = entryCodec.decompress(src, dst,
                                mapEntry.uncompressedSize());
                        decompressed = slice.toArray(ValueLayout.JAVA_BYTE);
                    }
                }
                parseDecompressedBlockEntries(decompressed);
            } else {
                // F-R1.contract_boundaries.03.01 — defensive runtime guard on the R10 invariant.
                // Under current v5-only invariants (recoveryScan rejects non-v5; v5 open enforces
                // compressionMap.blockCount() == footerBlockCount; the iterator halts at
                // blocksWalked >= footerBlockCount), this branch is unreachable. It is retained
                // as a fail-closed sentinel: if a future regression ever lands the iterator here,
                // parsing the raw payload without CRC verification would silently violate R10.
                // Surface the broken invariant instead of accepting un-verified bytes.
                throw new IllegalStateException(
                        "recovery-scan R10 invariant broken: per-block CRC verification path "
                                + "unreachable (compressionMap="
                                + (compressionMap == null ? "null"
                                        : "blockCount=" + compressionMap.blockCount())
                                + ", blocksWalked=" + blocksWalked + ", footerBlockCount="
                                + footerBlockCount
                                + "); refusing to parse un-verified block payload");
            }
            cursor = payloadEnd;
            blocksWalked++;
        }

        private void parseDecompressedBlockEntries(byte[] decompressed) throws IOException {
            bufferedEntries = new ArrayList<>();
            bufferedIndex = 0;
            if (decompressed.length < 4) {
                throw new IOException("decoded block too small: " + decompressed.length);
            }
            int count = readInt(decompressed, 0);
            int off = 4;
            for (int i = 0; i < count; i++) {
                Entry entry = EntryCodec.decode(decompressed, off);
                bufferedEntries.add(entry);
                off += EntryCodec.encodedSize(entry);
            }
        }

        private byte[] readAbs(long fileOffset, int length) throws IOException {
            if (eagerData != null) {
                if (fileOffset < 0 || fileOffset + length > eagerData.length) {
                    throw new IOException("recovery scan read out of bounds at " + fileOffset
                            + " length=" + length + " (eager size=" + eagerData.length + ")");
                }
                byte[] out = new byte[length];
                System.arraycopy(eagerData, (int) fileOffset, out, 0, length);
                return out;
            }
            // F-R1.concurrency.1.5 — route through readLazyChannel so a concurrent close()
            // surfaces as IllegalStateException("reader is closed") rather than a raw
            // ClosedChannelException leaking out of the recovery scan.
            return readLazyChannel(fileOffset, length);
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Entry next() {
            if (next == null) {
                throw new NoSuchElementException();
            }
            Entry result = next;
            advance();
            return result;
        }

        /**
         * Releases the recovery mutex early — callers that abandon iteration mid-stream (e.g. an
         * early break from a for-each loop) must invoke this to avoid permanently wedging the
         * reader. Idempotent via the {@code released} flag. Marks the iterator exhausted so
         * subsequent {@link #hasNext()}/{@link #next()} calls do not attempt further channel I/O.
         *
         * @spec sstable.end-to-end-integrity.R38
         * @spec sstable.end-to-end-integrity.R44
         */
        @Override
        public void close() {
            exhausted = true;
            next = null;
            releaseOnceExhausted();
        }
    }

    /**
     * Closes this reader and releases any underlying resources (e.g. lazy channels).
     *
     * <p>
     * Any {@link Iterator}s returned by {@link #scan()} or
     * {@link #scan(MemorySegment, MemorySegment)} become invalid after {@code close()}. Their
     * {@code hasNext()} and {@code next()} methods may produce inconsistent results or throw an
     * implementation-specific exception; callers must not consume them after close.
     *
     * <p>
     * {@code close()} is idempotent — repeated calls are no-ops.
     */
    // @spec sstable.writer.R10 — iterator behavior after close is undefined; documented in public
    // API
    @Override
    public void close() throws IOException {
        if (!CLOSED.compareAndSet(this, false, true))
            return;
        if (lazyChannel != null && lazyChannel.isOpen()) {
            try {
                lazyChannel.close();
            } catch (IOException ignored) {
                // R43(c): close() must not throw on repeated invocation; swallow on lazy close.
            }
        }
    }

    // ---- v2 compression helpers ----

    // @spec sstable.writer.R5 — cache stores decompressed blocks; decompress before cache, cache
    // hit returns
    // decompressed
    // @spec sstable.writer.R7 — runtime checks for untrusted on-disk data, not assertions
    // @spec sstable.writer.R8 — corrupt blocks produce IOException
    // @spec sstable.writer.R12 — MemorySegment decompress, no byte[] conversion
    // @spec sstable.writer.R13 — compression map format unchanged
    /**
     * Reads and decompresses a single block by index using the compression map.
     */
    // @spec sstable.v3-format-upgrade.R6,R7,R8 — CRC32C verify before decompress; cache hit skips;
    // skipped when v2
    private byte[] readAndDecompressBlock(int blockIndex) throws IOException {
        assert compressionMap != null : "readAndDecompressBlock called on v1 reader";
        assert codecMap != null : "codecMap must not be null for v2";

        CompressionMap.Entry mapEntry = compressionMap.entry(blockIndex);

        if (blockCache != null) {
            Optional<MemorySegment> hit = blockCache.get(metadata.id(), blockIndex);
            if (hit.isPresent()) {
                return hit.get().toArray(ValueLayout.JAVA_BYTE);
            }
        }

        byte[] compressed;
        if (eagerData != null) {
            int intOffset;
            try {
                intOffset = Math.toIntExact(mapEntry.blockOffset());
            } catch (ArithmeticException e) {
                throw new IOException("block %d offset %d exceeds int range".formatted(blockIndex,
                        mapEntry.blockOffset()), e);
            }
            if (intOffset + mapEntry.compressedSize() > eagerData.length
                    || intOffset + mapEntry.compressedSize() < 0) {
                throw new IOException("block %d bounds [%d, %d) exceed eager data length %d"
                        .formatted(blockIndex, intOffset,
                                (long) intOffset + mapEntry.compressedSize(), eagerData.length));
            }
            compressed = new byte[mapEntry.compressedSize()];
            System.arraycopy(eagerData, intOffset, compressed, 0, mapEntry.compressedSize());
        } else {
            compressed = readLazyChannel(mapEntry.blockOffset(), mapEntry.compressedSize());
        }

        // v3: CRC32C verification before decompression — cache hits skip this
        if (hasChecksums) {
            verifyCrc32c(compressed, blockIndex, mapEntry.checksum());
        }

        CompressionCodec codec = codecMap.get(mapEntry.codecId());
        if (codec == null) {
            throw new IOException("codec not found for ID 0x%02x in block %d"
                    .formatted(mapEntry.codecId(), blockIndex));
        }
        // F17.R38: native MemorySegment decompress — Arena-allocated for zero-copy
        byte[] decompressed;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment compSeg = arena.allocate(compressed.length);
            MemorySegment.copy(compressed, 0, compSeg, ValueLayout.JAVA_BYTE, 0, compressed.length);

            MemorySegment decompDst = arena.allocate(mapEntry.uncompressedSize());
            MemorySegment decompSlice = codec.decompress(compSeg, decompDst,
                    mapEntry.uncompressedSize());
            if (decompSlice.byteSize() != mapEntry.uncompressedSize()) {
                throw new IOException(
                        "block %d decompression size mismatch: got %d bytes, expected %d".formatted(
                                blockIndex, decompSlice.byteSize(), mapEntry.uncompressedSize()));
            }
            decompressed = decompSlice.toArray(ValueLayout.JAVA_BYTE);
        }

        if (blockCache != null && !closed) {
            blockCache.put(metadata.id(), blockIndex, MemorySegment.ofArray(decompressed));
        }

        return decompressed;
    }

    // @spec compression.streaming-decompression.R10 — same decompression logic as
    // readAndDecompressBlock, no BlockCache get/put
    // @spec compression.streaming-decompression.R8,R9 — scan iterators bypass shared BlockCache via
    // this method
    /**
     * Reads and decompresses a single block by index, bypassing the BlockCache. Used by scan
     * iterators to avoid polluting the shared cache with sequential reads.
     */
    // @spec sstable.v3-format-upgrade.R7 — scan paths verify CRC32C on every disk read (no
    // BlockCache coupling)
    private byte[] readAndDecompressBlockNoCache(int blockIndex) throws IOException {
        assert compressionMap != null : "readAndDecompressBlockNoCache called on v1 reader";
        assert codecMap != null : "codecMap must not be null for v2";

        CompressionMap.Entry mapEntry = compressionMap.entry(blockIndex);

        byte[] compressed;
        if (eagerData != null) {
            int intOffset;
            try {
                intOffset = Math.toIntExact(mapEntry.blockOffset());
            } catch (ArithmeticException e) {
                throw new IOException("block %d offset %d exceeds int range".formatted(blockIndex,
                        mapEntry.blockOffset()), e);
            }
            if (intOffset + mapEntry.compressedSize() > eagerData.length
                    || intOffset + mapEntry.compressedSize() < 0) {
                throw new IOException("block %d bounds [%d, %d) exceed eager data length %d"
                        .formatted(blockIndex, intOffset,
                                (long) intOffset + mapEntry.compressedSize(), eagerData.length));
            }
            compressed = new byte[mapEntry.compressedSize()];
            System.arraycopy(eagerData, intOffset, compressed, 0, mapEntry.compressedSize());
        } else {
            compressed = readLazyChannel(mapEntry.blockOffset(), mapEntry.compressedSize());
        }

        // v3: CRC32C verification before decompression
        if (hasChecksums) {
            verifyCrc32c(compressed, blockIndex, mapEntry.checksum());
        }

        CompressionCodec codec = codecMap.get(mapEntry.codecId());
        if (codec == null) {
            throw new IOException("codec not found for ID 0x%02x in block %d"
                    .formatted(mapEntry.codecId(), blockIndex));
        }
        // F17.R38: native MemorySegment decompress — Arena-allocated for zero-copy
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment compSeg = arena.allocate(compressed.length);
            MemorySegment.copy(compressed, 0, compSeg, ValueLayout.JAVA_BYTE, 0, compressed.length);

            MemorySegment decompDst = arena.allocate(mapEntry.uncompressedSize());
            MemorySegment decompSlice = codec.decompress(compSeg, decompDst,
                    mapEntry.uncompressedSize());
            if (decompSlice.byteSize() != mapEntry.uncompressedSize()) {
                throw new IOException(
                        "block %d decompression size mismatch: got %d bytes, expected %d".formatted(
                                blockIndex, decompSlice.byteSize(), mapEntry.uncompressedSize()));
            }
            return decompSlice.toArray(ValueLayout.JAVA_BYTE);
        }
    }

    // @spec sstable.writer.R9 — trailing bytes in compression map/deflate silently ignored
    // (documented)
    /**
     * Verifies the CRC32C checksum of compressed block bytes.
     *
     * @throws CorruptBlockException if the computed checksum does not match the expected value
     */
    // @spec sstable.v3-format-upgrade.R5,R6,R9 — java.util.zip.CRC32C over on-disk bytes; mismatch
    // →
    // CorruptBlockException
    private static void verifyCrc32c(byte[] data, int blockIndex, int expectedChecksum)
            throws CorruptBlockException {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        int actual = (int) crc.getValue();
        if (actual != expectedChecksum) {
            throw new CorruptBlockException(blockIndex, expectedChecksum, actual);
        }
    }

    // @spec sstable.format-v2.R8 — explicit duplicate codec ID rejection
    // @spec sstable.format-v2.R9 — auto-includes NONE codec
    // @spec sstable.format-v2.R11 — null codec element rejected with index
    private static Map<Byte, CompressionCodec> buildCodecMap(CompressionCodec... codecs) {
        Map<Byte, CompressionCodec> map = new HashMap<>();
        // Always include NoneCodec — the writer falls back to NONE for incompressible blocks,
        // so any v2 file may contain codec ID 0x00 regardless of the configured codec.
        CompressionCodec none = CompressionCodec.none();
        map.put(none.codecId(), none);
        for (int i = 0; i < codecs.length; i++) {
            Objects.requireNonNull(codecs[i], "codecs[%d] must not be null".formatted(i));
            byte id = codecs[i].codecId();
            // Reject user-supplied codecs that claim codecId 0x00 — this is reserved for NoneCodec.
            // The writer's incompressible fallback always stores raw blocks with codecId 0x00.
            // Allow the actual NoneCodec instance (it's already in the map, so just skip it).
            if (id == 0x00) {
                if (codecs[i] == none) {
                    continue; // NoneCodec is already in the map
                }
                throw new IllegalArgumentException(
                        "codecs[%d] has codecId 0x00 which is reserved for NoneCodec".formatted(i));
            }
            CompressionCodec previous = map.put(id, codecs[i]);
            if (previous != null && previous != none) {
                throw new IllegalArgumentException(
                        "duplicate codec ID 0x%02x: %s and %s".formatted(id, previous, codecs[i]));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Reads the dictionary meta-block from a v4 SSTable file and overrides the caller-provided ZSTD
     * codec (ID 0x03) with a dictionary-bound codec. Returns a new mutable codec map with the
     * override applied.
     */
    // @spec compression.zstd-dictionary.R20,R20a — v4 with dictLength > 0: replace caller's codec
    // for ID 0x03 with
    // a codec bound to the file's embedded dictionary
    private static Map<Byte, CompressionCodec> overrideWithDictionaryCodec(SeekableByteChannel ch,
            Footer footer, Map<Byte, CompressionCodec> originalMap) throws IOException {
        byte[] dictBytes = readBytes(ch, footer.dictOffset, (int) footer.dictLength);
        MemorySegment dictionary = MemorySegment.ofArray(dictBytes);
        CompressionCodec dictCodec = CompressionCodec.zstd(3, dictionary);

        // Create a mutable copy and override codec ID 0x03
        Map<Byte, CompressionCodec> mutableMap = new HashMap<>(originalMap);
        mutableMap.put((byte) 0x03, dictCodec);
        return Collections.unmodifiableMap(mutableMap);
    }

    /**
     * Replaces any entry for codec ID 0x03 in the codec map with a fresh plain (no-dictionary) ZSTD
     * codec. Called for v3 or earlier SSTables (no dictionary meta-block) so that the on-disk file
     * metadata, not the caller's codec list, determines how ID 0x03 blocks are decompressed. On
     * Tier 2/3 (native libzstd unavailable), writers never emit codec ID 0x03 in the first place,
     * but the override is harmless — the injected codec is only used if the file's compression map
     * references ID 0x03.
     */
    // @spec compression.zstd-dictionary.R20a,R21 — v3 or earlier: reader always uses plain ZSTD for
    // ID 0x03
    private static Map<Byte, CompressionCodec> overrideWithPlainZstdCodec(
            Map<Byte, CompressionCodec> originalMap) {
        Map<Byte, CompressionCodec> mutableMap = new HashMap<>(originalMap);
        mutableMap.put((byte) 0x03, CompressionCodec.zstd());
        return Collections.unmodifiableMap(mutableMap);
    }

    // @spec sstable.format-v2.R10 — unknown codec ID throws IOException
    private static void validateCodecMap(CompressionMap compressionMap,
            Map<Byte, CompressionCodec> codecMap) throws IOException {
        for (int i = 0; i < compressionMap.blockCount(); i++) {
            byte codecId = compressionMap.entry(i).codecId();
            if (!codecMap.containsKey(codecId)) {
                throw new IOException(
                        "unknown compression codec ID 0x%02x in block %d; available codecs: %s"
                                .formatted(codecId, i, codecMap.keySet()));
            }
        }
    }

    // ---- v1 internal helpers ----

    /**
     * Reads bytes from the lazy channel, converting ClosedChannelException to IllegalStateException
     * when the reader has been closed. This prevents callers from seeing a raw
     * ClosedChannelException in the TOCTOU window between checkNotClosed() and the actual I/O.
     */
    private byte[] readLazyChannel(long offset, int length) throws IOException {
        try {
            return readBytes(lazyChannel, offset, length);
        } catch (java.nio.channels.ClosedChannelException e) {
            if (closed) {
                throw new IllegalStateException("reader is closed");
            }
            throw e;
        }
    }

    private void checkNotClosed() throws IOException {
        if (closed)
            throw new IllegalStateException("reader is closed");
    }

    /**
     * R43: if this reader has been transitioned to FAILED state by a lazy first-load failure,
     * surface the originating cause wrapped in an IllegalStateException.
     */
    // @spec sstable.end-to-end-integrity.R43 — rejects get/scan/recoveryScan/section-load calls
    // with IllegalStateException whose cause is the originating CorruptSectionException once the
    // reader has transitioned to FAILED; close() intentionally does not call this method so it
    // remains runnable to completion.
    private void checkNotFailed() {
        // Snapshot both fields into locals. The reads are two independent volatile loads
        // today (F-R1.concurrency.1.4); if a future writer publishes failureCause before
        // failureSection, a concurrent reader can observe a torn state (cause != null,
        // section == null). Substitute a non-null sentinel so the R43 diagnostic never
        // renders the literal string "null" — the cause chain is preserved regardless.
        IOException cause = failureCause;
        if (cause != null) {
            String section = failureSection;
            IllegalStateException ise = new IllegalStateException(
                    "reader failed: " + (section != null ? section : "<pending>"), cause);
            throw ise;
        }
    }

    /**
     * R43: transition this reader to the FAILED state using the given section-level corruption as
     * the originating cause. Publishes {@code failureSection} before {@code failureCause} so a
     * concurrent {@link #checkNotFailed()} reader that snapshots {@code failureCause} first always
     * observes a non-null {@code failureSection} — the diagnostic message is therefore never
     * rendered as "reader failed: null" regardless of which ordering the reader sees.
     *
     * <p>
     * The transition is effectively single-shot: once {@code failureCause} is non-null, subsequent
     * get/scan/recoveryScan calls reject via {@link #checkNotFailed()} before any I/O is attempted.
     * Concurrent callers racing to transition the reader will each publish their own cause, and the
     * last writer wins — but this is harmless because every such cause is a genuine section-level
     * corruption and the reader is already logically FAILED.
     */
    // @spec sstable.end-to-end-integrity.R43 — FAILED-state transition: publishes section before
    // cause so checkNotFailed observers always see a non-null section when they see a non-null
    // cause. Subsequent reads reject with IllegalStateException wrapping the originating cause.
    private void transitionToFailed(CorruptSectionException cause) {
        assert cause != null : "transitionToFailed requires a non-null cause";
        // Publish section first so any reader snapshotting failureCause first sees a non-null
        // section via the happens-before established by the subsequent volatile write to
        // failureCause.
        failureSection = cause.sectionName();
        failureCause = cause;
    }

    /**
     * R38: acquire a slot in the recovery/read mutex. A recovery scan in progress rejects new
     * readers with ISE; a normal read proceeds only after incrementing the active-ops counter.
     *
     * <p>
     * The check-and-increment is serialized against recoveryScan's check-and-claim via
     * {@link #recoveryLock}: without this, the two sides each read their counter/flag independently
     * (check-then-act on two variables), permitting an interleaving in which both a reader and a
     * recovery-scan observe the pre-claim state and proceed concurrently — a direct R38 violation.
     * By requiring the lock here (tryLock with a brief wait for simultaneous reader arrivals), the
     * reader either observes a concurrent recoveryScan holding the lock (tryLock fails → ISE) or
     * acquires the lock and increments {@code activeReaderOps} before recoveryScan's subsequent
     * {@code activeReaderOps.get() > 0} check runs under the same lock.
     */
    private void acquireReaderSlot() {
        // Wait briefly for another reader's tiny lock-held window to finish; do not wait
        // for a recovery-scan (which holds the lock for its iterator's full lifetime).
        // If the lock cannot be obtained quickly, treat it as "recovery-scan in progress."
        boolean acquired;
        try {
            acquired = recoveryLock.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while acquiring reader slot", e);
        }
        if (!acquired) {
            throw new IllegalStateException("recovery-scan in progress");
        }
        try {
            if (recoveryInProgress) {
                throw new IllegalStateException("recovery-scan in progress");
            }
            activeReaderOps.incrementAndGet();
        } finally {
            recoveryLock.unlock();
        }
    }

    private void releaseReaderSlot() {
        activeReaderOps.decrementAndGet();
    }

    /** Returns the data at {@code fileOffset} (v1 absolute offset), reading at most maxBytes. */
    private byte[] readDataAtV1(long fileOffset, int maxBytes) throws IOException {
        if (fileOffset < 0) {
            throw new IOException("negative file offset: " + fileOffset);
        }
        int len = (int) Math.min(maxBytes, dataEnd - fileOffset);
        if (len <= 0)
            throw new IOException("file offset out of data range: " + fileOffset);

        if (blockCache != null) {
            Optional<MemorySegment> hit = blockCache.get(metadata.id(), fileOffset);
            if (hit.isPresent()) {
                return hit.get().toArray(ValueLayout.JAVA_BYTE);
            }
        }

        byte[] data;
        if (eagerData != null) {
            data = new byte[len];
            System.arraycopy(eagerData, (int) fileOffset, data, 0, len);
        } else {
            data = readLazyChannel(fileOffset, len);
        }

        if (blockCache != null) {
            blockCache.put(metadata.id(), fileOffset, MemorySegment.ofArray(data));
        }
        return data;
    }

    /** Returns the full data region for v1 full scan. */
    private byte[] getAllDataV1() throws IOException {
        if (eagerData != null)
            return eagerData;
        return readLazyChannel(0L, (int) dataEnd);
    }

    /**
     * Rethrows a checked exception as unchecked without wrapping, using type-erasure semantics.
     * Returns RuntimeException so callers can write {@code throw sneakyThrow(e)} to satisfy the
     * compiler's control-flow analysis.
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    // ---- Footer / index / filter loading ----

    // @spec sstable.format-v2.R16 — non-negative offset/length validation
    // @spec sstable.format-v2.R17 — long-to-int truncation guarded
    // @spec sstable.format-v2.R18 — all offsets as long, no int narrowing
    // @spec sstable.format-v2.R21 — section overlap detection
    // @spec sstable.v3-format-upgrade.R15 — v3+ footer carries blockSize; v1/v2 populate with 4096
    // (pre-v3 default)
    private record Footer(int version, long mapOffset, long mapLength, long dictOffset,
            long dictLength, long idxOffset, long idxLength, long fltOffset, long fltLength,
            long entryCount, long blockSize, int blockCount, int mapChecksum, int dictChecksum,
            int idxChecksum, int fltChecksum) {

        /**
         * Narrow constructor for v1-v4 callers that do not populate v5 checksum fields.
         */
        Footer(int version, long mapOffset, long mapLength, long dictOffset, long dictLength,
                long idxOffset, long idxLength, long fltOffset, long fltLength, long entryCount,
                long blockSize) {
            this(version, mapOffset, mapLength, dictOffset, dictLength, idxOffset, idxLength,
                    fltOffset, fltLength, entryCount, blockSize, /* blockCount */ 0,
                    /* mapChecksum */ 0, /* dictChecksum */ 0, /* idxChecksum */ 0,
                    /* fltChecksum */ 0);
        }

        /**
         * Validates footer field consistency against the file size. All offsets and lengths must be
         * non-negative and within file bounds.
         *
         * @throws IOException if any field is invalid
         */
        // @spec sstable.v3-format-upgrade.R20,R21 — non-negative offsets/lengths, section-ordering,
        // all IOException
        void validate(long fileSize) throws IOException {
            if (entryCount < 0) {
                throw new IOException("corrupt SSTable footer: negative entryCount " + entryCount);
            }
            if (idxOffset < 0 || idxLength < 0) {
                throw new IOException(
                        "corrupt SSTable footer: negative idxOffset=%d or idxLength=%d"
                                .formatted(idxOffset, idxLength));
            }
            if (fltOffset < 0 || fltLength < 0) {
                throw new IOException(
                        "corrupt SSTable footer: negative fltOffset=%d or fltLength=%d"
                                .formatted(fltOffset, fltLength));
            }
            if (version >= 2) {
                if (mapOffset < 0 || mapLength < 0) {
                    throw new IOException(
                            "corrupt SSTable footer: negative mapOffset=%d or mapLength=%d"
                                    .formatted(mapOffset, mapLength));
                }
                // C2-F2: Guard against long-to-int truncation for data region size
                if (mapOffset > Integer.MAX_VALUE) {
                    throw new IOException(
                            "SSTable data region too large for eager read: mapOffset=%d exceeds 2 GiB"
                                    .formatted(mapOffset));
                }
                if (mapLength > Integer.MAX_VALUE) {
                    throw new IOException(
                            "SSTable compression map too large: mapLength=%d exceeds 2 GiB"
                                    .formatted(mapLength));
                }
            } else {
                // v1: idxOffset defines the data region end
                if (idxOffset > Integer.MAX_VALUE) {
                    throw new IOException(
                            "SSTable data region too large for eager read: idxOffset=%d exceeds 2 GiB"
                                    .formatted(idxOffset));
                }
            }
            // Guard against long-to-int truncation for idxLength and fltLength
            if (idxLength > Integer.MAX_VALUE) {
                throw new IOException("SSTable key index too large: idxLength=%d exceeds 2 GiB"
                        .formatted(idxLength));
            }
            if (fltLength > Integer.MAX_VALUE) {
                throw new IOException("SSTable bloom filter too large: fltLength=%d exceeds 2 GiB"
                        .formatted(fltLength));
            }
            // Bounds-vs-fileSize: offset+length must not exceed file size
            if (idxOffset + idxLength > fileSize) {
                throw new IOException(
                        "corrupt SSTable footer: idx section [%d, %d) exceeds file size %d"
                                .formatted(idxOffset, idxOffset + idxLength, fileSize));
            }
            if (fltOffset + fltLength > fileSize) {
                throw new IOException(
                        "corrupt SSTable footer: flt section [%d, %d) exceeds file size %d"
                                .formatted(fltOffset, fltOffset + fltLength, fileSize));
            }
            if (version >= 2 && mapOffset + mapLength > fileSize) {
                throw new IOException(
                        "corrupt SSTable footer: map section [%d, %d) exceeds file size %d"
                                .formatted(mapOffset, mapOffset + mapLength, fileSize));
            }
            // Cross-field overlap: sections must not overlap each other
            if (fltLength > 0 && idxOffset + idxLength > fltOffset) {
                throw new IOException(
                        "corrupt SSTable footer: idx section [%d, %d) overlaps flt section at offset %d"
                                .formatted(idxOffset, idxOffset + idxLength, fltOffset));
            }
            if (version >= 2 && idxLength > 0 && mapOffset + mapLength > idxOffset) {
                throw new IOException(
                        "corrupt SSTable footer: map section [%d, %d) overlaps idx section at offset %d"
                                .formatted(mapOffset, mapOffset + mapLength, idxOffset));
            }
            // v4: validate dictionary section
            if (version >= 4) {
                if (dictOffset < 0 || dictLength < 0) {
                    throw new IOException(
                            "corrupt SSTable footer: negative dictOffset=%d or dictLength=%d"
                                    .formatted(dictOffset, dictLength));
                }
                if (dictLength > 0 && dictOffset + dictLength > fileSize) {
                    throw new IOException(
                            "corrupt SSTable footer: dict section [%d, %d) exceeds file size %d"
                                    .formatted(dictOffset, dictOffset + dictLength, fileSize));
                }
            }
        }
    }

    /** Reads the v5 footer; rejects any other (legacy or unrecognised) magic. */
    // @spec sstable.end-to-end-integrity.R25,R34,R40 — magic-first open; sub-magic files become
    // IncompleteSSTableException; unrecognised trailing magic → IncompleteSSTableException.
    // @spec sstable.end-to-end-integrity.R35 — version dispatch: only MAGIC_V5 is recognised
    // post v1-v4 collapse. Pre-collapse vocabulary (MAGIC, MAGIC_V2..V4) is now treated as
    // unrecognised and surfaces as IncompleteSSTableException (R40).
    // @spec sstable.end-to-end-integrity.R48 — v5 footer length-field int-narrowing guards
    // (mapOffset/mapLength/idxLength/fltLength/dictLength Integer.MAX_VALUE cap) applied inline
    // before any downstream (int) cast.
    // @spec sstable.end-to-end-integrity.R52 — pre-dispatch speculative v5-hypothesis CRC
    // recomputation detects a v5 file whose magic discriminant has been corrupted, surfacing as
    // CorruptSectionException(SECTION_FOOTER) before R40's incomplete classification runs.
    private static Footer readFooter(SeekableByteChannel ch, long fileSize) throws IOException {
        // R25 + R40: files shorter than the minimum magic are incomplete, not corrupt.
        if (fileSize < 8) {
            throw new IncompleteSSTableException("no-magic", fileSize, 0,
                    "file too small to contain an SSTable magic number");
        }
        // R25: read the trailing 8 bytes FIRST so we can dispatch on magic.
        byte[] magicBuf = readBytes(ch, fileSize - 8, 8);
        long magic = readLong(magicBuf, 0);

        // R26 defence-in-depth: dispatch-discriminant corruption detection. A genuine v5 file
        // whose magic byte was corrupted would otherwise be reported as "unknown magic" by R40
        // below. Speculatively verify the v5 footer self-checksum over the trailing
        // FOOTER_SIZE_V5 bytes with a HYPOTHETICAL intact MAGIC_V5 substituted into the scope:
        // if the result matches the stored checksum, the file is a genuine v5 whose magic
        // discriminant was corrupted — surface as CorruptSectionException(footer) so R26 is
        // honoured. False-positive probability for a non-v5 file is ~2^-32.
        if (magic != SSTableFormat.MAGIC_V5 && fileSize >= SSTableFormat.FOOTER_SIZE_V5) {
            byte[] speculative = readBytes(ch, fileSize - SSTableFormat.FOOTER_SIZE_V5,
                    SSTableFormat.FOOTER_SIZE_V5);
            int storedChecksum = readInt(speculative, 100);
            byte[] hypothesis = speculative.clone();
            writeLongBigEndian(hypothesis, SSTableFormat.FOOTER_SIZE_V5 - 8,
                    SSTableFormat.MAGIC_V5);
            int hypotheticalChecksum = V5Footer.computeFooterChecksum(hypothesis);
            if (storedChecksum == hypotheticalChecksum) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "v5 footer self-checksum matched with hypothetical intact MAGIC_V5 but "
                                + "trailing magic is " + String.format("0x%016x", magic)
                                + "; discriminant byte has been corrupted (expected MAGIC_V5)");
            }
        }

        // R40: unrecognised trailing magic → IncompleteSSTableException. Post v1-v4 collapse,
        // only MAGIC_V5 is recognised; the historical magics no longer match any branch.
        if (magic != SSTableFormat.MAGIC_V5) {
            throw new IncompleteSSTableException(String.format("0x%016x", magic), fileSize, 0,
                    "trailing bytes do not match any recognised SSTable magic");
        }

        {
            // R25: validate file-size against v5 footer BEFORE reading footer bytes.
            if (fileSize < SSTableFormat.FOOTER_SIZE_V5) {
                throw new IncompleteSSTableException(String.format("0x%016x", magic), fileSize,
                        SSTableFormat.FOOTER_SIZE_V5,
                        "file shorter than v5 footer size (" + SSTableFormat.FOOTER_SIZE_V5 + ")");
            }
            byte[] footerBytes = readBytes(ch, fileSize - SSTableFormat.FOOTER_SIZE_V5,
                    SSTableFormat.FOOTER_SIZE_V5);
            int expectedFooterChecksum = readInt(footerBytes, 100);
            int recomputedFooterChecksum = V5Footer.computeFooterChecksum(footerBytes);
            if (expectedFooterChecksum != recomputedFooterChecksum) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        expectedFooterChecksum, recomputedFooterChecksum);
            }
            V5Footer v5 = V5Footer.decode(footerBytes, 0);
            // @spec sstable.end-to-end-integrity.R18 — validate blockCount >= 1 and mapLength >= 1
            // in the footer before the compression-map entry count is compared against blockCount
            if (v5.blockCount() < 1) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "v5 footer.blockCount must be >= 1; got: " + v5.blockCount());
            }
            if (v5.mapLength() < 1) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "v5 footer.mapLength must be >= 1; got: " + v5.mapLength());
            }
            // R15: dictionary sentinel consistency
            if (!V5Footer.isDictionarySentinelConsistent(v5)) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "v5 footer dictionary sentinel inconsistent: dictOffset=" + v5.dictOffset()
                                + ", dictLength=" + v5.dictLength() + ", dictChecksum="
                                + v5.dictChecksum());
            }
            // R37: tight-packing of sections
            String violation = V5Footer.validateTightPacking(v5);
            if (violation != null) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "v5 footer tight-packing violated at section " + violation);
            }
            // Validate blockSize
            long rawBlockSize = v5.blockSize();
            if (rawBlockSize < SSTableFormat.MIN_BLOCK_SIZE
                    || rawBlockSize > SSTableFormat.MAX_BLOCK_SIZE
                    || (rawBlockSize & (rawBlockSize - 1)) != 0) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "v5 footer invalid blockSize " + rawBlockSize);
            }
            // Validate footer-relative section extents against the file.
            long footerStart = fileSize - SSTableFormat.FOOTER_SIZE_V5;
            long maxSectionEnd = Math.max(
                    Math.max(v5.mapOffset() + v5.mapLength(), v5.idxOffset() + v5.idxLength()),
                    v5.fltOffset() + v5.fltLength());
            if (v5.dictLength() > 0) {
                maxSectionEnd = Math.max(maxSectionEnd, v5.dictOffset() + v5.dictLength());
            }
            if (maxSectionEnd > footerStart) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "v5 footer section extends past footer start (" + footerStart + ")");
            }
            // Guard against long-to-int truncation in downstream `(int) ...Length` casts at the
            // open()/openLazy() call sites (e.g. readBytes(ch, mapOffset, (int) mapLength)). Pre-v5
            // paths delegate these checks to Footer.validate(fileSize); the v5 path does not call
            // validate() and must enforce the Integer.MAX_VALUE bound inline to avoid silent
            // section truncation or an IllegalArgumentException leak from ByteBuffer.allocate(-n).
            if (v5.mapOffset() > Integer.MAX_VALUE) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "v5 footer mapOffset=" + v5.mapOffset() + " exceeds 2 GiB");
            }
            if (v5.mapLength() > Integer.MAX_VALUE) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "v5 footer mapLength=" + v5.mapLength() + " exceeds 2 GiB");
            }
            if (v5.idxLength() > Integer.MAX_VALUE) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "v5 footer idxLength=" + v5.idxLength() + " exceeds 2 GiB");
            }
            if (v5.fltLength() > Integer.MAX_VALUE) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "v5 footer fltLength=" + v5.fltLength() + " exceeds 2 GiB");
            }
            if (v5.dictLength() > Integer.MAX_VALUE) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_FOOTER,
                        "v5 footer dictLength=" + v5.dictLength() + " exceeds 2 GiB");
            }
            return new Footer(5, v5.mapOffset(), v5.mapLength(), v5.dictOffset(), v5.dictLength(),
                    v5.idxOffset(), v5.idxLength(), v5.fltOffset(), v5.fltLength(), v5.entryCount(),
                    rawBlockSize, v5.blockCount(), v5.mapChecksum(), v5.dictChecksum(),
                    v5.idxChecksum(), v5.fltChecksum());
        }
    }

    /**
     * Reads v2 key index: [numKeys(4)][per key: keyLen(4) + key + blockIndex(4) + intraOff(4)].
     *
     * <p>
     * Each entry's {@code blockIndex} must be in {@code [0, blockCount)} and
     * {@code intraBlockOffset} must be non-negative; corrupt values from on-disk data are rejected
     * with {@link IOException} rather than propagating as internal
     * {@link IndexOutOfBoundsException} downstream.
     */
    // @spec sstable.format-v2.R20 — validate blockIndex in [0, blockCount) and intraBlockOffset >=
    // 0 at read
    // time; spec R40 forbids assert-only guards on data reachable from untrusted on-disk state.
    private static KeyIndex readKeyIndexV2(SeekableByteChannel ch, Footer footer, int blockCount)
            throws IOException {
        byte[] buf = readBytes(ch, footer.idxOffset, (int) footer.idxLength);
        int numKeys = readInt(buf, 0);
        List<MemorySegment> keys = new ArrayList<>(numKeys);
        List<Long> offsets = new ArrayList<>(numKeys);
        int off = 4;
        for (int i = 0; i < numKeys; i++) {
            int keyLen = readInt(buf, off);
            off += 4;
            byte[] keyBytes = new byte[keyLen];
            System.arraycopy(buf, off, keyBytes, 0, keyLen);
            off += keyLen;
            int blockIndex = readInt(buf, off);
            off += 4;
            int intraBlockOffset = readInt(buf, off);
            off += 4;
            if (blockIndex < 0 || blockIndex >= blockCount) {
                throw new IOException("v2 key index entry %d: blockIndex %d out of range [0, %d)"
                        .formatted(i, blockIndex, blockCount));
            }
            if (intraBlockOffset < 0) {
                throw new IOException(
                        "v2 key index entry %d: intra-block offset must be non-negative, got %d"
                                .formatted(i, intraBlockOffset));
            }
            // Pack into a single long, same encoding as the writer
            long packed = ((long) blockIndex << 32) | (intraBlockOffset & 0xFFFFFFFFL);
            keys.add(MemorySegment.ofArray(keyBytes));
            offsets.add(packed);
        }
        return new KeyIndex(keys, offsets);
    }

    private static BloomFilter readBloomFilter(SeekableByteChannel ch, Footer footer,
            BloomFilter.Deserializer deserializer) throws IOException {
        byte[] buf = readBytes(ch, footer.fltOffset, (int) footer.fltLength);
        // Use an Arena-allocated segment to satisfy the alignment constraints of the deserializer
        // (BlockedBloomFilter uses JAVA_INT/JAVA_LONG without withByteAlignment(1)).
        // Arena.ofAuto() is used instead of ofShared() because the deserialized BloomFilter may
        // retain a reference to the MemorySegment — closing the Arena would invalidate it.
        Arena arena = Arena.ofAuto();
        MemorySegment seg = arena.allocate(buf.length, 8);
        MemorySegment.copy(MemorySegment.ofArray(buf), 0, seg, 0, buf.length);
        return deserializer.deserialize(seg);
    }

    /**
     * v5-aware bloom filter read: reads the bytes, optionally verifies CRC32C against the
     * footer-stored checksum, and invokes the deserializer.
     *
     * @spec sstable.end-to-end-integrity.R14
     * @spec sstable.end-to-end-integrity.R27
     * @spec sstable.end-to-end-integrity.R29
     * @spec sstable.end-to-end-integrity.R49
     */
    private static BloomFilter readBloomFilterWithCrc(SeekableByteChannel ch, Footer footer,
            BloomFilter.Deserializer deserializer, boolean verifyCrc) throws IOException {
        // @spec sstable.end-to-end-integrity.R29 — a zero-length bloom section on a v5 file is a
        // corruption: the CRC32C of empty bytes is 0x00000000, so an attacker-supplied
        // fltChecksum=0 paired with fltLength=0 would pass the verifySectionCrc32c gate and feed
        // a zero-byte MemorySegment to the deserializer. BloomFilter.Deserializer implementations
        // signal that state with IllegalArgumentException, which would escape the factory's
        // declared IOException contract. Reject the zero-length bloom section up front as a
        // CorruptSectionException so callers can distinguish corrupt input from programming error.
        if (verifyCrc && footer.fltLength == 0) {
            throw new CorruptSectionException(CorruptSectionException.SECTION_BLOOM_FILTER,
                    "v5 bloom section must be non-empty; got fltLength=0");
        }
        byte[] buf = readBytes(ch, footer.fltOffset, (int) footer.fltLength);
        if (verifyCrc) {
            verifySectionCrc32c(buf, footer.fltChecksum,
                    CorruptSectionException.SECTION_BLOOM_FILTER);
        }
        Arena arena = Arena.ofAuto();
        MemorySegment seg = arena.allocate(buf.length, 8);
        MemorySegment.copy(MemorySegment.ofArray(buf), 0, seg, 0, buf.length);
        return deserializer.deserialize(seg);
    }

    /**
     * v5-aware key-index read: reads the bytes, optionally verifies CRC32C, then decodes. The
     * decoded KeyIndex has the same in-memory representation used by v2/v3/v4.
     *
     * @spec sstable.end-to-end-integrity.R14
     * @spec sstable.end-to-end-integrity.R27
     * @spec sstable.end-to-end-integrity.R29
     */
    private static KeyIndex readKeyIndexV2Bytes(SeekableByteChannel ch, Footer footer,
            int blockCount, boolean verifyCrc) throws IOException {
        byte[] buf = readBytes(ch, footer.idxOffset, (int) footer.idxLength);
        if (verifyCrc) {
            verifySectionCrc32c(buf, footer.idxChecksum, CorruptSectionException.SECTION_KEY_INDEX);
        }
        int numKeys = readInt(buf, 0);
        List<MemorySegment> keys = new ArrayList<>(numKeys);
        List<Long> offsets = new ArrayList<>(numKeys);
        int off = 4;
        for (int i = 0; i < numKeys; i++) {
            int keyLen = readInt(buf, off);
            off += 4;
            byte[] keyBytes = new byte[keyLen];
            System.arraycopy(buf, off, keyBytes, 0, keyLen);
            off += keyLen;
            int blockIndex = readInt(buf, off);
            off += 4;
            int intraBlockOffset = readInt(buf, off);
            off += 4;
            if (blockIndex < 0 || blockIndex >= blockCount) {
                throw new IOException("v2 key index entry %d: blockIndex %d out of range [0, %d)"
                        .formatted(i, blockIndex, blockCount));
            }
            if (intraBlockOffset < 0) {
                throw new IOException(
                        "v2 key index entry %d: intra-block offset must be non-negative, got %d"
                                .formatted(i, intraBlockOffset));
            }
            long packed = ((long) blockIndex << 32) | (intraBlockOffset & 0xFFFFFFFFL);
            keys.add(MemorySegment.ofArray(keyBytes));
            offsets.add(packed);
        }
        return new KeyIndex(keys, offsets);
    }

    /**
     * CRC32C-check a section's raw bytes against the stored footer checksum.
     *
     * @spec sstable.end-to-end-integrity.R13
     * @spec sstable.end-to-end-integrity.R14
     * @spec sstable.end-to-end-integrity.R29
     */
    private static void verifySectionCrc32c(byte[] bytes, int expected, String sectionName)
            throws CorruptSectionException {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        int computed = (int) crc.getValue();
        if (computed != expected) {
            throw new CorruptSectionException(sectionName, expected, computed);
        }
    }

    private static SSTableMetadata buildMetadata(Path path, long fileSize, Footer footer,
            KeyIndex keyIndex) {
        MemorySegment smallestKey = null;
        MemorySegment largestKey = null;
        Iterator<KeyIndex.Entry> it = keyIndex.iterator();
        while (it.hasNext()) {
            KeyIndex.Entry e = it.next();
            if (smallestKey == null)
                smallestKey = e.key();
            largestKey = e.key();
        }
        assert smallestKey != null : "key index must not be empty when building metadata";
        return new SSTableMetadata(0L, path, Level.L0, smallestKey, largestKey, SequenceNumber.ZERO,
                SequenceNumber.ZERO, fileSize, footer.entryCount);
    }

    // ---- Low-level I/O ----

    /**
     * Maximum consecutive zero-progress reads tolerated before readBytes fails with an IOException.
     * NIO permits {@link SeekableByteChannel#read(ByteBuffer)} to return {@code 0} (no bytes
     * available yet, no error, no EOF), and a conforming remote provider (S3, HTTP-range) may do so
     * for spurious reasons. We tolerate a bounded run of zero-returns before treating the channel
     * as stalled, per the "Every iteration must terminate" coding guideline.
     */
    private static final int MAX_ZERO_PROGRESS_READS = 1024;

    // @spec sstable.writer.R4 — lazy reader synchronizes channel reads
    /**
     * Reads {@code length} bytes from the channel starting at {@code offset}.
     *
     * <p>
     * The position-then-read sequence is synchronized on the channel to prevent concurrent callers
     * (e.g., multiple threads sharing a lazy reader) from interleaving position and read calls,
     * which would produce silently corrupt data (C2-F18).
     *
     * <p>
     * The loop tolerates up to {@link #MAX_ZERO_PROGRESS_READS} consecutive zero-byte returns
     * (permitted by the NIO contract — see F-R1.data_transformation.C2.03) before throwing an
     * {@link IOException}, preventing an unbounded spin on a stalled remote channel.
     *
     * @spec sstable.end-to-end-integrity.R50
     * @spec sstable.end-to-end-integrity.R51
     */
    private static byte[] readBytes(SeekableByteChannel ch, long offset, int length)
            throws IOException {
        if (length < 0) {
            // Defense-in-depth against upstream long-to-int narrowing bugs: a caller may have
            // cast a section length > Integer.MAX_VALUE (e.g. (int) footer.dictLength when
            // Footer.validate lacks a MAX_VALUE guard for that field) to a negative int. Surface
            // this as an IOException so the factory's declared contract is honored — never leak
            // IllegalArgumentException from ByteBuffer.allocate(negative) (F-R1.dt.C2.04).
            throw new IOException("corrupt SSTable: readBytes invoked with negative length="
                    + length + " at offset=" + offset
                    + " (upstream long-to-int narrowing; section length exceeds 2 GiB)");
        }
        if (length == 0)
            return new byte[0];
        ByteBuffer buf = ByteBuffer.allocate(length);
        synchronized (ch) {
            ch.position(offset);
            int zeroProgressReads = 0;
            while (buf.hasRemaining()) {
                int read = ch.read(buf);
                if (read < 0)
                    throw new IOException("unexpected EOF at offset " + (offset + buf.position()));
                if (read == 0) {
                    if (++zeroProgressReads >= MAX_ZERO_PROGRESS_READS) {
                        throw new IOException("channel returned zero bytes for "
                                + MAX_ZERO_PROGRESS_READS + " consecutive reads at offset "
                                + (offset + buf.position())
                                + " (stalled or non-progressing); aborting to avoid "
                                + "unbounded spin");
                    }
                } else {
                    zeroProgressReads = 0;
                }
            }
        }
        return buf.array();
    }

    private static int readInt(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24) | ((buf[off + 1] & 0xFF) << 16)
                | ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
    }

    private static long readLong(byte[] buf, int off) {
        return ((long) (buf[off] & 0xFF) << 56) | ((long) (buf[off + 1] & 0xFF) << 48)
                | ((long) (buf[off + 2] & 0xFF) << 40) | ((long) (buf[off + 3] & 0xFF) << 32)
                | ((long) (buf[off + 4] & 0xFF) << 24) | ((long) (buf[off + 5] & 0xFF) << 16)
                | ((long) (buf[off + 6] & 0xFF) << 8) | (long) (buf[off + 7] & 0xFF);
    }

    private static void writeLongBigEndian(byte[] buf, int off, long value) {
        buf[off] = (byte) (value >>> 56);
        buf[off + 1] = (byte) (value >>> 48);
        buf[off + 2] = (byte) (value >>> 40);
        buf[off + 3] = (byte) (value >>> 32);
        buf[off + 4] = (byte) (value >>> 24);
        buf[off + 5] = (byte) (value >>> 16);
        buf[off + 6] = (byte) (value >>> 8);
        buf[off + 7] = (byte) value;
    }

    // ---- Iterators ----

    /**
     * Iterates entries by parsing the raw data region linearly (block by block). Parses: [int
     * count][entry...][int count][entry...]... until offset reaches dataEnd.
     */
    private static final class DataRegionIterator implements Iterator<Entry> {
        private final byte[] data;
        private final long dataEnd;
        private int offset = 0;
        private List<Entry> blockEntries = new ArrayList<>();
        private int entryIdx = 0;
        private Entry next;

        DataRegionIterator(byte[] data, long dataEnd) {
            this.data = data;
            this.dataEnd = dataEnd;
            advance();
        }

        private void advance() {
            next = null;
            while (true) {
                if (entryIdx < blockEntries.size()) {
                    next = blockEntries.get(entryIdx++);
                    return;
                }
                if (offset >= dataEnd)
                    return;
                // Parse next block
                int count = readInt(data, offset);
                offset += 4;
                blockEntries = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    Entry e = EntryCodec.decode(data, offset);
                    blockEntries.add(e);
                    offset += EntryCodec.encodedSize(e);
                }
                entryIdx = 0;
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

        private static int readInt(byte[] buf, int off) {
            return ((buf[off] & 0xFF) << 24) | ((buf[off + 1] & 0xFF) << 16)
                    | ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
        }
    }

    // @spec compression.streaming-decompression.R1 — decompresses blocks incrementally as iteration
    // advances
    // @spec compression.streaming-decompression.R2 — holds at most one decompressed block in memory
    // @spec compression.streaming-decompression.R3 — decompresses blocks in ascending block-index
    // order
    // @spec compression.streaming-decompression.R4 — yields entries in same order as
    // DataRegionIterator
    // @spec compression.streaming-decompression.R8 — full-scan bypasses shared BlockCache
    // @spec compression.streaming-decompression.R14 — reads 4-byte big-endian entry count, decodes
    // exactly that many entries
    // @spec compression.streaming-decompression.R16 — hasNext returns false after last entry of
    // last block
    // @spec compression.streaming-decompression.R17 — throws NoSuchElementException when no more
    // entries
    // @spec compression.streaming-decompression.R18 — wraps IOException in UncheckedIOException
    // @spec compression.streaming-decompression.R19 — detects closed reader, throws
    // IllegalStateException
    // @spec compression.streaming-decompression.R21 — zero blocks yields immediate hasNext()==false
    // @spec compression.streaming-decompression.R22 — single block decompresses, yields entries,
    // then hasNext()==false
    // @spec compression.streaming-decompression.R24 — decompression failure propagates as
    // UncheckedIOException, no skip
    // @spec compression.streaming-decompression.R26 — independent iterators maintain own
    // block-index position
    // @spec compression.streaming-decompression.R29 — v2 iterator throws on close (vs v1 snapshot
    // continuation)
    /**
     * Iterates entries from a v2 compressed SSTable by decompressing one block at a time. Only one
     * decompressed block is held in memory at any point — O(single block uncompressed size).
     *
     * <p>
     * Does not interact with the shared BlockCache — each scan maintains its own block buffer to
     * prevent cache pollution of point-get entries.
     */
    private final class CompressedBlockIterator implements Iterator<Entry> {
        private int currentBlockIndex;
        private List<Entry> blockEntries;
        private int entryIdx;
        private Entry next;

        CompressedBlockIterator() {
            assert compressionMap != null : "CompressedBlockIterator requires v2 reader";
            this.currentBlockIndex = 0;
            this.blockEntries = List.of();
            this.entryIdx = 0;
            advance();
        }

        private void advance() {
            next = null;
            if (closed) {
                throw new IllegalStateException("reader is closed");
            }
            while (true) {
                if (entryIdx < blockEntries.size()) {
                    next = blockEntries.get(entryIdx++);
                    return;
                }
                if (currentBlockIndex >= compressionMap.blockCount()) {
                    return;
                }
                // Decompress next block and parse entries
                try {
                    byte[] decompressed = readAndDecompressBlockNoCache(currentBlockIndex);
                    currentBlockIndex++;
                    int count = readBlockInt(decompressed, 0);
                    // Minimum encoded entry size: 1 (type) + 4 (keyLen) + 0 (key) +
                    // 8 (seqNum) + 4 (valLen) + 0 (val) = 17 bytes.
                    // Validate that the block is large enough to hold the claimed entries.
                    if (count < 0 || 4L + (long) count * 17 > decompressed.length) {
                        throw new IOException(
                                "corrupt block %d: entry count %d exceeds block capacity (%d bytes)"
                                        .formatted(currentBlockIndex - 1, count,
                                                decompressed.length));
                    }
                    blockEntries = new ArrayList<>(count);
                    int off = 4;
                    for (int i = 0; i < count; i++) {
                        Entry e = EntryCodec.decode(decompressed, off);
                        blockEntries.add(e);
                        off += EntryCodec.encodedSize(e);
                    }
                    entryIdx = 0;
                } catch (CorruptBlockException e) {
                    // Data corruption must propagate directly — it is not a transient I/O error
                    // and callers must be able to catch the specific type for diagnostics.
                    throw sneakyThrow(e);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        // @spec compression.streaming-decompression.R19 — signal mid-iteration reader close via
        // hasNext(); silently returning
        // false would let a for-each loop terminate without the caller noticing the close.
        @Override
        public boolean hasNext() {
            if (closed) {
                throw new IllegalStateException("reader is closed");
            }
            return next != null;
        }

        @Override
        public Entry next() {
            if (closed) {
                throw new IllegalStateException("reader is closed");
            }
            if (next == null) {
                throw new NoSuchElementException();
            }
            Entry result = next;
            advance();
            return result;
        }

        private static int readBlockInt(byte[] buf, int off) {
            return ((buf[off] & 0xFF) << 24) | ((buf[off + 1] & 0xFF) << 16)
                    | ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
        }
    }

    // @spec compression.streaming-decompression.R5 — caches most recently decompressed block,
    // reuses for same-block entries
    // @spec compression.streaming-decompression.R6 — replaces cached block when next entry is in
    // different block
    // @spec compression.streaming-decompression.R7 — cached block index initialized to -1
    // (sentinel, no valid block match)
    // @spec compression.streaming-decompression.R9 — range-scan bypasses shared BlockCache
    // @spec compression.streaming-decompression.R12 — v1 uses absolute file offsets, v2 uses block
    // cache path
    // @spec compression.streaming-decompression.R15 — decodes single entry at intra-block offset in
    // decompressed data
    // @spec compression.streaming-decompression.R17 — throws NoSuchElementException when no more
    // entries
    // @spec compression.streaming-decompression.R18 — wraps IOException in UncheckedIOException
    // @spec compression.streaming-decompression.R20 — detects closed reader, throws
    // IllegalStateException
    // @spec compression.streaming-decompression.R23 — single-block range decompresses block exactly
    // once
    // @spec compression.streaming-decompression.R25 — decompression failure propagates, cached
    // block not updated
    // @spec compression.streaming-decompression.R26 — independent iterators maintain own
    // cached-block state
    // @spec compression.streaming-decompression.R27 — channel reads synchronized (via
    // readLazyChannel)
    /**
     * Iterates entries via key index range results, reading each entry at its offset. Handles both
     * v1 (absolute file offset) and v2 (packed blockIndex + intraBlockOffset) formats.
     */
    private final class IndexRangeIterator implements Iterator<Entry> {
        private final Iterator<KeyIndex.Entry> indexIter;
        private Entry next;

        // v2 block cache: reuse the current decompressed block for consecutive entries
        private int cachedBlockIndex = -1;
        private byte[] cachedBlock = null;

        IndexRangeIterator(Iterator<KeyIndex.Entry> indexIter) {
            this.indexIter = indexIter;
            advance();
        }

        private void advance() {
            next = null;
            if (closed) {
                throw new IllegalStateException("reader is closed");
            }
            if (!indexIter.hasNext())
                return;
            KeyIndex.Entry ie = indexIter.next();
            try {
                if (compressionMap != null) {
                    // v2: unpack (blockIndex, intraBlockOffset) and decompress
                    long packed = ie.fileOffset();
                    int blockIndex = (int) (packed >>> 32);
                    int intraBlockOffset = (int) packed;
                    byte[] block;
                    if (blockIndex == cachedBlockIndex) {
                        block = cachedBlock;
                    } else {
                        block = readAndDecompressBlockNoCache(blockIndex);
                        cachedBlockIndex = blockIndex;
                        cachedBlock = block;
                    }
                    next = EntryCodec.decode(block, intraBlockOffset);
                } else {
                    // v1: read at absolute file offset
                    byte[] buf = readDataAtV1(ie.fileOffset(), SSTableFormat.DEFAULT_BLOCK_SIZE);
                    next = EntryCodec.decode(buf, 0);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean hasNext() {
            return !closed && next != null;
        }

        @Override
        public Entry next() {
            if (closed)
                throw new IllegalStateException("reader is closed");
            if (next == null)
                throw new NoSuchElementException();
            Entry result = next;
            advance();
            return result;
        }
    }
}
