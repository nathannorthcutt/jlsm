package jlsm.encryption.internal;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

import jlsm.encryption.TenantId;

/**
 * In-memory-cached, per-tenant key registry. Reads are wait-free via a volatile
 * snapshot (R64); writes are serialized per-tenant by a {@link
 * java.util.concurrent.locks.ReentrantReadWriteLock} write-lock and persisted via
 * {@link ShardStorage} (R63, R34a). Different tenants' snapshots are fully isolated
 * — mutations in one tenant's shard never touch another's (R82a, three-tier-key-
 * hierarchy ADR).
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R19b, R62, R62a,
 * R63, R64, R82a.
 */
public final class TenantShardRegistry implements AutoCloseable {

    private final ShardStorage storage;

    /**
     * @throws NullPointerException if {@code storage} is null
     */
    public TenantShardRegistry(ShardStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
    }

    /**
     * Wait-free read of the latest snapshot for a tenant. Performs a lazy load from
     * storage on first access; subsequent calls read the cached volatile reference
     * directly (R64).
     *
     * @throws NullPointerException if {@code tenantId} is null
     * @throws IOException on initial-load failure
     */
    public KeyRegistryShard readSnapshot(TenantId tenantId) throws IOException {
        throw new UnsupportedOperationException(
                "TenantShardRegistry.readSnapshot stub — WU-3 scope");
    }

    /**
     * Serially update a tenant's shard. Acquires the per-tenant write lock, loads
     * the current snapshot, invokes {@code mutator}, persists the new shard
     * atomically via {@link ShardStorage#writeShard}, publishes the new snapshot
     * via a volatile swap (R63, R64), and returns the mutator-provided result.
     *
     * @throws NullPointerException if any argument is null
     * @throws IOException on persistence failure (lock released before throw)
     */
    public <R> R updateShard(
            TenantId tenantId, Function<KeyRegistryShard, ShardUpdate<R>> mutator)
            throws IOException {
        throw new UnsupportedOperationException(
                "TenantShardRegistry.updateShard stub — WU-3 scope");
    }

    /**
     * Release cached snapshots and zeroize any cached salt bytes. Idempotent.
     */
    @Override
    public void close() {
        throw new UnsupportedOperationException("TenantShardRegistry.close stub — WU-3 scope");
    }

    /**
     * Return value of an {@link #updateShard} mutator: the new shard to persist and
     * a caller-visible result.
     *
     * @param newShard the new shard state to persist and publish
     * @param result the value returned to the {@code updateShard} caller
     * @param <R> caller-visible result type
     */
    public static record ShardUpdate<R>(KeyRegistryShard newShard, R result) {
        public ShardUpdate {
            Objects.requireNonNull(newShard, "newShard must not be null");
            // result may be null (caller may not need one)
        }
    }
}
