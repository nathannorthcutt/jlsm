package jlsm.sstable.internal;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

import jlsm.sstable.CorruptSectionException;

/**
 * Canonical unsigned LEB128 encode/decode utility for SSTable v5 per-block length prefixes.
 *
 * <p>
 * Encoding rules per spec:
 * </p>
 * <ul>
 * <li>Unsigned LEB128, 7 payload bits per byte, MSB=1 on all bytes except the final.</li>
 * <li>At most 4 bytes — values are constrained to the closed range
 * {@code [1, SSTableFormat.MAX_BLOCK_SIZE]}.</li>
 * <li>Encoding is canonical: a decoder must reject a multi-byte form whose high bits are all zero
 * (i.e. one more byte than necessary).</li>
 * </ul>
 *
 * <p>
 * Decode rejects, by throwing {@link CorruptSectionException} with
 * {@link CorruptSectionException#SECTION_DATA section=data} and both checksum fields set to
 * {@code 0}:
 * </p>
 * <ul>
 * <li>a 5th continuation byte,</li>
 * <li>a decoded value that exceeds {@code SSTableFormat.MAX_BLOCK_SIZE},</li>
 * <li>a decoded value equal to {@code 0},</li>
 * <li>a non-canonical encoding whose final byte's payload bits are all zero.</li>
 * </ul>
 *
 * @spec sstable.end-to-end-integrity.R1
 * @spec sstable.end-to-end-integrity.R2
 * @spec sstable.end-to-end-integrity.R5
 * @spec sstable.end-to-end-integrity.R6
 */
public final class VarInt {

    private VarInt() {
        // no instances
    }

    /**
     * Result of a successful decode: the logical value and the number of bytes consumed from the
     * source (1..4).
     *
     * @spec sstable.end-to-end-integrity.R2
     * @spec sstable.end-to-end-integrity.R5
     */
    public record DecodedVarInt(int value, int bytesConsumed) {
    }

    /**
     * Encode {@code value} as canonical unsigned LEB128 into {@code dst} starting at
     * {@code dstOffset}.
     *
     * @return number of bytes written (1..4)
     * @throws IOException if {@code value < 1} or {@code value > SSTableFormat.MAX_BLOCK_SIZE}
     * @spec sstable.end-to-end-integrity.R1
     * @spec sstable.end-to-end-integrity.R2
     * @spec sstable.end-to-end-integrity.R5
     */
    public static int encode(int value, byte[] dst, int dstOffset) throws IOException {
        if (value < 1 || value > SSTableFormat.MAX_BLOCK_SIZE) {
            throw new IOException("VarInt value out of range: " + value + " (must be in [1, "
                    + SSTableFormat.MAX_BLOCK_SIZE + "])");
        }
        int v = value;
        int written = 0;
        while ((v & ~0x7F) != 0) {
            dst[dstOffset + written++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        dst[dstOffset + written++] = (byte) (v & 0x7F);
        return written;
    }

    /**
     * Return the number of bytes {@link #encode(int, byte[], int)} would write for {@code value},
     * without writing anything.
     *
     * @throws IllegalArgumentException if {@code value < 1} or
     *             {@code value > SSTableFormat.MAX_BLOCK_SIZE}
     * @spec sstable.end-to-end-integrity.R5
     */
    public static int encodedLength(int value) {
        if (value < 1 || value > SSTableFormat.MAX_BLOCK_SIZE) {
            throw new IllegalArgumentException("VarInt value out of range: " + value
                    + " (must be in [1, " + SSTableFormat.MAX_BLOCK_SIZE + "])");
        }
        if (value < 0x80) {
            return 1;
        }
        if (value < 0x4000) {
            return 2;
        }
        if (value < 0x200000) {
            return 3;
        }
        return 4;
    }

    /**
     * Decode a canonical unsigned LEB128 value from {@code src} at its current position.
     *
     * @param src the byte buffer to read from
     * @param absoluteOffsetForDiagnostic absolute file offset of the first byte being decoded (used
     *            only for diagnostic messages)
     * @throws CorruptSectionException with {@link CorruptSectionException#SECTION_DATA} if any of
     *             the canonical-LEB128 rejection conditions holds
     * @spec sstable.end-to-end-integrity.R2
     * @spec sstable.end-to-end-integrity.R5
     * @spec sstable.end-to-end-integrity.R6
     */
    public static DecodedVarInt decode(ByteBuffer src, long absoluteOffsetForDiagnostic)
            throws CorruptSectionException {
        int result = 0;
        int shift = 0;
        int bytesRead = 0;
        byte lastByte = 0;
        while (true) {
            if (bytesRead >= 4) {
                throw corruption(absoluteOffsetForDiagnostic,
                        "VarInt exceeds 4 bytes (5th continuation byte)");
            }
            if (!src.hasRemaining()) {
                throw corruption(absoluteOffsetForDiagnostic,
                        "truncated VarInt: buffer exhausted after " + bytesRead + " bytes");
            }
            byte b = src.get();
            lastByte = b;
            bytesRead++;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        validateDecoded(result, bytesRead, lastByte, absoluteOffsetForDiagnostic);
        return new DecodedVarInt(result, bytesRead);
    }

    /**
     * Decode a canonical unsigned LEB128 value from {@code src} at {@code offsetWithinSegment}.
     *
     * @param src the memory segment to read from
     * @param offsetWithinSegment byte offset inside {@code src}
     * @param absoluteOffsetForDiagnostic absolute file offset of the first byte being decoded (used
     *            only for diagnostic messages)
     * @throws CorruptSectionException with {@link CorruptSectionException#SECTION_DATA} if any of
     *             the canonical-LEB128 rejection conditions holds
     * @spec sstable.end-to-end-integrity.R2
     * @spec sstable.end-to-end-integrity.R5
     * @spec sstable.end-to-end-integrity.R6
     */
    public static DecodedVarInt decode(MemorySegment src, long offsetWithinSegment,
            long absoluteOffsetForDiagnostic) throws CorruptSectionException {
        int result = 0;
        int shift = 0;
        int bytesRead = 0;
        byte lastByte = 0;
        final long segSize = src.byteSize();
        while (true) {
            if (bytesRead >= 4) {
                throw corruption(absoluteOffsetForDiagnostic,
                        "VarInt exceeds 4 bytes (5th continuation byte)");
            }
            final long readAt = offsetWithinSegment + bytesRead;
            if (readAt < 0 || readAt >= segSize) {
                throw corruption(absoluteOffsetForDiagnostic,
                        "VarInt decode from MemorySegment at offset " + readAt
                                + ": past end of segment (byteSize=" + segSize + ")");
            }
            byte b = src.get(ValueLayout.JAVA_BYTE, readAt);
            lastByte = b;
            bytesRead++;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        validateDecoded(result, bytesRead, lastByte, absoluteOffsetForDiagnostic);
        return new DecodedVarInt(result, bytesRead);
    }

    private static void validateDecoded(int result, int bytesRead, byte lastByte,
            long absoluteOffsetForDiagnostic) throws CorruptSectionException {
        if (result == 0) {
            throw corruption(absoluteOffsetForDiagnostic,
                    "VarInt value is zero (zero-length blocks are illegal)");
        }
        // result can be negative when bytes of the 4-byte encoding set bits into the high
        // half of the int (28-bit LEB128 values can exceed 2^31-1? no, 4*7=28 bits, so max
        // is 2^28-1 = 268_435_455, always non-negative). Still guard with unsigned compare
        // as a defensive measure.
        if (Integer.toUnsignedLong(result) > (long) SSTableFormat.MAX_BLOCK_SIZE) {
            throw corruption(absoluteOffsetForDiagnostic,
                    "VarInt value %d exceeds MAX_BLOCK_SIZE (%d)".formatted(
                            Integer.toUnsignedLong(result), (long) SSTableFormat.MAX_BLOCK_SIZE));
        }
        if (bytesRead > 1 && lastByte == 0x00) {
            throw corruption(absoluteOffsetForDiagnostic,
                    "non-canonical VarInt: trailing zero-payload byte");
        }
    }

    private static CorruptSectionException corruption(long absoluteOffset, String reason) {
        return new CorruptSectionException(CorruptSectionException.SECTION_DATA,
                "VarInt decode failed at file offset " + absoluteOffset + ": " + reason);
    }
}
