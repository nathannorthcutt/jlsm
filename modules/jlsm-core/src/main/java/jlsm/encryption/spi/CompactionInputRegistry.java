package jlsm.encryption.spi;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * SPI: registry of SSTables currently being consumed by an in-flight compaction. The encryption
 * lifecycle layer consults this registry under the DEK-prune snapshot lock so a DEK whose only
 * remaining consumers are compaction inputs is not pruned mid-merge (R30c).
 *
 * <p>
 * The compaction module is expected to register its inputs at compaction start and deregister them
 * at compaction completion or abort. The snapshot lock held by
 * {@link jlsm.encryption.internal.DekPruner} prevents new registrations while the prune snapshot is
 * being taken (R30b).
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R30, R30a, R30b, R30c); ADR
 * encryption-three-tier-key-hierarchy.
 *
 * @spec encryption.primitives-lifecycle R30b
 * @spec encryption.primitives-lifecycle R30c
 */
public interface CompactionInputRegistry {

    /**
     * Register {@code inputs} as the consumed-input set of compaction {@code id}. Idempotent in
     * {@code id}: re-registering replaces the prior set.
     *
     * @throws NullPointerException if any argument is null
     */
    void registerInputs(CompactionId id, Set<SSTableId> inputs);

    /**
     * Deregister compaction {@code id}. No-op if {@code id} is not currently registered.
     *
     * @throws NullPointerException if {@code id} is null
     */
    void deregisterInputs(CompactionId id);

    /**
     * Snapshot the union of all currently-registered input sets. Returned set is immutable.
     */
    Set<SSTableId> currentInputSet();

    /**
     * Run {@code body} under a shared read lock that prevents concurrent
     * {@link #registerInputs}/{@link #deregisterInputs} from making progress until {@code body}
     * returns. Implementations that do not require atomic snapshot semantics may execute
     * {@code body} without locking; the {@link DekPruner} consults this primitive to honor the R30c
     * "manifest snapshot + compaction-input set must be read as a single atomic operation"
     * invariant.
     *
     * <p>
     * Default implementation is a pass-through (no locking) — suitable for impls whose snapshot
     * semantics are already atomic by construction. Production-grade impls override.
     *
     * @throws NullPointerException if {@code body} is null
     * @spec encryption.primitives-lifecycle R30c
     */
    default <T> T withSnapshotLock(Supplier<T> body) {
        Objects.requireNonNull(body, "body");
        return body.get();
    }

    /** Opaque compaction identifier. */
    record CompactionId(String value) {

        public CompactionId {
            Objects.requireNonNull(value, "value");
            if (value.isEmpty()) {
                throw new IllegalArgumentException("CompactionId value must not be empty");
            }
        }
    }

    /** Opaque SSTable identifier. */
    record SSTableId(String value) {

        public SSTableId {
            Objects.requireNonNull(value, "value");
            if (value.isEmpty()) {
                throw new IllegalArgumentException("SSTableId value must not be empty");
            }
        }
    }
}
