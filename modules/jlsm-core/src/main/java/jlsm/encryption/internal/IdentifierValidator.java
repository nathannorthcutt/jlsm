package jlsm.encryption.internal;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * UTF-8 identifier validator for the SSTable footer scope-signalling layer. Enforces the identifier
 * rules from {@code sstable.footer-encryption-scope} on both writer-side (eager rejection of bad
 * input) and reader-side (defensive rejection of bad on-disk bytes without leaking byte values into
 * exception messages).
 *
 * <p>
 * Rules (R2b, R2c, R2e):
 * <ul>
 * <li>Length 1..65536 bytes (UTF-8 encoded).</li>
 * <li>No C0/C1 control bytes ({@code [0x00..0x1F]}, {@code 0x7F}).</li>
 * <li>No Unicode general categories Cc, Cf, Co, Cs.</li>
 * <li>No additional line/paragraph separators U+0085, U+2028, U+2029.</li>
 * <li>Well-formed UTF-8 (no malformed/incomplete sequences, no overlong encodings).</li>
 * </ul>
 *
 * <p>
 * Side effects: none.<br>
 * Error conditions: {@link IllegalArgumentException} on writer side; {@link IOException} on reader
 * side.<br>
 * Shared state: none.
 *
 * @spec sstable.footer-encryption-scope.R2b
 * @spec sstable.footer-encryption-scope.R2c
 * @spec sstable.footer-encryption-scope.R2e
 * @spec sstable.footer-encryption-scope.R12
 */
public final class IdentifierValidator {

    /** Maximum identifier UTF-8 byte length (R2b). */
    public static final int MAX_LENGTH = 65536;

    /** Minimum identifier UTF-8 byte length (R2c). */
    public static final int MIN_LENGTH = 1;

    private IdentifierValidator() {
    }

    /**
     * Validates a UTF-8 identifier per R2e on the writer side: well-formed UTF-8; length 1..65536
     * bytes; no {@code [0x00-0x1F]} / {@code 0x7F}; no Cc/Cf/Co/Cs; no U+0085 / U+2028 / U+2029.
     *
     * @param identifier the identifier string to validate
     * @param fieldName the logical field name to embed in any failure message
     * @throws IllegalArgumentException if the identifier violates any rule
     * @throws NullPointerException if {@code identifier} or {@code fieldName} is null
     * @spec sstable.footer-encryption-scope.R2b
     * @spec sstable.footer-encryption-scope.R2c
     * @spec sstable.footer-encryption-scope.R2e
     */
    public static void validateForWrite(String identifier, String fieldName) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        // Strict UTF-8 encode: catches unpaired surrogates (Cs at the source) before length
        // checks or codepoint scan. The encoded byte buffer's position equals the encoded byte
        // count; we use getBytes for the exact-length array.
        try {
            final var encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            // Discard the result — we only need to know the encode succeeds (well-formed UTF-8).
            encoder.encode(java.nio.CharBuffer.wrap(identifier));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    fieldName + ": identifier is not well-formed UTF-8 (R2e)");
        }
        final byte[] bytes = identifier.getBytes(StandardCharsets.UTF_8);
        validateLength(bytes.length, fieldName);
        validateCodepointsForWrite(bytes, fieldName);
    }

    /**
     * Validates a parsed identifier from on-disk bytes; throws {@link IOException} on violation.
     * R12 forbids exposing concrete byte values in failure messages — the caller's exception text
     * must reference position only, never raw bytes.
     *
     * @param utf8Bytes the on-disk identifier bytes
     * @param fieldName the logical field name to embed in any failure message
     * @throws IOException if the identifier is malformed or violates a rule
     * @throws NullPointerException if {@code utf8Bytes} or {@code fieldName} is null
     * @spec sstable.footer-encryption-scope.R2b
     * @spec sstable.footer-encryption-scope.R2c
     * @spec sstable.footer-encryption-scope.R2e
     * @spec sstable.footer-encryption-scope.R12
     */
    public static void validateForRead(byte[] utf8Bytes, String fieldName) throws IOException {
        Objects.requireNonNull(utf8Bytes, "utf8Bytes must not be null");
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        if (utf8Bytes.length < MIN_LENGTH) {
            throw new IOException(
                    fieldName + ": identifier byte length is below minimum (R2c) — corrupt footer");
        }
        if (utf8Bytes.length > MAX_LENGTH) {
            throw new IOException(
                    fieldName + ": identifier byte length exceeds maximum (R2b) — corrupt footer");
        }
        // Decode via strict UTF-8 decoder; any malformed sequence surfaces a position-only
        // diagnostic (no byte values revealed per R12).
        final String decoded;
        try {
            final var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            decoded = decoder.decode(java.nio.ByteBuffer.wrap(utf8Bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException(fieldName
                    + ": identifier bytes are not well-formed UTF-8 (R2e) — corrupt footer");
        }
        // Now scan for forbidden codepoints. Use position counters (codepoint index), no byte
        // values, to honour R12.
        int cpIndex = 0;
        for (int i = 0; i < decoded.length();) {
            int cp = decoded.codePointAt(i);
            if (isForbiddenCodepoint(cp)) {
                throw new IOException(fieldName + ": identifier contains forbidden codepoint at"
                        + " position " + cpIndex + " (R2e) — corrupt footer");
            }
            i += Character.charCount(cp);
            cpIndex++;
        }
    }

    private static void validateLength(int length, String fieldName) {
        if (length < MIN_LENGTH) {
            throw new IllegalArgumentException(fieldName
                    + ": identifier UTF-8 byte length must be >= " + MIN_LENGTH + " (R2c)");
        }
        if (length > MAX_LENGTH) {
            throw new IllegalArgumentException(fieldName
                    + ": identifier UTF-8 byte length must be <= " + MAX_LENGTH + " (R2b)");
        }
    }

    private static void validateCodepointsForWrite(byte[] utf8Bytes, String fieldName) {
        // Decode via strict UTF-8 decoder; any pre-encoded violation should already have
        // surfaced from the encoder check above. Use the decoded form to scan for forbidden
        // codepoints.
        final String decoded;
        try {
            final var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            decoded = decoder.decode(java.nio.ByteBuffer.wrap(utf8Bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    fieldName + ": identifier is not well-formed UTF-8 (R2e)");
        }
        int cpIndex = 0;
        for (int i = 0; i < decoded.length();) {
            int cp = decoded.codePointAt(i);
            if (isForbiddenCodepoint(cp)) {
                throw new IllegalArgumentException(
                        fieldName + ": identifier contains forbidden codepoint at position "
                                + cpIndex + " (R2e)");
            }
            i += Character.charCount(cp);
            cpIndex++;
        }
    }

    /**
     * Returns true if the given Unicode codepoint must be rejected per R2e.
     */
    private static boolean isForbiddenCodepoint(int cp) {
        // C0 controls and DEL: 0x00..0x1F and 0x7F
        if ((cp >= 0x00 && cp <= 0x1F) || cp == 0x7F) {
            return true;
        }
        // Explicit additional separators (R2e)
        if (cp == 0x0085 || cp == 0x2028 || cp == 0x2029) {
            return true;
        }
        // Cc (Other, Control), Cf (Other, Format), Co (Other, Private Use), Cs (Other, Surrogate)
        switch (Character.getType(cp)) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.PRIVATE_USE:
            case Character.SURROGATE:
                return true;
            default:
                return false;
        }
    }
}
