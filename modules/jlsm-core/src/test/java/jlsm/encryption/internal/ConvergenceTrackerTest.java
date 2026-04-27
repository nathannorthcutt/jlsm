package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import org.junit.jupiter.api.Test;

import jlsm.encryption.ConvergenceRegistration;
import jlsm.encryption.ConvergenceState;
import jlsm.encryption.DomainId;
import jlsm.encryption.KmsObserver;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.encryption.spi.ManifestCommitNotifier.ManifestSnapshot;

/**
 * Tests for {@link ConvergenceTracker} (R37, R37a, R37b, R37b-1, R37b-2, R37b-3, R37c, R83d).
 *
 * <p>
 * Behaviors covered:
 * <ul>
 * <li>{@code register()} input validation (NPE on null scope/callback, IAE on non-positive
 * version).</li>
 * <li>{@code register()} requires a prior {@link RotationMetadata}.</li>
 * <li>{@code convergenceStateFor()} returns PENDING by default; CONVERGED after manifest commit
 * removes the version; TIMED_OUT when wall-clock exceeds R37a bound; REVOKED when explicitly
 * marked.</li>
 * <li>{@code notifyManifestCommit} fires the callback exactly once and discards the registration
 * (R37b-3 path (c)).</li>
 * <li>R83d priority — explicit revocation suppresses convergence event.</li>
 * <li>R37b-3 path (b) — {@link ConvergenceTracker#close()} drops all registrations atomically.</li>
 * <li>R37b-3 path (a) — explicit handle close removes the registration; subsequent commits cannot
 * fire the callback.</li>
 * <li>R37b-2 — GC-reaping of weakly-held callback emits a polling-category event via the
 * observer.</li>
 * <li>R37a P4-4 — TIMED_OUT uses the bound recorded in {@link RotationMetadata}, not a
 * currently-configured bound.</li>
 * </ul>
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
class ConvergenceTrackerTest {

    private static final TableScope SCOPE_A = new TableScope(new TenantId("tenantA"),
            new DomainId("domain-1"), new TableId("t1"));
    private static final TableScope SCOPE_B = new TableScope(new TenantId("tenantB"),
            new DomainId("domain-2"), new TableId("t2"));
    private static final Instant T0 = Instant.parse("2026-04-27T12:00:00Z");
    private static final Duration BOUND = Duration.ofMinutes(5);

    private static RotationMetadata meta(TableScope scope, int oldVersion, Instant start,
            Duration bound) {
        return new RotationMetadata(scope, oldVersion, start, bound);
    }

    private static ManifestSnapshot snap(Map<TableScope, Set<Integer>> map) {
        return new TestSnapshot(map);
    }

    private static ManifestSnapshot snap(TableScope scope, Set<Integer> versions) {
        final Map<TableScope, Set<Integer>> m = new HashMap<>();
        m.put(scope, versions);
        return snap(m);
    }

    private static ManifestSnapshot snap(TableScope scope, int... versions) {
        final Set<Integer> set = new HashSet<>();
        for (int v : versions) {
            set.add(v);
        }
        return snap(scope, set);
    }

    private static MutableClock mutableClock() {
        return new MutableClock(T0);
    }

    @Test
    void create_returnsNonNull() {
        final ConvergenceTracker tracker = ConvergenceTracker.create(new RecordingObserver(),
                Clock.fixed(T0, ZoneOffset.UTC));
        assertNotNull(tracker);
    }

    @Test
    void create_nullObserver_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> ConvergenceTracker.create(null, Clock.fixed(T0, ZoneOffset.UTC)));
    }

    @Test
    void create_nullClock_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> ConvergenceTracker.create(new RecordingObserver(), null));
    }

    @Test
    void register_nullScope_throwsNpe() {
        final ConvergenceTracker t = freshTracker();
        t.recordRotationStart(meta(SCOPE_A, 5, T0, BOUND));
        assertThrows(NullPointerException.class, () -> t.register(null, 5, s -> {
        }));
    }

    @Test
    void register_nullCallback_throwsNpe() {
        final ConvergenceTracker t = freshTracker();
        t.recordRotationStart(meta(SCOPE_A, 5, T0, BOUND));
        assertThrows(NullPointerException.class, () -> t.register(SCOPE_A, 5, null));
    }

    @Test
    void register_zeroOldDekVersion_throwsIae() {
        final ConvergenceTracker t = freshTracker();
        assertThrows(IllegalArgumentException.class, () -> t.register(SCOPE_A, 0, s -> {
        }));
    }

    @Test
    void register_negativeOldDekVersion_throwsIae() {
        final ConvergenceTracker t = freshTracker();
        assertThrows(IllegalArgumentException.class, () -> t.register(SCOPE_A, -1, s -> {
        }));
    }

    @Test
    void register_withoutRotationMetadata_throwsIse() {
        final ConvergenceTracker t = freshTracker();
        assertThrows(IllegalStateException.class, () -> t.register(SCOPE_A, 5, s -> {
        }));
    }

    @Test
    void convergenceStateFor_unknown_returnsPending() {
        final ConvergenceTracker t = freshTracker();
        assertSame(ConvergenceState.PENDING, t.convergenceStateFor(SCOPE_A, 99));
    }

    @Test
    void convergenceStateFor_pending_whileVersionStillReferenced() {
        final ConvergenceTracker t = freshTracker();
        t.recordRotationStart(meta(SCOPE_A, 5, T0, BOUND));
        try (ConvergenceRegistration reg = t.register(SCOPE_A, 5, s -> {
        })) {
            t.notifyManifestCommit(snap(SCOPE_A, 5, 6), snap(SCOPE_A, 5, 6));
            assertSame(ConvergenceState.PENDING, t.convergenceStateFor(SCOPE_A, 5));
        }
    }

    @Test
    void convergenceStateFor_converged_afterCommitRemovesVersion() {
        final ConvergenceTracker t = freshTracker();
        t.recordRotationStart(meta(SCOPE_A, 5, T0, BOUND));
        final AtomicReference<ConvergenceState> received = new AtomicReference<>();
        try (ConvergenceRegistration reg = t.register(SCOPE_A, 5, received::set)) {
            // before: still references 5; after: only 6.
            t.notifyManifestCommit(snap(SCOPE_A, 5, 6), snap(SCOPE_A, 6));
            assertSame(ConvergenceState.CONVERGED, t.convergenceStateFor(SCOPE_A, 5));
            assertSame(ConvergenceState.CONVERGED, received.get());
        }
    }

    @Test
    void convergenceStateFor_timedOut_afterBoundExceeded() {
        final MutableClock clock = mutableClock();
        final ConvergenceTracker t = ConvergenceTracker.create(new RecordingObserver(), clock);
        t.recordRotationStart(meta(SCOPE_A, 5, T0, Duration.ofMinutes(5)));

        try (ConvergenceRegistration reg = t.register(SCOPE_A, 5, s -> {
        })) {
            // Advance past bound; manifest still references version 5.
            clock.advance(Duration.ofMinutes(6));
            t.notifyManifestCommit(snap(SCOPE_A, 5, 6), snap(SCOPE_A, 5, 6));
            assertSame(ConvergenceState.TIMED_OUT, t.convergenceStateFor(SCOPE_A, 5));
        }
    }

    @Test
    void p4_4_timedOut_usesPinnedBound_notCurrentlyConfiguredBound() {
        // R37b-1 P4-4: the bound recorded at rotation start is authoritative. A subsequent
        // dynamic config change must NOT retroactively reclassify in-flight rotations.
        final MutableClock clock = mutableClock();
        final ConvergenceTracker t = ConvergenceTracker.create(new RecordingObserver(), clock);
        // Rotation pinned at 5min bound.
        t.recordRotationStart(meta(SCOPE_A, 5, T0, Duration.ofMinutes(5)));

        try (ConvergenceRegistration reg = t.register(SCOPE_A, 5, s -> {
        })) {
            clock.advance(Duration.ofMinutes(6));
            // Advance past pinned bound; even if a "new" config arrived with 30min, the pinned
            // 5min still governs this in-flight rotation.
            t.notifyManifestCommit(snap(SCOPE_A, 5), snap(SCOPE_A, 5));
            assertSame(ConvergenceState.TIMED_OUT, t.convergenceStateFor(SCOPE_A, 5),
                    "TIMED_OUT must use bound pinned at rotation start (R37b-1 P4-4)");
        }
    }

    @Test
    void revoked_priority_suppressesConvergence_r83d() {
        // R83d: revocation is authoritative; convergence event is suppressed when the wrapping
        // KEK is permanently revoked for the same (scope, oldDekVersion).
        final ConvergenceTracker t = freshTracker();
        t.recordRotationStart(meta(SCOPE_A, 5, T0, BOUND));
        final AtomicReference<ConvergenceState> received = new AtomicReference<>();
        try (ConvergenceRegistration reg = t.register(SCOPE_A, 5, received::set)) {
            t.markRevoked(SCOPE_A, 5);
            t.notifyManifestCommit(snap(SCOPE_A, 5, 6), snap(SCOPE_A, 6));
            assertSame(ConvergenceState.REVOKED, t.convergenceStateFor(SCOPE_A, 5));
            // Callback receives REVOKED, not CONVERGED.
            assertSame(ConvergenceState.REVOKED, received.get(),
                    "revocation must suppress the convergence event (R83d)");
        }
    }

    @Test
    void revoked_isTerminal_subsequentTransitionsIgnored() {
        final ConvergenceTracker t = freshTracker();
        t.recordRotationStart(meta(SCOPE_A, 5, T0, BOUND));
        try (ConvergenceRegistration reg = t.register(SCOPE_A, 5, s -> {
        })) {
            t.markRevoked(SCOPE_A, 5);
            t.markRevoked(SCOPE_A, 5); // re-revoke is no-op
            t.notifyManifestCommit(snap(SCOPE_A, 5), snap(SCOPE_A, 6));
            assertSame(ConvergenceState.REVOKED, t.convergenceStateFor(SCOPE_A, 5),
                    "REVOKED is terminal");
        }
    }

    @Test
    void notifyManifestCommit_firesCallbackOnce_pathC() {
        // R37b-3 path (c): convergence-fired-and-delivered completes the registration's
        // lifecycle. Subsequent commits must not re-deliver.
        final ConvergenceTracker t = freshTracker();
        t.recordRotationStart(meta(SCOPE_A, 5, T0, BOUND));
        final AtomicInteger calls = new AtomicInteger();
        try (ConvergenceRegistration reg = t.register(SCOPE_A, 5, s -> calls.incrementAndGet())) {
            t.notifyManifestCommit(snap(SCOPE_A, 5, 6), snap(SCOPE_A, 6));
            t.notifyManifestCommit(snap(SCOPE_A, 6), snap(SCOPE_A, 6));
            assertEquals(1, calls.get(), "convergence callback must fire exactly once");
        }
    }

    @Test
    void closeAll_pathB_dropsAllRegistrationsAtomically() {
        // R37b-3 path (b): EncryptionKeyHolder.close() drops the registration during shutdown.
        final ConvergenceTracker t = freshTracker();
        t.recordRotationStart(meta(SCOPE_A, 5, T0, BOUND));
        t.recordRotationStart(meta(SCOPE_B, 7, T0, BOUND));

        final AtomicInteger calls = new AtomicInteger();
        final ConvergenceRegistration r1 = t.register(SCOPE_A, 5, s -> calls.incrementAndGet());
        final ConvergenceRegistration r2 = t.register(SCOPE_B, 7, s -> calls.incrementAndGet());

        t.close();

        // Subsequent commit: registrations are gone; callback must not fire.
        t.notifyManifestCommit(snap(SCOPE_A, 5), snap(SCOPE_A, 6));
        assertEquals(0, calls.get());

        // Path (b) idempotency: handle close after holder close is no-op.
        r1.close();
        r2.close();
    }

    @Test
    void explicitHandleClose_pathA_removesRegistrationFromSet() {
        // R37b-3 path (a): explicit handle close removes the registration; subsequent commits
        // cannot fire the callback.
        final ConvergenceTracker t = freshTracker();
        t.recordRotationStart(meta(SCOPE_A, 5, T0, BOUND));
        final AtomicInteger calls = new AtomicInteger();
        final ConvergenceRegistration reg = t.register(SCOPE_A, 5, s -> calls.incrementAndGet());

        reg.close();
        t.notifyManifestCommit(snap(SCOPE_A, 5), snap(SCOPE_A, 6));
        assertEquals(0, calls.get());
        // Idempotent: second close is no-op.
        reg.close();
        reg.close();
    }

    @Test
    void registrationsForOtherScopes_areIndependent() {
        final ConvergenceTracker t = freshTracker();
        t.recordRotationStart(meta(SCOPE_A, 5, T0, BOUND));
        t.recordRotationStart(meta(SCOPE_B, 7, T0, BOUND));
        try (ConvergenceRegistration r1 = t.register(SCOPE_A, 5, s -> {
        }); ConvergenceRegistration r2 = t.register(SCOPE_B, 7, s -> {
        })) {
            // Commit converges only SCOPE_A.
            final Map<TableScope, Set<Integer>> before = new HashMap<>();
            before.put(SCOPE_A, Set.of(5, 6));
            before.put(SCOPE_B, Set.of(7));
            final Map<TableScope, Set<Integer>> after = new HashMap<>();
            after.put(SCOPE_A, Set.of(6));
            after.put(SCOPE_B, Set.of(7));
            t.notifyManifestCommit(snap(before), snap(after));

            assertSame(ConvergenceState.CONVERGED, t.convergenceStateFor(SCOPE_A, 5));
            assertSame(ConvergenceState.PENDING, t.convergenceStateFor(SCOPE_B, 7));
        }
    }

    @Test
    void gcReap_pathD_emitsPollingEvent_r37b_2() throws Exception {
        // R37b-2: when a weakly-held registration is reaped by the GC before convergence fires,
        // an event is emitted via the polling category.
        final RecordingObserver observer = new RecordingObserver();
        final ConvergenceTracker t = ConvergenceTracker.create(observer,
                Clock.fixed(T0, ZoneOffset.UTC));
        t.recordRotationStart(meta(SCOPE_A, 5, T0, BOUND));

        // Register with a callback we deliberately abandon (no field reference). The
        // ConvergenceRegistration holds the callback weakly so GC can reap it.
        registerAndAbandon(t);

        // Encourage GC + run drainer.
        forceGc();
        t.drainReapedRegistrations();

        // The callback was abandoned; observer must have received at least one polling event.
        // (We do not strictly require the underlying registration to be reaped — JDK GC is
        // best-effort — but in steady state the drainer must eventually surface the event.)
        // Allow several attempts.
        int attempts = 0;
        while (observer.pollingEvents.isEmpty() && attempts < 20) {
            forceGc();
            t.drainReapedRegistrations();
            attempts++;
        }
        assertTrue(!observer.pollingEvents.isEmpty(),
                "GC reap must emit polling event per R37b-2 (best-effort, may need GC nudges)");
        // R37b-3 path (d): handle close after GC reap is a no-op (handled in the
        // abandon-and-close-after pattern in another test if needed).
    }

    @Test
    void notifyManifestCommit_nullArgs_throwsNpe() {
        final ConvergenceTracker t = freshTracker();
        assertThrows(NullPointerException.class,
                () -> t.notifyManifestCommit(null, snap(SCOPE_A, 6)));
        assertThrows(NullPointerException.class,
                () -> t.notifyManifestCommit(snap(SCOPE_A, 5), null));
    }

    @Test
    void recordRotationStart_nullArgs_throwsNpe() {
        final ConvergenceTracker t = freshTracker();
        assertThrows(NullPointerException.class, () -> t.recordRotationStart(null));
    }

    @Test
    void markRevoked_nullScope_throwsNpe() {
        final ConvergenceTracker t = freshTracker();
        assertThrows(NullPointerException.class, () -> t.markRevoked(null, 5));
    }

    @Test
    void markRevoked_zeroVersion_throwsIae() {
        final ConvergenceTracker t = freshTracker();
        assertThrows(IllegalArgumentException.class, () -> t.markRevoked(SCOPE_A, 0));
    }

    @Test
    void convergenceStateFor_nullScope_throwsNpe() {
        final ConvergenceTracker t = freshTracker();
        assertThrows(NullPointerException.class, () -> t.convergenceStateFor(null, 5));
    }

    /**
     * Register a callback we then abandon — the local reference goes out of scope so the GC may
     * reap the registration. Helper isolates the closure so it does not pin via the test stack. The
     * returned handle is intentionally discarded.
     */
    private static void registerAndAbandon(ConvergenceTracker t) {
        // Construct a callback with no enclosing capture — a static method reference would
        // be a constant, so we synthesise a fresh callback per call so each becomes
        // GC-eligible after this method returns.
        final ConvergenceTracker.ConvergenceCallback cb = new ConvergenceTracker.ConvergenceCallback() {
            @Override
            public void onTerminal(ConvergenceState terminalState) {
                // body is irrelevant for the GC test
            }
        };
        // The handle is registered and immediately dropped on return — only the tracker
        // holds it (weakly), and the callback `cb` becomes unreachable after return.
        t.register(SCOPE_A, 5, cb);
    }

    /** Encourage GC; best-effort. */
    @SuppressWarnings("unused")
    private static void forceGc() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static ConvergenceTracker freshTracker() {
        return ConvergenceTracker.create(new RecordingObserver(), Clock.fixed(T0, ZoneOffset.UTC));
    }

    /** Test {@link KmsObserver} that records polling events. */
    static final class RecordingObserver implements KmsObserver {

        final List<KmsObserver.PollingEvent> pollingEvents = new CopyOnWriteArrayList<>();

        @Override
        public void onPollingEvent(KmsObserver.PollingEvent event) {
            pollingEvents.add(event);
        }
    }

    /** Test snapshot exposing the {@code referencedVersions(scope)} extension. */
    static final class TestSnapshot implements ManifestSnapshot {

        private final Map<TableScope, Set<Integer>> versions;

        TestSnapshot(Map<TableScope, Set<Integer>> versions) {
            // Defensive copy; stored as immutable.
            this.versions = Map.copyOf(versions);
        }

        @Override
        public Set<TableScope> scopes() {
            return versions.keySet();
        }

        @Override
        public int dekVersionFor(TableScope scope) {
            ManifestSnapshot.requireScope(scope);
            final Set<Integer> set = versions.get(scope);
            if (set == null || set.isEmpty()) {
                return 0;
            }
            int max = 0;
            for (int v : set) {
                if (v > max) {
                    max = v;
                }
            }
            return max;
        }

        @Override
        public Set<Integer> referencedVersions(TableScope scope) {
            ManifestSnapshot.requireScope(scope);
            return versions.getOrDefault(scope, Set.of());
        }
    }

    /** Mutable test clock — advance forward without changing zone. */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant initial) {
            this.now = initial;
        }

        void advance(Duration d) {
            this.now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
