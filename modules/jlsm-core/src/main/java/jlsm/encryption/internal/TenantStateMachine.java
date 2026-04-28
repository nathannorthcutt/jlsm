package jlsm.encryption.internal;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import jlsm.encryption.KmsErrorClassifier.ErrorClass;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.encryption.TenantState;

/**
 * Per-tenant atomic state machine (R83a, R83c, R83c-1). Holds the canonical {@link TenantState} for
 * each tenant via an {@link AtomicReference} with a backing durable {@link TenantStateProgress}
 * file. On bootstrap, replays the durable record (R76b-2) so a process restart cannot lose a
 * previously-recorded transition.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R76b-2, R83a, R83c, R83c-1).
 *
 * @spec encryption.primitives-lifecycle R76b-2
 * @spec encryption.primitives-lifecycle R83a
 * @spec encryption.primitives-lifecycle R83c-1
 */
public final class TenantStateMachine {

    /**
     * Default permanent-class failure threshold per R76 (configurable; default N=5).
     */
    public static final int DEFAULT_GRACE_THRESHOLD = 5;

    /** Sentinel marker used by the dedup hook to indicate "no detection-epoch supplied". */
    private static final long NO_EPOCH = Long.MIN_VALUE;

    private final TenantStateProgress progress;
    private final int graceThreshold;

    /**
     * Per-tenant lazy-initialised state cell. The atomic reference holds the canonical
     * {@link TenantState}; {@link #recordPermanentFailure} performs the CAS on the cell's state
     * field via {@link AtomicReference#compareAndSet(Object, Object)} to guarantee R83c-1's
     * single-transitioning-read invariant.
     */
    private final ConcurrentHashMap<TenantId, TenantCell> cells = new ConcurrentHashMap<>();

    private TenantStateMachine(TenantStateProgress progress, int graceThreshold) {
        this.progress = Objects.requireNonNull(progress, "progress");
        if (graceThreshold <= 0) {
            throw new IllegalArgumentException(
                    "graceThreshold must be positive, got " + graceThreshold);
        }
        this.graceThreshold = graceThreshold;
    }

    /** Construct a state machine. On creation, no tenant state is loaded — load is lazy. */
    public static TenantStateMachine create(TenantStateProgress progress) {
        Objects.requireNonNull(progress, "progress");
        return new TenantStateMachine(progress, DEFAULT_GRACE_THRESHOLD);
    }

    /** Construct a state machine with a custom grace threshold (for tests / config). */
    public static TenantStateMachine create(TenantStateProgress progress, int graceThreshold) {
        Objects.requireNonNull(progress, "progress");
        return new TenantStateMachine(progress, graceThreshold);
    }

    /**
     * Return the current state for {@code tenantId}. Lazy: loads from durable progress on first
     * access; subsequent accesses are wait-free.
     *
     * @throws NullPointerException if {@code tenantId} is null
     * @throws IOException on durable-load failure
     */
    public TenantState currentState(TenantId tenantId) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        return cellFor(tenantId).state.get();
    }

    /**
     * Record a permanent failure for {@code tenantId} on {@code scope}. Returns the resulting
     * transition iff this call was the one that performed the transition (R83c-1: a permanent
     * failure observed by N concurrent readers must produce exactly one transition). Subsequent
     * concurrent calls return empty.
     *
     * @throws NullPointerException if any argument is null
     * @throws IOException on durable-commit failure
     */
    public Optional<StateTransition> recordPermanentFailure(TenantId tenantId, TableScope scope,
            ErrorClass cause) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(cause, "cause");
        return recordPermanentFailureInternal(tenantId, scope, cause, NO_EPOCH);
    }

    /**
     * Detection-epoch overload (R83a dedup hook). Multiple calls within the same {@code
     * detectionEpoch} for the same tenant coalesce into a single counter increment, preserving
     * R83a's "at most one increment per detection epoch" invariant. WU-5's {@code RevokedDekCache}
     * supplies the epoch identifier.
     *
     * @throws NullPointerException if any reference argument is null
     * @throws IOException on durable-commit failure
     */
    public Optional<StateTransition> recordPermanentFailure(TenantId tenantId, TableScope scope,
            ErrorClass cause, long detectionEpoch) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(cause, "cause");
        return recordPermanentFailureInternal(tenantId, scope, cause, detectionEpoch);
    }

    private Optional<StateTransition> recordPermanentFailureInternal(TenantId tenantId,
            TableScope scope, ErrorClass cause, long detectionEpoch) throws IOException {
        if (cause != ErrorClass.PERMANENT) {
            // R76a: only PERMANENT errors count toward N; TRANSIENT and UNCLASSIFIED do not
            // increment the state-machine counter directly. (UNCLASSIFIED is escalated by
            // UnclassifiedErrorEscalator before reaching here.)
            return Optional.empty();
        }

        final TenantCell cell = cellFor(tenantId);

        // R83a dedup: per-tenant in-process detection-epoch coalescing.
        if (detectionEpoch != NO_EPOCH) {
            final long prior = cell.lastDetectionEpoch.getAndSet(detectionEpoch);
            if (prior == detectionEpoch) {
                // Already counted this epoch — no-op.
                return Optional.empty();
            }
        }

        // R83a saturating long counter — never overflow into negatives.
        final long updatedCount = saturatingIncrement(cell.permanentFailureCount);
        assert updatedCount > 0 : "saturating counter must produce a positive value";

        // R83c-1 atomic CAS: only one observer wins the transition.
        final TenantState prior = cell.state.get();
        if (prior != TenantState.HEALTHY) {
            // Already past the transition — no fresh transition fires.
            return Optional.empty();
        }

        if (updatedCount < graceThreshold) {
            return Optional.empty();
        }

        // Race for the transition.
        if (!cell.state.compareAndSet(TenantState.HEALTHY, TenantState.GRACE_READ_ONLY)) {
            // Another thread won the CAS — they get the transitioning event.
            return Optional.empty();
        }

        final long seq = cell.eventSeq.incrementAndGet();
        final Instant now = Instant.now();
        final TenantStateProgress.StateRecord record = new TenantStateProgress.StateRecord(
                TenantState.GRACE_READ_ONLY, now, seq, seq, now);
        progress.commit(tenantId, record);

        return Optional.of(new StateTransition(tenantId, TenantState.HEALTHY,
                TenantState.GRACE_READ_ONLY, seq));
    }

    /**
     * Saturating counter increment: returns the new value bounded at {@link Long#MAX_VALUE} per
     * R83a. Implemented with a CAS loop that detects MAX_VALUE saturation before bumping.
     */
    private static long saturatingIncrement(java.util.concurrent.atomic.AtomicLong counter) {
        while (true) {
            final long current = counter.get();
            if (current == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            final long next = current + 1L;
            if (counter.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    private TenantCell cellFor(TenantId tenantId) {
        return cells.computeIfAbsent(tenantId, this::loadCell);
    }

    /**
     * Load the per-tenant state cell. Reads the durable record (R76b-2); falls back to FAILED on
     * CRC mismatch (R76b-1a) and to HEALTHY when no record is persisted.
     */
    private TenantCell loadCell(TenantId tenantId) {
        try {
            final Optional<TenantStateProgress.StateRecord> persisted = progress.read(tenantId);
            if (persisted.isPresent()) {
                final TenantStateProgress.StateRecord record = persisted.get();
                // R76b-2: in-process eventSeq starts from durable lastEmittedEventSeq + 1.
                return new TenantCell(record.state(), record.lastEmittedEventSeq());
            }
            return new TenantCell(TenantState.HEALTHY, 0L);
        } catch (IOException ioe) {
            // R76b-1a: CRC mismatch (or any read failure) initialises the affected tenant to a
            // conservative FAILED state, surfacing the integrity failure to operators.
            return new TenantCell(TenantState.FAILED, 0L);
        }
    }

    /** Internal per-tenant state cell. Mutable references — never publish externally. */
    private static final class TenantCell {
        private final AtomicReference<TenantState> state;
        private final java.util.concurrent.atomic.AtomicLong permanentFailureCount = new java.util.concurrent.atomic.AtomicLong(
                0L);
        private final java.util.concurrent.atomic.AtomicLong eventSeq;
        private final java.util.concurrent.atomic.AtomicLong lastDetectionEpoch = new java.util.concurrent.atomic.AtomicLong(
                NO_EPOCH);

        TenantCell(TenantState initialState, long initialEventSeq) {
            this.state = new AtomicReference<>(Objects.requireNonNull(initialState));
            this.eventSeq = new java.util.concurrent.atomic.AtomicLong(initialEventSeq);
        }
    }

    /** Record of a state transition observed via {@link #recordPermanentFailure}. */
    public record StateTransition(TenantId tenantId, TenantState fromState, TenantState toState,
            long eventSeq) {

        public StateTransition {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(fromState, "fromState");
            Objects.requireNonNull(toState, "toState");
            if (eventSeq < 0) {
                throw new IllegalArgumentException(
                        "eventSeq must be non-negative, got " + eventSeq);
            }
        }
    }

}
