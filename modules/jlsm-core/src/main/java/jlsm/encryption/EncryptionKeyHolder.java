package jlsm.encryption;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.Objects;

import jlsm.encryption.internal.TenantShardRegistry;

/**
 * Three-tier envelope facade for jlsm's at-rest encryption pipeline. This is the
 * F41 v6 facade introduced in WD-01 of {@code implement-encryption-lifecycle}; it
 * supersedes the previous per-field-key holder (renamed to
 * {@link jlsm.encryption.internal.OffHeapKeyMaterial}) by adding the tier-1 Tenant
 * KEK and tier-2 Domain KEK layers between raw key material and the per-field
 * derivation already used by the encryptors.
 *
 * <h2>Three-tier envelope</h2>
 * <pre>
 *   Tenant KEK (tier-1, KMS-managed)
 *     └─ wraps ──▶ Domain KEK (tier-2, persisted wrapped, cached unwrapped)
 *                     └─ wraps ──▶ DEK (tier-3, per scope + version)
 *                                     └─ HKDF-derives ──▶ per-field subkey
 * </pre>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #openDomain} unwraps and caches the tier-2 Domain KEK for a given
 *       {@code (tenant, domain)}. Must precede any DEK operation in that scope.
 *   </li>
 *   <li>{@link #currentDek} / {@link #resolveDek} locate a DekHandle for an
 *       in-use or specific-version DEK.</li>
 *   <li>{@link #generateDek} produces a new DEK and persists its wrapped bytes;
 *       typical callers do this at table-create or on policy-triggered rotation.
 *   </li>
 *   <li>{@link #deriveFieldKey} turns a DekHandle plus field identifiers into a
 *       freshly-derived per-field key in a caller-provided {@link Arena}.</li>
 * </ol>
 *
 * <h2>Zeroization</h2>
 * {@link #close} zeroizes cached domain-KEK and DEK segments and closes the
 * registry. Callers holding field-key segments derived from this facade continue
 * to own those segments' lifetimes via their own {@code Arena}s (R66, R69).
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R9, R10a, R10b,
 * R17, R21, R29, R55, R62, R62a, R66–R69; ADRs
 * {@code .decisions/three-tier-key-hierarchy/adr.md},
 * {@code .decisions/dek-scoping-granularity/adr.md},
 * {@code .decisions/kms-integration-model/adr.md}.
 */
public final class EncryptionKeyHolder implements AutoCloseable {

    // Instance state intentionally omitted at stub stage; TDD drives the exact
    // field layout (cacheArena, cached domain-KEK map, closed flag, ...).

    private EncryptionKeyHolder(Builder builder) {
        throw new UnsupportedOperationException(
                "EncryptionKeyHolder constructor stub — WU-4 scope");
    }

    /** Start a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Ensure the tier-2 Domain KEK for {@code (tenantId, domainId)} is unwrapped and
     * cached. Must be called before any DEK operation in that scope (R55).
     *
     * @throws NullPointerException if either argument is null
     * @throws KmsException if the KMS call fails
     * @throws IOException on registry-shard I/O failure
     * @throws IllegalStateException if this holder has been closed
     */
    public void openDomain(TenantId tenantId, DomainId domainId)
            throws KmsException, IOException {
        throw new UnsupportedOperationException(
                "EncryptionKeyHolder.openDomain stub — WU-4 scope");
    }

    /**
     * Return the handle for the highest-version DEK in the given scope.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalStateException if no DEK exists for the scope or the holder is
     *         closed
     */
    public DekHandle currentDek(TenantId tenantId, DomainId domainId, TableId tableId) {
        throw new UnsupportedOperationException(
                "EncryptionKeyHolder.currentDek stub — WU-4 scope");
    }

    /**
     * Return the handle for a specific {@link DekVersion} in the given scope.
     *
     * @throws NullPointerException if any argument is null
     * @throws DekNotFoundException if no DEK exists for {@code version} (R57)
     * @throws IllegalStateException if the holder is closed
     */
    public DekHandle resolveDek(
            TenantId tenantId, DomainId domainId, TableId tableId, DekVersion version) {
        throw new UnsupportedOperationException(
                "EncryptionKeyHolder.resolveDek stub — WU-4 scope");
    }

    /**
     * Generate a new random DEK for the scope, wrap it under the cached Domain KEK
     * (R29), and persist the {@link WrappedDek} in the tenant's registry shard. The
     * new DEK's version is the previous version + 1 (R18), rejecting
     * {@link Integer#MAX_VALUE} (R18a).
     *
     * @return the handle for the newly-generated DEK
     * @throws NullPointerException if any argument is null
     * @throws KmsException if the KMS is not reachable for the domain-KEK lookup
     * @throws IOException on persistence failure
     * @throws IllegalStateException on version overflow or closed holder
     */
    public DekHandle generateDek(TenantId tenantId, DomainId domainId, TableId tableId)
            throws KmsException, IOException {
        throw new UnsupportedOperationException(
                "EncryptionKeyHolder.generateDek stub — WU-4 scope");
    }

    /**
     * Derive a per-field key from the DEK identified by {@code handle} using HKDF-
     * SHA256 with a length-prefixed {@code info} built from
     * {@code (tenantId, domainId, tableName, fieldName, version)} (R9, R11). The
     * returned segment is allocated in {@code callerArena} and is independent of
     * this facade's internal cache.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code tableName} or {@code fieldName} is
     *         empty
     * @throws IllegalStateException if the holder is closed or the domain has not
     *         been opened (R55)
     */
    public MemorySegment deriveFieldKey(
            DekHandle handle, String tableName, String fieldName, Arena callerArena) {
        throw new UnsupportedOperationException(
                "EncryptionKeyHolder.deriveFieldKey stub — WU-4 scope");
    }

    /**
     * Zeroize all cached key material, close the internal registry, and release the
     * shared cache arena. Idempotent. Waits for in-flight derive callers before
     * closing the shared arena (R62a).
     */
    @Override
    public void close() {
        throw new UnsupportedOperationException("EncryptionKeyHolder.close stub — WU-4 scope");
    }

    /**
     * Fluent builder for {@link EncryptionKeyHolder}. {@code kmsClient} and
     * {@code registry} are required; {@code hkdfSalt} defaults to 32 zero bytes (R10)
     * and {@code cacheTtl} defaults to 30 minutes (kms-integration-model ADR).
     */
    public static final class Builder {

        private KmsClient kmsClient;
        private TenantShardRegistry registry;
        private byte[] hkdfSalt;
        private Duration cacheTtl;

        private Builder() {}

        /**
         * @throws NullPointerException if {@code kmsClient} is null
         */
        public Builder kmsClient(KmsClient kmsClient) {
            this.kmsClient = Objects.requireNonNull(kmsClient, "kmsClient must not be null");
            return this;
        }

        /**
         * @throws NullPointerException if {@code registry} is null
         */
        public Builder registry(TenantShardRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry must not be null");
            return this;
        }

        /**
         * Override the HKDF salt. Defensive copy taken.
         *
         * @throws NullPointerException if {@code hkdfSalt} is null
         * @throws IllegalArgumentException if {@code hkdfSalt} is empty
         */
        public Builder hkdfSalt(byte[] hkdfSalt) {
            Objects.requireNonNull(hkdfSalt, "hkdfSalt must not be null");
            if (hkdfSalt.length == 0) {
                throw new IllegalArgumentException("hkdfSalt must not be empty");
            }
            this.hkdfSalt = hkdfSalt.clone();
            return this;
        }

        /**
         * Override the cache TTL for unwrapped Domain KEKs.
         *
         * @throws NullPointerException if {@code ttl} is null
         * @throws IllegalArgumentException if {@code ttl} is zero or negative
         */
        public Builder cacheTtl(Duration ttl) {
            Objects.requireNonNull(ttl, "ttl must not be null");
            if (ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("cacheTtl must be positive");
            }
            this.cacheTtl = ttl;
            return this;
        }

        /**
         * @throws IllegalStateException if {@code kmsClient} or {@code registry} is
         *         unset
         */
        public EncryptionKeyHolder build() {
            if (kmsClient == null) {
                throw new IllegalStateException("kmsClient is required");
            }
            if (registry == null) {
                throw new IllegalStateException("registry is required");
            }
            return new EncryptionKeyHolder(this);
        }

        // Package-private accessors used by the enclosing class's constructor once
        // the real impl lands.
        KmsClient kmsClientRef() {
            return kmsClient;
        }

        TenantShardRegistry registryRef() {
            return registry;
        }

        byte[] hkdfSaltRef() {
            return hkdfSalt;
        }

        Duration cacheTtlRef() {
            return cacheTtl;
        }
    }
}
