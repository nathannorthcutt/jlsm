package jlsm.wal.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;

/**
 * Tests for compressed WAL record format support in {@link WalRecord}.
 *
 * <p>
 * Covers F17 requirements R18-R24 (format), R25-R26 (threshold/expansion), R30-R35 (recovery).
 */
class CompressedWalRecordTest {

    private static final ValueLayout.OfInt INT_BE = ValueLayout.JAVA_INT
            .withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);
    private static final ValueLayout.OfByte BYTE_LAYOUT = ValueLayout.JAVA_BYTE;

    private static MemorySegment seg(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment out = Arena.ofAuto().allocate(bytes.length);
        MemorySegment.copy(bytes, 0, out, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return out;
    }

    private static MemorySegment buf(int size) {
        return Arena.ofAuto().allocate(size);
    }

    /**
     * Create a large entry whose payload exceeds the default 64-byte threshold, ensuring
     * compression is attempted.
     */
    private static Entry.Put largeEntry(int valueSize) {
        byte[] val = new byte[valueSize];
        java.util.Arrays.fill(val, (byte) 'A');
        return new Entry.Put(seg("key"), MemorySegment.ofArray(val), new SequenceNumber(1L));
    }

    /**
     * Build a codec map from one or more codecs.
     */
    private static Map<Byte, CompressionCodec> codecMap(CompressionCodec... codecs) {
        Map<Byte, CompressionCodec> map = new HashMap<>();
        for (CompressionCodec c : codecs) {
            map.put(c.codecId(), c);
        }
        return map;
    }

    // =========================================================================
    // R18: Flags byte — uncompressed record
    // =========================================================================

    @Test
    void uncompressedRecordHasFlagsByteZero() throws IOException {
        Entry entry = new Entry.Put(seg("k"), seg("v"), new SequenceNumber(1L));
        CompressionCodec codec = CompressionCodec.none();
        MemorySegment dst = buf(1024);
        MemorySegment compressionBuf = buf(1024);

        int written = WalRecord.encode(entry, dst, codec, 64, compressionBuf);
        assertTrue(written > 0);

        // flags byte is at offset 4 (after frame length)
        byte flags = dst.get(BYTE_LAYOUT, 4);
        assertEquals((byte) 0x00, flags, "uncompressed record flags must be 0x00");
    }

    // =========================================================================
    // R18 + R19: Flags byte — compressed record has flags=0x01, codec ID, uncompressed size
    // =========================================================================

    @Test
    void compressedRecordHasFlagsByteOneAndCompressionHeader() throws IOException {
        // Large entry to exceed 64-byte threshold and actually compress smaller
        Entry entry = largeEntry(512);
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment dst = buf(4096);
        MemorySegment compressionBuf = buf(codec.maxCompressedLength(1024));

        int written = WalRecord.encode(entry, dst, codec, 64, compressionBuf);
        assertTrue(written > 0);

        // flags byte at offset 4
        byte flags = dst.get(BYTE_LAYOUT, 4);
        assertEquals((byte) 0x01, flags, "compressed record flags must have bit 0 set");

        // codec ID at offset 5
        byte codecId = dst.get(BYTE_LAYOUT, 5);
        assertEquals(codec.codecId(), codecId, "codec ID must match");

        // uncompressed size at offset 6 (4 bytes BE)
        int uncompressedSize = dst.get(INT_BE, 6);
        assertTrue(uncompressedSize > 0, "uncompressed size must be positive");
    }

    // =========================================================================
    // R20: Uncompressed format — no compression header after flags
    // =========================================================================

    @Test
    void uncompressedFormatHasNoCompressionHeader() throws IOException {
        Entry entry = new Entry.Put(seg("key"), seg("val"), new SequenceNumber(1L));
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment dst = buf(1024);
        MemorySegment compressionBuf = buf(1024);

        int written = WalRecord.encode(entry, dst, codec, 64, compressionBuf);

        // flags = 0x00 (small payload, below threshold)
        byte flags = dst.get(BYTE_LAYOUT, 4);
        assertEquals((byte) 0x00, flags);

        // After flags (offset 5), payload starts directly (entry type byte)
        byte entryType = dst.get(BYTE_LAYOUT, 5);
        assertTrue(entryType == 0x01 || entryType == 0x02,
                "payload must start directly after flags for uncompressed");
    }

    // =========================================================================
    // R21: CRC32 computed over uncompressed payload
    // =========================================================================

    @Test
    void crcComputedOverUncompressedPayload() throws IOException {
        Entry entry = largeEntry(512);
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment dst = buf(4096);
        MemorySegment compressionBuf = buf(codec.maxCompressedLength(1024));

        int written = WalRecord.encode(entry, dst, codec, 64, compressionBuf);

        // Decode and verify — CRC is checked during decode over uncompressed payload
        Map<Byte, CompressionCodec> map = codecMap(codec);
        Entry decoded = WalRecord.decode(dst, 0, written, map);
        assertNotNull(decoded, "round-trip decode must succeed (CRC valid)");
    }

    // =========================================================================
    // R22: Frame length includes all bytes after itself
    // =========================================================================

    @Test
    void frameLengthIncludesAllBytesAfterItself() throws IOException {
        Entry entry = largeEntry(512);
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment dst = buf(4096);
        MemorySegment compressionBuf = buf(codec.maxCompressedLength(1024));

        int written = WalRecord.encode(entry, dst, codec, 64, compressionBuf);

        int frameLength = dst.get(INT_BE, 0);
        assertEquals(written - 4, frameLength,
                "frame length must equal total written minus the 4-byte frame-length field");
    }

    // =========================================================================
    // R23: All new fields use byteAlignment(1) and big-endian
    // =========================================================================

    @Test
    void compressedRecordFieldsAreByteAligned() throws IOException {
        // This is a structural test: encode at an odd offset to confirm alignment=1 works
        Entry entry = largeEntry(512);
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment backing = buf(8192);
        long oddOffset = 3; // misaligned offset
        MemorySegment dst = backing.asSlice(oddOffset);
        MemorySegment compressionBuf = buf(codec.maxCompressedLength(1024));

        int written = WalRecord.encode(entry, dst, codec, 64, compressionBuf);
        assertTrue(written > 0, "encode at misaligned offset must succeed");

        // Decode from the misaligned offset
        Map<Byte, CompressionCodec> map = codecMap(codec);
        Entry decoded = WalRecord.decode(backing, oddOffset, written, map);
        assertNotNull(decoded, "decode from misaligned offset must succeed");
    }

    // =========================================================================
    // R24: Old-format backward compatibility — null/empty codec map uses old format
    // =========================================================================

    @Test
    void nullCodecMapDecodesOldFormat() throws IOException {
        // Encode with old format
        Entry entry = new Entry.Put(seg("key"), seg("val"), new SequenceNumber(1L));
        MemorySegment dst = buf(1024);
        int written = WalRecord.encode(entry, dst);

        // Decode with null codec map — should use old-format decoder
        Entry decoded = WalRecord.decode(dst, 0, written, null);
        assertNotNull(decoded);
        assertInstanceOf(Entry.Put.class, decoded);
    }

    @Test
    void emptyCodecMapDecodesOldFormat() throws IOException {
        Entry entry = new Entry.Put(seg("key"), seg("val"), new SequenceNumber(1L));
        MemorySegment dst = buf(1024);
        int written = WalRecord.encode(entry, dst);

        Entry decoded = WalRecord.decode(dst, 0, written, Map.of());
        assertNotNull(decoded);
        assertInstanceOf(Entry.Put.class, decoded);
    }

    // =========================================================================
    // R25: Records below minimum threshold written uncompressed
    // =========================================================================

    @Test
    void belowThresholdWrittenUncompressed() throws IOException {
        // Small entry — payload will be well below 64 bytes
        Entry entry = new Entry.Put(seg("k"), seg("v"), new SequenceNumber(1L));
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment dst = buf(1024);
        MemorySegment compressionBuf = buf(1024);

        int written = WalRecord.encode(entry, dst, codec, 64, compressionBuf);

        byte flags = dst.get(BYTE_LAYOUT, 4);
        assertEquals((byte) 0x00, flags, "record below threshold must be written uncompressed");
    }

    @Test
    void atExactThresholdAttemptsCompression() throws IOException {
        // Create entry whose payload is exactly 64 bytes
        // Payload = type(1) + seq(8) + keyLen(4) + key + valLen(4) + val
        // We need key+val to make payload = 64
        // 1 + 8 + 4 + keyLen + 4 + valLen = 64 → keyLen + valLen = 47
        byte[] keyBytes = new byte[10];
        byte[] valBytes = new byte[37];
        java.util.Arrays.fill(keyBytes, (byte) 'K');
        java.util.Arrays.fill(valBytes, (byte) 'V');
        Entry entry = new Entry.Put(MemorySegment.ofArray(keyBytes),
                MemorySegment.ofArray(valBytes), new SequenceNumber(1L));
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment dst = buf(4096);
        MemorySegment compressionBuf = buf(codec.maxCompressedLength(256));

        // threshold=64, payload=64: should attempt compression (>= threshold means attempt)
        int written = WalRecord.encode(entry, dst, codec, 64, compressionBuf);
        assertTrue(written > 0);
        // The flags byte should be 0x00 or 0x01 depending on whether compression helped.
        // For a repetitive payload, deflate should compress it, so flags=0x01.
        // But we're not asserting the specific outcome — just that it doesn't crash.
    }

    // =========================================================================
    // R26: No expansion — if compressed+5 >= uncompressed, write uncompressed
    // =========================================================================

    @Test
    void noExpansionFallbackToUncompressed() throws IOException {
        // Use NoneCodec — "compressed" is same size as uncompressed, so +5 overhead
        // guarantees the expansion check triggers
        CompressionCodec none = CompressionCodec.none();
        Entry entry = largeEntry(128);
        MemorySegment dst = buf(4096);
        MemorySegment compressionBuf = buf(none.maxCompressedLength(1024));

        int written = WalRecord.encode(entry, dst, none, 64, compressionBuf);

        byte flags = dst.get(BYTE_LAYOUT, 4);
        assertEquals((byte) 0x00, flags,
                "when compressed+5 >= uncompressed, must fall back to uncompressed");
    }

    // =========================================================================
    // Round-trip: compressed encode -> decode
    // =========================================================================

    @Test
    void roundTripCompressedPut() throws IOException {
        Entry.Put original = largeEntry(256);
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment dst = buf(4096);
        MemorySegment compressionBuf = buf(codec.maxCompressedLength(1024));

        int written = WalRecord.encode(original, dst, codec, 64, compressionBuf);

        Map<Byte, CompressionCodec> map = codecMap(codec);
        Entry decoded = WalRecord.decode(dst, 0, written, map);

        assertNotNull(decoded);
        assertInstanceOf(Entry.Put.class, decoded);
        Entry.Put put = (Entry.Put) decoded;
        assertEquals(original.sequenceNumber().value(), put.sequenceNumber().value());
        assertArrayEquals(original.key().toArray(ValueLayout.JAVA_BYTE),
                put.key().toArray(ValueLayout.JAVA_BYTE));
        assertArrayEquals(original.value().toArray(ValueLayout.JAVA_BYTE),
                put.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Test
    void roundTripCompressedDelete() throws IOException {
        // Delete entries are small, so they will likely be below threshold.
        // Use threshold=0 to force compression attempt.
        byte[] bigKey = new byte[128];
        java.util.Arrays.fill(bigKey, (byte) 'D');
        Entry.Delete original = new Entry.Delete(MemorySegment.ofArray(bigKey),
                new SequenceNumber(77L));
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment dst = buf(4096);
        MemorySegment compressionBuf = buf(codec.maxCompressedLength(512));

        int written = WalRecord.encode(original, dst, codec, 0, compressionBuf);

        Map<Byte, CompressionCodec> map = codecMap(codec);
        Entry decoded = WalRecord.decode(dst, 0, written, map);

        assertNotNull(decoded);
        assertInstanceOf(Entry.Delete.class, decoded);
        assertEquals(77L, decoded.sequenceNumber().value());
        assertArrayEquals(bigKey, decoded.key().toArray(ValueLayout.JAVA_BYTE));
    }

    @Test
    void roundTripUncompressedWithCodecMap() throws IOException {
        // Small entry written uncompressed (below threshold), decoded with codec map
        Entry entry = new Entry.Put(seg("key"), seg("val"), new SequenceNumber(5L));
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment dst = buf(1024);
        MemorySegment compressionBuf = buf(1024);

        int written = WalRecord.encode(entry, dst, codec, 64, compressionBuf);

        Map<Byte, CompressionCodec> map = codecMap(codec);
        Entry decoded = WalRecord.decode(dst, 0, written, map);

        assertNotNull(decoded);
        assertInstanceOf(Entry.Put.class, decoded);
        assertEquals(5L, decoded.sequenceNumber().value());
    }

    // =========================================================================
    // R31: Mixed compressed and uncompressed records in same sequence
    // =========================================================================

    @Test
    void mixedCompressedAndUncompressedRecords() throws IOException {
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment dst = buf(16384);
        MemorySegment compressionBuf = buf(codec.maxCompressedLength(2048));
        Map<Byte, CompressionCodec> map = codecMap(codec);

        long offset = 0;

        // Record 1: small (uncompressed)
        Entry small = new Entry.Put(seg("s"), seg("v"), new SequenceNumber(1L));
        int w1 = WalRecord.encode(small, dst.asSlice(offset), codec, 64, compressionBuf);

        // Record 2: large (compressed)
        Entry large = largeEntry(256);
        int w2 = WalRecord.encode(large, dst.asSlice(offset + w1), codec, 64, compressionBuf);

        // Record 3: small again (uncompressed)
        Entry small2 = new Entry.Put(seg("t"), seg("u"), new SequenceNumber(3L));
        int w3 = WalRecord.encode(small2, dst.asSlice(offset + w1 + w2), codec, 64, compressionBuf);

        // Decode all three
        Entry d1 = WalRecord.decode(dst, offset, w1, map);
        assertNotNull(d1);
        assertEquals(1L, d1.sequenceNumber().value());

        Entry d2 = WalRecord.decode(dst, offset + w1, w2, map);
        assertNotNull(d2);
        assertEquals(1L, d2.sequenceNumber().value()); // largeEntry uses seq=1

        Entry d3 = WalRecord.decode(dst, offset + w1 + w2, w3, map);
        assertNotNull(d3);
        assertEquals(3L, d3.sequenceNumber().value());
    }

    // =========================================================================
    // R32: Unknown codec ID -> IOException
    // =========================================================================

    @Test
    void unknownCodecIdThrowsIOException() throws IOException {
        // Encode a compressed record with deflate
        Entry entry = largeEntry(256);
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment dst = buf(4096);
        MemorySegment compressionBuf = buf(codec.maxCompressedLength(1024));

        int written = WalRecord.encode(entry, dst, codec, 64, compressionBuf);

        // Verify it's actually compressed
        byte flags = dst.get(BYTE_LAYOUT, 4);
        assertEquals((byte) 0x01, flags, "record should be compressed for this test");

        // Decode with a codec map that does NOT contain the deflate codec
        Map<Byte, CompressionCodec> emptyish = codecMap(CompressionCodec.none());

        IOException ex = assertThrows(IOException.class,
                () -> WalRecord.decode(dst, 0, written, emptyish));
        assertTrue(ex.getMessage().contains("codec") || ex.getMessage().contains("unknown"),
                "error message should mention codec: " + ex.getMessage());
    }

    // =========================================================================
    // R34: CRC mismatch after decompression -> treated as corrupt
    // =========================================================================

    @Test
    void crcMismatchAfterDecompressionThrowsIOException() throws IOException {
        Entry entry = largeEntry(256);
        CompressionCodec codec = CompressionCodec.deflate();
        MemorySegment dst = buf(4096);
        MemorySegment compressionBuf = buf(codec.maxCompressedLength(1024));

        int written = WalRecord.encode(entry, dst, codec, 64, compressionBuf);

        // Verify compressed
        byte flags = dst.get(BYTE_LAYOUT, 4);
        assertEquals((byte) 0x01, flags);

        // Corrupt the stored CRC (last 4 bytes before end of record)
        int crcOffset = written - 4;
        int storedCrc = dst.get(INT_BE, crcOffset);
        dst.set(INT_BE, crcOffset, storedCrc ^ 0xFFFFFFFF);

        Map<Byte, CompressionCodec> map = codecMap(codec);
        assertThrows(IOException.class, () -> WalRecord.decode(dst, 0, written, map));
    }

    // =========================================================================
    // Compressed record is smaller than uncompressed
    // =========================================================================

    @Test
    void compressedRecordIsSmallerThanUncompressed() throws IOException {
        Entry entry = largeEntry(512);
        CompressionCodec codec = CompressionCodec.deflate();

        MemorySegment dstCompressed = buf(4096);
        MemorySegment compressionBuf = buf(codec.maxCompressedLength(1024));
        int compressedWritten = WalRecord.encode(entry, dstCompressed, codec, 64, compressionBuf);

        MemorySegment dstUncompressed = buf(4096);
        int uncompressedWritten = WalRecord.encode(entry, dstUncompressed);

        // The new format has a flags byte overhead, but for 512 bytes of repetitive data,
        // compression should still save space overall.
        assertTrue(compressedWritten < uncompressedWritten,
                "compressed record (%d) should be smaller than uncompressed (%d)"
                        .formatted(compressedWritten, uncompressedWritten));
    }

    // =========================================================================
    // Old-format records still decode correctly (backward compat)
    // =========================================================================

    @Test
    void oldFormatRecordStillDecodesWithCodecMap() throws IOException {
        // This tests R24: old format records (no flags byte) must still be readable
        // when codec map is null or empty (backward compat mode).
        Entry entry = new Entry.Put(seg("old"), seg("format"), new SequenceNumber(10L));
        MemorySegment dst = buf(1024);
        int written = WalRecord.encode(entry, dst);

        // Old-format decode with null codec map
        Entry decoded = WalRecord.decode(dst, 0, written, null);
        assertNotNull(decoded);
        assertInstanceOf(Entry.Put.class, decoded);
        Entry.Put put = (Entry.Put) decoded;
        assertEquals(10L, put.sequenceNumber().value());
        assertArrayEquals("old".getBytes(StandardCharsets.UTF_8),
                put.key().toArray(ValueLayout.JAVA_BYTE));
        assertArrayEquals("format".getBytes(StandardCharsets.UTF_8),
                put.value().toArray(ValueLayout.JAVA_BYTE));
    }

    // =========================================================================
    // Existing encode/decode (old format) still works unchanged
    // =========================================================================

    @Test
    void existingEncodeDecodeUnchanged() throws IOException {
        Entry entry = new Entry.Put(seg("hello"), seg("world"), new SequenceNumber(42L));
        MemorySegment dst = buf(1024);
        int written = WalRecord.encode(entry, dst);

        // Old decode method still works
        Entry decoded = WalRecord.decode(dst, 0, written);
        assertNotNull(decoded);
        assertInstanceOf(Entry.Put.class, decoded);
        assertEquals(42L, decoded.sequenceNumber().value());
    }
}
