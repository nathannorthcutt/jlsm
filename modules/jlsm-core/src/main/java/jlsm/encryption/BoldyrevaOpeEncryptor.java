package jlsm.encryption;

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
    private static final int BLOCK_SIZE = 16;

    private final long domainSize;
    private final long rangeSize;

    /**
     * Per-thread AES/ECB cipher — stateless between doFinal calls, safe to reuse without re-init.
     */
    private final ThreadLocal<Cipher> prfCipher;

    /** Per-thread 16-byte buffer for PRF input assembly. */
    private final ThreadLocal<byte[]> prfBuffer;

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
        final byte[] keyBytes = keyHolder.getKeyBytes();
        this.domainSize = domainSize;
        this.rangeSize = rangeSize;

        final SecretKeySpec prfKeySpec = new SecretKeySpec(keyBytes, 0,
                Math.min(keyBytes.length, 32), "AES");
        this.prfCipher = ThreadLocal.withInitial(() -> {
            try {
                final Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, prfKeySpec);
                return c;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Failed to initialize AES PRF cipher", e);
            }
        });
        this.prfBuffer = ThreadLocal.withInitial(() -> new byte[BLOCK_SIZE]);
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
     * Derives a deterministic seed from the key and node parameters using AES encryption. Uses
     * cached prfCipher — AES/ECB is stateless between doFinal calls.
     */
    private long prfSeed(long dLo, long dHi, long rLo, long rHi) {
        try {
            final byte[] buf = prfBuffer.get();
            // Pack 4 ints into buf (big-endian, matching original ByteBuffer.putInt behavior)
            final int i0 = (int) (dLo ^ (dLo >>> 32));
            final int i1 = (int) (dHi ^ (dHi >>> 32));
            final int i2 = (int) (rLo ^ (rLo >>> 32));
            final int i3 = (int) (rHi ^ (rHi >>> 32));

            buf[0] = (byte) (i0 >>> 24);
            buf[1] = (byte) (i0 >>> 16);
            buf[2] = (byte) (i0 >>> 8);
            buf[3] = (byte) i0;
            buf[4] = (byte) (i1 >>> 24);
            buf[5] = (byte) (i1 >>> 16);
            buf[6] = (byte) (i1 >>> 8);
            buf[7] = (byte) i1;
            buf[8] = (byte) (i2 >>> 24);
            buf[9] = (byte) (i2 >>> 16);
            buf[10] = (byte) (i2 >>> 8);
            buf[11] = (byte) i2;
            buf[12] = (byte) (i3 >>> 24);
            buf[13] = (byte) (i3 >>> 16);
            buf[14] = (byte) (i3 >>> 8);
            buf[15] = (byte) i3;

            final byte[] encrypted = prfCipher.get().doFinal(buf);
            assert encrypted.length == BLOCK_SIZE : "AES block output must be 16 bytes";
            return ((long) (encrypted[0] & 0xFF) << 56) | ((long) (encrypted[1] & 0xFF) << 48)
                    | ((long) (encrypted[2] & 0xFF) << 40) | ((long) (encrypted[3] & 0xFF) << 32)
                    | ((long) (encrypted[4] & 0xFF) << 24) | ((long) (encrypted[5] & 0xFF) << 16)
                    | ((long) (encrypted[6] & 0xFF) << 8) | ((long) (encrypted[7] & 0xFF));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES PRF failed", e);
        }
    }

    /**
     * Derives the next PRF state from current state and iteration. Uses cached prfCipher — AES/ECB
     * is stateless between doFinal calls.
     */
    private long prfNext(long state, long iteration) {
        try {
            final byte[] buf = prfBuffer.get();
            // Pack 2 longs into buf (big-endian, matching original ByteBuffer.putLong behavior)
            buf[0] = (byte) (state >>> 56);
            buf[1] = (byte) (state >>> 48);
            buf[2] = (byte) (state >>> 40);
            buf[3] = (byte) (state >>> 32);
            buf[4] = (byte) (state >>> 24);
            buf[5] = (byte) (state >>> 16);
            buf[6] = (byte) (state >>> 8);
            buf[7] = (byte) state;
            buf[8] = (byte) (iteration >>> 56);
            buf[9] = (byte) (iteration >>> 48);
            buf[10] = (byte) (iteration >>> 40);
            buf[11] = (byte) (iteration >>> 32);
            buf[12] = (byte) (iteration >>> 24);
            buf[13] = (byte) (iteration >>> 16);
            buf[14] = (byte) (iteration >>> 8);
            buf[15] = (byte) iteration;

            final byte[] encrypted = prfCipher.get().doFinal(buf);
            assert encrypted.length == BLOCK_SIZE : "AES block output must be 16 bytes";
            return ((long) (encrypted[0] & 0xFF) << 56) | ((long) (encrypted[1] & 0xFF) << 48)
                    | ((long) (encrypted[2] & 0xFF) << 40) | ((long) (encrypted[3] & 0xFF) << 32)
                    | ((long) (encrypted[4] & 0xFF) << 24) | ((long) (encrypted[5] & 0xFF) << 16)
                    | ((long) (encrypted[6] & 0xFF) << 8) | ((long) (encrypted[7] & 0xFF));
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
