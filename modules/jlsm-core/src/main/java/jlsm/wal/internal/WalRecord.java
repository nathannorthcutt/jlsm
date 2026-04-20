package jlsm.wal.internal;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;
import jlsm.core.compression.CompressionCodec;
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

    // -------------------------------------------------------------------------
    // New-format constants (compressed WAL records — F17.R18-R24)
    // -------------------------------------------------------------------------

    /** Flags byte value: payload is not compressed. */
    private static final byte FLAG_UNCOMPRESSED = 0x00;

    /** Flags byte value: payload is compressed (bit 0 set). */
    private static final byte FLAG_COMPRESSED = 0x01;

    /** Overhead of the compression header: codecId(1) + uncompressedSize(4). */
    private static final int COMPRESSION_HEADER_SIZE = 5;

    // @spec F17.R18 — flags byte after frame length (bit 0 = compressed)
    // @spec F17.R19 — compressed header: codec ID (1) + uncompressed size (4)
    // @spec F17.R20 — uncompressed: no header, payload follows flags
    // @spec F17.R21 — CRC32 over uncompressed payload
    // @spec F17.R22 — frame length includes flags + header + payload + CRC
    // @spec F17.R23 — all fields use byteAlignment(1), big-endian
    /**
     * Encodes {@code entry} into {@code dst} starting at offset 0, applying compression when the
     * payload exceeds the minimum threshold and compression reduces size.
     *
     * <p>
     * New record format (F17.R18-R23):
     *
     * <pre>
     * [frameLength(4)] [flags(1)] [if compressed: codecId(1) + uncompressedSize(4)]
     *                             [payload (compressed or uncompressed)] [CRC32(4)]
     * </pre>
     *
     * @param entry the entry to encode
     * @param dst destination segment
     * @param codec compression codec to use
     * @param minCompressSize minimum payload size to attempt compression (F17.R25)
     * @param compressionBuffer temporary buffer for compression output
     * @return total bytes written
     */
    public static int encode(Entry entry, MemorySegment dst, CompressionCodec codec,
            int minCompressSize, MemorySegment compressionBuffer) {
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(dst, "dst must not be null");
        Objects.requireNonNull(codec, "codec must not be null");
        Objects.requireNonNull(compressionBuffer, "compressionBuffer must not be null");

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

        // Compute uncompressed payload size: type(1) + seq(8) + keyLen(4) + key + [valLen(4) + val]
        int payloadSize = 1 + 8 + 4 + keyLen + (type == TYPE_PUT ? 4 + valLen : 0);

        // Write payload into dst starting after max possible header (frameLength + flags + header),
        // compute CRC, attempt compression, then write final record from offset 0.
        int maxHeaderSize = 4 + 1 + COMPRESSION_HEADER_SIZE;

        // Write payload into dst starting at maxHeaderSize
        long payloadStart = maxHeaderSize;
        long pos = payloadStart;

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

        assert pos - payloadStart == payloadSize : "payload size mismatch";

        // CRC32 over uncompressed payload (F17.R21)
        int crc = computeCrc(dst, payloadStart, payloadSize);

        // Decide whether to compress (F17.R25, R26)
        boolean compress = payloadSize >= minCompressSize;
        MemorySegment compressedSlice = null;
        int compressedSize = 0;

        if (compress) {
            MemorySegment payloadSegment = dst.asSlice(payloadStart, payloadSize);
            compressedSlice = codec.compress(payloadSegment, compressionBuffer);
            compressedSize = (int) compressedSlice.byteSize();

            // R26: if compressed + 5 >= uncompressed, fall back to uncompressed
            if (compressedSize + COMPRESSION_HEADER_SIZE >= payloadSize) {
                compress = false;
            }
        }

        // Now write the final record into dst from offset 0
        long writePos = 0;

        if (compress) {
            // Frame content: flags(1) + codecId(1) + uncompressedSize(4) + compressedPayload +
            // CRC(4)
            int frameContentLen = 1 + COMPRESSION_HEADER_SIZE + compressedSize + 4;
            int totalBytes = 4 + frameContentLen;

            assert dst.byteSize() >= totalBytes
                    : "destination buffer too small for compressed record";

            dst.set(INT_BE, writePos, frameContentLen);
            writePos += 4;

            dst.set(BYTE_LAYOUT, writePos, FLAG_COMPRESSED);
            writePos += 1;

            dst.set(BYTE_LAYOUT, writePos, codec.codecId());
            writePos += 1;

            dst.set(INT_BE, writePos, payloadSize);
            writePos += 4;

            // Copy compressed payload from compressionBuffer
            MemorySegment.copy(compressedSlice, 0, dst, writePos, compressedSize);
            writePos += compressedSize;

            dst.set(INT_BE, writePos, crc);
            writePos += 4;

            assert writePos == totalBytes : "byte count mismatch";
            return (int) writePos;
        } else {
            // Frame content: flags(1) + payload + CRC(4)
            int frameContentLen = 1 + payloadSize + 4;
            int totalBytes = 4 + frameContentLen;

            assert dst.byteSize() >= totalBytes
                    : "destination buffer too small for uncompressed record";

            dst.set(INT_BE, 0, frameContentLen);
            writePos = 4;

            dst.set(BYTE_LAYOUT, writePos, FLAG_UNCOMPRESSED);
            writePos += 1;

            // Payload is already in dst starting at payloadStart (offset 10).
            // We need it at writePos (offset 5). Move it.
            if (writePos != payloadStart) {
                MemorySegment.copy(dst, payloadStart, dst, writePos, payloadSize);
            }
            writePos += payloadSize;

            dst.set(INT_BE, writePos, crc);
            writePos += 4;

            assert writePos == totalBytes : "byte count mismatch";
            return (int) writePos;
        }
    }

    // @spec F17.R24 — old-format detection
    // @spec F17.R30 — recovery codec map from codec set
    // @spec F17.R31 — mixed compressed/uncompressed records
    // @spec F17.R32 — unknown codec ID throws IOException
    // @spec F17.R33 — decompression failure treated as corrupt, skip
    // @spec F17.R34 — CRC verification after decompression
    /**
     * Decodes one record from {@code src} at {@code offset} given {@code available} bytes, using
     * the new compressed format when a codec map is provided.
     *
     * <p>
     * If {@code codecMap} is null or empty, delegates to the old-format decoder (F17.R24).
     *
     * @param src source segment
     * @param offset byte offset into src
     * @param available number of available bytes from offset
     * @param codecMap mapping of codec IDs to implementations; null/empty for old-format decode
     * @return the decoded entry, or null for partial records
     * @throws IOException on CRC mismatch, unknown codec, or corrupt data
     */
    public static Entry decode(MemorySegment src, long offset, long available,
            Map<Byte, CompressionCodec> codecMap) throws IOException {
        // R24: null or empty codec map — use old-format decoder
        if (codecMap == null || codecMap.isEmpty()) {
            return decode(src, offset, available);
        }

        if (available < 4) {
            return null;
        }

        int frameContentLen = src.get(INT_BE, offset);
        if (frameContentLen < 2) { // minimum: flags(1) + CRC(4) = 5, but be lenient
            return null;
        }

        long totalNeeded = 4L + frameContentLen;
        if (available < totalNeeded) {
            return null;
        }

        long pos = offset + 4;
        byte flags = src.get(BYTE_LAYOUT, pos);
        pos += 1;

        boolean compressed = (flags & 0x01) != 0;

        MemorySegment payloadSegment;
        int payloadSize;

        if (compressed) {
            // Read compression header: codecId(1) + uncompressedSize(4)
            byte codecId = src.get(BYTE_LAYOUT, pos);
            pos += 1;

            int uncompressedSize = src.get(INT_BE, pos);
            pos += 4;

            // R32: unknown codec ID
            CompressionCodec codec = codecMap.get(codecId);
            if (codec == null) {
                throw new IOException("unknown codec ID 0x%02x in WAL record; available codecs: %s"
                        .formatted(codecId, codecMap.keySet()));
            }

            // Compressed payload size = frameContentLen - flags(1) - header(5) - CRC(4)
            int compressedPayloadSize = frameContentLen - 1 - COMPRESSION_HEADER_SIZE - 4;
            if (compressedPayloadSize < 0) {
                throw new IOException("invalid compressed WAL record: negative payload size");
            }

            MemorySegment compressedPayload = src.asSlice(pos, compressedPayloadSize);
            pos += compressedPayloadSize;

            // Decompress
            MemorySegment decompressDst = MemorySegment.ofArray(new byte[uncompressedSize]);
            payloadSegment = codec.decompress(compressedPayload, decompressDst, uncompressedSize);
            payloadSize = uncompressedSize;
        } else {
            // Uncompressed: payload follows flags directly
            // payload size = frameContentLen - flags(1) - CRC(4)
            payloadSize = frameContentLen - 1 - 4;
            if (payloadSize < 0) {
                throw new IOException("invalid WAL record: negative payload size");
            }
            payloadSegment = src.asSlice(pos, payloadSize);
            pos += payloadSize;
        }

        // Read stored CRC
        int storedCrc = src.get(INT_BE, pos);

        // R21/R34: CRC over uncompressed payload
        int computedCrc = computeCrc(payloadSegment, 0, payloadSize);
        if (storedCrc != computedCrc) {
            throw new IOException("CRC mismatch: stored=0x%08x, computed=0x%08x"
                    .formatted(storedCrc, computedCrc));
        }

        // Parse the uncompressed payload
        return parsePayload(payloadSegment, payloadSize);
    }

    /**
     * Parses an uncompressed payload segment into an Entry.
     */
    private static Entry parsePayload(MemorySegment payload, int payloadSize) throws IOException {
        long pos = 0;

        byte type = payload.get(BYTE_LAYOUT, pos);
        pos += 1;

        long seqNum = payload.get(LONG_BE, pos);
        pos += 8;

        int keyLen = payload.get(INT_BE, pos);
        pos += 4;

        if (keyLen < 0 || pos + keyLen > payloadSize) {
            throw new IOException("corrupted WAL record: invalid key length " + keyLen);
        }

        byte[] keyBytes = new byte[keyLen];
        MemorySegment.copy(payload, BYTE_LAYOUT, pos, keyBytes, 0, keyLen);
        pos += keyLen;

        byte[] valBytes = null;
        if (type == TYPE_PUT) {
            int valLen = payload.get(INT_BE, pos);
            pos += 4;
            if (valLen < 0 || pos + valLen > payloadSize) {
                throw new IOException("corrupted WAL record: invalid value length " + valLen);
            }
            valBytes = new byte[valLen];
            MemorySegment.copy(payload, BYTE_LAYOUT, pos, valBytes, 0, valLen);
            pos += valLen;
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
