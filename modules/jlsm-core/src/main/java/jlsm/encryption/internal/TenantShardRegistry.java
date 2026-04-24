package jlsm.encryption.internal;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import jlsm.encryption.KekRef;
import jlsm.encryption.TenantId;

/**
 * In-memory-cached, per-tenant key registry. Reads are wait-free via a volatile
 * {@link AtomicReference} snapshot (R64); writes are serialized per-tenant by a
 * {@link ReentrantLock} write-mutex and persisted via {@link ShardStorage} (R63, R34a). Different
 * tenants' snapshots are fully isolated — mutations in one tenant's shard never touch another's
 * (R82a, three-tier-key-hierarchy ADR).
 *
 * <p>
 * <b>Initial-load semantics.</b> When a tenant is first accessed (via {@link #readSnapshot} or
 * {@link #updateShard}), the registry acquires the per-tenant initialization lock once, loads the
 * shard from disk (or synthesizes an empty shard if none exists), and publishes it. Subsequent
 * reads are wait-free.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R19b, R62, R62a, R63, R64, R82a.
 */
public final class TenantShardRegistry implements AutoCloseable {

    /** Default synthetic salt returned for tenants that have no persisted shard yet. */
    private static final byte[] EMPTY_SALT_32 = new byte[32];

    /**
     * Thread-local re-entry guard for {@link #updateShard}. A mutator invoking {@code updateShard}
     * (for any tenant — same or different) on the calling thread would acquire a second per-tenant
     * writer lock and expose the registry to AB/BA deadlocks across threads (F-R1.concurrency.2.4).
     * The guard fails-fast with {@link IllegalStateException} on any nested invocation on the same
     * thread; callers must not invoke the registry's write path from within a mutator.
     */
    private static final ThreadLocal<Boolean> UPDATE_IN_PROGRESS = ThreadLocal
            .withInitial(() -> Boolean.FALSE);

    private final ShardStorage storage;
    private final ConcurrentHashMap<TenantId, TenantEntry> tenants = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @throws NullPointerException if {@code storage} is null
     */
    public TenantShardRegistry(ShardStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
    }

    /**
     * Wait-free read of the latest snapshot for a tenant. Performs a lazy load from storage on
     * first access; subsequent calls read the cached volatile reference directly (R64).
     *
     * @throws NullPointerException if {@code tenantId} is null
     * @throws IllegalStateException if this registry has been {@link #close closed}
     * @throws IOException on initial-load failure
     */
    public KeyRegistryShard readSnapshot(TenantId tenantId) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        requireOpen();
        final TenantEntry entry = entry(tenantId);
        final KeyRegistryShard snap = entry.snapshot.get();
        if (snap == null) {
            // Raced a concurrent close() that nulled the cached snapshot between our
            // requireOpen() above and this read. Surface the close via the documented
            // IllegalStateException signal rather than returning null to the caller
            // (R62/R62a: close must be observable via IllegalStateException, never a null
            // return).
            requireOpen();
            throw new IllegalStateException("TenantShardRegistry is closed");
        }
        return snap;
    }

    /**
     * Serially update a tenant's shard. Acquires the per-tenant write lock, loads the current
     * snapshot, invokes {@code mutator}, persists the new shard atomically via
     * {@link ShardStorage#writeShard}, publishes the new snapshot via a volatile swap (R63, R64),
     * and returns the mutator-provided result.
     *
     * <p>
     * <b>Mutator re-entry is forbidden.</b> The mutator must not invoke {@code updateShard} (for
     * the same or any other tenant) — such re-entry acquires a second per-tenant writer lock and,
     * with two concurrent callers, would expose the registry to AB/BA deadlocks. A thread-local
     * guard detects nested invocation on the same thread and throws {@link IllegalStateException}
     * (F-R1.concurrency.2.4). Mutators may call {@link #readSnapshot} for other tenants — only the
     * write path is forbidden.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalStateException if this registry has been {@link #close closed}, or if a
     *             mutator re-enters {@code updateShard} on the same thread
     * @throws IOException on persistence failure (lock released before throw)
     */
    public <R> R updateShard(TenantId tenantId, Function<KeyRegistryShard, ShardUpdate<R>> mutator)
            throws IOException {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(mutator, "mutator must not be null");
        if (UPDATE_IN_PROGRESS.get()) {
            // A mutator on this thread is attempting to re-enter updateShard. Acquiring a
            // second per-tenant writer lock would expose the registry to AB/BA deadlocks
            // across threads (F-R1.concurrency.2.4). Fail fast with a descriptive
            // IllegalStateException — callers must restructure to hoist nested updates out
            // of the mutator.
            throw new IllegalStateException(
                    "TenantShardRegistry.updateShard must not be invoked re-entrantly "
                            + "from within a mutator (F-R1.concurrency.2.4)");
        }
        requireOpen();
        final TenantEntry entry = entry(tenantId);
        entry.writerLock.lock();
        UPDATE_IN_PROGRESS.set(Boolean.TRUE);
        try {
            requireOpen();
            final KeyRegistryShard current = entry.snapshot.get();
            final ShardUpdate<R> update = mutator.apply(current);
            Objects.requireNonNull(update, "mutator must not return null");
            // Enforce R82a per-tenant isolation at the registry boundary: the mutator must
            // return a shard whose internal tenantId matches the routing key. Without this
            // guard, a caller-supplied mutator could publish a wrong-tenant shard under the
            // routing key (F-R1.dispatch_routing.1.01). Storage also validates this, but the
            // registry is the authoritative enforcement point for the isolation contract.
            if (!update.newShard().tenantId().equals(tenantId)) {
                throw new IllegalStateException(
                        "mutator returned shard for wrong tenant: expected " + tenantId + ", got "
                                + update.newShard().tenantId() + " (F-R1.dispatch_routing.1.01)");
            }
            // Close may have executed while the mutator was running (close does not acquire
            // writerLock). Re-check before the durable write so a racing close cannot allow a
            // phantom post-close write to commit to disk (R62/R62a). The mutator's effects on
            // its own local state are discarded; no shard file is created/updated.
            requireOpen();
            storage.writeShard(tenantId, update.newShard());
            // Close may have CAS'd closed=false→true between our pre-writeShard
            // requireOpen() at line 140 and here — close() does not acquire writerLock
            // and runs concurrently with an in-flight writer that is inside writeShard's
            // fsync window. If so, unconditionally calling snapshot.set below would
            // re-publish a non-null snapshot on a TenantEntry that close()'s
            // getAndSet(null)/zeroizeSalt has already drained — I-CL violated
            // (F-R1.shared_state.2.1). Re-check closed here and throw
            // IllegalStateException rather than re-publishing. The on-disk shard
            // already reflects this update; the F-R1.concurrency.2.2 guard at line 140
            // only closed the pre-writeShard window and an orthogonal during-writeShard
            // fence would require close() to drain writers (incompatible with the
            // non-blocking close() contract used elsewhere in this registry).
            requireOpen();
            entry.snapshot.set(update.newShard());
            return update.result();
        } finally {
            UPDATE_IN_PROGRESS.set(Boolean.FALSE);
            entry.writerLock.unlock();
        }
    }

    /**
     * Release cached snapshots and zeroize any cached salt bytes (R69). Idempotent.
     *
     * <p>
     * Note: {@code KeyRegistryShard}'s internal salt array is a clone of the original constructor
     * input. After close, the snapshot reference is dropped and the JVM reclaims the internal array
     * — we cannot reach the internal array through the public API, so the zeroization here is
     * best-effort over the returned clone; the authoritative guarantee for salt hygiene is the
     * no-longer-reachable state.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (TenantEntry entry : tenants.values()) {
            final KeyRegistryShard snap = entry.snapshot.getAndSet(null);
            if (snap != null) {
                // Zero the authoritative internal salt array — KeyRegistryShard.hkdfSalt()
                // returns a defensive clone, so Arrays.fill on the accessor result would
                // scrub only a throwaway copy. zeroizeSalt() touches the real backing
                // array, satisfying R69's best-effort zeroization contract.
                snap.zeroizeSalt();
            }
        }
        tenants.clear();
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("TenantShardRegistry is closed");
        }
    }

    private TenantEntry entry(TenantId tenantId) throws IOException {
        TenantEntry existing = tenants.get(tenantId);
        if (existing != null) {
            return existing;
        }
        // Slow path: initialize once per tenant. Use computeIfAbsent with a wrapper that catches
        // IOException (computeIfAbsent cannot natively propagate checked exceptions).
        final IOException[] captured = new IOException[1];
        final TenantEntry created = tenants.computeIfAbsent(tenantId, tid -> {
            try {
                final Optional<KeyRegistryShard> loaded = storage.loadShard(tid);
                final KeyRegistryShard initial = loaded.orElseGet(() -> emptyShard(tid));
                return new TenantEntry(initial);
            } catch (IOException ex) {
                captured[0] = ex;
                return null;
            }
        });
        if (captured[0] != null) {
            throw captured[0];
        }
        assert created != null
                : "computeIfAbsent must produce an entry when no exception was captured";
        // Close may have completed while computeIfAbsent was running its lambda — close does
        // not acquire any per-tenant lock. If the registry is now closed, the TenantEntry we
        // just installed would be a phantom post-close entry whose salt bytes bypass R69
        // zeroise-on-close. Best-effort zeroise the just-installed salt and surface the close
        // via the documented IllegalStateException (R62/R62a).
        if (closed.get()) {
            final KeyRegistryShard orphan = created.snapshot.getAndSet(null);
            if (orphan != null) {
                // Zero the authoritative internal salt array (same rationale as close()
                // above — the public accessor clones, so a direct Arrays.fill on it is
                // a no-op on the real bytes).
                orphan.zeroizeSalt();
            }
            tenants.remove(tenantId, created);
            throw new IllegalStateException("TenantShardRegistry is closed");
        }
        return created;
    }

    private static KeyRegistryShard emptyShard(TenantId tenantId) {
        return new KeyRegistryShard(tenantId, Map.of(), Map.of(), (KekRef) null, EMPTY_SALT_32);
    }

    /** Per-tenant state: exclusive writer mutex + volatile snapshot for wait-free reads. */
    private static final class TenantEntry {
        final ReentrantLock writerLock = new ReentrantLock();
        final AtomicReference<KeyRegistryShard> snapshot;

        TenantEntry(KeyRegistryShard initial) {
            this.snapshot = new AtomicReference<>(initial);
        }
    }

    /**
     * Return value of an {@link #updateShard} mutator: the new shard to persist and a
     * caller-visible result.
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
