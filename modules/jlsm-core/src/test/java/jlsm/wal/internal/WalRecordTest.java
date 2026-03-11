package jlsm.wal.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;

class WalRecordTest {

    private static MemorySegment seg(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment out = Arena.ofAuto().allocate(bytes.length);
        MemorySegment.copy(bytes, 0, out, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return out;
    }

    private static MemorySegment buf(int size) {
        return Arena.ofAuto().allocate(size);
    }

    // -------------------------------------------------------------------------
    // Round-trip: PUT
    // -------------------------------------------------------------------------

    @Test
    void roundTripPut() throws IOException {
        MemorySegment key = seg("hello");
        MemorySegment value = seg("world");
        SequenceNumber seq = new SequenceNumber(42L);
        Entry entry = new Entry.Put(key, value, seq);

        MemorySegment dst = buf(1024);
        int written = WalRecord.encode(entry, dst);
        assertTrue(written > 0);

        Entry decoded = WalRecord.decode(dst, 0, written);
        assertNotNull(decoded);
        assertInstanceOf(Entry.Put.class, decoded);
        Entry.Put put = (Entry.Put) decoded;
        assertEquals(42L, put.sequenceNumber().value());
        assertArrayEquals(key.toArray(ValueLayout.JAVA_BYTE), put.key().toArray(ValueLayout.JAVA_BYTE));
        assertArrayEquals(value.toArray(ValueLayout.JAVA_BYTE), put.value().toArray(ValueLayout.JAVA_BYTE));
    }

    // -------------------------------------------------------------------------
    // Round-trip: DELETE
    // -------------------------------------------------------------------------

    @Test
    void roundTripDelete() throws IOException {
        MemorySegment key = seg("deleted-key");
        SequenceNumber seq = new SequenceNumber(99L);
        Entry entry = new Entry.Delete(key, seq);

        MemorySegment dst = buf(1024);
        int written = WalRecord.encode(entry, dst);
        assertTrue(written > 0);

        Entry decoded = WalRecord.decode(dst, 0, written);
        assertNotNull(decoded);
        assertInstanceOf(Entry.Delete.class, decoded);
        Entry.Delete del = (Entry.Delete) decoded;
        assertEquals(99L, del.sequenceNumber().value());
        assertArrayEquals(key.toArray(ValueLayout.JAVA_BYTE), del.key().toArray(ValueLayout.JAVA_BYTE));
    }

    // -------------------------------------------------------------------------
    // Partial record
    // -------------------------------------------------------------------------

    @Test
    void partialRecordReturnsNull() throws IOException {
        MemorySegment key = seg("key");
        MemorySegment value = seg("val");
        Entry entry = new Entry.Put(key, value, new SequenceNumber(1L));

        MemorySegment dst = buf(1024);
        int written = WalRecord.encode(entry, dst);

        // Only show half the bytes
        Entry decoded = WalRecord.decode(dst, 0, written / 2);
        assertNull(decoded);
    }

    @Test
    void zeroAvailableReturnsNull() throws IOException {
        Entry decoded = WalRecord.decode(buf(16), 0, 0);
        assertNull(decoded);
    }

    @Test
    void lessThanFourBytesReturnsNull() throws IOException {
        MemorySegment src = buf(16);
        assertNull(WalRecord.decode(src, 0, 3));
    }

    // -------------------------------------------------------------------------
    // CRC mismatch
    // -------------------------------------------------------------------------

    @Test
    void crcMismatchThrowsIOException() throws IOException {
        MemorySegment key = seg("key");
        MemorySegment value = seg("value");
        Entry entry = new Entry.Put(key, value, new SequenceNumber(7L));

        MemorySegment dst = buf(1024);
        int written = WalRecord.encode(entry, dst);

        // Corrupt one byte in the middle of the record
        dst.set(ValueLayout.JAVA_BYTE, written / 2, (byte) 0xFF);

        assertThrows(IOException.class, () -> WalRecord.decode(dst, 0, written));
    }

    // -------------------------------------------------------------------------
    // Empty key and value
    // -------------------------------------------------------------------------

    @Test
    void emptyKeyAndValue() throws IOException {
        Entry entry = new Entry.Put(MemorySegment.ofArray(new byte[0]),
                MemorySegment.ofArray(new byte[0]), new SequenceNumber(1L));
        MemorySegment dst = buf(256);
        int written = WalRecord.encode(entry, dst);
        Entry decoded = WalRecord.decode(dst, 0, written);
        assertNotNull(decoded);
        assertInstanceOf(Entry.Put.class, decoded);
        assertEquals(0, ((Entry.Put) decoded).key().byteSize());
        assertEquals(0, ((Entry.Put) decoded).value().byteSize());
    }

    // -------------------------------------------------------------------------
    // Decode at offset
    // -------------------------------------------------------------------------

    @Test
    void decodeAtOffset() throws IOException {
        MemorySegment dst = buf(2048);
        long offset = 100L;

        Entry entry = new Entry.Put(seg("offsetKey"), seg("offsetVal"), new SequenceNumber(5L));
        // Encode into dst at offset by using a slice
        MemorySegment slice = dst.asSlice(offset);
        int written = WalRecord.encode(entry, slice);

        Entry decoded = WalRecord.decode(dst, offset, written);
        assertNotNull(decoded);
        assertEquals(5L, decoded.sequenceNumber().value());
    }

    // -------------------------------------------------------------------------
    // SegmentFile helpers
    // -------------------------------------------------------------------------

    @Test
    void segmentFileToFileName() {
        assertEquals("wal-0000000000000001.log", SegmentFile.toFileName(1L));
        assertEquals("wal-0000000000000000.log", SegmentFile.toFileName(0L));
        assertEquals("wal-9999999999999999.log", SegmentFile.toFileName(9_999_999_999_999_999L));
    }

    @Test
    void segmentFileParseNumber() {
        assertEquals(1L, SegmentFile.parseNumber("wal-0000000000000001.log"));
        assertEquals(0L, SegmentFile.parseNumber("wal-0000000000000000.log"));
        assertEquals(-1L, SegmentFile.parseNumber("not-a-wal-file.log"));
        assertEquals(-1L, SegmentFile.parseNumber(null));
        assertEquals(-1L, SegmentFile.parseNumber("wal-123.log"));
    }
}
