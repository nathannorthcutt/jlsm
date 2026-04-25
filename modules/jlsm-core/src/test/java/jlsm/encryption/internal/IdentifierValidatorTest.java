package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link IdentifierValidator}, covering both writer-side and reader-side rules: length
 * 1..65536 bytes, no C0/C1 controls (0x00..0x1F, 0x7F), no Unicode Cc/Cf/Co/Cs categories, no
 * U+0085/U+2028/U+2029, and well-formed UTF-8.
 *
 * <p>
 * R12 discipline: reader-side messages must not embed concrete byte values.
 *
 * @spec sstable.footer-encryption-scope.R2b
 * @spec sstable.footer-encryption-scope.R2c
 * @spec sstable.footer-encryption-scope.R2e
 * @spec sstable.footer-encryption-scope.R12
 */
final class IdentifierValidatorTest {

    private static final String FIELD = "tenantId";

    // --- writer-side: validateForWrite ---

    @Test
    void writerAcceptsAsciiIdentifier() {
        assertDoesNotThrow(() -> IdentifierValidator.validateForWrite("tenant-A.42", FIELD));
    }

    @Test
    void writerAcceptsBmpUnicodeLetters() {
        // covers: R2e — Letters category permitted
        assertDoesNotThrow(() -> IdentifierValidator.validateForWrite("équipe", FIELD));
    }

    @Test
    void writerRejectsNullIdentifier() {
        assertThrows(NullPointerException.class,
                () -> IdentifierValidator.validateForWrite(null, FIELD));
    }

    @Test
    void writerRejectsNullFieldName() {
        assertThrows(NullPointerException.class,
                () -> IdentifierValidator.validateForWrite("tenant", null));
    }

    @Test
    void writerRejectsEmptyIdentifier() {
        // covers: R2c — UTF-8 byte length must be >= 1
        assertThrows(IllegalArgumentException.class,
                () -> IdentifierValidator.validateForWrite("", FIELD));
    }

    @Test
    void writerRejectsIdentifierOver65536Bytes() {
        // covers: R2b — UTF-8 byte length cap is 65536
        final String tooLong = "a".repeat(65537);
        assertThrows(IllegalArgumentException.class,
                () -> IdentifierValidator.validateForWrite(tooLong, FIELD));
    }

    @Test
    void writerAcceptsIdentifierAt65536Bytes() {
        // covers: R2b — boundary
        final String exact = "a".repeat(65536);
        assertDoesNotThrow(() -> IdentifierValidator.validateForWrite(exact, FIELD));
    }

    @Test
    void writerAcceptsIdentifierAtSingleByte() {
        // covers: R2c — boundary
        assertDoesNotThrow(() -> IdentifierValidator.validateForWrite("a", FIELD));
    }

    @Test
    void writerRejectsControlByte0x00ThroughByte0x1F() {
        // covers: R2e — every byte in [0x00, 0x1F] is rejected
        for (int b = 0x00; b <= 0x1F; b++) {
            final String s = "ok" + (char) b + "tail";
            try {
                IdentifierValidator.validateForWrite(s, FIELD);
                fail("expected IllegalArgumentException for control byte 0x"
                        + Integer.toHexString(b));
            } catch (IllegalArgumentException expected) {
                // R12: writer-side may surface position; should not embed the raw byte value
                final String msg = expected.getMessage();
                assertNotNull(msg);
            }
        }
    }

    @Test
    void writerRejectsByte0x7F() {
        assertThrows(IllegalArgumentException.class,
                () -> IdentifierValidator.validateForWrite("oktail", FIELD));
    }

    @Test
    void writerRejectsCfFormatCharacters() {
        // covers: R2e — Cf includes U+200B, U+200C, U+200D, U+200E, U+200F, U+202A..U+202E,
        // U+2066..U+2069, U+FEFF
        final int[] cf = { 0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x202A, 0x202E, 0x2066, 0x2069,
                0xFEFF };
        for (int cp : cf) {
            final String s = "x" + new String(Character.toChars(cp)) + "y";
            try {
                IdentifierValidator.validateForWrite(s, FIELD);
                fail("expected IllegalArgumentException for Cf U+"
                        + Integer.toHexString(cp).toUpperCase());
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    void writerRejectsCoPrivateUseCharacter() {
        // covers: R2e — Co (Private Use), e.g. U+E000
        assertThrows(IllegalArgumentException.class, () -> IdentifierValidator
                .validateForWrite("x" + new String(Character.toChars(0xE000)) + "y", FIELD));
    }

    @Test
    void writerRejectsLineSeparatorU0085() {
        assertThrows(IllegalArgumentException.class,
                () -> IdentifierValidator.validateForWrite("xy", FIELD));
    }

    @Test
    void writerRejectsLineSeparatorU2028() {
        assertThrows(IllegalArgumentException.class,
                () -> IdentifierValidator.validateForWrite("x y", FIELD));
    }

    @Test
    void writerRejectsParagraphSeparatorU2029() {
        assertThrows(IllegalArgumentException.class,
                () -> IdentifierValidator.validateForWrite("x y", FIELD));
    }

    @Test
    void writerRejectsUnpairedHighSurrogate() {
        // covers: R2e — Cs (Surrogate) at the codepoint level. We construct a String
        // containing an unpaired high surrogate, which is not well-formed when encoded to UTF-8.
        final String malformed = "x\uD800y"; // unpaired high surrogate
        assertThrows(IllegalArgumentException.class,
                () -> IdentifierValidator.validateForWrite(malformed, FIELD));
    }

    // --- reader-side: validateForRead ---

    @Test
    void readerAcceptsAsciiIdentifier() {
        assertDoesNotThrow(() -> IdentifierValidator
                .validateForRead("tenant-A".getBytes(StandardCharsets.UTF_8), FIELD));
    }

    @Test
    void readerRejectsNullBytes() {
        assertThrows(NullPointerException.class,
                () -> IdentifierValidator.validateForRead(null, FIELD));
    }

    @Test
    void readerRejectsNullFieldName() {
        assertThrows(NullPointerException.class, () -> IdentifierValidator
                .validateForRead("ok".getBytes(StandardCharsets.UTF_8), null));
    }

    @Test
    void readerRejectsEmptyBytes() {
        // covers: R2c
        IOException thrown = assertThrows(IOException.class,
                () -> IdentifierValidator.validateForRead(new byte[0], FIELD));
        assertNotNull(thrown.getMessage());
    }

    @Test
    void readerRejectsLengthOver65536() {
        // covers: R2b
        final byte[] tooLong = new byte[65537];
        for (int i = 0; i < tooLong.length; i++) {
            tooLong[i] = (byte) 'a';
        }
        assertThrows(IOException.class, () -> IdentifierValidator.validateForRead(tooLong, FIELD));
    }

    @Test
    void readerRejectsControlByteAndDoesNotLeakByteValue() {
        // covers: R2e + R12 — message must not embed the offending byte value
        final byte[] withCtrl = new byte[]{ 'o', 'k', 0x07, 't', 'a', 'i', 'l' };
        IOException ex = assertThrows(IOException.class,
                () -> IdentifierValidator.validateForRead(withCtrl, FIELD));
        final String msg = ex.getMessage() == null ? "" : ex.getMessage();
        // R12: must not embed the byte literally as "0x07" or as decimal "7" surrounded by
        // value markers
        assertTrue(!msg.contains("0x07") && !msg.contains("0x7,") && !msg.contains("byte=7"),
                "R12 violation — message leaks raw byte value: " + msg);
        // R12: position-only diagnostic — the offending position is fine to include
    }

    @Test
    void readerRejectsByte0x7FAndDoesNotLeak() {
        final byte[] withDel = new byte[]{ 'o', 'k', 0x7F };
        IOException ex = assertThrows(IOException.class,
                () -> IdentifierValidator.validateForRead(withDel, FIELD));
        final String msg = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(!msg.contains("0x7F") && !msg.contains("0x7f"),
                "R12 violation — message leaks raw 0x7F byte value: " + msg);
    }

    @Test
    void readerRejectsMalformedUtf8Sequence() {
        // covers: R2e — well-formed UTF-8 mandate; 0xFE is not a valid start byte
        final byte[] malformed = new byte[]{ (byte) 0xFE, 'a', 'b' };
        IOException ex = assertThrows(IOException.class,
                () -> IdentifierValidator.validateForRead(malformed, FIELD));
        final String msg = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(!msg.contains("0xFE") && !msg.contains("0xfe"),
                "R12 violation — message leaks raw 0xFE byte value: " + msg);
    }

    @Test
    void readerRejectsOverlongUtf8Encoding() {
        // covers: R2e — overlong encoding: U+0041 ('A') in 2-byte form (0xC1 0x81) is malformed
        final byte[] overlong = new byte[]{ (byte) 0xC1, (byte) 0x81 };
        assertThrows(IOException.class, () -> IdentifierValidator.validateForRead(overlong, FIELD));
    }

    @Test
    void readerRejectsU2028BytesEncoded() {
        // covers: R2e — U+2028 in UTF-8: E2 80 A8
        final byte[] u2028 = new byte[]{ 'x', (byte) 0xE2, (byte) 0x80, (byte) 0xA8, 'y' };
        assertThrows(IOException.class, () -> IdentifierValidator.validateForRead(u2028, FIELD));
    }

    @Test
    void readerRejectsU0085BytesEncoded() {
        // U+0085 in UTF-8: C2 85
        final byte[] u0085 = new byte[]{ 'x', (byte) 0xC2, (byte) 0x85, 'y' };
        assertThrows(IOException.class, () -> IdentifierValidator.validateForRead(u0085, FIELD));
    }
}
