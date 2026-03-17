package jlsm.sstable.internal;

import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;

import java.lang.foreign.MemorySegment;

/**
 * Encodes and decodes {@link Entry} instances to/from a compact big-endian binary format.
 *
 * <p>
 * Entry encoding:
 *
 * <pre>
 *   [byte  type   ]  1 byte  — 0=PUT, 1=DELETE
 *   [int   keyLen ]  4 bytes — big-endian
 *   [byte[] key   ]  keyLen bytes
 *   [long  seqNum ]  8 bytes — big-endian
 *   [int   valLen ]  4 bytes — big-endian; always present; 0 for DELETE
 *   [byte[] value ]  valLen bytes — absent for DELETE
 * </pre>
 *
 * All multi-byte fields use {@code withByteAlignment(1)} to handle unaligned offsets.
 */
public final class EntryCodec {

    private static final byte TYPE_PUT = 0;
    private static final byte TYPE_DELETE = 1;

    private EntryCodec() {
    }

    /** Returns the encoded byte length for {@code entry} without allocating. */
    public static int encodedSize(Entry entry) {
        assert entry != null : "entry must not be null";
        int keyLen = (int) entry.key().byteSize();
        int valLen = switch (entry) {
            case Entry.Put put -> (int) put.value().byteSize();
            case Entry.Delete _ -> 0;
        };
        return 1 + 4 + keyLen + 8 + 4 + valLen;
    }

    /**
     * Encodes {@code entry} into a new byte array.
     *
     * @param entry the entry to encode; must not be null
     * @return encoded bytes
     */
    public static byte[] encode(Entry entry) {
        assert entry != null : "entry must not be null";
        byte[] keyBytes = entry.key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        return encode(entry, keyBytes);
    }

    /**
     * Encodes {@code entry} into a new byte array, using pre-extracted key bytes to avoid a
     * redundant {@link MemorySegment#toArray} call.
     *
     * @param entry the entry to encode; must not be null
     * @param keyBytes the key bytes already extracted from {@code entry.key()}; must not be null
     * @return encoded bytes
     */
    public static byte[] encode(Entry entry, byte[] keyBytes) {
        assert entry != null : "entry must not be null";
        assert keyBytes != null : "keyBytes must not be null";

        byte type;
        long seqNum = entry.sequenceNumber().value();
        byte[] valBytes;

        switch (entry) {
            case Entry.Put put -> {
                type = TYPE_PUT;
                valBytes = put.value().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            }
            case Entry.Delete _ -> {
                type = TYPE_DELETE;
                valBytes = new byte[0];
            }
        }

        int keyLen = keyBytes.length;
        int valLen = valBytes.length;
        byte[] buf = new byte[1 + 4 + keyLen + 8 + 4 + valLen];
        int off = 0;

        // type
        buf[off++] = type;

        // keyLen (big-endian int)
        buf[off++] = (byte) (keyLen >>> 24);
        buf[off++] = (byte) (keyLen >>> 16);
        buf[off++] = (byte) (keyLen >>> 8);
        buf[off++] = (byte) keyLen;

        // key bytes
        System.arraycopy(keyBytes, 0, buf, off, keyLen);
        off += keyLen;

        // seqNum (big-endian long)
        buf[off++] = (byte) (seqNum >>> 56);
        buf[off++] = (byte) (seqNum >>> 48);
        buf[off++] = (byte) (seqNum >>> 40);
        buf[off++] = (byte) (seqNum >>> 32);
        buf[off++] = (byte) (seqNum >>> 24);
        buf[off++] = (byte) (seqNum >>> 16);
        buf[off++] = (byte) (seqNum >>> 8);
        buf[off++] = (byte) seqNum;

        // valLen (big-endian int)
        buf[off++] = (byte) (valLen >>> 24);
        buf[off++] = (byte) (valLen >>> 16);
        buf[off++] = (byte) (valLen >>> 8);
        buf[off++] = (byte) valLen;

        // value bytes
        if (valLen > 0) {
            System.arraycopy(valBytes, 0, buf, off, valLen);
        }

        return buf;
    }

    /**
     * Decodes one {@link Entry} from {@code buf} starting at {@code offset}.
     *
     * @param buf source byte array
     * @param offset start position within buf
     * @return decoded entry
     */
    public static Entry decode(byte[] buf, int offset) {
        assert buf != null : "buf must not be null";
        assert offset >= 0 && offset < buf.length : "offset out of range";

        int off = offset;
        byte type = buf[off++];

        int keyLen = readInt(buf, off);
        off += 4;
        byte[] keyBytes = new byte[keyLen];
        System.arraycopy(buf, off, keyBytes, 0, keyLen);
        off += keyLen;

        long seqNum = readLong(buf, off);
        off += 8;

        int valLen = readInt(buf, off);
        off += 4;

        MemorySegment key = MemorySegment.ofArray(keyBytes);
        SequenceNumber seq = new SequenceNumber(seqNum);

        if (type == TYPE_PUT) {
            byte[] valBytes = new byte[valLen];
            if (valLen > 0) {
                System.arraycopy(buf, off, valBytes, 0, valLen);
            }
            return new Entry.Put(key, MemorySegment.ofArray(valBytes), seq);
        } else {
            assert type == TYPE_DELETE : "unknown entry type: " + type;
            return new Entry.Delete(key, seq);
        }
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
