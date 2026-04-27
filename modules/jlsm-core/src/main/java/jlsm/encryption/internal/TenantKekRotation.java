package jlsm.encryption.internal;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import jlsm.encryption.DomainId;
import jlsm.encryption.EncryptionContext;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.KmsException;
import jlsm.encryption.TenantId;
import jlsm.encryption.UnwrapResult;
import jlsm.encryption.WrappedDomainKek;

/**
 * Streaming per-shard tier-1 (tenant) KEK rotation (R32a). Iterates the tenant's shards via
 * {@link TenantShardRegistry#shardIterator(TenantId)}, takes a per-shard exclusive lock via
 * {@link ShardLockRegistry}, rewraps each shard's domain KEKs under the new {@link KekRef}, and
 * releases between batches subject to the {@link ShardLockRegistry} R32c max-hold-time bound
 * (default 250ms). Tracks retired refs via {@link RetiredReferences} (R33).
 *
 * <p>
 * Tier-1 rotation does <b>not</b> touch tier-3 DEKs (R32a) — DEK cipher material on disk remains
 * wrapped under its original domain KEK. The cascading lazy rewrap continues at the tier-3 layer on
 * next access.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R32a, R32c, R33, R34a); ADR
 * encryption-three-tier-key-hierarchy.
 *
 * @spec encryption.primitives-lifecycle R32a
 * @spec encryption.primitives-lifecycle R32c
 * @spec encryption.primitives-lifecycle R33
 * @spec encryption.primitives-lifecycle R34a
 */
public final class TenantKekRotation {

    /** Default per-batch max-hold-time per R32c (default 250ms). */
    private static final Duration DEFAULT_MAX_HOLD_TIME = Duration.ofMillis(250);
    /** Retired-ref retention default — generous since R33a couples to WAL retention via WU-6. */
    private static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    private final TenantShardRegistry registry;
    private final ShardLockRegistry locks;
    private final KmsClient kmsClient;

    private TenantKekRotation(TenantShardRegistry registry, ShardLockRegistry locks,
            KmsClient kmsClient) {
        this.registry = registry;
        this.locks = locks;
        this.kmsClient = kmsClient;
    }

    /**
     * Construct a tenant-KEK rotation orchestrator backed by {@code kmsClient} for domain-KEK
     * unwrap+wrap operations during rotation.
     */
    public static TenantKekRotation create(TenantShardRegistry registry, ShardLockRegistry locks,
            KmsClient kmsClient) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(locks, "locks");
        Objects.requireNonNull(kmsClient, "kmsClient");
        return new TenantKekRotation(registry, locks, kmsClient);
    }

    /**
     * Begin a streaming rotation for {@code tenantId} from {@code oldRef} to {@code newRef}. The
     * returned handle drives the per-shard rotation loop; it is the caller's responsibility to pump
     * the iterator under the rotation cadence.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code oldRef.equals(newRef)}
     * @throws IOException on I/O failure during shard enumeration
     */
    public RotationHandle startRotation(TenantId tenantId, KekRef oldRef, KekRef newRef)
            throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(oldRef, "oldRef");
        Objects.requireNonNull(newRef, "newRef");
        if (oldRef.equals(newRef)) {
            throw new IllegalArgumentException(
                    "oldRef and newRef must differ; got both=" + oldRef.value());
        }
        return new RotationHandleImpl(tenantId, oldRef, newRef);
    }

    /** Handle for a streaming rotation. Iterator-shaped; one shard per advance. */
    public interface RotationHandle extends AutoCloseable {

        /**
         * Advance one shard. Returns true if a shard was rewrapped, false if rotation is complete.
         */
        boolean advance() throws IOException;

        /** Cumulative number of shards rewrapped so far. */
        long rewrappedCount();

        /** Configured max-hold-time per advance (R32c). */
        Duration maxHoldTime();

        @Override
        void close();
    }

    private final class RotationHandleImpl implements RotationHandle {

        private final TenantId tenantId;
        private final KekRef oldRef;
        private final KekRef newRef;
        private final Iterator<ShardLockRegistry.ShardKey> shardIter;
        private long rewrapped = 0L;
        private boolean closed = false;

        RotationHandleImpl(TenantId tenantId, KekRef oldRef, KekRef newRef) {
            this.tenantId = tenantId;
            this.oldRef = oldRef;
            this.newRef = newRef;
            this.shardIter = registry.shardIterator(tenantId);
        }

        @Override
        public boolean advance() throws IOException {
            if (closed) {
                return false;
            }
            if (!shardIter.hasNext()) {
                return false;
            }
            final ShardLockRegistry.ShardKey shardKey = shardIter.next();
            // R34a + R32c: hold an exclusive lock on this shard for the rewrap, capped at
            // the configured max-hold-time. The lock is released on return from this method
            // — successive advance() calls reacquire so other operations on the shard can
            // interleave between batches.
            final ShardLockRegistry.ExclusiveStamp stamp = locks.acquireExclusiveTimed(shardKey,
                    DEFAULT_MAX_HOLD_TIME);
            try {
                rewrapShard(tenantId);
                rewrapped++;
                return true;
            } finally {
                locks.releaseExclusive(stamp);
            }
        }

        @Override
        public long rewrappedCount() {
            return rewrapped;
        }

        @Override
        public Duration maxHoldTime() {
            return DEFAULT_MAX_HOLD_TIME;
        }

        @Override
        public void close() {
            closed = true;
        }

        /**
         * Rewrap the tenant's shard under the new tier-1 KEK ref. Each domain KEK is unwrapped
         * under {@code oldRef} (synthetic for LocalKmsClient — the master key is shared across
         * refs), then re-wrapped under {@code newRef}. The DEKs are not rewrapped (R32a: tier-1
         * rotation does not touch tier-3 cipher material). The retired-references set accumulates
         * {@code oldRef}.
         */
        private void rewrapShard(TenantId tenantId) throws IOException {
            try {
                registry.updateShard(tenantId, current -> {
                    final Map<DomainId, WrappedDomainKek> updatedDomainKeks = new HashMap<>();
                    for (Map.Entry<DomainId, WrappedDomainKek> e : current.domainKeks()
                            .entrySet()) {
                        final WrappedDomainKek dk = e.getValue();
                        final byte[] rewrappedBytes = unwrapAndRewrap(tenantId, e.getKey(), dk,
                                oldRef, newRef);
                        updatedDomainKeks.put(e.getKey(), new WrappedDomainKek(dk.domainId(),
                                dk.version(), rewrappedBytes, newRef));
                    }
                    final RetiredReferences nextRetired = current.retiredReferences()
                            .markRetired(oldRef, Instant.now().plus(DEFAULT_RETENTION));
                    final KeyRegistryShard updated = new KeyRegistryShard(current.tenantId(),
                            current.deks(), updatedDomainKeks, newRef, current.hkdfSalt(),
                            nextRetired, current.rekeyCompleteMarker(),
                            current.permanentlyRevokedDeks());
                    return new TenantShardRegistry.ShardUpdate<>(updated, null);
                });
            } catch (RuntimeIo wrapper) {
                // KMS unwrap/wrap surfaced via the unchecked carrier — propagate the underlying
                // IOException to the caller verbatim so the throws contract is honoured.
                throw (IOException) wrapper.getCause();
            }
        }

        private byte[] unwrapAndRewrap(TenantId tenantId, DomainId domainId,
                WrappedDomainKek wrapped, KekRef oldRef, KekRef newRef) {
            final EncryptionContext ctx = EncryptionContext.forDomainKek(tenantId, domainId);
            UnwrapResult unwrapped = null;
            try {
                final ByteBuffer wrappedBb = ByteBuffer.wrap(wrapped.wrappedBytes());
                unwrapped = kmsClient.unwrapKek(wrappedBb, oldRef, ctx);
                final MemorySegment plaintext = unwrapped.plaintext();
                // Wrap under newRef. We funnel the plaintext through a confined arena so we
                // don't accidentally leak the unwrap arena's segment beyond the wrap call.
                try (Arena arena = Arena.ofConfined()) {
                    final long len = plaintext.byteSize();
                    final MemorySegment copy = arena.allocate(len);
                    MemorySegment.copy(plaintext, 0, copy, 0, len);
                    final var wrapResult = kmsClient.wrapKek(copy, newRef, ctx);
                    final ByteBuffer out = wrapResult.wrappedBytes();
                    final byte[] outBytes = new byte[out.remaining()];
                    out.get(outBytes);
                    return outBytes;
                }
            } catch (KmsException e) {
                throw new RuntimeIo(new IOException("KMS rewrap failed during tenant rotation", e));
            } finally {
                // R66: close the unwrap arena to zeroize the plaintext segment.
                if (unwrapped != null) {
                    try {
                        unwrapped.owner().close();
                    } catch (RuntimeException ignored) {
                        // best-effort cleanup
                    }
                }
            }
        }
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
}
