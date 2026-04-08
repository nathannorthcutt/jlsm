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
public final class SseEncryptedIndex implements AutoCloseable {

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
    /** Cached AES key spec for encrypt/decrypt — avoids per-call cloning of encKey. */
    private final SecretKeySpec aesKeySpec;
    /** Cached HMAC key spec for prfKey — avoids per-call cloning in deriveToken/deriveAddress. */
    private final SecretKeySpec hmacKeySpec;

    /** Closed flag — set by {@link #close()} to reject subsequent operations. */
    private volatile boolean closed;

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

        // Cache the AES key spec to avoid per-call SecretKeySpec cloning of encKey
        this.aesKeySpec = new SecretKeySpec(encKey, 0, Math.min(encKey.length, 32), "AES");
        // Cache the HMAC key spec to avoid per-call SecretKeySpec cloning of prfKey
        this.hmacKeySpec = new SecretKeySpec(prfKey, "HmacSHA256");

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
        ensureOpen();
        Objects.requireNonNull(term, "term must not be null");
        return hmacSha256(hmacKeySpec, term.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        ensureOpen();
        Objects.requireNonNull(term, "term must not be null");
        Objects.requireNonNull(docId, "docId must not be null");

        final byte[] token = deriveToken(term);

        // Get or create the per-term counter. Synchronize on it to ensure
        // atomicity of counter-increment + store-put: search() scans
        // positions 0..N and breaks on the first null, so no position < counter
        // may be absent from the store when another thread can observe the
        // incremented counter value.
        final AtomicInteger counter = termCounters.computeIfAbsent(term, k -> new AtomicInteger(0));

        synchronized (counter) {
            if (counter.get() == Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        "Per-term counter overflow: term has reached Integer.MAX_VALUE entries");
            }
            final int stateNum = counter.getAndIncrement();

            // Derive storage address from token + counter
            final byte[] address = deriveAddress(token, stateNum);

            // Encrypt the docId
            final byte[] encryptedEntry = encryptDocId(docId, address);

            store.put(new ByteArrayKey(address), encryptedEntry);
        }
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
        ensureOpen();
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
        ensureOpen();
        Objects.requireNonNull(token, "token must not be null");

        final List<byte[]> results = new ArrayList<>();

        // Scan addresses starting from counter 0. The synchronized block in add()
        // ensures that counter positions are populated in order — when counter N
        // is visible via getAndIncrement, all positions 0..N-1 are already in the
        // store. A null entry therefore means no more entries exist for this token.
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

    /**
     * Closes this SSE index, zeroing derived key material (prfKey and encKey) and rejecting
     * subsequent operations. Idempotent — multiple calls are safe.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            Arrays.fill(prfKey, (byte) 0);
            Arrays.fill(encKey, (byte) 0);
            try {
                aesKeySpec.destroy();
            } catch (javax.security.auth.DestroyFailedException e) {
                // Best-effort cleanup — SecretKeySpec.destroy() may not be supported
                // on all JDK implementations
            }
            try {
                hmacKeySpec.destroy();
            } catch (javax.security.auth.DestroyFailedException e) {
                // Best-effort cleanup
            }
        }
    }

    /** Throws {@link IllegalStateException} if this index has been closed. */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SseEncryptedIndex has been closed");
        }
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
            cipher.init(Cipher.ENCRYPT_MODE, aesKeySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
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
        Objects.requireNonNull(entry, "entry must not be null");
        if (entry.length <= 1 + GCM_IV_BYTES) {
            throw new IllegalArgumentException("entry too short for decryption: length "
                    + entry.length + ", minimum " + (2 + GCM_IV_BYTES));
        }

        try {
            final byte[] iv = Arrays.copyOfRange(entry, 1, 1 + GCM_IV_BYTES);
            final byte[] encrypted = Arrays.copyOfRange(entry, 1 + GCM_IV_BYTES, entry.length);

            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKeySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
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
     * Computes HMAC-SHA256 using a pre-built key spec (avoids per-call key material cloning).
     */
    private static byte[] hmacSha256(SecretKeySpec keySpec, byte[] data) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keySpec);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    /**
     * Computes HMAC-SHA256 from raw key bytes. Creates a temporary {@link SecretKeySpec} and
     * destroys it after use to avoid leaving key material clones on the heap.
     */
    private static byte[] hmacSha256(byte[] key, byte[] data) {
        final SecretKeySpec keySpec = new SecretKeySpec(key, "HmacSHA256");
        try {
            return hmacSha256(keySpec, data);
        } finally {
            try {
                keySpec.destroy();
            } catch (javax.security.auth.DestroyFailedException e) {
                // Best-effort cleanup
            }
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
