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

    // v2 compression support (null for v1)
    private final CompressionMap compressionMap;
    private final Map<Byte, CompressionCodec> codecMap;

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

    private TrieSSTableReader(SSTableMetadata metadata, KeyIndex keyIndex, BloomFilter bloomFilter,
            long dataEnd, byte[] eagerData, SeekableByteChannel lazyChannel, BlockCache blockCache,
            CompressionMap compressionMap, Map<Byte, CompressionCodec> codecMap) {
        this.metadata = metadata;
        this.keyIndex = keyIndex;
        this.bloomFilter = bloomFilter;
        this.dataEnd = dataEnd;
        this.eagerData = eagerData;
        this.lazyChannel = lazyChannel;
        this.blockCache = blockCache;
        this.compressionMap = compressionMap;
        this.codecMap = codecMap;
    }

    // ---- v1 factory methods (no compression) ----

    public static TrieSSTableReader open(Path path, BloomFilter.Deserializer bloomDeserializer)
            throws IOException {
        return open(path, bloomDeserializer, null);
    }

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
                    blockCache, null, null);
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
                    blockCache, null, null);
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

            if (footer.version == 2) {
                byte[] mapBytes = readBytes(ch, footer.mapOffset, (int) footer.mapLength);
                compressionMap = CompressionMap.deserialize(mapBytes);
                codecMap = buildCodecMap(codecs);
                validateCodecMap(compressionMap, codecMap);
                keyIndex = readKeyIndexV2(ch, footer);
                dataEnd = footer.mapOffset;
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
                    compressionMap, codecMap);
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
     * Opens an SSTable file lazily, with support for both v1 and v2 (compressed) formats.
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

            if (footer.version == 2) {
                byte[] mapBytes = readBytes(ch, footer.mapOffset, (int) footer.mapLength);
                compressionMap = CompressionMap.deserialize(mapBytes);
                codecMap = buildCodecMap(codecs);
                validateCodecMap(compressionMap, codecMap);
                keyIndex = readKeyIndexV2(ch, footer);
                dataEnd = footer.mapOffset;
            } else {
                keyIndex = readKeyIndexV1(ch, footer);
                dataEnd = footer.idxOffset;
            }

            BloomFilter bloom = readBloomFilter(ch, footer, bloomDeserializer);
            SSTableMetadata meta = buildMetadata(path, fileSize, footer, keyIndex);

            return new TrieSSTableReader(meta, keyIndex, bloom, dataEnd, null, ch, blockCache,
                    compressionMap, codecMap);
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

    @Override
    public Iterator<Entry> scan(MemorySegment fromKey, MemorySegment toKey) throws IOException {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        checkNotClosed();
        return new IndexRangeIterator(keyIndex.rangeIterator(fromKey, toKey));
    }

    @Override
    public void close() throws IOException {
        if (!CLOSED.compareAndSet(this, false, true))
            return;
        if (lazyChannel != null) {
            lazyChannel.close();
        }
    }

    // ---- v2 compression helpers ----

    /**
     * Reads and decompresses a single block by index using the compression map.
     */
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

        CompressionCodec codec = codecMap.get(mapEntry.codecId());
        if (codec == null) {
            throw new IOException("codec not found for ID 0x%02x in block %d"
                    .formatted(mapEntry.codecId(), blockIndex));
        }
        byte[] decompressed = codec.decompress(compressed, 0, compressed.length,
                mapEntry.uncompressedSize());
        if (decompressed.length != mapEntry.uncompressedSize()) {
            throw new IOException("block %d decompression size mismatch: got %d bytes, expected %d"
                    .formatted(blockIndex, decompressed.length, mapEntry.uncompressedSize()));
        }

        if (blockCache != null && !closed) {
            blockCache.put(metadata.id(), blockIndex, MemorySegment.ofArray(decompressed));
        }

        return decompressed;
    }

    /**
     * Reads and decompresses a single block by index, bypassing the BlockCache. Used by scan
     * iterators to avoid polluting the shared cache with sequential reads.
     */
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

        CompressionCodec codec = codecMap.get(mapEntry.codecId());
        if (codec == null) {
            throw new IOException("codec not found for ID 0x%02x in block %d"
                    .formatted(mapEntry.codecId(), blockIndex));
        }
        byte[] decompressed = codec.decompress(compressed, 0, compressed.length,
                mapEntry.uncompressedSize());
        if (decompressed.length != mapEntry.uncompressedSize()) {
            throw new IOException("block %d decompression size mismatch: got %d bytes, expected %d"
                    .formatted(blockIndex, decompressed.length, mapEntry.uncompressedSize()));
        }
        return decompressed;
    }

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

    // ---- Footer / index / filter loading ----

    private record Footer(int version, long mapOffset, long mapLength, long idxOffset,
            long idxLength, long fltOffset, long fltLength, long entryCount) {

        /**
         * Validates footer field consistency against the file size. All offsets and lengths must be
         * non-negative and within file bounds.
         *
         * @throws IOException if any field is invalid
         */
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
            if (version == 2) {
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
            if (version == 2 && mapOffset + mapLength > fileSize) {
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
            if (version == 2 && idxLength > 0 && mapOffset + mapLength > idxOffset) {
                throw new IOException(
                        "corrupt SSTable footer: map section [%d, %d) overlaps idx section at offset %d"
                                .formatted(mapOffset, mapOffset + mapLength, idxOffset));
            }
        }
    }

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
        if (magic == SSTableFormat.MAGIC_V2) {
            throw new IOException(
                    "SSTable file is v2 format — use the open/openLazy overload that accepts CompressionCodec");
        }
        if (magic != SSTableFormat.MAGIC) {
            throw new IOException("not a valid SSTable file: bad magic " + Long.toHexString(magic));
        }
        Footer footer = new Footer(1, 0, 0, idxOffset, idxLength, fltOffset, fltLength, entryCount);
        footer.validate(fileSize);
        return footer;
    }

    /** Reads footer detecting v1 or v2 format from magic bytes. */
    private static Footer readFooter(SeekableByteChannel ch, long fileSize) throws IOException {
        if (fileSize < SSTableFormat.FOOTER_SIZE) {
            throw new IOException("not a valid SSTable file: too small");
        }
        // Read the last 8 bytes to check magic
        byte[] magicBuf = readBytes(ch, fileSize - 8, 8);
        long magic = readLong(magicBuf, 0);

        if (magic == SSTableFormat.MAGIC_V2) {
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
            Footer footer = new Footer(2, mapOffset, mapLength, idxOffset, idxLength, fltOffset,
                    fltLength, entryCount);
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
            Footer footer = new Footer(1, 0, 0, idxOffset, idxLength, fltOffset, fltLength,
                    entryCount);
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

    /** Reads v2 key index: [numKeys(4)][per key: keyLen(4) + key + blockIndex(4) + intraOff(4)]. */
    private static KeyIndex readKeyIndexV2(SeekableByteChannel ch, Footer footer)
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
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        @Override
        public boolean hasNext() {
            return !closed && next != null;
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
