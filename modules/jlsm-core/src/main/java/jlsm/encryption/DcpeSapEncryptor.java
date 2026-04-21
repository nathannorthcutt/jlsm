package jlsm.encryption;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Contract: Distance-comparison-preserving encryption using Scale-And-Perturb (SAP). Encrypts a
 * {@code float[]} vector to a {@code float[]} of the same dimensionality, approximately preserving
 * distance relationships so that existing HNSW/IVF indexes work on encrypted vectors. Each
 * encryption is authenticated by a detached 16-byte HMAC-SHA256 tag bound to the seed, encrypted
 * values, and caller-supplied associated data (typically the UTF-8 field name).
 *
 * <p>
 * Encryption: {@code c = s * v + noise}, where {@code s} is a scaling factor derived from the key
 * and {@code noise} is sampled from a d-ball using a per-vector perturbation seed. Decryption
 * requires the stored seed and tag to reconstruct and authenticate the noise vector.
 *
 * <p>
 * Wrong-key decryption, tampered seed/values/tag, and cross-field substitution are all caught by
 * MAC verification, which runs in constant time via {@link MessageDigest#isEqual(byte[], byte[])}
 * before any arithmetic inverse is applied.
 *
 * <p>
 * Governed by: F03.R42-R47, F03.R79, F41.R22;
 * .kb/algorithms/encryption/vector-encryption-approaches.md
 */
public final class DcpeSapEncryptor implements AutoCloseable {

    /** Detached MAC tag length in bytes (truncated from HMAC-SHA256's 32-byte output). */
    public static final int MAC_TAG_BYTES = 16;

    /** Bytes per encrypted float in the serialized blob format: {@code N * 4}. */
    public static final int BYTES_PER_FLOAT = 4;

    /** Seed length in bytes in the serialized blob format: big-endian long. */
    public static final int SEED_BYTES = 8;

    private final double scaleFactor;
    private final int dimensions;
    private final SecureRandom seedRng;
    /**
     * Per-instance HMAC key spec for detached authentication tags. Derived once from the master key
     * via HMAC-SHA256("dcpe-mac-key") and zeroed in the constructor's finally block.
     */
    private final SecretKeySpec macKeySpec;
    private volatile boolean closed;

    /**
     * Creates a DCPE Scale-And-Perturb encryptor with the given key.
     *
     * @param keyHolder the key holder providing key material
     * @param dimensions the expected vector dimensionality; must be positive
     * @throws IllegalArgumentException if dimensions is not positive
     */
    // @spec encryption.primitives-variants.R55, F41.R22 — derive MAC sub-key with domain-separated label
    public DcpeSapEncryptor(EncryptionKeyHolder keyHolder, int dimensions) {
        Objects.requireNonNull(keyHolder, "keyHolder must not be null");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive, got " + dimensions);
        }
        this.dimensions = dimensions;

        final byte[] keyBytes = keyHolder.getKeyBytes();
        byte[] dcpeMacKey = null;
        try {
            // Derive scaling factor from key: hash first 8 bytes to a positive double in [1.5, 3.5)
            final long keyLong = ByteBuffer.wrap(keyBytes, 0, 8).getLong();
            this.scaleFactor = 1.5 + 2.0 * ((double) (keyLong >>> 1) / (double) Long.MAX_VALUE);
            assert scaleFactor >= 1.5 && scaleFactor < 3.5 : "scaleFactor out of expected range";

            // Seed RNG from key bytes for generating per-vector seeds (SecureRandom
            // internally clones the seed, so zeroing keyBytes below is sufficient).
            this.seedRng = new SecureRandom(keyBytes);

            dcpeMacKey = hmacSha256(keyBytes, "dcpe-mac-key");
            this.macKeySpec = new SecretKeySpec(dcpeMacKey, "HmacSHA256");
        } finally {
            // @spec encryption.primitives-key-holder.R8, F41.R16 — zero intermediates in finally, even on exception
            Arrays.fill(keyBytes, (byte) 0);
            if (dcpeMacKey != null) {
                Arrays.fill(dcpeMacKey, (byte) 0);
            }
        }
    }

    /**
     * Encrypts a plaintext vector, preserving approximate distance relationships and producing a
     * detached authentication tag bound to the seed, encrypted values, and associated data.
     *
     * @param vector the plaintext vector; must have length equal to configured dimensions
     * @param associatedData bytes (typically the UTF-8 field name) that the MAC authenticates; must
     *            not be null (use an empty array if no binding is desired, though this weakens the
     *            cross-field-substitution protection)
     * @return a record containing the perturbation seed, encrypted values, and MAC tag
     * @throws IllegalArgumentException if vector length does not match dimensions
     * @throws IllegalStateException if the scaling or perturbation produces any non-finite
     *             component (NaN or Infinity)
     */
    // @spec encryption.primitives-variants.R24,R25,R27,R29,R55 — dimensionality preservation, rejection, non-determinism,
    // finite output, authenticated wrapping
    public EncryptedVector encrypt(float[] vector, byte[] associatedData) {
        if (closed) {
            throw new IllegalStateException("DcpeSapEncryptor is closed");
        }
        Objects.requireNonNull(vector, "vector must not be null");
        Objects.requireNonNull(associatedData, "associatedData must not be null");
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                    "Vector length must be " + dimensions + ", got " + vector.length);
        }

        final long seed = seedRng.nextLong();
        final float[] noise = generateNoise(seed);

        final float[] encrypted = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            final float component = (float) (scaleFactor * vector[i]) + noise[i];
            if (!Float.isFinite(component)) {
                // @spec encryption.primitives-variants.R29 — reject non-finite output rather than storing NaN/Infinity
                throw new IllegalStateException(
                        "DCPE encryption produced a non-finite component at index " + i
                                + "; input vector magnitude likely exceeds representable range "
                                + "under the configured scaleFactor");
            }
            encrypted[i] = component;
        }

        final byte[] tag = computeMacTag(seed, encrypted, associatedData);
        return new EncryptedVector(seed, encrypted, tag);
    }

    /**
     * Decrypts an encrypted vector. Verifies the MAC tag in constant time before applying the
     * scale-and-perturb inverse; a tag mismatch throws {@link SecurityException}.
     *
     * @param encrypted the authenticated encrypted vector (seed + values + tag)
     * @param associatedData the same associated data used during encryption
     * @return the reconstructed plaintext vector
     * @throws SecurityException if the MAC tag does not match (wrong key, tampered data, or
     *             cross-field substitution)
     */
    // @spec encryption.primitives-variants.R28,R55 — authenticate before decrypt
    public float[] decrypt(EncryptedVector encrypted, byte[] associatedData) {
        if (closed) {
            throw new IllegalStateException("DcpeSapEncryptor is closed");
        }
        Objects.requireNonNull(encrypted, "encrypted must not be null");
        Objects.requireNonNull(associatedData, "associatedData must not be null");
        if (encrypted.values().length != dimensions) {
            throw new IllegalArgumentException("Encrypted vector length must be " + dimensions
                    + ", got " + encrypted.values().length);
        }
        final byte[] expectedTag = computeMacTag(encrypted.seed(), encrypted.values(),
                associatedData);
        if (!MessageDigest.isEqual(expectedTag, encrypted.tag())) {
            throw new SecurityException("DCPE MAC verification failed: wrong key, tampered "
                    + "ciphertext, or cross-field substitution");
        }
        final float[] noise = generateNoise(encrypted.seed());
        final float[] values = encrypted.values();
        final float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (float) ((values[i] - noise[i]) / scaleFactor);
        }
        return result;
    }

    /**
     * Closes this encryptor, zeroing the key material and marking it as closed. Subsequent calls to
     * {@link #encrypt} or {@link #decrypt} will throw {@link IllegalStateException}. Idempotent.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                macKeySpec.destroy();
            } catch (javax.security.auth.DestroyFailedException _) {
                // Best-effort — SecretKeySpec.destroy() is not universally supported
            }
        }
    }

    // ── MAC computation ─────────────────────────────────────────────────────

    /**
     * Computes HMAC-SHA256 over {@code [8-byte BE seed | 4-byte BE values * N | associatedData]}
     * and returns the first {@link #MAC_TAG_BYTES} bytes.
     */
    private byte[] computeMacTag(long seed, float[] values, byte[] associatedData) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKeySpec);
            final byte[] seedBytes = new byte[SEED_BYTES];
            for (int i = 0; i < SEED_BYTES; i++) {
                seedBytes[i] = (byte) (seed >>> (56 - i * 8));
            }
            mac.update(seedBytes);
            final byte[] valueBytes = new byte[values.length * BYTES_PER_FLOAT];
            for (int i = 0; i < values.length; i++) {
                final int bits = Float.floatToRawIntBits(values[i]);
                valueBytes[i * 4] = (byte) (bits >>> 24);
                valueBytes[i * 4 + 1] = (byte) (bits >>> 16);
                valueBytes[i * 4 + 2] = (byte) (bits >>> 8);
                valueBytes[i * 4 + 3] = (byte) bits;
            }
            mac.update(valueBytes);
            if (associatedData.length > 0) {
                mac.update(associatedData);
            }
            final byte[] full = mac.doFinal();
            return Arrays.copyOf(full, MAC_TAG_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 tag computation failed", e);
        }
    }

    /**
     * Derives a sub-key from the master key using HMAC-SHA256 with a domain-separation label.
     */
    private static byte[] hmacSha256(byte[] key, String label) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 key derivation failed", e);
        }
    }

    /**
     * Generates a noise vector sampled from the d-ball using a deterministic seed. The perturbation
     * radius beta is kept small (0.01) to maintain distance ordering.
     */
    private float[] generateNoise(long seed) {
        final double beta = 0.01;
        final Random rng = new Random(seed);

        // Sample from d-dimensional unit ball using Gaussian method:
        // 1. Sample d Gaussian values
        // 2. Normalize to unit sphere
        // 3. Scale by uniform radius^(1/d) * beta
        final float[] noise = new float[dimensions];
        double normSq = 0.0;
        for (int i = 0; i < dimensions; i++) {
            final double g = rng.nextGaussian();
            noise[i] = (float) g;
            normSq += g * g;
        }

        final double norm = Math.sqrt(normSq);
        if (norm > 0) {
            // Uniform radius in d-ball: r = U^(1/d) * beta
            final double u = rng.nextDouble();
            final double r = Math.pow(u, 1.0 / dimensions) * beta;

            for (int i = 0; i < dimensions; i++) {
                noise[i] = (float) (noise[i] / norm * r);
            }
        }

        return noise;
    }

    /**
     * Holds an authenticated encrypted vector: the perturbation seed, the encrypted values, and a
     * detached {@link #MAC_TAG_BYTES}-byte HMAC-SHA256 tag. Use {@link DcpeSapEncryptor#decrypt} to
     * verify the tag and recover the plaintext.
     *
     * <p>
     * The serialized wire format is {@code [8B seed | 4N encrypted floats | 16B tag]}, totaling
     * {@code 8 + dimensions * 4 + 16} bytes.
     *
     * @param seed the perturbation seed used during encryption (non-secret; acts like an IV)
     * @param values the encrypted vector components (same dimensionality as plaintext)
     * @param tag the detached HMAC-SHA256 authentication tag (16 bytes)
     */
    // @spec serialization.encrypted-field-serialization.R4, F41.R22 — authenticated DCPE ciphertext
    // @spec encryption.primitives-variants.R55, F41.R22 — authenticated DCPE ciphertext
    public record EncryptedVector(long seed, float[] values, byte[] tag) {
        public EncryptedVector {
            Objects.requireNonNull(values, "values must not be null");
            Objects.requireNonNull(tag, "tag must not be null");
            if (tag.length != MAC_TAG_BYTES) {
                throw new IllegalArgumentException(
                        "tag must be " + MAC_TAG_BYTES + " bytes, got " + tag.length);
            }
            values = values.clone();
            tag = tag.clone();
        }

        @Override
        public float[] values() {
            return values.clone();
        }

        @Override
        public byte[] tag() {
            return tag.clone();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof EncryptedVector other && seed == other.seed
                    && java.util.Arrays.equals(values, other.values)
                    && java.util.Arrays.equals(tag, other.tag);
        }

        @Override
        public int hashCode() {
            int h = Long.hashCode(seed);
            h = 31 * h + java.util.Arrays.hashCode(values);
            h = 31 * h + java.util.Arrays.hashCode(tag);
            return h;
        }
    }

    // ── Blob encoding helpers ────────────────────────────────────────────────

    /**
     * Encodes an encrypted vector as the on-wire blob format
     * {@code [8B BE seed | 4N BE floats | 16B tag]}.
     */
    // @spec serialization.encrypted-field-serialization.R4, F41.R22 — serialized DCPE blob layout
    public static byte[] toBlob(EncryptedVector ev) {
        Objects.requireNonNull(ev, "ev must not be null");
        final float[] values = ev.values();
        final byte[] blob = new byte[SEED_BYTES + values.length * BYTES_PER_FLOAT + MAC_TAG_BYTES];
        final long seed = ev.seed();
        for (int i = 0; i < SEED_BYTES; i++) {
            blob[i] = (byte) (seed >>> (56 - i * 8));
        }
        int off = SEED_BYTES;
        for (final float v : values) {
            final int bits = Float.floatToRawIntBits(v);
            blob[off] = (byte) (bits >>> 24);
            blob[off + 1] = (byte) (bits >>> 16);
            blob[off + 2] = (byte) (bits >>> 8);
            blob[off + 3] = (byte) bits;
            off += 4;
        }
        System.arraycopy(ev.tag(), 0, blob, off, MAC_TAG_BYTES);
        return blob;
    }

    /**
     * Decodes an on-wire blob {@code [8B BE seed | 4N BE floats | 16B tag]} into an
     * {@link EncryptedVector}. Does not verify the MAC — pass the returned record to
     * {@link #decrypt(EncryptedVector, byte[])} for authenticated decryption.
     *
     * @throws IllegalArgumentException if blob length does not match {@code 8 + dims*4 + 16}
     */
    // @spec serialization.encrypted-field-serialization.R4, F41.R22 — decode serialized DCPE blob
    public static EncryptedVector fromBlob(byte[] blob, int dims) {
        Objects.requireNonNull(blob, "blob must not be null");
        if (dims <= 0) {
            throw new IllegalArgumentException("dims must be positive, got " + dims);
        }
        final int expected = SEED_BYTES + dims * BYTES_PER_FLOAT + MAC_TAG_BYTES;
        if (blob.length != expected) {
            throw new IllegalArgumentException("blob length must be " + expected + " (8+4*" + dims
                    + "+16), got " + blob.length);
        }
        long seed = 0L;
        for (int i = 0; i < SEED_BYTES; i++) {
            seed = (seed << 8) | (blob[i] & 0xFFL);
        }
        final float[] values = new float[dims];
        int off = SEED_BYTES;
        for (int i = 0; i < dims; i++) {
            final int bits = ((blob[off] & 0xFF) << 24) | ((blob[off + 1] & 0xFF) << 16)
                    | ((blob[off + 2] & 0xFF) << 8) | (blob[off + 3] & 0xFF);
            values[i] = Float.intBitsToFloat(bits);
            off += 4;
        }
        final byte[] tag = Arrays.copyOfRange(blob, off, off + MAC_TAG_BYTES);
        return new EncryptedVector(seed, values, tag);
    }
}
