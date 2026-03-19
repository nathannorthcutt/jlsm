package jlsm.table.internal;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jlsm.encryption.EncryptionKeyHolder;

/**
 * Contract: Tier 3 SSE (Searchable Symmetric Encryption) encrypted inverted index based on Curtmola
 * SSE-2 / Dyn2Lev. For each term, derives a search token {@code Tw = PRF(K, term)}, encrypts the
 * document ID and stores it in a {@link ConcurrentHashMap} keyed by a hash derived from the token
 * and a per-term state counter.
 *
 * <p>
 * Search: caller submits Tw, index iterates over state counters 0..N, derives addresses, collects
 * all matching encrypted docIds, decrypts and returns them.
 *
 * <p>
 * Forward privacy: each add operation increments a per-term state counter, producing a new storage
 * address. This prevents linking new additions to previous ones.
 *
 * <p>
 * Governed by: .decisions/encrypted-index-strategy/adr.md
 */
public final class SseEncryptedIndex {

    /** Tag length for AES-GCM encryption of docIds. */
    private static final int GCM_TAG_BITS = 128;
    /** IV size for AES-GCM. */
    private static final int GCM_IV_BYTES = 12;
    /** Marker byte for a live (non-deleted) entry. */
    private static final byte LIVE_MARKER = (byte) 0x01;
    /** Marker byte for a deleted entry. */
    private static final byte DELETED_MARKER = (byte) 0x00;

    /** Key material for HMAC-based PRF. */
    private final byte[] prfKey;
    /** Key material for AES-GCM docId encryption. */
    private final byte[] encKey;

    /**
     * Backing store: address (derived from token + counter) -> encrypted entry. Each entry is:
     * [1-byte live/deleted marker][GCM-IV][encrypted docId + tag].
     */
    private final ConcurrentHashMap<ByteArrayKey, byte[]> store;

    /** Per-term state counter for forward privacy. */
    private final ConcurrentHashMap<String, AtomicInteger> termCounters;

    /**
     * Creates a new SSE encrypted index with the given key.
     *
     * @param keyHolder the key holder for PRF and posting list encryption
     * @throws NullPointerException if keyHolder is null
     */
    public SseEncryptedIndex(EncryptionKeyHolder keyHolder) {
        Objects.requireNonNull(keyHolder, "keyHolder must not be null");
        final byte[] keyBytes = keyHolder.getKeyBytes();

        // Derive two sub-keys from the master key via HMAC with different labels
        this.prfKey = deriveSubKey(keyBytes, "SSE-PRF-KEY");
        this.encKey = deriveSubKey(keyBytes, "SSE-ENC-KEY");
        Arrays.fill(keyBytes, (byte) 0);

        this.store = new ConcurrentHashMap<>();
        this.termCounters = new ConcurrentHashMap<>();
    }

    /**
     * Derives a search token for the given term. Token derivation is deterministic:
     * {@code Tw = HMAC-SHA256(prfKey, term)}.
     *
     * @param term the plaintext search term
     * @return the search token bytes (32 bytes)
     * @throws NullPointerException if term is null
     */
    public byte[] deriveToken(String term) {
        Objects.requireNonNull(term, "term must not be null");
        return hmacSha256(prfKey, term.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Adds a document to the posting list for the given term. Each add increments a per-term state
     * counter for forward privacy, producing a unique storage address.
     *
     * @param term the plaintext term
     * @param docId the document identifier
     * @throws NullPointerException if term or docId is null
     */
    public void add(String term, byte[] docId) {
        Objects.requireNonNull(term, "term must not be null");
        Objects.requireNonNull(docId, "docId must not be null");

        final byte[] token = deriveToken(term);

        // Increment state counter for forward privacy
        final AtomicInteger counter = termCounters.computeIfAbsent(term, k -> new AtomicInteger(0));
        final int stateNum = counter.getAndIncrement();

        // Derive storage address from token + counter
        final byte[] address = deriveAddress(token, stateNum);

        // Encrypt the docId
        final byte[] encryptedEntry = encryptDocId(docId, address);

        store.put(new ByteArrayKey(address), encryptedEntry);
    }

    /**
     * Removes a document from the posting list for the given term. Marks the entry as deleted by
     * scanning all state counter positions and finding the matching docId.
     *
     * @param term the plaintext term
     * @param docId the document identifier to remove
     * @throws NullPointerException if term or docId is null
     */
    public void delete(String term, byte[] docId) {
        Objects.requireNonNull(term, "term must not be null");
        Objects.requireNonNull(docId, "docId must not be null");

        final byte[] token = deriveToken(term);
        final AtomicInteger counter = termCounters.get(term);
        if (counter == null) {
            return; // Term never added — nothing to delete
        }

        final int maxCounter = counter.get();
        for (int i = 0; i < maxCounter; i++) {
            final byte[] address = deriveAddress(token, i);
            final ByteArrayKey key = new ByteArrayKey(address);
            final byte[] entry = store.get(key);
            if (entry == null || entry[0] == DELETED_MARKER) {
                continue;
            }

            // Decrypt and check if this is the docId to delete
            final byte[] decrypted = decryptDocId(entry, address);
            if (decrypted != null && Arrays.equals(decrypted, docId)) {
                // Mark as deleted by replacing the live marker
                final byte[] deletedEntry = Arrays.copyOf(entry, entry.length);
                deletedEntry[0] = DELETED_MARKER;
                store.put(key, deletedEntry);
                return;
            }
        }
    }

    /**
     * Searches for documents matching the given search token. Iterates over all state counter
     * positions, decrypts live entries, and returns the matching docIds.
     *
     * @param token the search token (derived via {@link #deriveToken})
     * @return the list of matching document identifiers
     * @throws NullPointerException if token is null
     */
    public List<byte[]> search(byte[] token) {
        Objects.requireNonNull(token, "token must not be null");

        final List<byte[]> results = new ArrayList<>();

        // We need to find the term that maps to this token. Since we store counters by term,
        // iterate all possible counter values. We scan addresses starting from 0 until we
        // hit a miss.
        for (int i = 0;; i++) {
            final byte[] address = deriveAddress(token, i);
            final ByteArrayKey key = new ByteArrayKey(address);
            final byte[] entry = store.get(key);
            if (entry == null) {
                break; // No more entries for this token
            }
            if (entry[0] == LIVE_MARKER) {
                final byte[] decrypted = decryptDocId(entry, address);
                if (decrypted != null) {
                    results.add(decrypted);
                }
            }
        }

        return results;
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Derives a storage address from a token and state counter:
     * {@code address = HMAC-SHA256(token, counter_bytes)}.
     */
    private byte[] deriveAddress(byte[] token, int counter) {
        final byte[] counterBytes = ByteBuffer.allocate(4).putInt(counter).array();
        return hmacSha256(token, counterBytes);
    }

    /**
     * Encrypts a docId using AES-GCM with the address as additional authenticated data. Format:
     * [1-byte live marker][12-byte IV][encrypted docId + 16-byte GCM tag].
     */
    private byte[] encryptDocId(byte[] docId, byte[] address) {
        try {
            // Derive a deterministic IV from the address (first 12 bytes of hash)
            final byte[] ivSource = MessageDigest.getInstance("SHA-256").digest(address);
            final byte[] iv = Arrays.copyOf(ivSource, GCM_IV_BYTES);

            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            final SecretKeySpec keySpec = new SecretKeySpec(encKey, 0, Math.min(encKey.length, 32),
                    "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(address);

            final byte[] encrypted = cipher.doFinal(docId);

            // [marker][iv][encrypted + tag]
            final byte[] entry = new byte[1 + GCM_IV_BYTES + encrypted.length];
            entry[0] = LIVE_MARKER;
            System.arraycopy(iv, 0, entry, 1, GCM_IV_BYTES);
            System.arraycopy(encrypted, 0, entry, 1 + GCM_IV_BYTES, encrypted.length);
            return entry;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts a docId from an encrypted entry.
     */
    private byte[] decryptDocId(byte[] entry, byte[] address) {
        assert entry != null : "entry must not be null";
        assert entry.length > 1 + GCM_IV_BYTES : "entry too short for decryption";

        try {
            final byte[] iv = Arrays.copyOfRange(entry, 1, 1 + GCM_IV_BYTES);
            final byte[] encrypted = Arrays.copyOfRange(entry, 1 + GCM_IV_BYTES, entry.length);

            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            final SecretKeySpec keySpec = new SecretKeySpec(encKey, 0, Math.min(encKey.length, 32),
                    "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(address);

            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException e) {
            // Decryption failure — possibly a deleted/corrupted entry
            return null;
        }
    }

    /**
     * Derives a sub-key from the master key using HMAC-SHA256 with a label.
     */
    private static byte[] deriveSubKey(byte[] masterKey, String label) {
        return hmacSha256(masterKey, label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Computes HMAC-SHA256.
     */
    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    /**
     * A byte-array wrapper that provides proper equals/hashCode semantics for use as a
     * ConcurrentHashMap key.
     */
    private record ByteArrayKey(byte[] data) {

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ByteArrayKey other && Arrays.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }
}
