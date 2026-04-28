package jlsm.encryption.spi;

import java.util.Objects;
import java.util.Set;

import jlsm.encryption.TableScope;

/**
 * SPI: the manifest layer publishes commits to subscribers (R37c). The encryption convergence
 * machinery subscribes to drive {@link jlsm.encryption.ConvergenceState} transitions. The manifest
 * module is expected to provide a concrete implementation; the encryption module treats this as
 * opaque.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R37c).
 *
 * @spec encryption.primitives-lifecycle R37c
 */
public interface ManifestCommitNotifier {

    /**
     * Subscribe {@code listener} to commit notifications. Returns a subscription handle; the caller
     * must close it (idempotent) when no longer interested.
     *
     * @throws NullPointerException if {@code listener} is null
     */
    Subscription subscribe(ManifestCommitListener listener);

    /** Listener invoked on each commit. */
    @FunctionalInterface
    interface ManifestCommitListener {

        /**
         * Invoked synchronously after each manifest commit with the before/after snapshots.
         *
         * @param before snapshot prior to the commit
         * @param after snapshot after the commit
         */
        void onCommit(ManifestSnapshot before, ManifestSnapshot after);
    }

    /** Subscription handle. */
    interface Subscription extends AutoCloseable {

        @Override
        void close();
    }

    /**
     * Read-only snapshot of the manifest at a given point in time. Implementations are expected to
     * return immutable views.
     */
    interface ManifestSnapshot {

        /** Set of scopes observable in this snapshot. */
        Set<TableScope> scopes();

        /** Highest DEK version known for {@code scope} in this snapshot, or zero if unknown. */
        int dekVersionFor(TableScope scope);

        /**
         * Set of DEK versions referenced by live SSTables for {@code scope} in this snapshot. The
         * default implementation returns the singleton of {@link #dekVersionFor(TableScope)} when
         * non-zero (assuming the snapshot does not distinguish multiple referenced versions);
         * implementations that track per-SSTable DEK references should override to surface the full
         * set so {@link jlsm.encryption.internal.ConvergenceTracker} can detect "no live SSTable
         * references this version" precisely (R37, R37c).
         *
         * @return immutable set of referenced versions; empty if {@code scope} is not present
         * @throws NullPointerException if {@code scope} is null
         *
         * @spec encryption.primitives-lifecycle R37
         * @spec encryption.primitives-lifecycle R37c
         */
        default Set<Integer> referencedVersions(TableScope scope) {
            requireScope(scope);
            final int v = dekVersionFor(scope);
            return v == 0 ? Set.of() : Set.of(v);
        }

        /** Convenience null-check. */
        static void requireScope(TableScope scope) {
            Objects.requireNonNull(scope, "scope");
        }
    }
}
