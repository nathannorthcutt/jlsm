package jlsm.sstable.internal;

import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

class EntryCodecTest {

    private static MemorySegment segOf(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    @Test
    void putRoundTrip() {
        MemorySegment key = segOf("hello");
        MemorySegment value = segOf("world");
        SequenceNumber seq = new SequenceNumber(42L);
        Entry.Put entry = new Entry.Put(key, value, seq);

        byte[] encoded = EntryCodec.encode(entry);
        Entry decoded = EntryCodec.decode(encoded, 0);

        assertInstanceOf(Entry.Put.class, decoded);
        Entry.Put put = (Entry.Put) decoded;
        assertEquals(-1L, key.mismatch(put.key()));
        assertEquals(-1L, value.mismatch(put.value()));
        assertEquals(seq, put.sequenceNumber());
    }

    @Test
    void deleteRoundTrip() {
        MemorySegment key = segOf("goodbye");
        SequenceNumber seq = new SequenceNumber(99L);
        Entry.Delete entry = new Entry.Delete(key, seq);

        byte[] encoded = EntryCodec.encode(entry);
        Entry decoded = EntryCodec.decode(encoded, 0);

        assertInstanceOf(Entry.Delete.class, decoded);
        Entry.Delete del = (Entry.Delete) decoded;
        assertEquals(-1L, key.mismatch(del.key()));
        assertEquals(seq, del.sequenceNumber());
    }

    @Test
    void putEncodedByteLength() {
        MemorySegment key = segOf("k");
        MemorySegment value = segOf("vvv");
        Entry.Put entry = new Entry.Put(key, value, new SequenceNumber(1L));

        int keyLen = (int) key.byteSize();
        int valLen = (int) value.byteSize();
        int expected = 1 + 4 + keyLen + 8 + 4 + valLen;

        byte[] encoded = EntryCodec.encode(entry);
        assertEquals(expected, encoded.length);
    }

    @Test
    void deleteEncodedByteLength() {
        MemorySegment key = segOf("mykey");
        Entry.Delete entry = new Entry.Delete(key, new SequenceNumber(7L));

        int keyLen = (int) key.byteSize();
        int expected = 1 + 4 + keyLen + 8 + 4; // valLen field present, value absent

        byte[] encoded = EntryCodec.encode(entry);
        assertEquals(expected, encoded.length);
    }

    @Test
    void decodeAtOffset() {
        MemorySegment key = segOf("offset-key");
        MemorySegment value = segOf("offset-val");
        Entry.Put entry = new Entry.Put(key, value, new SequenceNumber(5L));

        byte[] encoded = EntryCodec.encode(entry);

        // place encoded bytes at offset 3 within a larger array
        byte[] buf = new byte[3 + encoded.length];
        System.arraycopy(encoded, 0, buf, 3, encoded.length);

        Entry decoded = EntryCodec.decode(buf, 3);
        assertInstanceOf(Entry.Put.class, decoded);
        assertEquals(-1L, key.mismatch(((Entry.Put) decoded).key()));
    }

    @Test
    void encodedSizeMethod() {
        MemorySegment key = segOf("sizekey");
        MemorySegment value = segOf("sizeval");
        Entry.Put entry = new Entry.Put(key, value, new SequenceNumber(3L));

        byte[] encoded = EntryCodec.encode(entry);
        assertEquals(encoded.length, EntryCodec.encodedSize(entry));
    }

    // @spec F02.R40 — data-dependent offset guard must be a runtime check, not assert-only
    @Test
    void decodeRejectsOffsetBeyondBufferWithIllegalArgumentException() {
        byte[] buf = new byte[10];
        // offset == buf.length is strictly out of range; before the fix the assert fires only
        // under -ea and the real failure surfaces as ArrayIndexOutOfBoundsException deeper in.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EntryCodec.decode(buf, buf.length));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("offset"),
                "expected descriptive offset-out-of-range message, got: " + ex.getMessage());
    }

    // @spec F02.R40 — negative offset must also produce a runtime IllegalArgumentException
    @Test
    void decodeRejectsNegativeOffsetWithIllegalArgumentException() {
        byte[] buf = new byte[10];
        assertThrows(IllegalArgumentException.class, () -> EntryCodec.decode(buf, -1));
    }
}
