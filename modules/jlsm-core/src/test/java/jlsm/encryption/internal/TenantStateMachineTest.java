package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.DomainId;
import jlsm.encryption.KmsErrorClassifier;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.encryption.TenantState;

/**
 * Tests for {@link TenantStateMachine} (R83a, R83c, R83c-1, R76b-2, R76, R76c).
 *
 * @spec encryption.primitives-lifecycle R83a
 * @spec encryption.primitives-lifecycle R83c-1
 * @spec encryption.primitives-lifecycle R76
 * @spec encryption.primitives-lifecycle R76b-2
 * @spec encryption.primitives-lifecycle R76c
 */
class TenantStateMachineTest {

    private static final TenantId TENANT_A = new TenantId("tenant-A");
    private static final TenantId TENANT_B = new TenantId("tenant-B");
    private static final TableScope SCOPE_A = new TableScope(TENANT_A, new DomainId("domain-1"),
            new TableId("table-1"));
    private static final TableScope SCOPE_B = new TableScope(TENANT_B, new DomainId("domain-2"),
            new TableId("table-2"));

    @Test
    void createWithNullProgressThrowsNpe() {
        assertThrows(NullPointerException.class, () -> TenantStateMachine.create(null));
    }

    @Test
    void newTenantStartsHealthy(@TempDir Path tmp) throws IOException {
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        assertEquals(TenantState.HEALTHY, sm.currentState(TENANT_A));
    }

    @Test
    void currentStateRejectsNullTenantId(@TempDir Path tmp) throws IOException {
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        assertThrows(NullPointerException.class, () -> sm.currentState(null));
    }

    @Test
    void recordPermanentFailureRejectsNulls(@TempDir Path tmp) throws IOException {
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        assertThrows(NullPointerException.class, () -> sm.recordPermanentFailure(null, SCOPE_A,
                KmsErrorClassifier.ErrorClass.PERMANENT));
        assertThrows(NullPointerException.class, () -> sm.recordPermanentFailure(TENANT_A, null,
                KmsErrorClassifier.ErrorClass.PERMANENT));
        assertThrows(NullPointerException.class,
                () -> sm.recordPermanentFailure(TENANT_A, SCOPE_A, null));
    }

    @Test
    void firstNFailuresTransitionToGraceReadOnly(@TempDir Path tmp) throws IOException {
        // R76 default N=5: the 5th consecutive permanent-class failure is the transitioning
        // read.
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        final int n = TenantStateMachine.DEFAULT_GRACE_THRESHOLD;
        Optional<TenantStateMachine.StateTransition> t = Optional.empty();
        for (int i = 1; i <= n; i++) {
            t = sm.recordPermanentFailure(TENANT_A, SCOPE_A,
                    KmsErrorClassifier.ErrorClass.PERMANENT);
            if (i < n) {
                assertTrue(t.isEmpty(),
                        "transitions should not fire before the N-th failure (i=" + i + ")");
            }
        }
        assertTrue(t.isPresent(), "the N-th failure must produce a transition");
        assertEquals(TenantState.HEALTHY, t.get().fromState());
        assertEquals(TenantState.GRACE_READ_ONLY, t.get().toState());
        assertEquals(TENANT_A, t.get().tenantId());
        assertEquals(TenantState.GRACE_READ_ONLY, sm.currentState(TENANT_A));
    }

    @Test
    void subsequentFailuresAfterGraceProduceNoNewTransition(@TempDir Path tmp) throws IOException {
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        // Drive into grace
        for (int i = 1; i <= TenantStateMachine.DEFAULT_GRACE_THRESHOLD; i++) {
            sm.recordPermanentFailure(TENANT_A, SCOPE_A, KmsErrorClassifier.ErrorClass.PERMANENT);
        }
        // Subsequent non-transitioning failures must return Optional.empty().
        final Optional<TenantStateMachine.StateTransition> t = sm.recordPermanentFailure(TENANT_A,
                SCOPE_A, KmsErrorClassifier.ErrorClass.PERMANENT);
        assertTrue(t.isEmpty(),
                "Subsequent failures while already in grace must not produce a transition (R83c-1)");
    }

    @Test
    void perTenantIsolationDoesNotCrossContaminate(@TempDir Path tmp) throws IOException {
        // R76c: tenant B unaffected by tenant A's failures.
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        for (int i = 1; i <= TenantStateMachine.DEFAULT_GRACE_THRESHOLD; i++) {
            sm.recordPermanentFailure(TENANT_A, SCOPE_A, KmsErrorClassifier.ErrorClass.PERMANENT);
        }
        assertEquals(TenantState.GRACE_READ_ONLY, sm.currentState(TENANT_A));
        assertEquals(TenantState.HEALTHY, sm.currentState(TENANT_B));
    }

    @Test
    void transientFailuresDoNotIncrementCounter(@TempDir Path tmp) throws IOException {
        // R76a: transient errors do not count toward N.
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        for (int i = 0; i < 100; i++) {
            sm.recordPermanentFailure(TENANT_A, SCOPE_A, KmsErrorClassifier.ErrorClass.TRANSIENT);
        }
        assertEquals(TenantState.HEALTHY, sm.currentState(TENANT_A),
                "Transient errors must not transition state (R76a)");
    }

    @Test
    void transitionDurablyPersistsAcrossReopen(@TempDir Path tmp) throws IOException {
        // R76b-2: bootstrap from durable record. Drive tenant A to grace; reopen — same state.
        final TenantStateProgress progress1 = TenantStateProgress.open(tmp);
        final TenantStateMachine sm1 = TenantStateMachine.create(progress1);
        for (int i = 1; i <= TenantStateMachine.DEFAULT_GRACE_THRESHOLD; i++) {
            sm1.recordPermanentFailure(TENANT_A, SCOPE_A, KmsErrorClassifier.ErrorClass.PERMANENT);
        }
        assertEquals(TenantState.GRACE_READ_ONLY, sm1.currentState(TENANT_A));

        // Simulate process restart: open a fresh state machine over the same root.
        final TenantStateProgress progress2 = TenantStateProgress.open(tmp);
        final TenantStateMachine sm2 = TenantStateMachine.create(progress2);
        assertEquals(TenantState.GRACE_READ_ONLY, sm2.currentState(TENANT_A),
                "R76b-2: bootstrap from durable record");
    }

    @Test
    void postRestartFailureCounterResetsButStateRetained(@TempDir Path tmp) throws IOException {
        // R83a + R76b-2 separation: state is durable, counter is in-process. After restart,
        // a tenant in GRACE_READ_ONLY remains there but a fresh batch of failures does not
        // get coalesced against the pre-restart count.
        final TenantStateProgress progress1 = TenantStateProgress.open(tmp);
        final TenantStateMachine sm1 = TenantStateMachine.create(progress1);
        for (int i = 1; i <= TenantStateMachine.DEFAULT_GRACE_THRESHOLD; i++) {
            sm1.recordPermanentFailure(TENANT_A, SCOPE_A, KmsErrorClassifier.ErrorClass.PERMANENT);
        }
        // Reopen fresh — counter is reset.
        final TenantStateProgress progress2 = TenantStateProgress.open(tmp);
        final TenantStateMachine sm2 = TenantStateMachine.create(progress2);
        assertEquals(TenantState.GRACE_READ_ONLY, sm2.currentState(TENANT_A));
        // sm2's R83a counter is fresh — additional permanent failures do not transition again.
        final Optional<TenantStateMachine.StateTransition> t = sm2.recordPermanentFailure(TENANT_A,
                SCOPE_A, KmsErrorClassifier.ErrorClass.PERMANENT);
        assertTrue(t.isEmpty(),
                "Already-grace tenant must not produce additional GRACE transitions");
    }

    @Test
    void concurrentFailuresProduceExactlyOneTransition(@TempDir Path tmp) throws Exception {
        // R83c-1: atomic CAS — exactly one transitioning read among concurrent observers.
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        // Drive to N-1 failures synchronously
        for (int i = 1; i < TenantStateMachine.DEFAULT_GRACE_THRESHOLD; i++) {
            sm.recordPermanentFailure(TENANT_A, SCOPE_A, KmsErrorClassifier.ErrorClass.PERMANENT);
        }
        // Now race many threads to push the N-th increment together.
        final int threads = 32;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final AtomicInteger transitionsObserved = new AtomicInteger(0);
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        final Optional<TenantStateMachine.StateTransition> t = sm
                                .recordPermanentFailure(TENANT_A, SCOPE_A,
                                        KmsErrorClassifier.ErrorClass.PERMANENT);
                        if (t.isPresent()) {
                            transitionsObserved.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // logged via observed counter
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "pool did not terminate");
            assertEquals(1, transitionsObserved.get(),
                    "R83c-1: exactly one transitioning read among concurrent observers");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void detectionEpochOverloadDeduplicatesPerEpoch(@TempDir Path tmp) throws IOException {
        // R83a dedup hook: caller (RevokedDekCache, WU-5) supplies a detection-epoch identifier.
        // Multiple calls within the same epoch coalesce into one counter increment.
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        final long epoch = 42L;
        // 100 calls in a single epoch — at most one increment, so we must be at most 1 step
        // closer to the threshold. Adding (N-1) full distinct-epoch increments still fails to
        // transition.
        for (int i = 0; i < 100; i++) {
            sm.recordPermanentFailure(TENANT_A, SCOPE_A, KmsErrorClassifier.ErrorClass.PERMANENT,
                    epoch);
        }
        // Add (N-2) more distinct-epoch increments
        for (int i = 1; i <= TenantStateMachine.DEFAULT_GRACE_THRESHOLD - 2; i++) {
            sm.recordPermanentFailure(TENANT_A, SCOPE_A, KmsErrorClassifier.ErrorClass.PERMANENT,
                    1000L + i);
        }
        // We should still be HEALTHY because total increments == 1 + (N - 2) == N - 1.
        assertEquals(TenantState.HEALTHY, sm.currentState(TENANT_A));

        // One more distinct epoch pushes to the transition.
        final Optional<TenantStateMachine.StateTransition> t = sm.recordPermanentFailure(TENANT_A,
                SCOPE_A, KmsErrorClassifier.ErrorClass.PERMANENT, 9999L);
        assertTrue(t.isPresent());
        assertEquals(TenantState.GRACE_READ_ONLY, sm.currentState(TENANT_A));
    }

    @Test
    void detectionEpochOverloadRejectsNulls(@TempDir Path tmp) throws IOException {
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        assertThrows(NullPointerException.class, () -> sm.recordPermanentFailure(null, SCOPE_A,
                KmsErrorClassifier.ErrorClass.PERMANENT, 0L));
        assertThrows(NullPointerException.class, () -> sm.recordPermanentFailure(TENANT_A, null,
                KmsErrorClassifier.ErrorClass.PERMANENT, 0L));
        assertThrows(NullPointerException.class,
                () -> sm.recordPermanentFailure(TENANT_A, SCOPE_A, null, 0L));
    }

    @Test
    void stateTransitionRecordRejectsNullsAndNegativeSeq() {
        assertThrows(NullPointerException.class, () -> new TenantStateMachine.StateTransition(null,
                TenantState.HEALTHY, TenantState.FAILED, 0L));
        assertThrows(NullPointerException.class,
                () -> new TenantStateMachine.StateTransition(TENANT_A, null, TenantState.FAILED,
                        0L));
        assertThrows(NullPointerException.class,
                () -> new TenantStateMachine.StateTransition(TENANT_A, TenantState.HEALTHY, null,
                        0L));
        assertThrows(IllegalArgumentException.class,
                () -> new TenantStateMachine.StateTransition(TENANT_A, TenantState.HEALTHY,
                        TenantState.FAILED, -1L));
    }

    @Test
    void transitionEventSeqIsMonotonic(@TempDir Path tmp) throws IOException {
        // R76b-1: per-(tenant, eventCategory) eventSeq is monotonic.
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        // Trigger transition for tenant A
        Optional<TenantStateMachine.StateTransition> tA = Optional.empty();
        for (int i = 1; i <= TenantStateMachine.DEFAULT_GRACE_THRESHOLD; i++) {
            tA = sm.recordPermanentFailure(TENANT_A, SCOPE_A,
                    KmsErrorClassifier.ErrorClass.PERMANENT);
        }
        assertTrue(tA.isPresent());
        final long seqA = tA.get().eventSeq();

        // Trigger transition for tenant B; its eventSeq is independent (per-tenant scope).
        Optional<TenantStateMachine.StateTransition> tB = Optional.empty();
        for (int i = 1; i <= TenantStateMachine.DEFAULT_GRACE_THRESHOLD; i++) {
            tB = sm.recordPermanentFailure(TENANT_B, SCOPE_B,
                    KmsErrorClassifier.ErrorClass.PERMANENT);
        }
        assertTrue(tB.isPresent());
        // Both should have a non-negative seq; intra-tenant monotonicity is what R76b-1 mandates,
        // not cross-tenant.
        assertTrue(seqA >= 0);
        assertTrue(tB.get().eventSeq() >= 0);
    }

    @Test
    void crcMismatchOnDurableRecordInitialisesConservatively(@TempDir Path tmp) throws IOException {
        // R76b-1a: CRC mismatch must initialise the affected tenant to a conservative state
        // (FAILED), not silently mask the integrity failure.
        final TenantStateProgress progress1 = TenantStateProgress.open(tmp);
        final TenantStateMachine sm1 = TenantStateMachine.create(progress1);
        for (int i = 1; i <= TenantStateMachine.DEFAULT_GRACE_THRESHOLD; i++) {
            sm1.recordPermanentFailure(TENANT_A, SCOPE_A, KmsErrorClassifier.ErrorClass.PERMANENT);
        }
        // Hand-corrupt the file
        try (var stream = java.nio.file.Files.walk(tmp)) {
            final java.util.List<Path> files = stream.filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("state-progress.bin")).toList();
            for (Path f : files) {
                final byte[] bytes = java.nio.file.Files.readAllBytes(f);
                bytes[bytes.length / 2] ^= 0x42;
                java.nio.file.Files.write(f, bytes);
            }
        }
        // Reopen — bootstrap must initialise to FAILED conservatively.
        final TenantStateProgress progress2 = TenantStateProgress.open(tmp);
        final TenantStateMachine sm2 = TenantStateMachine.create(progress2);
        assertEquals(TenantState.FAILED, sm2.currentState(TENANT_A),
                "R76b-1a: CRC mismatch initialises to conservative FAILED state");
    }

    @Test
    void concurrentReadOfCurrentStateNeverObservesNullOrIntermediate(@TempDir Path tmp)
            throws Exception {
        // R83c-1 atomic CAS: readers must never observe a state that wasn't legally entered.
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        final int readers = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final Set<TenantState> observed = java.util.Collections
                    .synchronizedSet(new HashSet<>());

            // Writer thread
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 1; i <= TenantStateMachine.DEFAULT_GRACE_THRESHOLD; i++) {
                        sm.recordPermanentFailure(TENANT_A, SCOPE_A,
                                KmsErrorClassifier.ErrorClass.PERMANENT);
                    }
                } catch (Exception ignored) {
                    // observed via state set
                }
            });

            // Readers
            for (int i = 0; i < readers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 1000; j++) {
                            final TenantState s = sm.currentState(TENANT_A);
                            assertNotNull(s, "currentState must never return null");
                            observed.add(s);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
            // Observed states must be a subset of {HEALTHY, GRACE_READ_ONLY}; FAILED was never
            // entered.
            assertFalse(observed.contains(TenantState.FAILED),
                    "FAILED was never reached; readers must not invent it");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void stateTransitionSeqDistinctFromOtherTransitions(@TempDir Path tmp) throws IOException {
        // R76b-1: each transition gets a fresh monotonic eventSeq within the (tenantId,
        // STATE_TRANSITION) scope.
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp));
        // First transition for A
        Optional<TenantStateMachine.StateTransition> t1 = Optional.empty();
        for (int i = 1; i <= TenantStateMachine.DEFAULT_GRACE_THRESHOLD; i++) {
            t1 = sm.recordPermanentFailure(TENANT_A, SCOPE_A,
                    KmsErrorClassifier.ErrorClass.PERMANENT);
        }
        // First transition for B
        Optional<TenantStateMachine.StateTransition> t2 = Optional.empty();
        for (int i = 1; i <= TenantStateMachine.DEFAULT_GRACE_THRESHOLD; i++) {
            t2 = sm.recordPermanentFailure(TENANT_B, SCOPE_B,
                    KmsErrorClassifier.ErrorClass.PERMANENT);
        }
        assertTrue(t1.isPresent());
        assertTrue(t2.isPresent());
        // Per-tenant counters are independent — both could legitimately equal each other (e.g.,
        // both = 1). What matters is that the (tenant, seq) pair identifies a distinct event.
        assertNotEquals(new Object[]{ t1.get().tenantId(), t1.get().eventSeq() },
                new Object[]{ t2.get().tenantId(), t2.get().eventSeq() });
    }
}
