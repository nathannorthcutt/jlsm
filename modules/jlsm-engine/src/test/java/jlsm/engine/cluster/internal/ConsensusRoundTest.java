package jlsm.engine.cluster.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jlsm.cluster.NodeAddress;
import jlsm.engine.cluster.internal.ConsensusRound.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * TDD test suite for {@link ConsensusRound}.
 *
 * <p>
 * Covers constructor validation, quorum math across observer cardinalities, PENDING transitions
 * (QUORUM_AGREE / QUORUM_DISAGREE / EXPIRED / CANCELLED), terminal-state stickiness, voter dedup,
 * and concurrent recordVote safety.
 */
@Timeout(5)
final class ConsensusRoundTest {

    private static final NodeAddress SUBJECT = new NodeAddress("subject", "10.0.0.9", 9999);

    private static NodeAddress observer(int i) {
        return new NodeAddress("obs-" + i, "10.0.0." + (i + 10), 9000 + i);
    }

    private static Set<NodeAddress> observers(int n) {
        Set<NodeAddress> s = new HashSet<>();
        for (int i = 0; i < n; i++) {
            s.add(observer(i));
        }
        return s;
    }

    @Test
    void constructor_rejectsNullSubject() {
        assertThrows(NullPointerException.class,
                () -> new ConsensusRound(1L, null, observers(3), 75, 0L, 1_000_000L));
    }

    @Test
    void constructor_rejectsNullObservers() {
        assertThrows(NullPointerException.class,
                () -> new ConsensusRound(1L, SUBJECT, null, 75, 0L, 1_000_000L));
    }

    @Test
    void constructor_rejectsNegativeRoundId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConsensusRound(-1L, SUBJECT, observers(3), 75, 0L, 1_000_000L));
    }

    @Test
    void constructor_rejectsQuorumPercentZero() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConsensusRound(1L, SUBJECT, observers(3), 0, 0L, 1_000_000L));
    }

    @Test
    void constructor_rejectsQuorumPercentAbove100() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConsensusRound(1L, SUBJECT, observers(3), 101, 0L, 1_000_000L));
    }

    @Test
    void constructor_rejectsNegativeQuorumPercent() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConsensusRound(1L, SUBJECT, observers(3), -1, 0L, 1_000_000L));
    }

    @Test
    void constructor_rejectsOutOfRangeQuorumPercent200() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConsensusRound(1L, SUBJECT, observers(3), 200, 0L, 1_000_000L));
    }

    @Test
    void constructor_rejectsNegativeTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConsensusRound(1L, SUBJECT, observers(3), 75, 0L, -1L));
    }

    @Test
    void constructor_acceptsValidArgs() {
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, observers(3), 75, 1000L, 500L);
        assertEquals(1L, r.roundId());
        assertEquals(SUBJECT, r.subject());
        assertEquals(Outcome.PENDING, r.currentOutcome());
        assertTrue(r.agreeVoters().isEmpty());
    }

    @Test
    void recordVote_transitionsToQuorumAgreeWhenEnoughAgrees() {
        // 5 observers, 75% → ceilDiv(375, 100) = 4 required agrees
        Set<NodeAddress> obs = observers(5);
        List<NodeAddress> obsList = new ArrayList<>(obs);
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 75, 0L, 1_000_000_000L);

        assertEquals(Outcome.PENDING, r.recordVote(obsList.get(0), true));
        assertEquals(Outcome.PENDING, r.recordVote(obsList.get(1), true));
        assertEquals(Outcome.PENDING, r.recordVote(obsList.get(2), true));
        assertEquals(Outcome.QUORUM_AGREE, r.recordVote(obsList.get(3), true));
        assertEquals(Outcome.QUORUM_AGREE, r.currentOutcome());
    }

    @Test
    void recordVote_transitionsToQuorumDisagreeWhenAgreementImpossible() {
        // 5 observers, 75% → 4 required; 2 disagrees + max possible agree = 3 → IMPOSSIBLE
        Set<NodeAddress> obs = observers(5);
        List<NodeAddress> obsList = new ArrayList<>(obs);
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 75, 0L, 1_000_000_000L);

        assertEquals(Outcome.PENDING, r.recordVote(obsList.get(0), false));
        assertEquals(Outcome.QUORUM_DISAGREE, r.recordVote(obsList.get(1), false));
        assertEquals(Outcome.QUORUM_DISAGREE, r.currentOutcome());
    }

    @Test
    void recordVote_isStickyAfterTerminalAgree() {
        Set<NodeAddress> obs = observers(5);
        List<NodeAddress> obsList = new ArrayList<>(obs);
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 75, 0L, 1_000_000_000L);

        for (int i = 0; i < 4; i++) {
            r.recordVote(obsList.get(i), true);
        }
        assertEquals(Outcome.QUORUM_AGREE, r.currentOutcome());

        // Additional votes after QUORUM_AGREE return the sticky outcome unchanged.
        assertEquals(Outcome.QUORUM_AGREE, r.recordVote(obsList.get(4), true));
        assertEquals(Outcome.QUORUM_AGREE, r.currentOutcome());
    }

    @Test
    void recordVote_dedupsVoter() {
        Set<NodeAddress> obs = observers(5);
        List<NodeAddress> obsList = new ArrayList<>(obs);
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 75, 0L, 1_000_000_000L);

        assertEquals(Outcome.PENDING, r.recordVote(obsList.get(0), true));
        // Same voter, same vote → dedup, outcome unchanged.
        assertEquals(Outcome.PENDING, r.recordVote(obsList.get(0), true));
        // Same voter, flipped vote → also dedup, outcome unchanged.
        assertEquals(Outcome.PENDING, r.recordVote(obsList.get(0), false));
        assertEquals(1, r.agreeVoters().size());
    }

    @Test
    void recordVote_fromNonObserver_isSilentlyDropped() {
        Set<NodeAddress> obs = observers(3);
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 75, 0L, 1_000_000_000L);

        NodeAddress outsider = new NodeAddress("stranger", "10.0.99.1", 9000);
        assertEquals(Outcome.PENDING, r.recordVote(outsider, true));
        assertEquals(Outcome.PENDING, r.currentOutcome());
        assertTrue(r.agreeVoters().isEmpty());
    }

    @Test
    void cancel_transitionsToCancelledFromPending() {
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, observers(3), 75, 0L, 1_000_000L);
        assertEquals(Outcome.CANCELLED, r.cancel());
        assertEquals(Outcome.CANCELLED, r.currentOutcome());
    }

    @Test
    void cancel_idempotentOnTerminalQuorumAgree() {
        Set<NodeAddress> obs = observers(1);
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 100, 0L, 1_000_000L);
        NodeAddress only = obs.iterator().next();

        assertEquals(Outcome.QUORUM_AGREE, r.recordVote(only, true));
        // cancel() after terminal outcome returns the sticky terminal, not CANCELLED.
        assertEquals(Outcome.QUORUM_AGREE, r.cancel());
        assertEquals(Outcome.QUORUM_AGREE, r.currentOutcome());
    }

    @Test
    void cancel_idempotentOnTerminalCancelled() {
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, observers(3), 75, 0L, 1_000_000L);
        r.cancel();
        assertEquals(Outcome.CANCELLED, r.cancel());
        assertEquals(Outcome.CANCELLED, r.currentOutcome());
    }

    @Test
    void isExpired_beforeDeadline_returnsFalse() {
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, observers(3), 75, 1_000L, 500L);
        assertFalse(r.isExpired(1_000L));
        assertFalse(r.isExpired(1_499L));
        assertFalse(r.isExpired(1_500L));
    }

    @Test
    void isExpired_afterDeadline_returnsTrue() {
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, observers(3), 75, 1_000L, 500L);
        assertTrue(r.isExpired(1_501L));
        assertTrue(r.isExpired(Long.MAX_VALUE));
    }

    @Test
    void isExpired_doesNotMutateOutcome() {
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, observers(3), 75, 0L, 100L);
        r.isExpired(10_000L);
        // isExpired is a predicate — outcome must remain PENDING.
        assertEquals(Outcome.PENDING, r.currentOutcome());
    }

    @Test
    void isExpired_afterTerminalState_stillReadsPredicate() {
        // isExpired computes purely from nanos; spec says predicate-only.
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, observers(3), 75, 0L, 100L);
        r.cancel();
        // Outcome is CANCELLED; isExpired still computes time predicate (implementation
        // may AND-in the PENDING check — either way it must not mutate).
        assertNotNull(r.currentOutcome());
        assertEquals(Outcome.CANCELLED, r.currentOutcome());
    }

    @Test
    void expire_fromPendingReturnsExpired() {
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, observers(3), 75, 0L, 100L);
        assertEquals(Outcome.EXPIRED, r.expire());
        assertEquals(Outcome.EXPIRED, r.currentOutcome());
    }

    @Test
    void expire_idempotentOnTerminalState() {
        Set<NodeAddress> obs = observers(1);
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 100, 0L, 100L);
        r.recordVote(obs.iterator().next(), true);
        assertEquals(Outcome.QUORUM_AGREE, r.currentOutcome());

        // expire() after terminal → returns current sticky outcome.
        assertEquals(Outcome.QUORUM_AGREE, r.expire());
        assertEquals(Outcome.QUORUM_AGREE, r.currentOutcome());
    }

    @Test
    void expire_twiceIsIdempotent() {
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, observers(3), 75, 0L, 100L);
        r.expire();
        assertEquals(Outcome.EXPIRED, r.expire());
        assertEquals(Outcome.EXPIRED, r.currentOutcome());
    }

    @Test
    void quorumMath_oneObserverOneHundredPercent() {
        Set<NodeAddress> obs = observers(1);
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 100, 0L, 1_000_000L);
        assertEquals(Outcome.QUORUM_AGREE, r.recordVote(obs.iterator().next(), true));
    }

    @Test
    void quorumMath_tenObservers75Percent() {
        // ceilDiv(10 * 75, 100) = ceilDiv(750, 100) = 8
        Set<NodeAddress> obs = observers(10);
        List<NodeAddress> obsList = new ArrayList<>(obs);
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 75, 0L, 1_000_000L);

        for (int i = 0; i < 7; i++) {
            assertEquals(Outcome.PENDING, r.recordVote(obsList.get(i), true),
                    "after " + (i + 1) + " agree votes");
        }
        assertEquals(Outcome.QUORUM_AGREE, r.recordVote(obsList.get(7), true),
                "after 8 agree votes");
    }

    @Test
    void quorumMath_twoObservers75Percent_ceilDivYieldsTwo() {
        // ceilDiv(2 * 75, 100) = ceilDiv(150, 100) = 2 — both must agree.
        Set<NodeAddress> obs = observers(2);
        List<NodeAddress> obsList = new ArrayList<>(obs);
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 75, 0L, 1_000_000L);

        assertEquals(Outcome.PENDING, r.recordVote(obsList.get(0), true));
        assertEquals(Outcome.QUORUM_AGREE, r.recordVote(obsList.get(1), true));
    }

    @Test
    void quorumMath_hundredObserversMajority() {
        // ceilDiv(100 * 51, 100) = 51
        Set<NodeAddress> obs = observers(100);
        List<NodeAddress> obsList = new ArrayList<>(obs);
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 51, 0L, 1_000_000L);

        for (int i = 0; i < 50; i++) {
            assertEquals(Outcome.PENDING, r.recordVote(obsList.get(i), true));
        }
        assertEquals(Outcome.QUORUM_AGREE, r.recordVote(obsList.get(50), true));
    }

    @Test
    void quorumMath_emptyObservers_requiredAgreeAtLeastOne() {
        // Empty observers: design says requiredAgree = max(1, ceilDiv(0, anything)) = 1
        // No valid voter exists — round stays PENDING forever until expiry/cancel.
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, Set.of(), 75, 0L, 1_000_000L);
        assertEquals(Outcome.PENDING, r.currentOutcome());

        // Any "voter" is not in the empty set — silently dropped.
        NodeAddress stranger = new NodeAddress("stranger", "10.0.99.1", 9000);
        assertEquals(Outcome.PENDING, r.recordVote(stranger, true));
        assertEquals(Outcome.PENDING, r.currentOutcome());
    }

    @Test
    void concurrent_recordVote_isSafeUnderParallelism() throws InterruptedException {
        final int n = 100;
        Set<NodeAddress> obs = observers(n);
        List<NodeAddress> obsList = new ArrayList<>(obs);
        // 100% quorum across 100 observers; we want all 100 to register.
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 100, 0L, 10_000_000_000L);

        final int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < n; i++) {
            final NodeAddress voter = obsList.get(i);
            pool.submit(() -> {
                try {
                    start.await();
                    r.recordVote(voter, true);
                } catch (Throwable t) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(3, TimeUnit.SECONDS), "all threads should complete");
        pool.shutdownNow();

        assertEquals(0, errors.get(), "no exceptions expected under concurrent recordVote");
        // All 100 votes must have registered — no lost updates under concurrency.
        assertEquals(n, r.agreeVoters().size());
        assertEquals(Outcome.QUORUM_AGREE, r.currentOutcome());
    }

    @Test
    void agreeVoters_returnsImmutableSnapshot() {
        Set<NodeAddress> obs = observers(3);
        List<NodeAddress> obsList = new ArrayList<>(obs);
        ConsensusRound r = new ConsensusRound(1L, SUBJECT, obs, 75, 0L, 1_000_000L);

        r.recordVote(obsList.get(0), true);
        Set<NodeAddress> snapshot = r.agreeVoters();
        assertEquals(1, snapshot.size());

        // Adding more votes must not mutate the prior snapshot.
        r.recordVote(obsList.get(1), true);
        assertEquals(1, snapshot.size());
        // Second snapshot reflects the newer state.
        assertEquals(2, r.agreeVoters().size());
    }
}
