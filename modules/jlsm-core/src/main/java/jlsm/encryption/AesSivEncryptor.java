package jlsm.encryption;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Contract: Deterministic encryption using AES-SIV (RFC 5297). A 512-bit key is split into K1 (CMAC
 * key) and K2 (CTR key). S2V derives a synthetic IV from the plaintext and optional associated
 * data; AES-CTR encrypts the plaintext under that IV. The same plaintext + associated data always
 * produces the same ciphertext, enabling equality queries on encrypted values.
 *
 * <p>
 * Ciphertext format: {@code [16-byte IV || ciphertext]} — +16 bytes expansion.
 *
 * <p>
 * Governed by: .kb/algorithms/encryption/searchable-encryption-schemes.md
 */
public final class AesSivEncryptor {

    private static final int BLOCK_SIZE = 16;
    private static final int KEY_512_BYTES = 64;

    /** Per-thread AES/ECB cipher for CMAC — stateless between doFinal calls, safe to reuse. */
    private final ThreadLocal<Cipher> cmacCipher;
    /**
     * Per-thread AES/ECB cipher for CTR keystream — stateless between doFinal calls, safe to reuse.
     */
    private final ThreadLocal<Cipher> ctrCipher;

    /**
     * Creates an AES-SIV encryptor using the given key holder.
     *
     * @param keyHolder the key holder providing a 512-bit (64-byte) key
     * @throws IllegalArgumentException if key length is not 64 bytes
     */
    public AesSivEncryptor(EncryptionKeyHolder keyHolder) {
        Objects.requireNonNull(keyHolder, "keyHolder must not be null");
        if (keyHolder.keyLength() != KEY_512_BYTES) {
            throw new IllegalArgumentException(
                    "AES-SIV requires a 512-bit (64-byte) key, got " + keyHolder.keyLength());
        }
        final byte[] fullKey = keyHolder.getKeyBytes();
        final byte[] cmacKey = Arrays.copyOfRange(fullKey, 0, 32);
        final byte[] ctrKey = Arrays.copyOfRange(fullKey, 32, 64);
        Arrays.fill(fullKey, (byte) 0);

        final SecretKeySpec cmacKeySpec = new SecretKeySpec(cmacKey, "AES");
        final SecretKeySpec ctrKeySpec = new SecretKeySpec(ctrKey, "AES");
        Arrays.fill(cmacKey, (byte) 0);
        Arrays.fill(ctrKey, (byte) 0);

        this.cmacCipher = ThreadLocal.withInitial(() -> {
            try {
                final Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, cmacKeySpec);
                return c;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Failed to initialize CMAC cipher", e);
            }
        });
        this.ctrCipher = ThreadLocal.withInitial(() -> {
            try {
                final Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, ctrKeySpec);
                return c;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Failed to initialize CTR cipher", e);
            }
        });
    }

    /**
     * Encrypts the plaintext deterministically with optional associated data.
     *
     * @param plaintext the data to encrypt; must not be null
     * @param associatedData optional associated data for IV derivation; may be null
     * @return the ciphertext as {@code [16-byte IV || encrypted bytes]}
     */
    public byte[] encrypt(byte[] plaintext, byte[] associatedData) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        final byte[] ad = associatedData != null ? associatedData : new byte[0];

        final byte[] iv = s2v(ad, plaintext);
        final byte[] encrypted = aesCtr(iv, plaintext);

        final byte[] result = new byte[BLOCK_SIZE + encrypted.length];
        System.arraycopy(iv, 0, result, 0, BLOCK_SIZE);
        System.arraycopy(encrypted, 0, result, BLOCK_SIZE, encrypted.length);
        return result;
    }

    /**
     * Decrypts AES-SIV ciphertext and verifies the synthetic IV.
     *
     * @param ciphertext the data to decrypt (IV || encrypted bytes); must not be null
     * @param associatedData the associated data used during encryption; may be null
     * @return the plaintext bytes
     * @throws IllegalArgumentException if ciphertext is too short
     * @throws SecurityException if IV verification fails (wrong key or tampered data)
     */
    public byte[] decrypt(byte[] ciphertext, byte[] associatedData) {
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
        if (ciphertext.length < BLOCK_SIZE) {
            throw new IllegalArgumentException("Ciphertext too short: minimum " + BLOCK_SIZE
                    + " bytes, got " + ciphertext.length);
        }
        final byte[] ad = associatedData != null ? associatedData : new byte[0];

        final byte[] iv = Arrays.copyOfRange(ciphertext, 0, BLOCK_SIZE);
        final byte[] encrypted = Arrays.copyOfRange(ciphertext, BLOCK_SIZE, ciphertext.length);
        final byte[] plaintext = aesCtr(iv, encrypted);

        // Verify: recompute S2V and check it matches the extracted IV
        final byte[] recomputedIv = s2v(ad, plaintext);
        if (!Arrays.equals(iv, recomputedIv)) {
            throw new SecurityException(
                    "AES-SIV IV verification failed: wrong key or tampered data");
        }
        return plaintext;
    }

    // ── S2V (RFC 5297 Section 2.4) ──────────────────────────────────────────

    /**
     * S2V: computes the synthetic IV from associated data and plaintext. With one AD string S1 and
     * plaintext Sn: D = CMAC(K, <zero>) D = dbl(D) XOR CMAC(K, S1) [for AD] if len(Sn) >= 128: T =
     * Sn xorend D else: T = dbl(D) XOR pad(Sn) return CMAC(K, T)
     */
    private byte[] s2v(byte[] ad, byte[] plaintext) {
        // D = CMAC(zero block)
        byte[] d = cmac(new byte[BLOCK_SIZE]);

        // Process AD: D = dbl(D) XOR CMAC(ad)
        d = dbl(d);
        final byte[] cmacAd = cmac(ad);
        xorInPlace(d, cmacAd);

        // Process plaintext (Sn)
        final byte[] t;
        if (plaintext.length >= BLOCK_SIZE) {
            // T = Sn xorend D — XOR D into the last 16 bytes of Sn
            t = Arrays.copyOf(plaintext, plaintext.length);
            final int offset = t.length - BLOCK_SIZE;
            for (int i = 0; i < BLOCK_SIZE; i++) {
                t[offset + i] ^= d[i];
            }
        } else {
            // T = dbl(D) XOR pad(Sn)
            d = dbl(d);
            t = pad(plaintext);
            xorInPlace(t, d);
        }

        return cmac(t);
    }

    // ── CMAC (RFC 4493) via AES/ECB ─────────────────────────────────────────

    /**
     * AES-CMAC per RFC 4493, implemented using cached AES/ECB/NoPadding cipher. The cipher is
     * stateless between doFinal calls — safe to reuse without re-init.
     */
    private byte[] cmac(byte[] message) {
        try {
            // Generate subkeys K1, K2
            final byte[] l = cmacCipher.get().doFinal(new byte[BLOCK_SIZE]);
            final byte[] k1 = dbl(l);
            final byte[] k2 = dbl(k1);

            final int n = (message.length + BLOCK_SIZE - 1) / BLOCK_SIZE;
            final boolean completeBlock = message.length > 0 && (message.length % BLOCK_SIZE == 0);
            final int blocks = Math.max(n, 1);

            // Process all blocks except the last with CBC-MAC
            final byte[] x = new byte[BLOCK_SIZE];
            for (int i = 0; i < blocks - 1; i++) {
                for (int j = 0; j < BLOCK_SIZE; j++) {
                    x[j] ^= message[i * BLOCK_SIZE + j];
                }
                final byte[] enc = cmacCipher.get().doFinal(x);
                System.arraycopy(enc, 0, x, 0, BLOCK_SIZE);
            }

            // Last block: XOR with K1 or K2
            final byte[] lastBlock;
            if (completeBlock) {
                lastBlock = Arrays.copyOfRange(message, (blocks - 1) * BLOCK_SIZE,
                        blocks * BLOCK_SIZE);
                xorInPlace(lastBlock, k1);
            } else {
                // Pad incomplete block: append 10*
                lastBlock = new byte[BLOCK_SIZE];
                final int lastStart = (blocks - 1) * BLOCK_SIZE;
                final int remaining = message.length - lastStart;
                if (remaining > 0) {
                    System.arraycopy(message, lastStart, lastBlock, 0, remaining);
                }
                lastBlock[remaining] = (byte) 0x80;
                // rest already 0x00
                xorInPlace(lastBlock, k2);
            }

            xorInPlace(x, lastBlock);
            return cmacCipher.get().doFinal(x);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-CMAC computation failed", e);
        }
    }

    // ── AES-CTR encryption ──────────────────────────────────────────────────

    /**
     * AES-CTR encryption/decryption using cached CTR cipher. Per RFC 5297, the counter is derived
     * from the SIV with bits 63 and 31 cleared. The cipher is stateless between doFinal calls.
     */
    private byte[] aesCtr(byte[] iv, byte[] input) {
        if (input.length == 0) {
            return new byte[0];
        }
        try {
            // Clear bits 63 and 31 of the IV to form the initial counter
            final byte[] ctr = Arrays.copyOf(iv, BLOCK_SIZE);
            ctr[8] &= 0x7F;
            ctr[12] &= 0x7F;

            final byte[] output = new byte[input.length];
            final int fullBlocks = input.length / BLOCK_SIZE;
            final int remainder = input.length % BLOCK_SIZE;

            for (int i = 0; i < fullBlocks; i++) {
                final byte[] keystream = ctrCipher.get().doFinal(ctr);
                for (int j = 0; j < BLOCK_SIZE; j++) {
                    output[i * BLOCK_SIZE + j] = (byte) (input[i * BLOCK_SIZE + j] ^ keystream[j]);
                }
                incrementCounter(ctr);
            }

            if (remainder > 0) {
                final byte[] keystream = ctrCipher.get().doFinal(ctr);
                final int offset = fullBlocks * BLOCK_SIZE;
                for (int j = 0; j < remainder; j++) {
                    output[offset + j] = (byte) (input[offset + j] ^ keystream[j]);
                }
            }

            return output;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-CTR encryption failed", e);
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────────

    /** Doubles a block in GF(2^128) with the irreducible polynomial 0x87. */
    private static byte[] dbl(byte[] input) {
        assert input.length == BLOCK_SIZE : "dbl input must be 16 bytes";
        final byte[] result = new byte[BLOCK_SIZE];
        final int carry = (input[0] & 0x80) != 0 ? 0x87 : 0x00;
        for (int i = 0; i < BLOCK_SIZE - 1; i++) {
            result[i] = (byte) (((input[i] & 0xFF) << 1) | ((input[i + 1] & 0x80) >>> 7));
        }
        result[BLOCK_SIZE - 1] = (byte) (((input[BLOCK_SIZE - 1] & 0xFF) << 1) ^ carry);
        return result;
    }

    /** Pads a message shorter than BLOCK_SIZE: append 0x80 then zeros. */
    private static byte[] pad(byte[] input) {
        assert input.length < BLOCK_SIZE : "pad called on full-length input";
        final byte[] padded = new byte[BLOCK_SIZE];
        System.arraycopy(input, 0, padded, 0, input.length);
        padded[input.length] = (byte) 0x80;
        return padded;
    }

    /** XORs src into dst in place: dst[i] ^= src[i]. */
    private static void xorInPlace(byte[] dst, byte[] src) {
        assert dst.length == src.length : "xorInPlace arrays must be same length";
        for (int i = 0; i < dst.length; i++) {
            dst[i] ^= src[i];
        }
    }

    /** Increments a 16-byte big-endian counter. */
    private static void incrementCounter(byte[] ctr) {
        for (int i = BLOCK_SIZE - 1; i >= 0; i--) {
            if (++ctr[i] != 0) {
                break;
            }
        }
    }
}
