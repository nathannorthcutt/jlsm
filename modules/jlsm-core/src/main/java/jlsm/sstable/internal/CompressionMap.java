package jlsm.sstable.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Compression offset map for SSTable v2 and v3 formats.
 *
 * <p>
 * Maps each data block to its on-disk position, compressed size, uncompressed size, the codec ID
 * used for compression, and (v3 only) a CRC32C checksum. This metadata is stored as a contiguous
 * section in the SSTable file between the data blocks and the key index, and is loaded eagerly at
 * reader open time.
 *
 * <h3>v2 binary format (17 bytes per entry)</h3>
 *
 * <pre>
 *   [blockCount — 4 bytes, big-endian int]
 *   [entries × blockCount]:
 *     blockOffset      — 8 bytes, big-endian long
 *     compressedSize   — 4 bytes, big-endian int
 *     uncompressedSize — 4 bytes, big-endian int
 *     codecId          — 1 byte
 *   Total per entry: 17 bytes
 * </pre>
 *
 * <h3>v3 binary format (21 bytes per entry)</h3>
 *
 * <pre>
 *   [blockCount — 4 bytes, big-endian int]
 *   [entries × blockCount]:
 *     blockOffset      — 8 bytes, big-endian long
 *     compressedSize   — 4 bytes, big-endian int
 *     uncompressedSize — 4 bytes, big-endian int
 *     codecId          — 1 byte
 *     checksum         — 4 bytes, big-endian int (CRC32C)
 *   Total per entry: 21 bytes
 * </pre>
 *
 * @see <a href="../../.decisions/sstable-block-compression-format/adr.md">ADR: SSTable Block
 *      Compression Format</a>
 */
// @spec sstable.format-v2.R3 — 17-byte entries: offset(8) + compressedSize(4) + uncompressedSize(4)
// + codecId(1)
// @spec sstable.v3-format-upgrade.R1,R3,R22 — v3 adds 4-byte CRC32C checksum; entry record accepts
// full signed int range
public final class CompressionMap {

    /** Size in bytes of a single compression map entry. */
    public static final int ENTRY_SIZE = 17;

    /** Known valid codec IDs: 0x00 = NoneCodec, 0x02 = DeflateCodec, 0x03 = ZstdCodec. */
    private static final Set<Byte> KNOWN_CODEC_IDS = Set.of((byte) 0x00, (byte) 0x02, (byte) 0x03);

    /**
     * A single entry in the compression map describing one data block.
     *
     * @param blockOffset absolute file offset of the compressed block data
     * @param compressedSize size of the block as stored on disk (compressed)
     * @param uncompressedSize original size of the block before compression
     * @param codecId identifier of the codec used (0x00 = none, 0x02 = deflate)
     * @param checksum CRC32C checksum of the on-disk block bytes (v3 only; 0 for v2)
     */
    // @spec sstable.format-v2.R12 — rejects negative offset, sizes
    // @spec sstable.format-v2.R13 — rejects impossible size combinations
    public record Entry(long blockOffset, int compressedSize, int uncompressedSize, byte codecId,
            int checksum) {
        public Entry {
            if (blockOffset < 0) {
                throw new IllegalArgumentException(
                        "blockOffset must be non-negative, got: " + blockOffset);
            }
            if (compressedSize < 0) {
                throw new IllegalArgumentException(
                        "compressedSize must be non-negative, got: " + compressedSize);
            }
            if (uncompressedSize < 0) {
                throw new IllegalArgumentException(
                        "uncompressedSize must be non-negative, got: " + uncompressedSize);
            }
            if (!KNOWN_CODEC_IDS.contains(codecId)) {
                throw new IllegalArgumentException(
                        "unknown codecId: 0x%02x (known: 0x00=none, 0x02=deflate, 0x03=zstd)"
                                .formatted(codecId));
            }
            // C1-F8: Reject physically impossible size combinations.
            // compressedSize=0 with uncompressedSize>0 means "decompress nothing into something"
            // — no codec can do this. The reverse (compressedSize>0, uncompressedSize=0) with a
            // non-none codec is similarly invalid.
            if (compressedSize == 0 && uncompressedSize > 0) {
                throw new IllegalArgumentException(
                        "compressedSize=0 with uncompressedSize=%d is physically impossible"
                                .formatted(uncompressedSize));
            }
            if (uncompressedSize == 0 && compressedSize > 0 && codecId != 0x00) {
                throw new IllegalArgumentException(
                        "uncompressedSize=0 with compressedSize=%d and codecId=0x%02x is invalid"
                                .formatted(compressedSize, codecId));
            }
        }

        /**
         * Backward-compatible constructor for v2 entries (checksum defaults to 0).
         */
        public Entry(long blockOffset, int compressedSize, int uncompressedSize, byte codecId) {
            this(blockOffset, compressedSize, uncompressedSize, codecId, 0);
        }
    }

    private final List<Entry> entries;

    /**
     * Creates a compression map from the given entries.
     *
     * @param entries list of per-block compression entries; must not be null
     * @throws NullPointerException if entries is null
     */
    public CompressionMap(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        this.entries = List.copyOf(entries);
    }

    /**
     * Returns the list of per-block entries.
     *
     * @return unmodifiable list of entries
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Returns the entry at the given block index.
     *
     * @param blockIndex zero-based block index
     * @return the entry for that block
     * @throws IndexOutOfBoundsException if blockIndex is out of range
     */
    public Entry entry(int blockIndex) {
        return entries.get(blockIndex);
    }

    /**
     * Returns the number of blocks in this map.
     *
     * @return block count
     */
    public int blockCount() {
        return entries.size();
    }

    /**
     * Serializes this compression map to its binary representation.
     *
     * @return byte array in the format described in the class javadoc
     */
    // @spec sstable.format-v2.R14 — long arithmetic for size calc, rejects overflow
    public byte[] serialize() {
        long longSize = 4L + (long) entries.size() * ENTRY_SIZE;
        if (longSize > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "compression map too large to serialize: %d entries require %d bytes"
                            .formatted(entries.size(), longSize));
        }
        int size = (int) longSize;
        byte[] buf = new byte[size];
        int off = 0;
        off = writeInt(buf, off, entries.size());
        for (Entry e : entries) {
            off = writeLong(buf, off, e.blockOffset());
            off = writeInt(buf, off, e.compressedSize());
            off = writeInt(buf, off, e.uncompressedSize());
            buf[off++] = e.codecId();
        }
        assert off == size : "serialized size mismatch: expected " + size + ", got " + off;
        return buf;
    }

    /**
     * Serializes this compression map to v3 binary representation (21-byte entries with CRC32C
     * checksum).
     *
     * @return byte array in v3 format
     */
    // @spec sstable.v3-format-upgrade.R1,R2 — v3 serialization: 21-byte entries with CRC32C, long
    // arithmetic on overflow
    public byte[] serializeV3() {
        long longSize = 4L + (long) entries.size() * SSTableFormat.COMPRESSION_MAP_ENTRY_SIZE_V3;
        if (longSize > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "compression map too large to serialize: %d entries require %d bytes"
                            .formatted(entries.size(), longSize));
        }
        int size = (int) longSize;
        byte[] buf = new byte[size];
        int off = 0;
        off = writeInt(buf, off, entries.size());
        for (Entry e : entries) {
            off = writeLong(buf, off, e.blockOffset());
            off = writeInt(buf, off, e.compressedSize());
            off = writeInt(buf, off, e.uncompressedSize());
            buf[off++] = e.codecId();
            off = writeInt(buf, off, e.checksum());
        }
        assert off == size : "serialized size mismatch: expected " + size + ", got " + off;
        return buf;
    }

    /**
     * Deserializes a compression map from its binary representation (v2 format, 17-byte entries).
     *
     * @param data byte array in the format described in the class javadoc
     * @return the deserialized compression map
     * @throws IllegalArgumentException if the data is malformed
     * @throws NullPointerException if data is null
     */
    // @spec sstable.format-v2.R15 — rejects negative block count
    public static CompressionMap deserialize(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        if (data.length < 4) {
            throw new IllegalArgumentException(
                    "compression map data too short: %d bytes (minimum 4)".formatted(data.length));
        }
        int blockCount = readInt(data, 0);
        if (blockCount < 0) {
            throw new IllegalArgumentException("negative block count: " + blockCount);
        }
        // Use long arithmetic to avoid integer overflow: blockCount * ENTRY_SIZE can
        // overflow int when blockCount is large (e.g., Integer.MAX_VALUE * 17 wraps negative).
        long expectedLength = 4L + (long) blockCount * ENTRY_SIZE;
        if (expectedLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "block count %d requires %d bytes, exceeds maximum array size"
                            .formatted(blockCount, expectedLength));
        }
        if (data.length < expectedLength) {
            throw new IllegalArgumentException(
                    "compression map data too short: %d bytes (expected %d for %d blocks)"
                            .formatted(data.length, expectedLength, blockCount));
        }
        if (data.length > expectedLength) {
            throw new IllegalArgumentException(
                    "compression map data has trailing bytes: %d bytes (expected %d for %d blocks)"
                            .formatted(data.length, expectedLength, blockCount));
        }
        List<Entry> entries = new ArrayList<>(blockCount);
        int off = 4;
        for (int i = 0; i < blockCount; i++) {
            long blockOffset = readLong(data, off);
            off += 8;
            int compressedSize = readInt(data, off);
            off += 4;
            int uncompressedSize = readInt(data, off);
            off += 4;
            byte codecId = data[off++];
            entries.add(new Entry(blockOffset, compressedSize, uncompressedSize, codecId));
        }
        return new CompressionMap(entries);
    }

    /**
     * Deserializes a compression map from its binary representation, version-aware.
     *
     * @param data byte array in the format for the given version
     * @param version format version (2 = 17-byte entries, 3 = 21-byte entries with checksum)
     * @return the deserialized compression map
     * @throws IllegalArgumentException if the data is malformed or version is unsupported
     * @throws NullPointerException if data is null
     */
    // @spec sstable.v3-format-upgrade.R2,R3 — version-aware deserialize: 17-byte v2 or 21-byte v3
    // entries
    public static CompressionMap deserialize(byte[] data, int version) {
        if (version == 2) {
            return deserialize(data);
        }
        if (version != 3) {
            throw new IllegalArgumentException("unsupported compression map version: " + version);
        }
        Objects.requireNonNull(data, "data must not be null");
        if (data.length < 4) {
            throw new IllegalArgumentException(
                    "compression map data too short: %d bytes (minimum 4)".formatted(data.length));
        }
        int blockCount = readInt(data, 0);
        if (blockCount < 0) {
            throw new IllegalArgumentException("negative block count: " + blockCount);
        }
        long expectedLength = 4L + (long) blockCount * SSTableFormat.COMPRESSION_MAP_ENTRY_SIZE_V3;
        if (expectedLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "block count %d requires %d bytes, exceeds maximum array size"
                            .formatted(blockCount, expectedLength));
        }
        if (data.length < expectedLength) {
            throw new IllegalArgumentException(
                    "compression map data too short: %d bytes (expected %d for %d blocks)"
                            .formatted(data.length, expectedLength, blockCount));
        }
        if (data.length > expectedLength) {
            throw new IllegalArgumentException(
                    "compression map data has trailing bytes: %d bytes (expected %d for %d blocks)"
                            .formatted(data.length, expectedLength, blockCount));
        }
        List<Entry> entries = new ArrayList<>(blockCount);
        int off = 4;
        for (int i = 0; i < blockCount; i++) {
            long blockOffset = readLong(data, off);
            off += 8;
            int compressedSize = readInt(data, off);
            off += 4;
            int uncompressedSize = readInt(data, off);
            off += 4;
            byte codecId = data[off++];
            int checksum = readInt(data, off);
            off += 4;
            entries.add(
                    new Entry(blockOffset, compressedSize, uncompressedSize, codecId, checksum));
        }
        return new CompressionMap(entries);
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
}
