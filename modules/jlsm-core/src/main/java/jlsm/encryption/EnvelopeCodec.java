package jlsm.encryption;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * 4-byte big-endian DEK-version prefix codec for the per-field ciphertext envelope. Every
 * encryption variant — Opaque (AES-GCM), Deterministic (AES-SIV), OrderPreserving (Boldyreva OPE),
 * DistancePreserving (DCPE/SAP) — prepends the same 4-byte plaintext DEK version tag to its variant
 * body so the read path can locate the correct DEK via wait-free R64 registry lookup before any
 * cryptographic work fires.
 *
 * <p>
 * Receives: variant body bytes (encode), or a full envelope byte array (parse / strip).<br>
 * Returns: prefixed envelope bytes (encode), parsed positive DEK version (parse), or variant body
 * without prefix (strip).<br>
 * Side effects: none.<br>
 * Error conditions: {@link IOException} on under-length envelope or non-positive parsed
 * version.<br>
 * Shared state: none.
 *
 * <p>
 * Governing spec: {@code encryption.ciphertext-envelope} R1, R1c, R2.
 *
 * @spec encryption.ciphertext-envelope.R1
 * @spec encryption.ciphertext-envelope.R1c
 * @spec encryption.ciphertext-envelope.R2
 */
public final class EnvelopeCodec {

    /** Length of the DEK version prefix in bytes. */
    public static final int VERSION_PREFIX_LENGTH = 4;

    private EnvelopeCodec() {
    }

    /**
     * Prepends a 4-byte big-endian DEK version to variant body bytes.
     *
     * @param dekVersion positive DEK version tag (must be {@code > 0})
     * @param variantBody non-null variant ciphertext body bytes
     * @return non-null envelope byte array of length
     *         {@code VERSION_PREFIX_LENGTH + variantBody.length}
     * @throws NullPointerException if {@code variantBody} is null
     * @throws IllegalArgumentException if {@code dekVersion} is not positive
     * @spec encryption.ciphertext-envelope.R1
     * @spec encryption.ciphertext-envelope.R1c
     */
    public static byte[] prefixVersion(int dekVersion, byte[] variantBody) {
        Objects.requireNonNull(variantBody, "variantBody must not be null");
        if (dekVersion <= 0) {
            // R2 — writer-side guard: 0 / negative DEK versions are never valid envelope tags.
            // Eager validation prevents allocation of a corrupt envelope.
            throw new IllegalArgumentException(
                    "dekVersion must be a positive integer, got " + dekVersion);
        }
        final byte[] envelope = new byte[VERSION_PREFIX_LENGTH + variantBody.length];
        // R1c — write 4-byte big-endian (high byte first).
        envelope[0] = (byte) (dekVersion >>> 24);
        envelope[1] = (byte) (dekVersion >>> 16);
        envelope[2] = (byte) (dekVersion >>> 8);
        envelope[3] = (byte) dekVersion;
        // Defensive copy — caller's body must not share backing storage with the envelope.
        System.arraycopy(variantBody, 0, envelope, VERSION_PREFIX_LENGTH, variantBody.length);
        return envelope;
    }

    /**
     * Parses a 4-byte big-endian DEK version from envelope bytes; rejects 0 / negative.
     *
     * @param envelope non-null envelope byte array, length {@code >= 4}
     * @return parsed positive DEK version
     * @throws IOException on under-length envelope or non-positive parsed version
     * @throws NullPointerException if {@code envelope} is null
     * @spec encryption.ciphertext-envelope.R2
     */
    public static int parseVersion(byte[] envelope) throws IOException {
        Objects.requireNonNull(envelope, "envelope must not be null");
        if (envelope.length < VERSION_PREFIX_LENGTH) {
            // R2 reader-side guard: under-length envelopes are corrupt, surfaced as IOException
            // rather than AIOOBE so callers can distinguish them from JVM bugs.
            throw new IOException("ciphertext envelope is too short to contain DEK version: "
                    + envelope.length + " bytes (need >= " + VERSION_PREFIX_LENGTH + ")");
        }
        // R1c — read 4-byte big-endian.
        final int version = ((envelope[0] & 0xFF) << 24) | ((envelope[1] & 0xFF) << 16)
                | ((envelope[2] & 0xFF) << 8) | (envelope[3] & 0xFF);
        if (version <= 0) {
            // R2 / R2a — zero and negative versions follow the same code path as
            // version-not-found in the registry. Both result in an IOException; the
            // wait-free property is preserved because no separate exception class is thrown.
            throw new IOException(
                    "ciphertext envelope DEK version is non-positive (corrupt envelope): "
                            + version);
        }
        return version;
    }

    /**
     * Strips the 4-byte prefix, returning variant body bytes. Length-only operation; does not
     * validate the parsed version (use {@link #parseVersion} for that).
     *
     * <p>
     * Sibling-method symmetry with {@link #parseVersion}: both entry points reject an under-length
     * envelope with {@link IOException} so identical on-disk corruption shapes surface as identical
     * exception types regardless of which sibling a caller invokes. Null input remains a
     * caller-side bug and is signalled with {@link NullPointerException}.
     *
     * @param envelope non-null envelope byte array, length {@code >= 4}
     * @return non-null variant body byte array of length
     *         {@code envelope.length - VERSION_PREFIX_LENGTH}
     * @throws NullPointerException if {@code envelope} is null
     * @throws IOException if {@code envelope.length < VERSION_PREFIX_LENGTH}
     */
    public static byte[] stripPrefix(byte[] envelope) throws IOException {
        Objects.requireNonNull(envelope, "envelope must not be null");
        if (envelope.length < VERSION_PREFIX_LENGTH) {
            // R2 reader-side guard symmetric with parseVersion: under-length envelopes are
            // on-disk corruption and surface as IOException so callers do not need a separate
            // catch clause for the sibling entry point.
            throw new IOException("envelope too short to strip prefix: " + envelope.length
                    + " bytes (need >= " + VERSION_PREFIX_LENGTH + ")");
        }
        // Defensive copy — the body must not share backing storage with the input envelope.
        return Arrays.copyOfRange(envelope, VERSION_PREFIX_LENGTH, envelope.length);
    }
}
