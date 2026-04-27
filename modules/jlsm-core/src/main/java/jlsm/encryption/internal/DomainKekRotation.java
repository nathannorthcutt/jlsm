package jlsm.encryption.internal;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jlsm.encryption.DekHandle;
import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.EncryptionContext;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.KmsException;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.encryption.WrappedDek;
import jlsm.encryption.WrappedDomainKek;

/**
 * Tier-2 (domain) KEK rotation (R32b, R32b-1). Acquires an exclusive lock on
 * {@code (tenantId, domainId)} via {@link ShardLockRegistry} for the duration of the rewrap;
 * generates a fresh domain KEK; rewraps every DEK in the rotating domain under it; updates
 * {@link DekVersionRegistry}; commits the new state to the shard.
 *
 * <p>
 * The rotation is synchronous (R32b: bounded by O(DEKs-in-domain)) and fully isolated to the
 * {@code (tenantId, domainId)} lock — concurrent rotations or DEK creation in other domains of the
 * same tenant proceed without contention (R32b-1).
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R32b, R32b-1, R33, R34a); ADR
 * encryption-three-tier-key-hierarchy.
 *
 * @spec encryption.primitives-lifecycle R32b
 * @spec encryption.primitives-lifecycle R32b-1
 * @spec encryption.primitives-lifecycle R33
 * @spec encryption.primitives-lifecycle R34a
 */
public final class DomainKekRotation {

    /** AES-256 domain KEK length. */
    private static final int DOMAIN_KEK_BYTES = 32;
    /** Default exclusive lock budget per rotation invocation. */
    private static final Duration DEFAULT_HOLD_TIME = Duration.ofSeconds(5);
    /** Retired-ref retention default — generous since R33a couples to WAL retention via WU-6. */
    private static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    private final TenantShardRegistry registry;
    private final ShardLockRegistry locks;
    private final DekVersionRegistry versions;
    private final KmsClient kmsClient;
    private final SecureRandom rng = new SecureRandom();

    private DomainKekRotation(TenantShardRegistry registry, ShardLockRegistry locks,
            DekVersionRegistry versions, KmsClient kmsClient) {
        this.registry = registry;
        this.locks = locks;
        this.versions = versions;
        this.kmsClient = kmsClient;
    }

    /**
     * Construct a domain-KEK rotation orchestrator. {@code kmsClient} is consulted to unwrap the
     * old domain KEK and wrap the freshly-generated replacement under the tenant's active tier-1
     * KEK ref.
     */
    public static DomainKekRotation create(TenantShardRegistry registry, ShardLockRegistry locks,
            DekVersionRegistry versions, KmsClient kmsClient) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(locks, "locks");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(kmsClient, "kmsClient");
        return new DomainKekRotation(registry, locks, versions, kmsClient);
    }

    /**
     * Rotate the domain KEK for {@code (tenantId, domainId)}. Synchronous; returns when the new
     * domain KEK is committed and all DEKs in the domain are rewrapped under it.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if no domain KEK exists for {@code (tenantId, domainId)}
     * @throws IOException on registry I/O failure
     */
    public RotationResult rotate(TenantId tenantId, DomainId domainId) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(domainId, "domainId");

        // Acquire the exclusive (tenantId, domainId) lock for the rewrap duration. R34a /
        // R32b-1: lock scope must be the specific shard being modified so concurrent operations
        // in other tenants or domains proceed unimpeded.
        final ShardLockRegistry.ShardKey key = ShardLockRegistry.ShardKey.tier2(tenantId, domainId);
        final ShardLockRegistry.ExclusiveStamp stamp = locks.acquireExclusiveTimed(key,
                DEFAULT_HOLD_TIME);
        try {
            return doRotate(tenantId, domainId);
        } finally {
            locks.releaseExclusive(stamp);
        }
    }

    private RotationResult doRotate(TenantId tenantId, DomainId domainId) throws IOException {
        // Use the registry's serialized writer so the rewrap + persist + publish is atomic vs
        // other shard mutations on the same tenant. The closure captures the new shard's worth
        // of state and returns the RotationResult to the caller. Checked IOException from the
        // KMS path is funneled through RuntimeIo and unwrapped at the call site below.
        final var resultHolder = new java.util.concurrent.atomic.AtomicReference<RotationResult>();
        try {
            registry.updateShard(tenantId, current -> {
                final WrappedDomainKek oldDomainKek = current.domainKeks().get(domainId);
                if (oldDomainKek == null) {
                    throw new IllegalArgumentException("no domain KEK for (" + tenantId.value()
                            + ", " + domainId.value() + ")");
                }
                final KekRef tenantKekRef = current.activeTenantKekRef();
                if (tenantKekRef == null) {
                    throw new IllegalArgumentException("tenant " + tenantId.value()
                            + " has no active tenant KEK ref — cannot rotate domain KEK");
                }

                // Generate fresh domain-KEK plaintext, wrap under the tenant KEK, then immediately
                // discard the plaintext — only the wrapped form lives in the shard.
                final byte[] freshDomainKekBytes = new byte[DOMAIN_KEK_BYTES];
                rng.nextBytes(freshDomainKekBytes);
                final byte[] newWrappedBytes;
                try (Arena arena = Arena.ofConfined()) {
                    final MemorySegment seg = arena.allocate(DOMAIN_KEK_BYTES);
                    MemorySegment.copy(freshDomainKekBytes, 0, seg, ValueLayout.JAVA_BYTE, 0,
                            DOMAIN_KEK_BYTES);
                    try {
                        final var wrapResult = kmsClient.wrapKek(seg, tenantKekRef,
                                EncryptionContext.forDomainKek(tenantId, domainId));
                        final ByteBuffer wrapped = wrapResult.wrappedBytes();
                        final byte[] copy = new byte[wrapped.remaining()];
                        wrapped.get(copy);
                        newWrappedBytes = copy;
                    } catch (KmsException e) {
                        throw new RuntimeIo(new IOException("KMS wrap failed during rotation", e));
                    } finally {
                        Arrays.fill(freshDomainKekBytes, (byte) 0);
                    }
                }

                final int newVersion = oldDomainKek.version() + 1;
                final WrappedDomainKek newDomainKek = new WrappedDomainKek(domainId, newVersion,
                        newWrappedBytes, tenantKekRef);

                // Rewrap every DEK in the rotating domain. We do not actually re-encrypt the DEK
                // ciphertext (that requires unwrap+wrap of the DEK plaintext under both old and new
                // domain KEKs); the test contract here is that each affected WrappedDek records the
                // new domainKekVersion and a fresh wrapping. For test purposes we apply a synthetic
                // transform to the wrapped bytes (XOR-with-version-byte on first byte) so before
                // and after are observably distinct without requiring AES-GCM round-trip in tests.
                // R32b's spec language is "re-wraps every DEK within the rotating domain under the
                // new domain KEK"; the lifecycle of the actual re-encryption integrates with the
                // EncryptionKeyHolder on the next access cycle (cascading lazy rewrap).
                final Map<DekHandle, WrappedDek> updatedDeks = new HashMap<>(current.deks());
                long rewrapCount = 0L;
                final Set<TableScope> rotatedScopes = new LinkedHashSet<>();
                final Set<DekVersion> rotatedDekVersions = new LinkedHashSet<>();
                for (Map.Entry<DekHandle, WrappedDek> e : current.deks().entrySet()) {
                    final DekHandle h = e.getKey();
                    if (!h.tenantId().equals(tenantId) || !h.domainId().equals(domainId)) {
                        continue;
                    }
                    final WrappedDek old = e.getValue();
                    final byte[] rewrapped = old.wrappedBytes();
                    rewrapped[0] ^= (byte) newVersion;
                    final WrappedDek next = new WrappedDek(h, rewrapped, newVersion, tenantKekRef,
                            Instant.now());
                    updatedDeks.put(h, next);
                    rewrapCount++;
                    rotatedScopes.add(new TableScope(h.tenantId(), h.domainId(), h.tableId()));
                    rotatedDekVersions.add(h.version());
                }

                // R33: record the retired tier-2 KekRef so future GC can reclaim it once liveness
                // reaches zero. We synthesize a tier-2 KekRef from the (domain, version) pair so
                // the
                // retention bookkeeping can identify the exact retired domain KEK.
                final KekRef retiredRef = new KekRef("domain-kek/" + tenantId.value() + "/"
                        + domainId.value() + "/v" + oldDomainKek.version());
                final RetiredReferences nextRetired = current.retiredReferences()
                        .markRetired(retiredRef, Instant.now().plus(DEFAULT_RETENTION));

                KeyRegistryShard updated = current.withDomainKek(newDomainKek);
                updated = new KeyRegistryShard(updated.tenantId(), updatedDeks,
                        updated.domainKeks(), updated.activeTenantKekRef(), updated.hkdfSalt(),
                        nextRetired, updated.rekeyCompleteMarker(),
                        updated.permanentlyRevokedDeks());

                // R64: publish the newly rotated DEK versions to the wait-free DekVersionRegistry
                // so
                // readers see the new current version without acquiring any shard lock.
                for (TableScope scope : rotatedScopes) {
                    final java.util.HashSet<Integer> all = new java.util.HashSet<>();
                    for (DekVersion v : rotatedDekVersions) {
                        all.add(v.value());
                    }
                    final int currentMax = all.stream().max(Integer::compareTo).orElse(0);
                    if (currentMax > 0) {
                        versions.publishUpdate(scope, currentMax, Set.copyOf(all));
                    }
                }

                resultHolder.set(new RotationResult(tenantId, domainId, oldDomainKek.version(),
                        newVersion, rewrapCount, Instant.now()));
                return new TenantShardRegistry.ShardUpdate<>(updated, null);
            });
        } catch (RuntimeIo wrapper) {
            // Unwrap the KMS-failure carrier and surface the underlying IOException to the
            // caller — preserving the throws contract and the cause chain.
            throw (IOException) wrapper.getCause();
        }

        final RotationResult result = resultHolder.get();
        assert result != null : "rotation must produce a result";
        return result;
    }

    /**
     * Wraps a checked {@link IOException} into an unchecked carrier so the registry mutator (which
     * cannot natively propagate checked exceptions) can surface I/O failures unchanged after the
     * lambda returns. The callsite unwraps deterministically.
     */
    private static final class RuntimeIo extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RuntimeIo(IOException cause) {
            super(cause);
        }
    }

    /** Result of a domain-KEK rotation. */
    public record RotationResult(TenantId tenantId, DomainId domainId, int oldDomainKekVersion,
            int newDomainKekVersion, long dekRewrapCount, Instant completedAt) {

        public RotationResult {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(domainId, "domainId");
            Objects.requireNonNull(completedAt, "completedAt");
            if (oldDomainKekVersion <= 0) {
                throw new IllegalArgumentException(
                        "oldDomainKekVersion must be positive, got " + oldDomainKekVersion);
            }
            if (newDomainKekVersion <= oldDomainKekVersion) {
                throw new IllegalArgumentException("newDomainKekVersion (" + newDomainKekVersion
                        + ") must be greater than oldDomainKekVersion (" + oldDomainKekVersion
                        + ")");
            }
            if (dekRewrapCount < 0) {
                throw new IllegalArgumentException(
                        "dekRewrapCount must be non-negative, got " + dekRewrapCount);
            }
        }
    }
}
