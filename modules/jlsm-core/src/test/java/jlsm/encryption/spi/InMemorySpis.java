package jlsm.encryption.spi;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import jlsm.encryption.KekRef;
import jlsm.encryption.TenantId;

/**
 * In-memory test fakes for the encryption SPIs. Used by /feature-test fixtures to drive WD-03
 * lifecycle scenarios without wiring real compaction or WAL machinery.
 *
 * <p>
 * Two nested fakes:
 * <ul>
 * <li>{@link InMemoryCompactionInputRegistry} — backing map of (CompactionId → input set)</li>
 * <li>{@link InMemoryWalLivenessSource} — backing map of (TenantId, KekRef) → count</li>
 * </ul>
 *
 * <p>
 * Test-scope only. Both fakes are thread-safe.
 */
public final class InMemorySpis {

    private InMemorySpis() {
        throw new UnsupportedOperationException("test-helper class — do not instantiate");
    }

    /**
     * In-memory {@link CompactionInputRegistry} fake. Implements the R30c snapshot lock as a
     * {@link ReadWriteLock} where {@code registerInputs}/{@code deregisterInputs} are writers
     * (exclusive) and {@link #withSnapshotLock} is a shared reader. The "shared read lock that
     * prevents new registrations" of R30c is exactly the readLock of a RWL — registrations (the
     * write side) block while any reader holds the lock.
     */
    public static final class InMemoryCompactionInputRegistry implements CompactionInputRegistry {

        private final Map<CompactionId, Set<SSTableId>> inputs = new ConcurrentHashMap<>();
        /**
         * Snapshot lock per R30c. Registrations and deregistrations acquire the WRITE lock so they
         * wait until any in-flight snapshot reader (held via {@link #withSnapshotLock}) releases.
         * Snapshots acquire the READ lock so multiple snapshots may proceed concurrently but no
         * registration may slip in mid-snapshot.
         */
        private final ReadWriteLock snapshotLock = new ReentrantReadWriteLock();

        @Override
        public void registerInputs(CompactionId id, Set<SSTableId> inputSet) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(inputSet, "inputSet");
            // Defensive copy: callers may mutate their input set after the call returns; we
            // must hold our own immutable snapshot.
            final Set<SSTableId> copy = Set.copyOf(inputSet);
            snapshotLock.writeLock().lock();
            try {
                inputs.put(id, copy);
            } finally {
                snapshotLock.writeLock().unlock();
            }
        }

        @Override
        public void deregisterInputs(CompactionId id) {
            Objects.requireNonNull(id, "id");
            snapshotLock.writeLock().lock();
            try {
                inputs.remove(id);
            } finally {
                snapshotLock.writeLock().unlock();
            }
        }

        @Override
        public Set<SSTableId> currentInputSet() {
            // currentInputSet must reflect a coherent union snapshot — no mid-iteration
            // mutation. Acquire the read lock so concurrent registrations cannot interleave.
            snapshotLock.readLock().lock();
            try {
                final HashSet<SSTableId> union = new HashSet<>();
                for (Set<SSTableId> set : inputs.values()) {
                    union.addAll(set);
                }
                return Set.copyOf(union);
            } finally {
                snapshotLock.readLock().unlock();
            }
        }

        /**
         * Run {@code body} under the shared snapshot lock — registrations are blocked until the
         * body returns. This is the R30c primitive consumed by {@code DekPruner}; overrides the SPI
         * default (no-op) with real RWL-backed atomicity.
         *
         * @throws NullPointerException if {@code body} is null
         */
        @Override
        public <T> T withSnapshotLock(Supplier<T> body) {
            Objects.requireNonNull(body, "body");
            snapshotLock.readLock().lock();
            try {
                return body.get();
            } finally {
                snapshotLock.readLock().unlock();
            }
        }
    }

    /** In-memory {@link WalLivenessSource} fake. */
    public static final class InMemoryWalLivenessSource implements WalLivenessSource {

        private final Map<DependencyKey, Long> counts = new ConcurrentHashMap<>();

        @Override
        public long dependsOnKekRef(TenantId tenantId, KekRef kekRef) {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(kekRef, "kekRef");
            return counts.getOrDefault(new DependencyKey(tenantId, kekRef), 0L);
        }

        /** Test-only: set the count returned for a (tenant, kekRef) pair. */
        public void setCount(TenantId tenantId, KekRef kekRef, long count) {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(kekRef, "kekRef");
            counts.put(new DependencyKey(tenantId, kekRef), count);
        }

        private record DependencyKey(TenantId tenantId, KekRef kekRef) {

            DependencyKey {
                Objects.requireNonNull(tenantId, "tenantId");
                Objects.requireNonNull(kekRef, "kekRef");
            }
        }
    }
}
