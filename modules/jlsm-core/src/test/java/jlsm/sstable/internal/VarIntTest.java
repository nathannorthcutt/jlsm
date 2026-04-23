package jlsm.sstable.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import jlsm.sstable.CorruptSectionException;

/**
 * TDD tests for {@link VarInt}.
 *
 * <p>
 * All tests are expected to fail because the stubs throw {@link UnsupportedOperationException}.
 * Once implemented, these tests define the contract for canonical unsigned LEB128 encoding/decoding
 * constrained to {@code [1, SSTableFormat.MAX_BLOCK_SIZE]}.
 * </p>
 *
 * @spec sstable.end-to-end-integrity.R1
 * @spec sstable.end-to-end-integrity.R2
 * @spec sstable.end-to-end-integrity.R5
 * @spec sstable.end-to-end-integrity.R6
 */
class VarIntTest {

    // ---------- round-trip (positive path) ----------

    @Test
    void roundtripValueOne() throws IOException {
        byte[] dst = new byte[8];
        int written = VarInt.encode(1, dst, 0);
        assertEquals(1, written);
        VarInt.DecodedVarInt decoded = VarInt.decode(ByteBuffer.wrap(dst), 0L);
        assertEquals(new VarInt.DecodedVarInt(1, 1), decoded);
    }

    @Test
    void roundtripValue127_oneByte() throws IOException {
        byte[] dst = new byte[8];
        int written = VarInt.encode(127, dst, 0);
        assertEquals(1, written);
        assertEquals(1, VarInt.encodedLength(127));
        VarInt.DecodedVarInt decoded = VarInt.decode(ByteBuffer.wrap(dst), 0L);
        assertEquals(new VarInt.DecodedVarInt(127, 1), decoded);
    }

    @Test
    void roundtripValue128_twoBytes() throws IOException {
        byte[] dst = new byte[8];
        int written = VarInt.encode(128, dst, 0);
        assertEquals(2, written);
        assertEquals(2, VarInt.encodedLength(128));
        VarInt.DecodedVarInt decoded = VarInt.decode(ByteBuffer.wrap(dst), 0L);
        assertEquals(new VarInt.DecodedVarInt(128, 2), decoded);
    }

    @Test
    void roundtripValue16383_twoBytes() throws IOException {
        byte[] dst = new byte[8];
        int written = VarInt.encode(16383, dst, 0);
        assertEquals(2, written);
        assertEquals(2, VarInt.encodedLength(16383));
        VarInt.DecodedVarInt decoded = VarInt.decode(ByteBuffer.wrap(dst), 0L);
        assertEquals(new VarInt.DecodedVarInt(16383, 2), decoded);
    }

    @Test
    void roundtripValue16384_threeBytes() throws IOException {
        byte[] dst = new byte[8];
        int written = VarInt.encode(16384, dst, 0);
        assertEquals(3, written);
        assertEquals(3, VarInt.encodedLength(16384));
        VarInt.DecodedVarInt decoded = VarInt.decode(ByteBuffer.wrap(dst), 0L);
        assertEquals(new VarInt.DecodedVarInt(16384, 3), decoded);
    }

    @Test
    void roundtripValue2097151_threeBytes() throws IOException {
        byte[] dst = new byte[8];
        int written = VarInt.encode(2097151, dst, 0);
        assertEquals(3, written);
        assertEquals(3, VarInt.encodedLength(2097151));
        VarInt.DecodedVarInt decoded = VarInt.decode(ByteBuffer.wrap(dst), 0L);
        assertEquals(new VarInt.DecodedVarInt(2097151, 3), decoded);
    }

    @Test
    void roundtripValue2097152_fourBytes() throws IOException {
        byte[] dst = new byte[8];
        int written = VarInt.encode(2097152, dst, 0);
        assertEquals(4, written);
        assertEquals(4, VarInt.encodedLength(2097152));
        VarInt.DecodedVarInt decoded = VarInt.decode(ByteBuffer.wrap(dst), 0L);
        assertEquals(new VarInt.DecodedVarInt(2097152, 4), decoded);
    }

    @Test
    void roundtripValueMaxBlockSize_fourBytes() throws IOException {
        int value = SSTableFormat.MAX_BLOCK_SIZE;
        byte[] dst = new byte[8];
        int written = VarInt.encode(value, dst, 0);
        assertEquals(4, written);
        assertEquals(4, VarInt.encodedLength(value));
        VarInt.DecodedVarInt decoded = VarInt.decode(ByteBuffer.wrap(dst), 0L);
        assertEquals(new VarInt.DecodedVarInt(value, 4), decoded);
    }

    @Test
    void encodedLengthMatchesActualEncodeByteCount() throws IOException {
        int[] samples = { 1, 127, 128, 16383, 16384, 2097151, 2097152,
                SSTableFormat.MAX_BLOCK_SIZE };
        for (int v : samples) {
            byte[] dst = new byte[8];
            int written = VarInt.encode(v, dst, 0);
            assertEquals(VarInt.encodedLength(v), written,
                    "encodedLength(%d) must equal actual bytes written".formatted(v));
        }
    }

    // ---------- encode rejection ----------

    @Test
    void encodeZeroThrowsIoException() {
        assertThrows(IOException.class, () -> VarInt.encode(0, new byte[8], 0));
    }

    @Test
    void encodeNegativeThrowsIoException() {
        assertThrows(IOException.class, () -> VarInt.encode(-1, new byte[8], 0));
    }

    @Test
    void encodeOverMaxThrowsIoException() {
        assertThrows(IOException.class,
                () -> VarInt.encode(SSTableFormat.MAX_BLOCK_SIZE + 1, new byte[8], 0));
    }

    @Test
    void encodedLengthZeroThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> VarInt.encodedLength(0));
    }

    @Test
    void encodedLengthOverMaxThrowsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> VarInt.encodedLength(SSTableFormat.MAX_BLOCK_SIZE + 1));
    }

    // ---------- decode rejection (canonical LEB128) ----------

    @Test
    void decodeFifthContinuationByteRejected() {
        byte[] bytes = { (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01 };
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> VarInt.decode(ByteBuffer.wrap(bytes), 0L));
        assertEquals(CorruptSectionException.SECTION_DATA, ex.sectionName());
    }

    @Test
    void decodeValueOverMaxRejected() {
        // LEB128 of 33_554_433 = MAX_BLOCK_SIZE + 1: {0x81, 0x80, 0x80, 0x10}.
        byte[] bytes = { (byte) 0x81, (byte) 0x80, (byte) 0x80, (byte) 0x10 };
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> VarInt.decode(ByteBuffer.wrap(bytes), 0L));
        assertEquals(CorruptSectionException.SECTION_DATA, ex.sectionName());
    }

    @Test
    void decodeValueZeroRejected() {
        byte[] bytes = { 0x00 };
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> VarInt.decode(ByteBuffer.wrap(bytes), 0L));
        assertEquals(CorruptSectionException.SECTION_DATA, ex.sectionName());
    }

    @Test
    void decodeNonCanonicalTrailingZeroPayloadRejected() {
        // {0x81, 0x00} encodes value 1 non-canonically (canonical is 1-byte {0x01}).
        byte[] bytes = { (byte) 0x81, 0x00 };
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> VarInt.decode(ByteBuffer.wrap(bytes), 0L));
        assertEquals(CorruptSectionException.SECTION_DATA, ex.sectionName());
    }

    @Test
    void decodeExceptionCarriesDataSection() {
        byte[] bytes = { 0x00 };
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> VarInt.decode(ByteBuffer.wrap(bytes), 0L));
        assertEquals(CorruptSectionException.SECTION_DATA, ex.sectionName());
    }

    @Test
    void decodeExceptionIncludesOffsetInMessage() {
        byte[] bytes = { 0x00 };
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> VarInt.decode(ByteBuffer.wrap(bytes), 4096L));
        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("4096"),
                "decode diagnostic message should contain absolute offset: " + msg);
    }

    // ---------- MemorySegment decode mirrors ByteBuffer ----------

    @Test
    void decodeMemorySegmentMatchesByteBufferAcrossAllWidths() throws IOException {
        int[] samples = { 1, 127, 128, 16383, 16384, 2097151, 2097152,
                SSTableFormat.MAX_BLOCK_SIZE };
        for (int v : samples) {
            byte[] bytes = new byte[8];
            int written = VarInt.encode(v, bytes, 0);
            VarInt.DecodedVarInt fromBuffer = VarInt.decode(ByteBuffer.wrap(bytes), 0L);
            VarInt.DecodedVarInt fromSegment = VarInt.decode(MemorySegment.ofArray(bytes), 0L, 0L);
            assertEquals(fromBuffer, fromSegment,
                    "MemorySegment decode must match ByteBuffer decode for value " + v
                            + " (written=" + written + ")");
            assertEquals(new VarInt.DecodedVarInt(v, written), fromSegment);
        }
    }

    // ===== Hardening (adversarial, Cycle 1) =====

    // Finding: H-DT-2
    // Bug: decode called on an empty ByteBuffer leaks IndexOutOfBoundsException / BufferUnderflow
    // into caller, producing a non-CorruptSectionException error path.
    // Correct behavior: empty buffer → CorruptSectionException(section=data) with diagnostic
    // offset.
    // Fix location: VarInt.decode(ByteBuffer, long)
    // Regression watch: one-byte buffer containing a valid VarInt must still decode successfully.
    @Test
    void decodeEmptyByteBufferThrowsCorruptSection() {
        ByteBuffer empty = ByteBuffer.wrap(new byte[0]);
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> VarInt.decode(empty, 100L));
        assertEquals(CorruptSectionException.SECTION_DATA, ex.sectionName());
    }

    // Finding: H-DT-3
    // Bug: a VarInt whose continuation bit is set but the buffer ends before the next byte
    // is silently truncated or throws BufferUnderflow.
    // Correct behavior: throw CorruptSectionException(section=data) — truncation is corruption.
    // Fix location: VarInt.decode(ByteBuffer, long)
    // Regression watch: a complete 2-byte VarInt in a 2-byte buffer must decode successfully.
    @Test
    void decodeTruncatedVarIntThrowsCorruptSection() {
        // First byte has continuation bit but no second byte follows.
        ByteBuffer truncated = ByteBuffer.wrap(new byte[]{ (byte) 0x80 });
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> VarInt.decode(truncated, 200L));
        assertEquals(CorruptSectionException.SECTION_DATA, ex.sectionName());
    }

    // Finding: H-DT-4
    // Bug: decode(MemorySegment, offset) with offset >= segment byteSize() produces an
    // IndexOutOfBoundsException or silent misread.
    // Correct behavior: throw CorruptSectionException(section=data) — out-of-range is corruption.
    // Fix location: VarInt.decode(MemorySegment, long, long)
    // Regression watch: offset 0 on a non-empty segment must decode normally.
    @Test
    void decodeMemorySegmentOffsetOutOfRangeThrowsCorruptSection() {
        MemorySegment seg = MemorySegment.ofArray(new byte[]{ 0x01 });
        // offsetWithinSegment == segment.byteSize() is past-the-end.
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> VarInt.decode(seg, 1L, 300L));
        assertEquals(CorruptSectionException.SECTION_DATA, ex.sectionName());
    }

    // Finding: H-DT-5
    // Bug: encode into a dst byte[] too small to hold the value may silently under-write
    // or throw a misleading low-level ArrayIndexOutOfBoundsException.
    // Correct behavior: throw IndexOutOfBoundsException (or subclass) at the earliest point,
    // with a message identifying dst length vs required bytes. No partial write.
    // Fix location: VarInt.encode(int, byte[], int)
    // Regression watch: encode with dst large enough must still succeed.
    @Test
    void encodeDstTooSmallThrowsIndexOutOfBounds() {
        // Value 128 needs 2 bytes, but dst has room for only 1 at offset 0.
        byte[] dst = new byte[1];
        assertThrows(IndexOutOfBoundsException.class, () -> VarInt.encode(128, dst, 0));
    }

    // Finding: H-DT-6 (promoted ABSENT — VarInt ByteBuffer decode position advancement)
    // Bug: decode returns bytesConsumed but leaves src.position() unchanged, forcing callers
    // to duplicate the advancement and causing off-by-N in streaming scans.
    // Correct behavior: on successful decode, src.position() advances by bytesConsumed
    // (matching ByteBuffer.getInt/getLong convention). On rejection, position is undefined.
    // Fix location: VarInt.decode(ByteBuffer, long)
    // Regression watch: DecodedVarInt.bytesConsumed must still equal the number of bytes read.
    @Test
    void decodeByteBufferAdvancesPositionByBytesConsumed() throws Exception {
        // Value 128 encodes as 2 bytes (0x80, 0x01).
        byte[] bytes = new byte[4];
        int written = VarInt.encode(128, bytes, 0);
        assertEquals(2, written);

        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int startPos = buf.position();
        VarInt.DecodedVarInt decoded = VarInt.decode(buf, 0L);

        assertEquals(128, decoded.value());
        assertEquals(2, decoded.bytesConsumed());
        assertEquals(startPos + decoded.bytesConsumed(), buf.position(),
                "ByteBuffer position must advance by DecodedVarInt.bytesConsumed on success");
    }
}
