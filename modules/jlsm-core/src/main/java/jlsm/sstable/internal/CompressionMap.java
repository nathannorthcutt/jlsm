package jlsm.sstable.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compression offset map for SSTable v2 format.
 *
 * <p>
 * Maps each data block to its on-disk position, compressed size, uncompressed size, and the codec
 * ID used for compression. This metadata is stored as a contiguous section in the SSTable file
 * between the data blocks and the key index, and is loaded eagerly at reader open time.
 *
 * <h3>Binary format</h3>
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
 * @see <a href="../../.decisions/sstable-block-compression-format/adr.md">ADR: SSTable Block
 *      Compression Format</a>
 */
public final class CompressionMap {

    /** Size in bytes of a single compression map entry. */
    public static final int ENTRY_SIZE = 17;

    /**
     * A single entry in the compression map describing one data block.
     *
     * @param blockOffset absolute file offset of the compressed block data
     * @param compressedSize size of the block as stored on disk (compressed)
     * @param uncompressedSize original size of the block before compression
     * @param codecId identifier of the codec used (0x00 = none, 0x02 = deflate)
     */
    public record Entry(long blockOffset, int compressedSize, int uncompressedSize, byte codecId) {
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
    public byte[] serialize() {
        int size = 4 + entries.size() * ENTRY_SIZE;
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
     * Deserializes a compression map from its binary representation.
     *
     * @param data byte array in the format described in the class javadoc
     * @return the deserialized compression map
     * @throws IllegalArgumentException if the data is malformed
     * @throws NullPointerException if data is null
     */
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
        int expectedLength = 4 + blockCount * ENTRY_SIZE;
        if (data.length < expectedLength) {
            throw new IllegalArgumentException(
                    "compression map data too short: %d bytes (expected %d for %d blocks)"
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
