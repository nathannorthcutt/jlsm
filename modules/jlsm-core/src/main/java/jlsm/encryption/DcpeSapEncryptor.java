package jlsm.encryption;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;

/**
 * Contract: Distance-comparison-preserving encryption using Scale-And-Perturb (SAP). Encrypts a
 * {@code float[]} vector to a {@code float[]} of the same dimensionality, approximately preserving
 * distance relationships so that existing HNSW/IVF indexes work on encrypted vectors.
 *
 * <p>
 * Encryption: {@code c = s * v + noise}, where {@code s} is a scaling factor derived from the key
 * and {@code noise} is sampled from a d-ball using a per-vector perturbation seed. Decryption
 * requires the stored seed to reconstruct the noise vector.
 *
 * <p>
 * Governed by: .kb/algorithms/encryption/vector-encryption-approaches.md
 */
public final class DcpeSapEncryptor {

    private final double scaleFactor;
    private final int dimensions;
    private final byte[] keyBytes;
    private final SecureRandom seedRng;

    /**
     * Creates a DCPE Scale-And-Perturb encryptor with the given key.
     *
     * @param keyHolder the key holder providing key material
     * @param dimensions the expected vector dimensionality; must be positive
     * @throws IllegalArgumentException if dimensions is not positive
     */
    public DcpeSapEncryptor(EncryptionKeyHolder keyHolder, int dimensions) {
        Objects.requireNonNull(keyHolder, "keyHolder must not be null");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive, got " + dimensions);
        }
        this.dimensions = dimensions;
        this.keyBytes = keyHolder.getKeyBytes();

        // Derive scaling factor from key: hash first 8 bytes to a positive double in [1.5, 3.5)
        final long keyLong = ByteBuffer.wrap(keyBytes, 0, 8).getLong();
        this.scaleFactor = 1.5 + 2.0 * ((double) (keyLong >>> 1) / (double) Long.MAX_VALUE);
        assert scaleFactor >= 1.5 && scaleFactor < 3.5 : "scaleFactor out of expected range";

        // Seed RNG from remaining key bytes for generating per-vector seeds
        this.seedRng = new SecureRandom(keyBytes);
    }

    /**
     * Encrypts a plaintext vector, preserving approximate distance relationships.
     *
     * @param vector the plaintext vector; must have length equal to configured dimensions
     * @return the encrypted vector and its perturbation seed
     * @throws IllegalArgumentException if vector length does not match dimensions
     */
    public EncryptedVector encrypt(float[] vector) {
        Objects.requireNonNull(vector, "vector must not be null");
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                    "Vector length must be " + dimensions + ", got " + vector.length);
        }

        final long seed = seedRng.nextLong();
        final float[] noise = generateNoise(seed);

        final float[] encrypted = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            encrypted[i] = (float) (scaleFactor * vector[i]) + noise[i];
        }

        return new EncryptedVector(encrypted, seed);
    }

    /**
     * Decrypts an encrypted vector using the stored perturbation seed.
     *
     * @param encrypted the encrypted vector values
     * @param seed the perturbation seed stored during encryption
     * @return the reconstructed plaintext vector
     */
    public float[] decrypt(float[] encrypted, long seed) {
        Objects.requireNonNull(encrypted, "encrypted must not be null");
        if (encrypted.length != dimensions) {
            throw new IllegalArgumentException(
                    "Encrypted vector length must be " + dimensions + ", got " + encrypted.length);
        }
        final float[] noise = generateNoise(seed);

        final float[] result = new float[encrypted.length];
        for (int i = 0; i < encrypted.length; i++) {
            result[i] = (float) ((encrypted[i] - noise[i]) / scaleFactor);
        }
        return result;
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
     * Holds an encrypted vector alongside the perturbation seed needed for decryption.
     *
     * @param values the encrypted vector components (same dimensionality as plaintext)
     * @param seed the perturbation seed used during encryption
     */
    public record EncryptedVector(float[] values, long seed) {
        public EncryptedVector {
            Objects.requireNonNull(values, "values must not be null");
            values = values.clone();
        }

        @Override
        public float[] values() {
            return values.clone();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof EncryptedVector other && seed == other.seed
                    && java.util.Arrays.equals(values, other.values);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(values) + Long.hashCode(seed);
        }
    }
}
