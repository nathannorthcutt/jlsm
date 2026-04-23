package jlsm.encryption.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.TenantId;

/**
 * HKDF-SHA256 (RFC 5869) derivation with length-prefixed domain separation. Used to
 * derive per-field subkeys from a DEK without allowing ambiguity between e.g.
 * {@code tenant="a",table="bc"} and {@code tenant="ab",table="c"}.
 *
 * <p>The {@code info} parameter is the only domain-separation vehicle in HKDF (the
 * salt is typically fixed per deployment); R11 specifies length-prefixed components to
 * block canonicalization-style attacks.
 *
 * <p>All intermediate heap buffers must be zeroed in {@code finally} blocks
 * (R16c).
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R9, R10, R10a, R11,
 * R16, R16a, R16c, R59.
 */
public final class Hkdf {

    private Hkdf() {}

    /**
     * Build the length-prefixed {@code info} parameter for per-field key derivation.
     * Layout: {@code "jlsm-field-key:"} literal, then for each of
     * {@code tenantId.value()}, {@code domainId.value()}, {@code tableName},
     * {@code fieldName} a 4-byte big-endian length followed by the UTF-8 bytes,
     * followed by a 4-byte big-endian dekVersion.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if any of {@code tableName} or
     *         {@code fieldName} is empty
     */
    public static byte[] buildFieldKeyInfo(
            TenantId tenantId,
            DomainId domainId,
            String tableName,
            String fieldName,
            DekVersion dekVersion) {
        throw new UnsupportedOperationException("Hkdf.buildFieldKeyInfo stub — WU-2 scope");
    }

    /**
     * HKDF-Extract: {@code HMAC-SHA256(salt, ikm)}. Zeros intermediate buffers.
     *
     * @throws NullPointerException if either argument is null
     */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        throw new UnsupportedOperationException("Hkdf.extract stub — WU-2 scope");
    }

    /**
     * HKDF-Expand with multi-counter output (R16a). Counter starts at {@code 0x01} and
     * increments; final block truncated to {@code outLenBytes}. Zeros intermediate
     * buffers.
     *
     * @throws NullPointerException if {@code prk} or {@code info} is null
     * @throws IllegalArgumentException if {@code outLenBytes} is not positive or
     *         exceeds HKDF's 255*HashLen limit
     */
    public static byte[] expand(byte[] prk, byte[] info, int outLenBytes) {
        throw new UnsupportedOperationException("Hkdf.expand stub — WU-2 scope");
    }

    /**
     * Full extract + expand into an off-heap segment owned by {@code callerArena}.
     * Copies {@code dekBytes} to a scoped heap buffer, derives, writes into a caller-
     * arena-allocated segment, zeroes all intermediate heap arrays in {@code finally}.
     * Rejects DEKs shorter than 16 bytes (R59).
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if DEK is shorter than 16 bytes or
     *         {@code outLenBytes} is not in {@code (0, 255*32]}
     */
    public static MemorySegment deriveKey(
            MemorySegment dekBytes,
            byte[] salt,
            byte[] info,
            int outLenBytes,
            Arena callerArena) {
        throw new UnsupportedOperationException("Hkdf.deriveKey stub — WU-2 scope");
    }
}
