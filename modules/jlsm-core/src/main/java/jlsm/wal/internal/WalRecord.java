package jlsm.wal.internal;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.zip.CRC32;
import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;

/**
 * Stateless encode/decode helper for the binary WAL record format.
 *
 * <pre>
 * ┌──────────────────────────────────────┐
 * │ frame length    4 bytes int          │  ← bytes from type through CRC
 * │ entry type      1 byte               │  0x01=PUT, 0x02=DEL
 * │ sequence number 8 bytes long         │
 * │ key length      4 bytes int          │
 * │ key bytes       keyLength bytes      │
 * │ [PUT] value length  4 bytes int      │
 * │ [PUT] value bytes   valueLength bytes│
 * │ CRC32 checksum  4 bytes int          │  ← covers type through value bytes
 * └──────────────────────────────────────┘
 * </pre>
 */
public final class WalRecord {

    static final byte TYPE_PUT = 0x01;
    static final byte TYPE_DELETE = 0x02;

    /** Minimum valid frame content size: type(1) + seq(8) + keyLen(4) + CRC(4) = 17 (DELETE). */
    private static final int MIN_FRAME_CONTENT = 17;

    // Use byte alignment (1) so reads/writes work at any offset regardless of native alignment.
    private static final ValueLayout.OfInt INT_BE = ValueLayout.JAVA_INT
            .withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);
    private static final ValueLayout.OfLong LONG_BE = ValueLayout.JAVA_LONG
            .withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);
    private static final ValueLayout.OfByte BYTE_LAYOUT = ValueLayout.JAVA_BYTE;

    private WalRecord() {
    }

    /**
     * Encodes {@code entry} into {@code dst} starting at offset 0.
     *
     * @return total bytes written (including the 4-byte frame-length prefix)
     */
    public static int encode(Entry entry, MemorySegment dst) {
        assert entry != null : "entry must not be null";
        assert dst != null : "dst must not be null";

        byte type;
        MemorySegment key = entry.key();
        MemorySegment value;
        long seqNum = entry.sequenceNumber().value();

        switch (entry) {
            case Entry.Put put -> {
                type = TYPE_PUT;
                value = put.value();
            }
            case Entry.Delete _ -> {
                type = TYPE_DELETE;
                value = null;
            }
        }

        int keyLen = (int) key.byteSize();
        int valLen = (value != null) ? (int) value.byteSize() : 0;

        // frame content: type(1) + seq(8) + keyLen(4) + keyBytes + [valLen(4) + valBytes] + CRC(4)
        int frameContentLen = 1 + 8 + 4 + keyLen + (type == TYPE_PUT ? 4 + valLen : 0) + 4;
        int totalBytes = 4 + frameContentLen;

        assert dst.byteSize() >= totalBytes : "destination buffer too small for record";

        long pos = 0;
        dst.set(INT_BE, pos, frameContentLen);
        pos += 4;

        long crcStart = pos;

        dst.set(BYTE_LAYOUT, pos, type);
        pos += 1;

        dst.set(LONG_BE, pos, seqNum);
        pos += 8;

        dst.set(INT_BE, pos, keyLen);
        pos += 4;

        MemorySegment.copy(key, 0, dst, pos, keyLen);
        pos += keyLen;

        if (type == TYPE_PUT) {
            dst.set(INT_BE, pos, valLen);
            pos += 4;
            if (valLen > 0) {
                MemorySegment.copy(value, 0, dst, pos, valLen);
            }
            pos += valLen;
        }

        int crc = computeCrc(dst, crcStart, pos - crcStart);
        dst.set(INT_BE, pos, crc);
        pos += 4;

        assert pos == totalBytes : "byte count mismatch: expected=" + totalBytes + " actual=" + pos;
        return (int) pos;
    }

    /**
     * Decodes one record from {@code src} at {@code offset} given {@code available} bytes.
     *
     * @return the decoded entry, or {@code null} if this is a partial record (safe stop)
     * @throws IOException if the record is fully framed but has a CRC mismatch (corruption)
     */
    public static Entry decode(MemorySegment src, long offset, long available) throws IOException {
        assert src != null : "src must not be null";
        assert offset >= 0 : "offset must be non-negative";
        assert available >= 0 : "available must be non-negative";

        if (available < 4) {
            return null;
        }

        int frameContentLen = src.get(INT_BE, offset);
        // Treat frames below minimum size as end-of-records (pre-allocated zeros or short garbage)
        if (frameContentLen < MIN_FRAME_CONTENT) {
            return null;
        }

        long totalNeeded = 4L + frameContentLen;
        if (available < totalNeeded) {
            return null; // partial record
        }

        long pos = offset + 4;
        long crcStart = pos;

        byte type = src.get(BYTE_LAYOUT, pos);
        pos += 1;

        long seqNum = src.get(LONG_BE, pos);
        pos += 8;

        int keyLen = src.get(INT_BE, pos);
        pos += 4;

        if (keyLen < 0 || pos + keyLen > offset + totalNeeded - 4) {
            throw new IOException("corrupted WAL record: invalid key length " + keyLen);
        }

        byte[] keyBytes = new byte[keyLen];
        MemorySegment.copy(src, BYTE_LAYOUT, pos, keyBytes, 0, keyLen);
        pos += keyLen;

        byte[] valBytes = null;
        if (type == TYPE_PUT) {
            int valLen = src.get(INT_BE, pos);
            pos += 4;
            if (valLen < 0 || pos + valLen > offset + totalNeeded - 4) {
                throw new IOException("corrupted WAL record: invalid value length " + valLen);
            }
            valBytes = new byte[valLen];
            MemorySegment.copy(src, BYTE_LAYOUT, pos, valBytes, 0, valLen);
            pos += valLen;
        }

        int storedCrc = src.get(INT_BE, pos);
        int computedCrc = computeCrc(src, crcStart, pos - crcStart);
        if (storedCrc != computedCrc) {
            throw new IOException("CRC mismatch: stored=0x%08x, computed=0x%08x"
                    .formatted(storedCrc, computedCrc));
        }

        SequenceNumber seqNumber = new SequenceNumber(seqNum);
        MemorySegment keySegment = MemorySegment.ofArray(keyBytes);

        return switch (type) {
            case TYPE_PUT -> new Entry.Put(keySegment,
                    MemorySegment.ofArray(valBytes != null ? valBytes : new byte[0]), seqNumber);
            case TYPE_DELETE -> new Entry.Delete(keySegment, seqNumber);
            default -> throw new IOException("unknown WAL record type: 0x%02x".formatted(type));
        };
    }

    /**
     * Computes CRC32 over {@code length} bytes starting at {@code offset} in {@code buf}.
     */
    public static int computeCrc(MemorySegment buf, long offset, long length) {
        assert buf != null : "buf must not be null";
        assert offset >= 0 : "offset must be non-negative";
        assert length >= 0 : "length must be non-negative";

        CRC32 crc32 = new CRC32();
        byte[] bytes = new byte[(int) length];
        MemorySegment.copy(buf, BYTE_LAYOUT, offset, bytes, 0, (int) length);
        crc32.update(bytes);
        return (int) crc32.getValue();
    }
}
