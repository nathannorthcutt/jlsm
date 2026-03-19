package jlsm.encryption;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Contract: Order-preserving encryption using the Boldyreva scheme with hypergeometric sampling.
 * Stateless and key-only — no mutable state required. Maps a plaintext domain {@code [1..M]} to a
 * ciphertext range {@code [1..N]} where {@code N >> M}, preserving ordering: if {@code a < b} then
 * {@code encrypt(a) < encrypt(b)}.
 *
 * <p>
 * Uses recursive bisection with key-seeded pseudorandom coin from AES-based PRF to sample from the
 * hypergeometric distribution, mapping each plaintext to a unique ciphertext preserving order.
 *
 * <p>
 * Governed by: .kb/algorithms/encryption/searchable-encryption-schemes.md
 */
public final class BoldyrevaOpeEncryptor {

    private static final int MAX_RECURSION_DEPTH = 128;

    private final byte[] keyBytes;
    private final long domainSize;
    private final long rangeSize;

    /**
     * Creates a Boldyreva OPE encryptor with the given key and domain/range configuration.
     *
     * @param keyHolder the key holder providing key material
     * @param domainSize the plaintext domain size M (values in [1..M])
     * @param rangeSize the ciphertext range size N (values in [1..N], must be {@code > domainSize})
     * @throws IllegalArgumentException if rangeSize is not greater than domainSize, or domainSize <
     *             1
     */
    public BoldyrevaOpeEncryptor(EncryptionKeyHolder keyHolder, long domainSize, long rangeSize) {
        Objects.requireNonNull(keyHolder, "keyHolder must not be null");
        if (domainSize < 1) {
            throw new IllegalArgumentException("domainSize must be >= 1, got " + domainSize);
        }
        if (rangeSize <= domainSize) {
            throw new IllegalArgumentException("rangeSize must be > domainSize, got rangeSize="
                    + rangeSize + " domainSize=" + domainSize);
        }
        this.keyBytes = keyHolder.getKeyBytes();
        this.domainSize = domainSize;
        this.rangeSize = rangeSize;
    }

    /**
     * Encrypts a plaintext value, preserving order.
     *
     * @param plaintext the value to encrypt; must be in {@code [1..domainSize]}
     * @return the encrypted value in {@code [1..rangeSize]}
     * @throws IllegalArgumentException if plaintext is out of domain bounds
     */
    public long encrypt(long plaintext) {
        if (plaintext < 1 || plaintext > domainSize) {
            throw new IllegalArgumentException(
                    "Plaintext must be in [1.." + domainSize + "], got " + plaintext);
        }
        return encryptRecursive(plaintext, 1, domainSize, 1, rangeSize, 0);
    }

    /**
     * Decrypts a ciphertext value via binary search.
     *
     * @param ciphertext the encrypted value; must be in {@code [1..rangeSize]}
     * @return the original plaintext in {@code [1..domainSize]}
     * @throws IllegalArgumentException if ciphertext is out of range bounds
     */
    public long decrypt(long ciphertext) {
        if (ciphertext < 1 || ciphertext > rangeSize) {
            throw new IllegalArgumentException(
                    "Ciphertext must be in [1.." + rangeSize + "], got " + ciphertext);
        }
        // Binary search: find plaintext p such that encrypt(p) == ciphertext
        long lo = 1;
        long hi = domainSize;
        while (lo <= hi) {
            final long mid = lo + (hi - lo) / 2;
            final long enc = encrypt(mid);
            if (enc == ciphertext) {
                return mid;
            } else if (enc < ciphertext) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        throw new IllegalArgumentException("No plaintext maps to ciphertext " + ciphertext
                + " in the configured domain/range");
    }

    // ── Recursive bisection (Boldyreva lazy-sample) ─────────────────────────

    /**
     * Recursively maps plaintext m in [dLo..dHi] to ciphertext in [rLo..rHi]. At each step, pick
     * midpoint of the range, sample a "coin" from HG distribution to decide how many domain values
     * fall in the left half, then recurse into the appropriate half.
     */
    private long encryptRecursive(long plaintext, long dLo, long dHi, long rLo, long rHi,
            int depth) {
        assert dLo <= dHi : "domain lo > hi";
        assert rLo <= rHi : "range lo > hi";
        assert depth <= MAX_RECURSION_DEPTH : "max recursion depth exceeded at " + depth;

        final long dSize = dHi - dLo + 1;
        final long rSize = rHi - rLo + 1;

        if (dSize == 1) {
            // Single domain value — map to midpoint of remaining range
            return rLo + rSize / 2;
        }

        // Midpoint of range
        final long rMid = rLo + (rSize - 1) / 2;
        final long rLeftSize = rMid - rLo + 1;

        // Sample how many domain values land in the left half [rLo..rMid]
        // using HG(rSize, dSize, rLeftSize) seeded by key + (dLo, dHi, rLo, rHi)
        final long dLeftCount = sampleHypergeometric(rSize, dSize, rLeftSize, dLo, dHi, rLo, rHi);

        if (plaintext <= dLo + dLeftCount - 1) {
            // Plaintext is in the left partition
            return encryptRecursive(plaintext, dLo, dLo + dLeftCount - 1, rLo, rMid, depth + 1);
        } else {
            // Plaintext is in the right partition
            return encryptRecursive(plaintext, dLo + dLeftCount, dHi, rMid + 1, rHi, depth + 1);
        }
    }

    /**
     * Samples from the hypergeometric distribution HG(N, K, n) using an AES-based PRF coin.
     *
     * Population N = rangeSegmentSize, success states K = domainSegmentSize, draws n =
     * leftRangeSize.
     *
     * Returns the number of domain values that fall in the left range partition. Uses the
     * sequential sampling algorithm (Fisher-Yates style).
     */
    private long sampleHypergeometric(long popN, long succK, long draws, long dLo, long dHi,
            long rLo, long rHi) {
        assert popN > 0 : "population must be positive";
        assert succK >= 0 && succK <= popN : "succK out of bounds";
        assert draws >= 0 && draws <= popN : "draws out of bounds";

        // Derive a deterministic seed for this particular node from key + parameters
        final long nodeSeed = prfSeed(dLo, dHi, rLo, rHi);

        // Sequential sampling: iterate through draws, each time flipping a coin
        long population = popN;
        long successes = succK;
        long selected = 0;
        long coinState = nodeSeed;

        for (long i = 0; i < draws && successes > 0; i++) {
            // Probability of selecting a success: successes / population
            // Use PRF coin to decide
            coinState = prfNext(coinState, i);
            final double threshold = (double) successes / (double) population;
            final double coin = pseudoUniform(coinState);

            if (coin < threshold) {
                selected++;
                successes--;
            }
            population--;
        }

        return selected;
    }

    /**
     * Derives a deterministic seed from the key and node parameters using AES encryption.
     */
    private long prfSeed(long dLo, long dHi, long rLo, long rHi) {
        try {
            final Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            final SecretKeySpec keySpec = new SecretKeySpec(keyBytes, 0,
                    Math.min(keyBytes.length, 32), "AES");
            aes.init(Cipher.ENCRYPT_MODE, keySpec);

            final ByteBuffer buf = ByteBuffer.allocate(16);
            buf.putInt((int) (dLo ^ (dLo >>> 32)));
            buf.putInt((int) (dHi ^ (dHi >>> 32)));
            buf.putInt((int) (rLo ^ (rLo >>> 32)));
            buf.putInt((int) (rHi ^ (rHi >>> 32)));

            final byte[] encrypted = aes.doFinal(buf.array());
            return ByteBuffer.wrap(encrypted).getLong();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES PRF failed", e);
        }
    }

    /**
     * Derives the next PRF state from current state and iteration.
     */
    private long prfNext(long state, long iteration) {
        try {
            final Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            final SecretKeySpec keySpec = new SecretKeySpec(keyBytes, 0,
                    Math.min(keyBytes.length, 32), "AES");
            aes.init(Cipher.ENCRYPT_MODE, keySpec);

            final ByteBuffer buf = ByteBuffer.allocate(16);
            buf.putLong(state);
            buf.putLong(iteration);

            final byte[] encrypted = aes.doFinal(buf.array());
            return ByteBuffer.wrap(encrypted).getLong();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES PRF failed", e);
        }
    }

    /**
     * Maps a long to a pseudo-uniform double in [0, 1).
     */
    private static double pseudoUniform(long value) {
        return (double) (value >>> 1) / (double) Long.MAX_VALUE;
    }
}
