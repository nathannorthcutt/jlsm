package jlsm.encryption.internal;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.TenantId;

/**
 * HKDF-SHA256 (RFC 5869) derivation with length-prefixed domain separation. Used to derive
 * per-field subkeys from a DEK without allowing ambiguity between e.g.
 * {@code tenant="a",table="bc"} and {@code tenant="ab",table="c"}.
 *
 * <p>
 * The {@code info} parameter is the only domain-separation vehicle in HKDF (the salt is typically
 * fixed per deployment); R11 specifies length-prefixed components to block canonicalization-style
 * attacks.
 *
 * <p>
 * All intermediate heap buffers must be zeroed in {@code finally} blocks (R16c).
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R9, R10, R10a, R11, R16, R16a, R16c,
 * R59.
 */
public final class Hkdf {

    private static final Logger LOG = System.getLogger(Hkdf.class.getName());
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int HASH_LEN = 32;
    private static final int MAX_EXPAND_BYTES = 255 * HASH_LEN;
    private static final int MIN_DEK_BYTES = 16;
    private static final int RECOMMENDED_MIN_DEK_BYTES = 32;
    private static final byte[] FIELD_KEY_INFO_PREFIX = "jlsm-field-key:"
            .getBytes(StandardCharsets.UTF_8);

    private Hkdf() {
    }

    /**
     * Build the length-prefixed {@code info} parameter for per-field key derivation. Layout:
     * {@code "jlsm-field-key:"} literal, then for each of {@code tenantId.value()},
     * {@code domainId.value()}, {@code tableName}, {@code fieldName} a 4-byte big-endian length
     * followed by the UTF-8 bytes, followed by a 4-byte big-endian dekVersion.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if any of {@code tableName} or {@code fieldName} is empty
     */
    public static byte[] buildFieldKeyInfo(TenantId tenantId, DomainId domainId, String tableName,
            String fieldName, DekVersion dekVersion) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        Objects.requireNonNull(tableName, "tableName must not be null");
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        Objects.requireNonNull(dekVersion, "dekVersion must not be null");
        if (tableName.isEmpty()) {
            throw new IllegalArgumentException("tableName must not be empty");
        }
        if (fieldName.isEmpty()) {
            throw new IllegalArgumentException("fieldName must not be empty");
        }

        final byte[] tenantBytes = tenantId.value().getBytes(StandardCharsets.UTF_8);
        final byte[] domainBytes = domainId.value().getBytes(StandardCharsets.UTF_8);
        final byte[] tableBytes = tableName.getBytes(StandardCharsets.UTF_8);
        final byte[] fieldBytes = fieldName.getBytes(StandardCharsets.UTF_8);

        // Prefix + 4 × (4-byte length + bytes) + 4-byte dekVersion
        final int total = FIELD_KEY_INFO_PREFIX.length + 4 * 4 + tenantBytes.length
                + domainBytes.length + tableBytes.length + fieldBytes.length + 4;
        final ByteBuffer buf = ByteBuffer.allocate(total);
        buf.put(FIELD_KEY_INFO_PREFIX);
        appendLengthPrefixed(buf, tenantBytes);
        appendLengthPrefixed(buf, domainBytes);
        appendLengthPrefixed(buf, tableBytes);
        appendLengthPrefixed(buf, fieldBytes);
        buf.putInt(dekVersion.value());
        assert !buf.hasRemaining() : "buildFieldKeyInfo sizing miscalculation";
        return buf.array();
    }

    private static void appendLengthPrefixed(ByteBuffer buf, byte[] bytes) {
        buf.putInt(bytes.length);
        buf.put(bytes);
    }

    /**
     * HKDF-Extract: {@code HMAC-SHA256(salt, ikm)}. Zeros intermediate buffers.
     *
     * @throws NullPointerException if {@code ikm} is null
     */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        Objects.requireNonNull(ikm, "ikm must not be null");
        // R10: salt may be null/empty → default 32 zero bytes per RFC 5869 §2.2.
        final boolean saltIsLocalCopy = (salt == null || salt.length == 0);
        final byte[] saltToUse = saltIsLocalCopy ? new byte[HASH_LEN] : salt;
        try {
            final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            // R68a: SecretKeySpec is a method-scoped local with no field retention.
            mac.init(new SecretKeySpec(saltToUse, HMAC_ALGORITHM));
            return mac.doFinal(ikm);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable — required for HKDF-Extract",
                    e);
        } finally {
            if (saltIsLocalCopy) {
                Arrays.fill(saltToUse, (byte) 0);
            }
        }
    }

    /**
     * HKDF-Expand with multi-counter output (R16a). Counter starts at {@code 0x01} and increments;
     * final block truncated to {@code outLenBytes}. Zeros intermediate buffers.
     *
     * @throws NullPointerException if {@code prk} or {@code info} is null
     * @throws IllegalArgumentException if {@code outLenBytes} is not positive or exceeds HKDF's
     *             255*HashLen limit
     */
    public static byte[] expand(byte[] prk, byte[] info, int outLenBytes) {
        Objects.requireNonNull(prk, "prk must not be null");
        Objects.requireNonNull(info, "info must not be null");
        if (outLenBytes <= 0) {
            throw new IllegalArgumentException("outLenBytes must be positive, got " + outLenBytes);
        }
        if (outLenBytes > MAX_EXPAND_BYTES) {
            throw new IllegalArgumentException("outLenBytes exceeds HKDF limit 255*HashLen="
                    + MAX_EXPAND_BYTES + ", got " + outLenBytes);
        }

        final int numBlocks = (outLenBytes + HASH_LEN - 1) / HASH_LEN;
        assert numBlocks >= 1 && numBlocks <= 255 : "block count must fit in a byte counter";

        final byte[] result = new byte[outLenBytes];
        byte[] previousBlock = new byte[0];
        byte[] currentBlock = null;
        try {
            final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            final SecretKeySpec keySpec = new SecretKeySpec(prk, HMAC_ALGORITHM);
            mac.init(keySpec);

            int written = 0;
            for (int counter = 1; counter <= numBlocks; counter++) {
                mac.update(previousBlock);
                mac.update(info);
                mac.update((byte) counter);
                currentBlock = mac.doFinal();
                final int copyLen = Math.min(HASH_LEN, outLenBytes - written);
                System.arraycopy(currentBlock, 0, result, written, copyLen);
                written += copyLen;
                // Rotate: previous ← current; zero the old previousBlock before replacing.
                Arrays.fill(previousBlock, (byte) 0);
                previousBlock = currentBlock;
                currentBlock = null;
            }
            assert written == outLenBytes : "expand should have produced exactly outLenBytes";
            return result;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable — required for HKDF-Expand",
                    e);
        } finally {
            // R16c: zero intermediate heap buffers.
            if (previousBlock != null) {
                Arrays.fill(previousBlock, (byte) 0);
            }
            if (currentBlock != null) {
                Arrays.fill(currentBlock, (byte) 0);
            }
        }
    }

    /**
     * Full extract + expand into an off-heap segment owned by {@code callerArena}. Copies
     * {@code dekBytes} to a scoped heap buffer, derives, writes into a caller- arena-allocated
     * segment, zeroes all intermediate heap arrays in {@code finally}. Rejects DEKs shorter than 16
     * bytes (R59).
     *
     * @throws NullPointerException if any non-salt argument is null
     * @throws IllegalArgumentException if DEK is shorter than 16 bytes or {@code outLenBytes} is
     *             not in {@code (0, 255*32]}
     */
    public static MemorySegment deriveKey(MemorySegment dekBytes, byte[] salt, byte[] info,
            int outLenBytes, Arena callerArena) {
        Objects.requireNonNull(dekBytes, "dekBytes must not be null");
        Objects.requireNonNull(info, "info must not be null");
        Objects.requireNonNull(callerArena, "callerArena must not be null");
        if (outLenBytes <= 0) {
            throw new IllegalArgumentException("outLenBytes must be positive, got " + outLenBytes);
        }
        if (outLenBytes > MAX_EXPAND_BYTES) {
            throw new IllegalArgumentException("outLenBytes exceeds HKDF limit 255*HashLen="
                    + MAX_EXPAND_BYTES + ", got " + outLenBytes);
        }
        final long dekLen = dekBytes.byteSize();
        if (dekLen < MIN_DEK_BYTES) {
            throw new IllegalArgumentException(
                    "DEK must be at least " + MIN_DEK_BYTES + " bytes (R59), got " + dekLen);
        }
        if (dekLen > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("DEK larger than Integer.MAX_VALUE is unsupported");
        }
        if (dekLen < RECOMMENDED_MIN_DEK_BYTES) {
            LOG.log(Level.WARNING,
                    "DEK length {0} is below the recommended {1} bytes (R59a) — entropy may be"
                            + " insufficient for long-term use.",
                    dekLen, RECOMMENDED_MIN_DEK_BYTES);
        }

        byte[] ikm = null;
        byte[] prk = null;
        byte[] okm = null;
        try {
            ikm = new byte[(int) dekLen];
            MemorySegment.copy(dekBytes, ValueLayout.JAVA_BYTE, 0, ikm, 0, (int) dekLen);

            prk = extract(salt, ikm);
            okm = expand(prk, info, outLenBytes);

            final MemorySegment out = callerArena.allocate(outLenBytes);
            MemorySegment.copy(okm, 0, out, ValueLayout.JAVA_BYTE, 0, outLenBytes);
            return out;
        } finally {
            // R16c: zero every intermediate heap buffer that held secret material.
            if (ikm != null) {
                Arrays.fill(ikm, (byte) 0);
            }
            if (prk != null) {
                Arrays.fill(prk, (byte) 0);
            }
            if (okm != null) {
                Arrays.fill(okm, (byte) 0);
            }
        }
    }
}
