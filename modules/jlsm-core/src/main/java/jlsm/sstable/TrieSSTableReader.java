package jlsm.sstable;

import jlsm.core.bloom.BloomFilter;
import jlsm.core.cache.BlockCache;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import jlsm.core.sstable.SSTableReader;
import jlsm.sstable.internal.CompressionMap;
import jlsm.sstable.internal.EntryCodec;
import jlsm.sstable.internal.KeyIndex;
import jlsm.sstable.internal.SSTableFormat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
import java.util.Optional;
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
// @spec F02.R35 — lazy reader synchronizes channel reads
// @spec F02.R43 — iterator after close is undefined (documented)
// @spec F08.R28 — decompressAllBlocks method removed (absent behavior)
// @spec F18.R19a — v4-capable reader also reads v1, v2, v3 via magic dispatch
// @spec F18.R19b,R19c — v4 section-ordering invariants + v3-style compression map
// @spec F18.R20,R20a,R21 — on-disk metadata determines codec for ID 0x03 (see override methods)
// @spec F18.R22 — v1-only reader on v4/v3/v2 file throws descriptive IOException
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

    // @spec F16.R15 — block size recorded at write time (v3+) or default (v1/v2)
    private final long blockSize;

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
    }

    /**
     * Returns the data-block size recorded at write time for this SSTable. For v3+ files this is
     * the value stored in the footer's blockSize field (validated during {@code open}). For v1 and
     * v2 files (which predate the footer blockSize field) this returns
     * {@link SSTableFormat#DEFAULT_BLOCK_SIZE} — the value v2 writers hardcoded.
     */
    // @spec F16.R15,R18 — exposes blockSize from footer (v3+) or 4096 default (v1/v2)
    public long blockSize() {
        return blockSize;
    }

    // ---- v1 factory methods (no compression) ----

    public static TrieSSTableReader open(Path path, BloomFilter.Deserializer bloomDeserializer)
            throws IOException {
        return open(path, bloomDeserializer, null);
    }

    // @spec F16.R19 — v1 path: no compression map, no decompression, no CRC32C (per F02 R15)
    public static TrieSSTableReader open(Path path, BloomFilter.Deserializer bloomDeserializer,
            BlockCache blockCache) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(bloomDeserializer, "bloomDeserializer must not be null");

        SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ);
        try {
            long fileSize = ch.size();
            Footer footer = readFooterV1(ch, fileSize);
            KeyIndex keyIndex = readKeyIndexV1(ch, footer);
            BloomFilter bloom = readBloomFilter(ch, footer, bloomDeserializer);
            SSTableMetadata meta = buildMetadata(path, fileSize, footer, keyIndex);

            // Read entire data region eagerly
            int dataLen = (int) footer.idxOffset;
            byte[] data = readBytes(ch, 0L, dataLen);
            ch.close();

            return new TrieSSTableReader(meta, keyIndex, bloom, footer.idxOffset, data, null,
                    blockCache, null, null, false, footer.blockSize);
        } catch (IOException e) {
            ch.close();
            throw e;
        } catch (Error e) {
            ch.close();
            throw e;
        }
    }

    public static TrieSSTableReader openLazy(Path path, BloomFilter.Deserializer bloomDeserializer)
            throws IOException {
        return openLazy(path, bloomDeserializer, null);
    }

    public static TrieSSTableReader openLazy(Path path, BloomFilter.Deserializer bloomDeserializer,
            BlockCache blockCache) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(bloomDeserializer, "bloomDeserializer must not be null");

        SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ);
        try {
            long fileSize = ch.size();
            Footer footer = readFooterV1(ch, fileSize);
            KeyIndex keyIndex = readKeyIndexV1(ch, footer);
            BloomFilter bloom = readBloomFilter(ch, footer, bloomDeserializer);
            SSTableMetadata meta = buildMetadata(path, fileSize, footer, keyIndex);

            return new TrieSSTableReader(meta, keyIndex, bloom, footer.idxOffset, null, ch,
                    blockCache, null, null, false, footer.blockSize);
        } catch (IOException e) {
            ch.close();
            throw e;
        } catch (Error e) {
            ch.close();
            throw e;
        }
    }

    // ---- v2 factory methods (with compression codec support) ----

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
    // @spec F02.R15 — detects v1 by magic, falls back to v1 reading
    // @spec F02.R16 — v1 reader on v2 file throws descriptive IOException
    // @spec F02.R17 — self-describing, no external config needed
    public static TrieSSTableReader open(Path path, BloomFilter.Deserializer bloomDeserializer,
            BlockCache blockCache, CompressionCodec... codecs) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(bloomDeserializer, "bloomDeserializer must not be null");
        Objects.requireNonNull(codecs, "codecs must not be null");

        SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ);
        try {
            long fileSize = ch.size();
            Footer footer = readFooter(ch, fileSize);

            CompressionMap compressionMap = null;
            Map<Byte, CompressionCodec> codecMap = null;
            long dataEnd;
            KeyIndex keyIndex;
            boolean checksums = false;

            if (footer.version >= 2) {
                // v4 uses v3-style compression map (21-byte entries with CRC32C)
                int mapVersion = footer.version >= 4 ? 3 : footer.version;
                byte[] mapBytes = readBytes(ch, footer.mapOffset, (int) footer.mapLength);
                compressionMap = CompressionMap.deserialize(mapBytes, mapVersion);
                codecMap = buildCodecMap(codecs);

                // @spec F18.R20a,R21 — file on-disk metadata determines ZSTD decompression
                // configuration for codec ID 0x03, not the caller-provided codec list.
                // v4 with dictionary: replace with dictionary-bound codec from meta-block.
                // v3 or earlier (no dictionary): inject plain ZSTD codec.
                if (footer.version >= 4 && footer.dictLength > 0) {
                    codecMap = overrideWithDictionaryCodec(ch, footer, codecMap);
                } else {
                    codecMap = overrideWithPlainZstdCodec(codecMap);
                }

                validateCodecMap(compressionMap, codecMap);
                keyIndex = readKeyIndexV2(ch, footer, compressionMap.blockCount());
                dataEnd = footer.mapOffset;
                checksums = footer.version >= 3;
            } else {
                keyIndex = readKeyIndexV1(ch, footer);
                dataEnd = footer.idxOffset;
            }

            BloomFilter bloom = readBloomFilter(ch, footer, bloomDeserializer);
            SSTableMetadata meta = buildMetadata(path, fileSize, footer, keyIndex);

            int dataLen = (int) dataEnd;
            byte[] data = readBytes(ch, 0L, dataLen);
            ch.close();

            return new TrieSSTableReader(meta, keyIndex, bloom, dataEnd, data, null, blockCache,
                    compressionMap, codecMap, checksums, footer.blockSize);
        } catch (IOException e) {
            ch.close();
            throw e;
        } catch (RuntimeException e) {
            ch.close();
            throw e;
        } catch (Error e) {
            ch.close();
            throw e;
        }
    }

    /**
     * Opens an SSTable file lazily, with support for v1, v2, v3, and v4 (compressed) formats.
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

            CompressionMap compressionMap = null;
            Map<Byte, CompressionCodec> codecMap = null;
            long dataEnd;
            KeyIndex keyIndex;
            boolean checksums = false;

            if (footer.version >= 2) {
                int mapVersion = footer.version >= 4 ? 3 : footer.version;
                byte[] mapBytes = readBytes(ch, footer.mapOffset, (int) footer.mapLength);
                compressionMap = CompressionMap.deserialize(mapBytes, mapVersion);
                codecMap = buildCodecMap(codecs);

                // @spec F18.R20a,R21 — file metadata wins for codec ID 0x03: dict-bound
                // for v4+dict, plain ZSTD for v3 or earlier. Caller's codec for 0x03 is ignored.
                if (footer.version >= 4 && footer.dictLength > 0) {
                    codecMap = overrideWithDictionaryCodec(ch, footer, codecMap);
                } else {
                    codecMap = overrideWithPlainZstdCodec(codecMap);
                }

                validateCodecMap(compressionMap, codecMap);
                keyIndex = readKeyIndexV2(ch, footer, compressionMap.blockCount());
                dataEnd = footer.mapOffset;
                checksums = footer.version >= 3;
            } else {
                keyIndex = readKeyIndexV1(ch, footer);
                dataEnd = footer.idxOffset;
            }

            BloomFilter bloom = readBloomFilter(ch, footer, bloomDeserializer);
            SSTableMetadata meta = buildMetadata(path, fileSize, footer, keyIndex);

            return new TrieSSTableReader(meta, keyIndex, bloom, dataEnd, null, ch, blockCache,
                    compressionMap, codecMap, checksums, footer.blockSize);
        } catch (IOException e) {
            ch.close();
            throw e;
        } catch (RuntimeException e) {
            ch.close();
            throw e;
        } catch (Error e) {
            ch.close();
            throw e;
        }
    }

    // ---- SSTableReader interface ----

    @Override
    public SSTableMetadata metadata() {
        return metadata;
    }

    // @spec F08.R13 — get() uses readAndDecompressBlock (with BlockCache), not streaming path
    @Override
    public Optional<Entry> get(MemorySegment key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();

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
    // @spec F02.R43 — iterator behavior after close is undefined; documented in public API
    // @spec F08.R1 — v2 scan returns lazy iterator, not upfront decompression
    // @spec F08.R11 — v1 scan uses existing DataRegionIterator unchanged
    @Override
    public Iterator<Entry> scan() throws IOException {
        checkNotClosed();
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
    // @spec F02.R43 — iterator behavior after close is undefined; documented in public API
    // @spec F08.R12 — v1 uses absolute offsets/readDataAtV1; v2 uses IndexRangeIterator with block
    // cache
    @Override
    public Iterator<Entry> scan(MemorySegment fromKey, MemorySegment toKey) throws IOException {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        checkNotClosed();
        return new IndexRangeIterator(keyIndex.rangeIterator(fromKey, toKey));
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
    // @spec F02.R43 — iterator behavior after close is undefined; documented in public API
    @Override
    public void close() throws IOException {
        if (!CLOSED.compareAndSet(this, false, true))
            return;
        if (lazyChannel != null) {
            lazyChannel.close();
        }
    }

    // ---- v2 compression helpers ----

    // @spec F02.R36 — cache stores decompressed blocks; decompress before cache, cache hit returns
    // decompressed
    // @spec F02.R40 — runtime checks for untrusted on-disk data, not assertions
    // @spec F02.R41 — corrupt blocks produce IOException
    // @spec F17.R38 — MemorySegment decompress, no byte[] conversion
    // @spec F17.R39 — compression map format unchanged
    /**
     * Reads and decompresses a single block by index using the compression map.
     */
    // @spec F16.R6,R7,R8 — CRC32C verify before decompress; cache hit skips; skipped when v2
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

    // @spec F08.R10 — same decompression logic as readAndDecompressBlock, no BlockCache get/put
    // @spec F08.R8,R9 — scan iterators bypass shared BlockCache via this method
    /**
     * Reads and decompresses a single block by index, bypassing the BlockCache. Used by scan
     * iterators to avoid polluting the shared cache with sequential reads.
     */
    // @spec F16.R7 — scan paths verify CRC32C on every disk read (no BlockCache coupling)
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

    // @spec F02.R42 — trailing bytes in compression map/deflate silently ignored (documented)
    /**
     * Verifies the CRC32C checksum of compressed block bytes.
     *
     * @throws CorruptBlockException if the computed checksum does not match the expected value
     */
    // @spec F16.R5,R6,R9 — java.util.zip.CRC32C over on-disk bytes; mismatch →
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

    // @spec F02.R18 — explicit duplicate codec ID rejection
    // @spec F02.R19 — auto-includes NONE codec
    // @spec F02.R21 — null codec element rejected with index
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
    // @spec F18.R20,R20a — v4 with dictLength > 0: replace caller's codec for ID 0x03 with
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
    // @spec F18.R20a,R21 — v3 or earlier: reader always uses plain ZSTD for ID 0x03
    private static Map<Byte, CompressionCodec> overrideWithPlainZstdCodec(
            Map<Byte, CompressionCodec> originalMap) {
        Map<Byte, CompressionCodec> mutableMap = new HashMap<>(originalMap);
        mutableMap.put((byte) 0x03, CompressionCodec.zstd());
        return Collections.unmodifiableMap(mutableMap);
    }

    // @spec F02.R20 — unknown codec ID throws IOException
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

    // @spec F02.R29 — non-negative offset/length validation
    // @spec F02.R30 — long-to-int truncation guarded
    // @spec F02.R31 — all offsets as long, no int narrowing
    // @spec F02.R34 — section overlap detection
    // @spec F16.R15 — v3+ footer carries blockSize; v1/v2 populate with 4096 (pre-v3 default)
    private record Footer(int version, long mapOffset, long mapLength, long dictOffset,
            long dictLength, long idxOffset, long idxLength, long fltOffset, long fltLength,
            long entryCount, long blockSize) {

        /**
         * Validates footer field consistency against the file size. All offsets and lengths must be
         * non-negative and within file bounds.
         *
         * @throws IOException if any field is invalid
         */
        // @spec F16.R20,R21 — non-negative offsets/lengths, section-ordering, all IOException
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

    // @spec F02.R15 — detects v1 by magic, falls back to v1 reading
    // @spec F02.R16 — v1 reader on v2 file throws descriptive IOException
    /** Reads a v1-only footer. Throws on v2 magic. */
    private static Footer readFooterV1(SeekableByteChannel ch, long fileSize) throws IOException {
        if (fileSize < SSTableFormat.FOOTER_SIZE) {
            throw new IOException("not a valid SSTable file: too small");
        }
        byte[] buf = readBytes(ch, fileSize - SSTableFormat.FOOTER_SIZE, SSTableFormat.FOOTER_SIZE);
        long idxOffset = readLong(buf, 0);
        long idxLength = readLong(buf, 8);
        long fltOffset = readLong(buf, 16);
        long fltLength = readLong(buf, 24);
        long entryCount = readLong(buf, 32);
        long magic = readLong(buf, 40);
        if (magic == SSTableFormat.MAGIC_V2 || magic == SSTableFormat.MAGIC_V3
                || magic == SSTableFormat.MAGIC_V4) {
            String detectedVersion = magic == SSTableFormat.MAGIC_V2 ? "v2"
                    : (magic == SSTableFormat.MAGIC_V3 ? "v3" : "v4");
            throw new IOException("SSTable file is " + detectedVersion
                    + " format — use the open/openLazy overload that accepts CompressionCodec");
        }
        if (magic != SSTableFormat.MAGIC) {
            // Re-read final 8 bytes in case the file is longer than a v1 footer (v3+)
            byte[] magicBuf = readBytes(ch, fileSize - 8, 8);
            long trueMagic = readLong(magicBuf, 0);
            if (trueMagic == SSTableFormat.MAGIC_V2 || trueMagic == SSTableFormat.MAGIC_V3
                    || trueMagic == SSTableFormat.MAGIC_V4) {
                String detectedVersion = trueMagic == SSTableFormat.MAGIC_V2 ? "v2"
                        : (trueMagic == SSTableFormat.MAGIC_V3 ? "v3" : "v4");
                throw new IOException("SSTable file is " + detectedVersion
                        + " format — use the open/openLazy overload that accepts CompressionCodec");
            }
            throw new IOException("not a valid SSTable file: bad magic " + Long.toHexString(magic));
        }
        Footer footer = new Footer(1, 0, 0, 0, 0, idxOffset, idxLength, fltOffset, fltLength,
                entryCount, SSTableFormat.DEFAULT_BLOCK_SIZE);
        footer.validate(fileSize);
        return footer;
    }

    /** Reads footer detecting v1, v2, or v3 format from magic bytes. */
    // @spec F16.R17,R20,R21 — magic-based version dispatch; validates blockSize + section ordering
    private static Footer readFooter(SeekableByteChannel ch, long fileSize) throws IOException {
        if (fileSize < SSTableFormat.FOOTER_SIZE) {
            throw new IOException("not a valid SSTable file: too small");
        }
        // Read the last 8 bytes to check magic
        byte[] magicBuf = readBytes(ch, fileSize - 8, 8);
        long magic = readLong(magicBuf, 0);

        if (magic == SSTableFormat.MAGIC_V4) {
            if (fileSize < SSTableFormat.FOOTER_SIZE_V4) {
                throw new IOException("not a valid v4 SSTable file: too small");
            }
            byte[] buf = readBytes(ch, fileSize - SSTableFormat.FOOTER_SIZE_V4,
                    SSTableFormat.FOOTER_SIZE_V4);
            long mapOffset = readLong(buf, 0);
            long mapLength = readLong(buf, 8);
            long dictOffset = readLong(buf, 16);
            long dictLength = readLong(buf, 24);
            long idxOffset = readLong(buf, 32);
            long idxLength = readLong(buf, 40);
            long fltOffset = readLong(buf, 48);
            long fltLength = readLong(buf, 56);
            long entryCount = readLong(buf, 64);
            long rawBlockSize = readLong(buf, 72);
            // Validate blockSize
            if (rawBlockSize < SSTableFormat.MIN_BLOCK_SIZE
                    || rawBlockSize > SSTableFormat.MAX_BLOCK_SIZE
                    || (rawBlockSize & (rawBlockSize - 1)) != 0) {
                throw new IOException(
                        "corrupt v4 SSTable footer: invalid blockSize %d".formatted(rawBlockSize));
            }
            // R19b: Validate v4 section ordering
            if (mapLength > 0 && dictLength > 0 && mapOffset + mapLength > dictOffset) {
                throw new IOException(
                        "corrupt v4 SSTable footer: map section [%d, %d) overlaps dict section at %d"
                                .formatted(mapOffset, mapOffset + mapLength, dictOffset));
            }
            if (dictLength > 0 && dictOffset + dictLength > idxOffset) {
                throw new IOException(
                        "corrupt v4 SSTable footer: dict section [%d, %d) overlaps idx section at %d"
                                .formatted(dictOffset, dictOffset + dictLength, idxOffset));
            }
            long footerStart = fileSize - SSTableFormat.FOOTER_SIZE_V4;
            if (fltOffset + fltLength > footerStart) {
                throw new IOException(
                        "corrupt v4 SSTable footer: flt section [%d, %d) overlaps footer at %d"
                                .formatted(fltOffset, fltOffset + fltLength, footerStart));
            }
            Footer footer = new Footer(4, mapOffset, mapLength, dictOffset, dictLength, idxOffset,
                    idxLength, fltOffset, fltLength, entryCount, rawBlockSize);
            footer.validate(fileSize);
            return footer;
        } else if (magic == SSTableFormat.MAGIC_V3) {
            if (fileSize < SSTableFormat.FOOTER_SIZE_V3) {
                throw new IOException("not a valid v3 SSTable file: too small");
            }
            byte[] buf = readBytes(ch, fileSize - SSTableFormat.FOOTER_SIZE_V3,
                    SSTableFormat.FOOTER_SIZE_V3);
            long mapOffset = readLong(buf, 0);
            long mapLength = readLong(buf, 8);
            long idxOffset = readLong(buf, 16);
            long idxLength = readLong(buf, 24);
            long fltOffset = readLong(buf, 32);
            long fltLength = readLong(buf, 40);
            long entryCount = readLong(buf, 48);
            long rawBlockSize = readLong(buf, 56);
            // Validate blockSize: must be in [MIN_BLOCK_SIZE, MAX_BLOCK_SIZE] and a power of 2
            if (rawBlockSize < SSTableFormat.MIN_BLOCK_SIZE
                    || rawBlockSize > SSTableFormat.MAX_BLOCK_SIZE
                    || (rawBlockSize & (rawBlockSize - 1)) != 0) {
                throw new IOException(
                        "corrupt v3 SSTable footer: invalid blockSize %d".formatted(rawBlockSize));
            }
            // Validate section ordering with FOOTER_SIZE_V3
            long footerStart = fileSize - SSTableFormat.FOOTER_SIZE_V3;
            if (fltOffset + fltLength > footerStart) {
                throw new IOException(
                        "corrupt v3 SSTable footer: flt section [%d, %d) overlaps footer at %d"
                                .formatted(fltOffset, fltOffset + fltLength, footerStart));
            }
            Footer footer = new Footer(3, mapOffset, mapLength, 0, 0, idxOffset, idxLength,
                    fltOffset, fltLength, entryCount, rawBlockSize);
            footer.validate(fileSize);
            return footer;
        } else if (magic == SSTableFormat.MAGIC_V2) {
            if (fileSize < SSTableFormat.FOOTER_SIZE_V2) {
                throw new IOException("not a valid v2 SSTable file: too small");
            }
            byte[] buf = readBytes(ch, fileSize - SSTableFormat.FOOTER_SIZE_V2,
                    SSTableFormat.FOOTER_SIZE_V2);
            long mapOffset = readLong(buf, 0);
            long mapLength = readLong(buf, 8);
            long idxOffset = readLong(buf, 16);
            long idxLength = readLong(buf, 24);
            long fltOffset = readLong(buf, 32);
            long fltLength = readLong(buf, 40);
            long entryCount = readLong(buf, 48);
            // @spec F16.R18 — v2 files predate the block-size footer field; writers hardcoded 4096
            Footer footer = new Footer(2, mapOffset, mapLength, 0, 0, idxOffset, idxLength,
                    fltOffset, fltLength, entryCount, SSTableFormat.DEFAULT_BLOCK_SIZE);
            footer.validate(fileSize);
            return footer;
        } else if (magic == SSTableFormat.MAGIC) {
            byte[] buf = readBytes(ch, fileSize - SSTableFormat.FOOTER_SIZE,
                    SSTableFormat.FOOTER_SIZE);
            long idxOffset = readLong(buf, 0);
            long idxLength = readLong(buf, 8);
            long fltOffset = readLong(buf, 16);
            long fltLength = readLong(buf, 24);
            long entryCount = readLong(buf, 32);
            Footer footer = new Footer(1, 0, 0, 0, 0, idxOffset, idxLength, fltOffset, fltLength,
                    entryCount, SSTableFormat.DEFAULT_BLOCK_SIZE);
            footer.validate(fileSize);
            return footer;
        } else {
            throw new IOException("not a valid SSTable file: bad magic " + Long.toHexString(magic));
        }
    }

    /** Reads v1 key index: [numKeys(4)][per key: keyLen(4) + key + fileOffset(8)]. */
    private static KeyIndex readKeyIndexV1(SeekableByteChannel ch, Footer footer)
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
            long fileOffset = readLong(buf, off);
            off += 8;
            keys.add(MemorySegment.ofArray(keyBytes));
            offsets.add(fileOffset);
        }
        return new KeyIndex(keys, offsets);
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
    // @spec F02.R33 — validate blockIndex in [0, blockCount) and intraBlockOffset >= 0 at read
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

    // @spec F02.R35 — lazy reader synchronizes channel reads
    /**
     * Reads {@code length} bytes from the channel starting at {@code offset}.
     *
     * <p>
     * The position-then-read sequence is synchronized on the channel to prevent concurrent callers
     * (e.g., multiple threads sharing a lazy reader) from interleaving position and read calls,
     * which would produce silently corrupt data (C2-F18).
     */
    private static byte[] readBytes(SeekableByteChannel ch, long offset, int length)
            throws IOException {
        if (length == 0)
            return new byte[0];
        ByteBuffer buf = ByteBuffer.allocate(length);
        synchronized (ch) {
            ch.position(offset);
            while (buf.hasRemaining()) {
                int read = ch.read(buf);
                if (read < 0)
                    throw new IOException("unexpected EOF at offset " + (offset + buf.position()));
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

    // @spec F08.R1 — decompresses blocks incrementally as iteration advances
    // @spec F08.R2 — holds at most one decompressed block in memory
    // @spec F08.R3 — decompresses blocks in ascending block-index order
    // @spec F08.R4 — yields entries in same order as DataRegionIterator
    // @spec F08.R8 — full-scan bypasses shared BlockCache
    // @spec F08.R14 — reads 4-byte big-endian entry count, decodes exactly that many entries
    // @spec F08.R16 — hasNext returns false after last entry of last block
    // @spec F08.R17 — throws NoSuchElementException when no more entries
    // @spec F08.R18 — wraps IOException in UncheckedIOException
    // @spec F08.R19 — detects closed reader, throws IllegalStateException
    // @spec F08.R21 — zero blocks yields immediate hasNext()==false
    // @spec F08.R22 — single block decompresses, yields entries, then hasNext()==false
    // @spec F08.R24 — decompression failure propagates as UncheckedIOException, no skip
    // @spec F08.R26 — independent iterators maintain own block-index position
    // @spec F08.R29 — v2 iterator throws on close (vs v1 snapshot continuation)
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

        // @spec F08.R19 — signal mid-iteration reader close via hasNext(); silently returning
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

    // @spec F08.R5 — caches most recently decompressed block, reuses for same-block entries
    // @spec F08.R6 — replaces cached block when next entry is in different block
    // @spec F08.R7 — cached block index initialized to -1 (sentinel, no valid block match)
    // @spec F08.R9 — range-scan bypasses shared BlockCache
    // @spec F08.R12 — v1 uses absolute file offsets, v2 uses block cache path
    // @spec F08.R15 — decodes single entry at intra-block offset in decompressed data
    // @spec F08.R17 — throws NoSuchElementException when no more entries
    // @spec F08.R18 — wraps IOException in UncheckedIOException
    // @spec F08.R20 — detects closed reader, throws IllegalStateException
    // @spec F08.R23 — single-block range decompresses block exactly once
    // @spec F08.R25 — decompression failure propagates, cached block not updated
    // @spec F08.R26 — independent iterators maintain own cached-block state
    // @spec F08.R27 — channel reads synchronized (via readLazyChannel)
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
