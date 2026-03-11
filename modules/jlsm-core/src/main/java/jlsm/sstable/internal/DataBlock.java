package jlsm.sstable.internal;

import jlsm.core.model.Entry;

import java.util.ArrayList;
import java.util.List;

/**
 * A mutable buffer that accumulates encoded entries for a single data block.
 *
 * <p>
 * Block on-disk format:
 *
 * <pre>
 *   [int  count  ]  4 bytes — number of entries in this block
 *   [entry 0 ... ]  variable
 * </pre>
 */
public final class DataBlock {

    private final List<byte[]> encodedEntries = new ArrayList<>();
    private int byteSize = 4; // initial 4 bytes for the count field

    /** Adds an encoded entry to this block. */
    public void add(byte[] encoded) {
        assert encoded != null : "encoded must not be null";
        encodedEntries.add(encoded);
        byteSize += encoded.length;
    }

    /** Returns the current byte size of this block (including the count header). */
    public int byteSize() {
        return byteSize;
    }

    /** Returns the number of entries in this block. */
    public int count() {
        return encodedEntries.size();
    }

    /**
     * Serializes this block to a byte array.
     *
     * @return big-endian serialized form
     */
    public byte[] serialize() {
        byte[] buf = new byte[byteSize];
        int off = 0;
        int count = encodedEntries.size();
        // write count (big-endian int)
        buf[off++] = (byte) (count >>> 24);
        buf[off++] = (byte) (count >>> 16);
        buf[off++] = (byte) (count >>> 8);
        buf[off++] = (byte) count;
        for (byte[] entry : encodedEntries) {
            System.arraycopy(entry, 0, buf, off, entry.length);
            off += entry.length;
        }
        assert off == byteSize : "serialized size mismatch";
        return buf;
    }

    /**
     * Deserializes a list of entries from a raw block byte array.
     *
     * @param blockBytes the raw block bytes
     * @return list of decoded entries
     */
    public static List<Entry> deserialize(byte[] blockBytes) {
        assert blockBytes != null : "blockBytes must not be null";
        assert blockBytes.length >= 4 : "block too short";
        int count = readInt(blockBytes, 0);
        assert count >= 0 : "negative entry count";
        List<Entry> entries = new ArrayList<>(count);
        int off = 4;
        for (int i = 0; i < count; i++) {
            Entry entry = EntryCodec.decode(blockBytes, off);
            entries.add(entry);
            off += EntryCodec.encodedSize(entry);
        }
        return entries;
    }

    private static int readInt(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24) | ((buf[off + 1] & 0xFF) << 16)
                | ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
    }
}
