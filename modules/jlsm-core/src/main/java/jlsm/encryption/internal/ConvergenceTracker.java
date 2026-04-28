package jlsm.encryption.internal;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import jlsm.encryption.ConvergenceRegistration;
import jlsm.encryption.ConvergenceState;
import jlsm.encryption.EventCategory;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsObserver;
import jlsm.encryption.TableScope;
import jlsm.encryption.spi.ManifestCommitNotifier.ManifestSnapshot;

/**
 * Per-{@code (scope, oldDekVersion)} convergence tracker. Holds {@link WeakReference}-based
 * callback registrations (R37b) so abandoned callers do not leak. Subscribes (via
 * {@link #notifyManifestCommit}) to manifest commits and drives state transitions.
 *
 * <p>
 * Priority: revocation suppresses convergence (R83d) — a {@code REVOKED} transition fires even if
 * the underlying convergence condition was concurrently observable.
 *
 * <p>
 * Threading: callers may invoke from multiple threads; the tracker is internally thread-safe.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R37, R37a, R37b, R37b-1, R37b-2,
 * R37b-3, R37c, R83d).
 *
 * @spec encryption.primitives-lifecycle R37
 * @spec encryption.primitives-lifecycle R37a
 * @spec encryption.primitives-lifecycle R37b
 * @spec encryption.primitives-lifecycle R37b-1
 * @spec encryption.primitives-lifecycle R37b-2
 * @spec encryption.primitives-lifecycle R37b-3
 * @spec encryption.primitives-lifecycle R37c
 * @spec encryption.primitives-lifecycle R83d
 */
public final class ConvergenceTracker implements AutoCloseable {

    /**
     * Sentinel KekRef used in the polling event payload of R37b-2 for the
     * {@code convergenceRegistrationDropped} event. The event semantically belongs to a (scope,
     * oldDekVersion) tuple; we synthesise a stable KekRef so the event payload is non-null per the
     * {@link KmsObserver.PollingEvent} contract.
     */
    private static final KekRef DROPPED_REG_KEKREF = new KekRef("convergence-registration-dropped");

    private final KmsObserver observer;
    private final Clock clock;
    private final Map<TrackerKey, TrackerState> states = new ConcurrentHashMap<>();
    private final ReferenceQueue<Object> reapedQueue = new ReferenceQueue<>();
    private final AtomicLong eventSeq = new AtomicLong();
    private volatile boolean closed;

    private ConvergenceTracker(KmsObserver observer, Clock clock) {
        this.observer = observer;
        this.clock = clock;
    }

    /**
     * Construct a tracker.
     *
     * @throws NullPointerException if any argument is null
     */
    public static ConvergenceTracker create(KmsObserver observer, Clock clock) {
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(clock, "clock");
        return new ConvergenceTracker(observer, clock);
    }

    /**
     * Record the start of a rotation. Must be called before any
     * {@link #register(TableScope, int, ConvergenceCallback)} for the same key tuple. The metadata
     * is consulted for the R37a bound (R37b-1 P4-4: pinned at rotation start).
     *
     * @throws NullPointerException if {@code metadata} is null
     */
    public void recordRotationStart(RotationMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        final TrackerKey key = new TrackerKey(metadata.scope(), metadata.oldDekVersion());
        states.compute(key, (k, existing) -> {
            if (existing == null) {
                return new TrackerState(metadata);
            }
            // Idempotent: existing pinned metadata is preserved (R37b-1 P4-4).
            return existing;
        });
    }

    /**
     * Register a callback for {@code (scope, oldDekVersion)}. Returns a registration handle the
     * caller may close; the callback is held weakly so a forgotten handle does not leak.
     *
     * @throws NullPointerException if {@code scope} or {@code callback} is null
     * @throws IllegalArgumentException if {@code oldDekVersion <= 0}
     * @throws IllegalStateException if no {@link RotationMetadata} has been recorded for the key
     *
     * @spec encryption.primitives-lifecycle R37b
     */
    public ConvergenceRegistration register(TableScope scope, int oldDekVersion,
            ConvergenceCallback callback) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(callback, "callback");
        if (oldDekVersion <= 0) {
            throw new IllegalArgumentException(
                    "oldDekVersion must be positive, got " + oldDekVersion);
        }
        final TrackerKey key = new TrackerKey(scope, oldDekVersion);
        final TrackerState state = states.get(key);
        if (state == null) {
            throw new IllegalStateException(
                    "no rotation recorded for scope/oldDekVersion; call recordRotationStart first");
        }
        final RegistrationImpl reg = new RegistrationImpl(this, key, callback, reapedQueue);
        state.add(reg);
        return reg;
    }

    /**
     * Return the current convergence state for {@code (scope, oldDekVersion)}.
     *
     * @throws NullPointerException if {@code scope} is null
     * @throws IllegalArgumentException if {@code oldDekVersion <= 0}
     *
     * @spec encryption.primitives-lifecycle R37b-1
     */
    public ConvergenceState convergenceStateFor(TableScope scope, int oldDekVersion) {
        Objects.requireNonNull(scope, "scope");
        if (oldDekVersion <= 0) {
            throw new IllegalArgumentException(
                    "oldDekVersion must be positive, got " + oldDekVersion);
        }
        final TrackerState state = states.get(new TrackerKey(scope, oldDekVersion));
        if (state == null) {
            return ConvergenceState.PENDING;
        }
        return state.derive(clock.instant());
    }

    /**
     * Hook invoked by the manifest-commit notifier subscription (R37c). Walks active states and
     * fires CONVERGED transitions where applicable.
     *
     * @throws NullPointerException if any argument is null
     *
     * @spec encryption.primitives-lifecycle R37c
     */
    public void notifyManifestCommit(ManifestSnapshot before, ManifestSnapshot after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (closed) {
            return;
        }
        final Instant now = clock.instant();
        for (Map.Entry<TrackerKey, TrackerState> e : states.entrySet()) {
            final TrackerKey key = e.getKey();
            final TrackerState state = e.getValue();
            final Set<Integer> referenced = after.referencedVersions(key.scope());
            if (referenced.contains(key.oldDekVersion())) {
                state.refreshTimedOut(now);
                continue;
            }
            // Not referenced — convergence-eligible. R83d priority is enforced inside the
            // state's tryConverge.
            state.tryConverge();
        }
    }

    /**
     * Mark the {@code (scope, oldDekVersion)} tuple as revoked (R83d). Idempotent — a registered
     * callback receives {@link ConvergenceState#REVOKED} once.
     *
     * @throws NullPointerException if {@code scope} is null
     * @throws IllegalArgumentException if {@code oldDekVersion <= 0}
     *
     * @spec encryption.primitives-lifecycle R83d
     */
    public void markRevoked(TableScope scope, int oldDekVersion) {
        Objects.requireNonNull(scope, "scope");
        if (oldDekVersion <= 0) {
            throw new IllegalArgumentException(
                    "oldDekVersion must be positive, got " + oldDekVersion);
        }
        final TrackerKey key = new TrackerKey(scope, oldDekVersion);
        states.compute(key, (k, existing) -> {
            final TrackerState s = existing != null ? existing : new TrackerState(null);
            s.revoke();
            return s;
        });
    }

    /**
     * Drain GC-reaped registrations and emit {@code convergenceRegistrationDropped} polling events
     * (R37b-2). Bounded — drains at most all currently-enqueued references.
     */
    public void drainReapedRegistrations() {
        if (closed) {
            return;
        }
        for (;;) {
            final java.lang.ref.Reference<?> ref = reapedQueue.poll();
            if (ref == null) {
                return;
            }
            if (ref instanceof RegistrationImpl reg) {
                final TrackerState state = states.get(reg.key);
                if (state != null) {
                    final boolean removed = state.remove(reg);
                    if (removed && !reg.isClosed()) {
                        reg.markClosed();
                        emitDroppedEvent(reg.key);
                    }
                }
            }
        }
    }

    /**
     * Holder-close path (R37b-3 path (b)): drop all registrations atomically. Idempotent —
     * subsequent calls are no-ops.
     *
     * @spec encryption.primitives-lifecycle R37b-3
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (TrackerState state : states.values()) {
            state.closeAll();
        }
    }

    /** Internal: a registration has been explicitly closed; remove from the per-key set. */
    private void onExplicitClose(RegistrationImpl reg) {
        final TrackerState state = states.get(reg.key);
        if (state != null) {
            state.remove(reg);
        }
    }

    private void emitDroppedEvent(TrackerKey key) {
        final KmsObserver.EventEnvelope env = new KmsObserver.EventEnvelope(
                eventSeq.incrementAndGet(), EventCategory.POLLING, false, clock.instant(),
                key.scope().tenantId(), "conv-drop-" + key.oldDekVersion());
        final KmsObserver.PollingEvent event = new KmsObserver.PollingEvent(env, DROPPED_REG_KEKREF,
                "convergenceRegistrationDropped");
        observer.onPollingEvent(event);
    }

    // ---------------------------------------------------------------------
    // Inner types
    // ---------------------------------------------------------------------

    /** Convergence-callback functional interface. */
    @FunctionalInterface
    public interface ConvergenceCallback {

        /** Invoked once when the registration reaches a terminal state. */
        void onTerminal(ConvergenceState terminalState);
    }

    /** Composite key — (scope, oldDekVersion). */
    private record TrackerKey(TableScope scope, int oldDekVersion) {

        TrackerKey {
            Objects.requireNonNull(scope, "scope");
        }
    }

    /**
     * Per-key state. Holds the rotation metadata (may be null in the rare case of
     * {@link #revoke()}-without-registration), the current convergence state, and the active
     * registrations.
     */
    private static final class TrackerState {

        private final RotationMetadata metadata; // may be null
        private final AtomicReference<ConvergenceState> state = new AtomicReference<>(
                ConvergenceState.PENDING);
        private final List<RegistrationImpl> registrations = new ArrayList<>();
        private final Object lock = new Object();

        TrackerState(RotationMetadata metadata) {
            this.metadata = metadata;
        }

        void add(RegistrationImpl reg) {
            assert reg != null : "reg must be non-null";
            synchronized (lock) {
                registrations.add(reg);
            }
        }

        boolean remove(RegistrationImpl reg) {
            synchronized (lock) {
                return registrations.remove(reg);
            }
        }

        ConvergenceState derive(Instant now) {
            final ConvergenceState s = state.get();
            // Time-based PENDING → TIMED_OUT promotion is observable via convergenceStateFor
            // even if no manifest commit has been notified.
            if (s == ConvergenceState.PENDING && metadata != null) {
                final Instant deadline = metadata.startedAt().plus(metadata.r37aBoundAtStart());
                if (!now.isBefore(deadline)) {
                    return ConvergenceState.TIMED_OUT;
                }
            }
            return s;
        }

        void refreshTimedOut(Instant now) {
            if (metadata == null) {
                return;
            }
            final Instant deadline = metadata.startedAt().plus(metadata.r37aBoundAtStart());
            if (now.isBefore(deadline)) {
                return;
            }
            // CAS PENDING → TIMED_OUT. Do not fire a callback — TIMED_OUT means rotation is
            // still pending convergence; callbacks fire only on a terminal CONVERGED or
            // REVOKED. Consumers consult convergenceStateFor for TIMED_OUT.
            state.compareAndSet(ConvergenceState.PENDING, ConvergenceState.TIMED_OUT);
        }

        void tryConverge() {
            // R83d priority: never demote from REVOKED.
            for (;;) {
                final ConvergenceState prev = state.get();
                if (prev == ConvergenceState.REVOKED) {
                    return;
                }
                if (prev == ConvergenceState.CONVERGED) {
                    return;
                }
                // Promote PENDING or TIMED_OUT → CONVERGED. Once the version is no longer
                // referenced, the index rebuild target is recoverable, even if the wall-clock
                // bound was previously exceeded.
                if (state.compareAndSet(prev, ConvergenceState.CONVERGED)) {
                    deliverAndDiscard(ConvergenceState.CONVERGED);
                    return;
                }
            }
        }

        void revoke() {
            // REVOKED is terminal; one-shot delivery.
            final ConvergenceState prev = state.getAndSet(ConvergenceState.REVOKED);
            if (prev != ConvergenceState.REVOKED) {
                deliverAndDiscard(ConvergenceState.REVOKED);
            }
        }

        void closeAll() {
            // Holder-close (R37b-3 path (b)): drop all registrations without firing the
            // callback. Subsequent handle.close() must be a no-op (the registration is gone).
            final List<RegistrationImpl> snapshot;
            synchronized (lock) {
                snapshot = new ArrayList<>(registrations);
                registrations.clear();
            }
            for (RegistrationImpl reg : snapshot) {
                reg.markClosed();
            }
        }

        private void deliverAndDiscard(ConvergenceState terminal) {
            final List<RegistrationImpl> snapshot;
            synchronized (lock) {
                snapshot = new ArrayList<>(registrations);
                registrations.clear();
            }
            for (RegistrationImpl reg : snapshot) {
                reg.deliver(terminal);
            }
        }
    }

    /**
     * Registration implementation. Extends {@link WeakReference} over the callback so the GC may
     * reap the registration when the callback closure is unreferenced (R37b). The {@link #key} and
     * {@link #closed} fields are strongly held so the drainer can identify the (scope, version)
     * tuple even after the referent is cleared. There is intentionally no strong field referencing
     * the callback — the only strong path to it is through the caller (via the returned
     * ConvergenceRegistration handle) so a caller-abandoned callback becomes eligible for GC.
     */
    private static final class RegistrationImpl extends WeakReference<ConvergenceCallback>
            implements ConvergenceRegistration {

        private final ConvergenceTracker owner;
        private final TrackerKey key;
        private volatile boolean closed;

        RegistrationImpl(ConvergenceTracker owner, TrackerKey key, ConvergenceCallback callback,
                ReferenceQueue<Object> queue) {
            // WeakReference referent is the callback — no other strong field holds it.
            super(callback, queue);
            this.owner = owner;
            this.key = key;
        }

        @Override
        public ConvergenceState currentState() {
            return owner.convergenceStateFor(key.scope(), key.oldDekVersion());
        }

        @Override
        public void close() {
            // R37b-3 path (a): explicit close is idempotent.
            if (closed) {
                return;
            }
            closed = true;
            owner.onExplicitClose(this);
        }

        boolean isClosed() {
            return closed;
        }

        void markClosed() {
            closed = true;
        }

        void deliver(ConvergenceState terminal) {
            if (closed) {
                return;
            }
            closed = true;
            // get() returns null if the GC has already reaped the callback. In that case,
            // delivery is moot — the consumer has gone away (path (d)) and the drainer will
            // emit the convergenceRegistrationDropped event instead.
            final ConvergenceCallback cb = get();
            if (cb == null) {
                return;
            }
            try {
                cb.onTerminal(terminal);
            } catch (RuntimeException ignored) {
                // Components must not crash on caller bugs; suppress so tracker invariants
                // hold. (See coding guidelines: graceful error handling.)
            }
        }
    }
}
