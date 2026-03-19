package jlsm.encryption;

/**
 * Contract: Declares the encryption mechanism for a field. Each variant maps 1:1 to an encryption
 * family and exposes capability methods that the {@link jlsm.table.internal.IndexRegistry} uses to
 * validate index compatibility at schema construction time.
 *
 * <p>
 * Governed by: .decisions/field-encryption-api-design/adr.md
 */
public sealed interface EncryptionSpec permits EncryptionSpec.None, EncryptionSpec.Deterministic,
        EncryptionSpec.OrderPreserving, EncryptionSpec.DistancePreserving, EncryptionSpec.Opaque {

    /** The singleton no-encryption specification. */
    EncryptionSpec NONE = new None();

    // ── Capability query methods ──────────────────────────────────────────────

    /** Returns {@code true} if equality comparisons are supported on encrypted values. */
    default boolean supportsEquality() {
        return false;
    }

    /** Returns {@code true} if range (ordering) queries are supported on encrypted values. */
    default boolean supportsRange() {
        return false;
    }

    /** Returns {@code true} if keyword (single-term) search is supported on encrypted values. */
    default boolean supportsKeywordSearch() {
        return false;
    }

    /** Returns {@code true} if phrase / proximity search is supported on encrypted values. */
    default boolean supportsPhraseSearch() {
        return false;
    }

    /** Returns {@code true} if SSE-based encrypted index search is supported. */
    default boolean supportsSseSearch() {
        return false;
    }

    /**
     * Returns {@code true} if approximate nearest-neighbor search is supported on encrypted values.
     */
    default boolean supportsANN() {
        return false;
    }

    // ── Permits ───────────────────────────────────────────────────────────────

    /**
     * No encryption — field values are stored in plaintext. All capabilities are trivially
     * supported (delegated to the underlying index).
     */
    record None() implements EncryptionSpec {
        @Override
        public boolean supportsEquality() {
            return true;
        }

        @Override
        public boolean supportsRange() {
            return true;
        }

        @Override
        public boolean supportsKeywordSearch() {
            return true;
        }

        @Override
        public boolean supportsPhraseSearch() {
            return true;
        }

        @Override
        public boolean supportsSseSearch() {
            return true;
        }

        @Override
        public boolean supportsANN() {
            return true;
        }
    }

    /**
     * Deterministic encryption (AES-SIV). Same plaintext always produces the same ciphertext under
     * the same key, enabling equality comparisons and keyword search via inverted index.
     */
    record Deterministic() implements EncryptionSpec {
        @Override
        public boolean supportsEquality() {
            return true;
        }

        @Override
        public boolean supportsKeywordSearch() {
            return true;
        }
    }

    /**
     * Order-preserving encryption (Boldyreva OPE). Ciphertext ordering matches plaintext ordering,
     * enabling range queries. Also supports equality.
     */
    record OrderPreserving() implements EncryptionSpec {
        @Override
        public boolean supportsEquality() {
            return true;
        }

        @Override
        public boolean supportsRange() {
            return true;
        }
    }

    /**
     * Distance-comparison-preserving encryption (DCPE / Scale-And-Perturb). Encrypted vectors
     * approximate the distance relationships of plaintext vectors, enabling ANN search.
     */
    record DistancePreserving() implements EncryptionSpec {
        @Override
        public boolean supportsANN() {
            return true;
        }
    }

    /**
     * Opaque encryption (AES-GCM). No search capability — values must be decrypted before any
     * comparison. Provides the strongest security guarantee.
     */
    record Opaque() implements EncryptionSpec {
        // All capabilities default to false
    }

    // ── Static factory methods ────────────────────────────────────────────────

    /** Returns the no-encryption specification. */
    static EncryptionSpec none() {
        return NONE;
    }

    /** Returns a deterministic encryption specification (AES-SIV). */
    static EncryptionSpec deterministic() {
        return new Deterministic();
    }

    /** Returns an order-preserving encryption specification (Boldyreva OPE). */
    static EncryptionSpec orderPreserving() {
        return new OrderPreserving();
    }

    /** Returns a distance-preserving encryption specification (DCPE / SAP). */
    static EncryptionSpec distancePreserving() {
        return new DistancePreserving();
    }

    /** Returns an opaque encryption specification (AES-GCM). */
    static EncryptionSpec opaque() {
        return new Opaque();
    }
}
