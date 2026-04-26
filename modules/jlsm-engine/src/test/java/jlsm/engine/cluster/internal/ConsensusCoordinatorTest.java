package jlsm.engine.cluster.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import jlsm.cluster.ClusterTransport;
import jlsm.engine.cluster.Member;
import jlsm.engine.cluster.MemberState;
import jlsm.engine.cluster.MembershipView;
import jlsm.cluster.Message;
import jlsm.cluster.MessageHandler;
import jlsm.cluster.MessageType;
import jlsm.cluster.NodeAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * TDD test suite for {@link ConsensusCoordinator}.
 *
 * <p>
 * Covers constructor validation, round lifecycle, proposal routing (self vs remote), vote
 * accumulation and quorum sink invocation, refutation cancellation, tick expiry, view-change round
 * invalidation, and close idempotence.
 *
 * @spec engine.clustering.R34 — multi-process cut detection with observer-quorum consensus
 * @spec engine.clustering.R36 — observers vote independently; proposals targeting self trigger
 *       refutation
 * @spec engine.clustering.R37 — bounded consensus round timeout; expired rounds abandoned
 * @spec engine.clustering.R38 — self-refutation cancels pending rounds; higher incarnation
 *       supersedes
 */
@Timeout(10)
final class ConsensusCoordinatorTest {

    private static final NodeAddress LOCAL = new NodeAddress("local", "127.0.0.1", 9000);
    private static final NodeAddress PROPOSER = new NodeAddress("proposer", "127.0.0.1", 9100);
    private static final NodeAddress SUBJECT = new NodeAddress("subject", "127.0.0.1", 9200);

    private static NodeAddress observer(int i) {
        return new NodeAddress("obs-" + i, "127.0.0.1", 9300 + i);
    }

    private static Set<NodeAddress> observerSet(int n) {
        Set<NodeAddress> s = new HashSet<>();
        for (int i = 0; i < n; i++) {
            s.add(observer(i));
        }
        return s;
    }

    private static Set<Member> aliveMembers(NodeAddress... addrs) {
        Set<Member> s = new HashSet<>();
        for (NodeAddress a : addrs) {
            s.add(new Member(a, MemberState.ALIVE, 0));
        }
        return s;
    }

    /** A wall-clock-backed {@link Clock} whose millis we can advance for tests. */
    private static final class MutableClock extends Clock {
        private final AtomicLong nowMillis;

        MutableClock(long startMillis) {
            this.nowMillis = new AtomicLong(startMillis);
        }

        void advance(Duration d) {
            nowMillis.addAndGet(d.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(nowMillis.get());
        }

        @Override
        public long millis() {
            return nowMillis.get();
        }
    }

    /** Records every outbound {@link #send} / {@link #request} call for assertion. */
    private static final class RecordingTransport implements ClusterTransport {
        final List<Sent> sends = new ArrayList<>();
        final ReentrantLock lock = new ReentrantLock();

        record Sent(NodeAddress target, Message msg) {
        }

        @Override
        public void send(NodeAddress target, Message msg) throws IOException {
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(msg, "msg must not be null");
            lock.lock();
            try {
                sends.add(new Sent(target, msg));
            } finally {
                lock.unlock();
            }
        }

        @Override
        public CompletableFuture<Message> request(NodeAddress target, Message msg) {
            return CompletableFuture
                    .failedFuture(new UnsupportedOperationException("request not used"));
        }

        @Override
        public void registerHandler(MessageType type, MessageHandler handler) {
            // no-op
        }

        @Override
        public void deregisterHandler(MessageType type) {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }

        List<Sent> snapshot() {
            lock.lock();
            try {
                return new ArrayList<>(sends);
            } finally {
                lock.unlock();
            }
        }

        int countByTargetAndSubType(NodeAddress target, byte subType) {
            int count = 0;
            for (Sent s : snapshot()) {
                if (s.target().equals(target) && s.msg().payload().length > 0
                        && s.msg().payload()[0] == subType) {
                    count++;
                }
            }
            return count;
        }

        int countBySubType(byte subType) {
            int count = 0;
            for (Sent s : snapshot()) {
                if (s.msg().payload().length > 0 && s.msg().payload()[0] == subType) {
                    count++;
                }
            }
            return count;
        }
    }

    /** Captures every {@link ViewChangeSink#applySuspicion} invocation. */
    private static final class RecordingSink implements ConsensusCoordinator.ViewChangeSink {
        final List<long[]> applyEpochs = new ArrayList<>();
        final List<NodeAddress> applySubjects = new ArrayList<>();
        final ReentrantLock lock = new ReentrantLock();

        @Override
        public void applySuspicion(NodeAddress subject, long epoch) {
            lock.lock();
            try {
                applySubjects.add(subject);
                applyEpochs.add(new long[]{ epoch });
            } finally {
                lock.unlock();
            }
        }

        int callCount() {
            lock.lock();
            try {
                return applySubjects.size();
            } finally {
                lock.unlock();
            }
        }
    }

    /** Builds a coordinator with sensible defaults; observers for {@code SUBJECT} are supplied. */
    private ConsensusCoordinator newCoordinator(RecordingTransport transport, RecordingSink sink,
            MutableClock clock, ExpanderGraphOverlay overlay, Duration timeout, int quorumPercent) {
        return new ConsensusCoordinator(LOCAL, transport, overlay, clock, timeout, quorumPercent,
                sink);
    }

    /** Builds an overlay whose ring yields the given observers for {@code subject}. */
    private ExpanderGraphOverlay overlayWithObservers(NodeAddress subject,
            Set<NodeAddress> observers) {
        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();
        Set<NodeAddress> members = new HashSet<>(observers);
        members.add(subject);
        // When degree >= N - 1, every ALIVE member observes every other ALIVE member, so
        // observersOf(subject) == members \ {subject} == observers.
        overlay.rebuild(members, Math.max(0, members.size() - 1), 1L);
        return overlay;
    }

    // ---------- Constructor / lifecycle ----------

    @Test
    void constructor_rejectsNullLocalAddress() {
        assertThrows(NullPointerException.class,
                () -> new ConsensusCoordinator(null, new RecordingTransport(),
                        new ExpanderGraphOverlay(), Clock.systemUTC(), Duration.ofMillis(100), 75,
                        new RecordingSink()));
    }

    @Test
    void constructor_rejectsNullTransport() {
        assertThrows(NullPointerException.class,
                () -> new ConsensusCoordinator(LOCAL, null, new ExpanderGraphOverlay(),
                        Clock.systemUTC(), Duration.ofMillis(100), 75, new RecordingSink()));
    }

    @Test
    void constructor_rejectsNullOverlay() {
        assertThrows(NullPointerException.class,
                () -> new ConsensusCoordinator(LOCAL, new RecordingTransport(), null,
                        Clock.systemUTC(), Duration.ofMillis(100), 75, new RecordingSink()));
    }

    @Test
    void constructor_rejectsNullClock() {
        assertThrows(NullPointerException.class,
                () -> new ConsensusCoordinator(LOCAL, new RecordingTransport(),
                        new ExpanderGraphOverlay(), null, Duration.ofMillis(100), 75,
                        new RecordingSink()));
    }

    @Test
    void constructor_rejectsNullTimeout() {
        assertThrows(NullPointerException.class,
                () -> new ConsensusCoordinator(LOCAL, new RecordingTransport(),
                        new ExpanderGraphOverlay(), Clock.systemUTC(), null, 75,
                        new RecordingSink()));
    }

    @Test
    void constructor_rejectsNullSink() {
        assertThrows(NullPointerException.class,
                () -> new ConsensusCoordinator(LOCAL, new RecordingTransport(),
                        new ExpanderGraphOverlay(), Clock.systemUTC(), Duration.ofMillis(100), 75,
                        null));
    }

    @Test
    void constructor_rejectsQuorumPercentZero() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConsensusCoordinator(LOCAL, new RecordingTransport(),
                        new ExpanderGraphOverlay(), Clock.systemUTC(), Duration.ofMillis(100), 0,
                        new RecordingSink()));
    }

    @Test
    void constructor_rejectsQuorumPercentAbove100() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConsensusCoordinator(LOCAL, new RecordingTransport(),
                        new ExpanderGraphOverlay(), Clock.systemUTC(), Duration.ofMillis(100), 101,
                        new RecordingSink()));
    }

    @Test
    void constructor_rejectsNonPositiveTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConsensusCoordinator(LOCAL, new RecordingTransport(),
                        new ExpanderGraphOverlay(), Clock.systemUTC(), Duration.ZERO, 75,
                        new RecordingSink()));
        assertThrows(IllegalArgumentException.class,
                () -> new ConsensusCoordinator(LOCAL, new RecordingTransport(),
                        new ExpanderGraphOverlay(), Clock.systemUTC(), Duration.ofMillis(-1), 75,
                        new RecordingSink()));
    }

    @Test
    void close_isIdempotent() {
        var c = new ConsensusCoordinator(LOCAL, new RecordingTransport(),
                new ExpanderGraphOverlay(), Clock.systemUTC(), Duration.ofMillis(100), 75,
                new RecordingSink());
        c.close();
        c.close(); // second call must not throw
    }

    @Test
    void startRound_afterClose_throwsIllegalStateException() {
        var c = new ConsensusCoordinator(LOCAL, new RecordingTransport(),
                new ExpanderGraphOverlay(), Clock.systemUTC(), Duration.ofMillis(100), 75,
                new RecordingSink());
        c.close();
        assertThrows(IllegalStateException.class, () -> c.startRound(SUBJECT, 1L, 0L));
    }

    // ---------- startRound ----------

    @Test
    void startRound_generatesUniqueRoundIds() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        var overlay = overlayWithObservers(SUBJECT, observerSet(1));
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            ids.add(c.startRound(SUBJECT, 1L, 0L));
        }
        assertEquals(10, ids.size(), "roundIds must be unique");
    }

    @Test
    void startRound_broadcastsProposalToEveryObserver() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        Set<NodeAddress> observers = observerSet(4);
        var overlay = overlayWithObservers(SUBJECT, observers);
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        c.startRound(SUBJECT, 7L, 3L);

        for (NodeAddress obs : observers) {
            assertEquals(1, tx.countByTargetAndSubType(obs, SuspicionProposal.SUB_TYPE),
                    "expected exactly one proposal sent to " + obs);
        }
        assertEquals(observers.size(), tx.countBySubType(SuspicionProposal.SUB_TYPE),
                "total proposals must match observer count");
    }

    @Test
    void startRound_broadcastsViaViewChangeMessageType() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        var overlay = overlayWithObservers(SUBJECT, observerSet(2));
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        c.startRound(SUBJECT, 1L, 0L);

        for (RecordingTransport.Sent s : tx.snapshot()) {
            assertEquals(MessageType.VIEW_CHANGE, s.msg().type(),
                    "all outbound messages must be VIEW_CHANGE");
            assertEquals(LOCAL, s.msg().sender(), "sender must be local address");
        }
    }

    @Test
    void startRound_broadcastsNothingWhenNoObservers() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        // No observers: subject not in overlay.
        var overlay = new ExpanderGraphOverlay();
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        long roundId = c.startRound(SUBJECT, 1L, 0L);
        assertTrue(roundId >= 0, "roundId still returned");
        assertEquals(0, tx.countBySubType(SuspicionProposal.SUB_TYPE),
                "no proposals sent when there are no observers");
    }

    @Test
    void startRound_subjectIncarnationPropagatedToPayload() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        var overlay = overlayWithObservers(SUBJECT, observerSet(1));
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        c.startRound(SUBJECT, 99L, 42L);

        List<RecordingTransport.Sent> sends = tx.snapshot();
        assertEquals(1, sends.size(), "expected one proposal send");
        SuspicionProposal proposal = SuspicionProposal.deserialize(sends.get(0).msg().payload());
        assertEquals(SUBJECT, proposal.subject());
        assertEquals(42L, proposal.subjectIncarnation());
        assertEquals(99L, proposal.epoch());
        assertEquals(LOCAL, proposal.proposer());
    }

    // ---------- onProposalReceived (remote subject) ----------

    @Test
    void onProposalReceived_remoteSubjectAgree_sendsVoteToProposer() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        var overlay = new ExpanderGraphOverlay();
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        var proposal = new SuspicionProposal(SUBJECT, 0L, 123L, 5L, PROPOSER);
        c.onProposalReceived(proposal, addr -> true);

        assertEquals(1, tx.countByTargetAndSubType(PROPOSER, SuspicionVote.SUB_TYPE),
                "expected exactly one vote to proposer");
        SuspicionVote vote = SuspicionVote.deserialize(tx.snapshot().get(0).msg().payload());
        assertTrue(vote.agree(), "agree must be true when phi agrees");
        assertEquals(LOCAL, vote.voter());
        assertEquals(SUBJECT, vote.subject());
        assertEquals(123L, vote.roundId());
    }

    @Test
    void onProposalReceived_remoteSubjectDisagree_sendsDisagreeVote() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        var overlay = new ExpanderGraphOverlay();
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        var proposal = new SuspicionProposal(SUBJECT, 0L, 123L, 5L, PROPOSER);
        c.onProposalReceived(proposal, addr -> false);

        assertEquals(1, tx.countByTargetAndSubType(PROPOSER, SuspicionVote.SUB_TYPE),
                "expected exactly one vote to proposer");
        SuspicionVote vote = SuspicionVote.deserialize(tx.snapshot().get(0).msg().payload());
        assertFalse(vote.agree(), "agree must be false when phi disagrees");
    }

    @Test
    void onProposalReceived_doesNotCreateLocalRound() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        var overlay = new ExpanderGraphOverlay();
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        var proposal = new SuspicionProposal(SUBJECT, 0L, 123L, 5L, PROPOSER);
        c.onProposalReceived(proposal, addr -> true);

        // Feeding the coordinator a vote for the same roundId must be dropped
        // (observer does not track remote proposals as local rounds).
        var vote = new SuspicionVote(123L, observer(0), SUBJECT, true, 0L);
        c.onVoteReceived(vote);
        assertEquals(0, sink.callCount(), "sink must not fire: no local round exists");
    }

    // ---------- onProposalReceived (self subject) ----------

    @Test
    void onProposalReceived_selfSubject_sendsNoVote() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        var overlay = new ExpanderGraphOverlay();
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);
        c.onViewChanged(new MembershipView(1L, aliveMembers(LOCAL, PROPOSER), Instant.EPOCH));

        var proposal = new SuspicionProposal(LOCAL, 0L, 42L, 1L, PROPOSER);
        c.onProposalReceived(proposal, addr -> true);

        assertEquals(0, tx.countBySubType(SuspicionVote.SUB_TYPE),
                "self-subject proposal must not emit a vote");
    }

    @Test
    void onProposalReceived_selfSubject_broadcastsRefutationWithBumpedIncarnation() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        var overlay = new ExpanderGraphOverlay();
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);
        c.onViewChanged(new MembershipView(1L, aliveMembers(LOCAL, PROPOSER), Instant.EPOCH));

        var proposal = new SuspicionProposal(LOCAL, 0L, 42L, 1L, PROPOSER);
        c.onProposalReceived(proposal, addr -> true);

        int refCount = tx.countBySubType(AliveRefutation.SUB_TYPE);
        assertTrue(refCount >= 1, "at least one refutation must be broadcast");
        // Find the first refutation payload — verify it references LOCAL at incarnation 1.
        AliveRefutation first = null;
        for (RecordingTransport.Sent s : tx.snapshot()) {
            byte[] p = s.msg().payload();
            if (p.length > 0 && p[0] == AliveRefutation.SUB_TYPE) {
                first = AliveRefutation.deserialize(p);
                break;
            }
        }
        assertEquals(LOCAL, first.subject());
        assertEquals(1L, first.incarnation(), "first self-refutation bumps incarnation to 1");

        // Second self-proposal bumps again.
        var proposal2 = new SuspicionProposal(LOCAL, 0L, 43L, 1L, PROPOSER);
        c.onProposalReceived(proposal2, addr -> true);

        AliveRefutation latest = null;
        for (RecordingTransport.Sent s : tx.snapshot()) {
            byte[] p = s.msg().payload();
            if (p.length > 0 && p[0] == AliveRefutation.SUB_TYPE) {
                latest = AliveRefutation.deserialize(p);
            }
        }
        assertEquals(2L, latest.incarnation(), "second self-refutation bumps incarnation to 2");
    }

    @Test
    void onProposalReceived_selfSubject_broadcastsRefutationToEveryAliveMember() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        var overlay = new ExpanderGraphOverlay();
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);
        NodeAddress other1 = observer(0);
        NodeAddress other2 = observer(1);
        c.onViewChanged(new MembershipView(1L, aliveMembers(LOCAL, PROPOSER, other1, other2),
                Instant.EPOCH));

        var proposal = new SuspicionProposal(LOCAL, 0L, 42L, 1L, PROPOSER);
        c.onProposalReceived(proposal, addr -> true);

        // Should have broadcast to PROPOSER + other1 + other2 (but not to LOCAL).
        assertEquals(1, tx.countByTargetAndSubType(PROPOSER, AliveRefutation.SUB_TYPE));
        assertEquals(1, tx.countByTargetAndSubType(other1, AliveRefutation.SUB_TYPE));
        assertEquals(1, tx.countByTargetAndSubType(other2, AliveRefutation.SUB_TYPE));
        assertEquals(0, tx.countByTargetAndSubType(LOCAL, AliveRefutation.SUB_TYPE));
    }

    // ---------- onVoteReceived ----------

    @Test
    void onVoteReceived_unknownRoundId_dropped() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        var overlay = new ExpanderGraphOverlay();
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        var stray = new SuspicionVote(999L, observer(0), SUBJECT, true, 0L);
        c.onVoteReceived(stray); // no round with id 999; silent drop
        assertEquals(0, sink.callCount());
    }

    @Test
    void onVoteReceived_quorumReached_invokesApplySuspicion() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        Set<NodeAddress> observers = observerSet(4);
        var overlay = overlayWithObservers(SUBJECT, observers);
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);
        long roundId = c.startRound(SUBJECT, 11L, 0L);

        // 4 observers × 75% ceil = 3 agrees required.
        int agreed = 0;
        for (NodeAddress obs : observers) {
            if (agreed < 3) {
                c.onVoteReceived(new SuspicionVote(roundId, obs, SUBJECT, true, 0L));
                agreed++;
            }
        }

        assertEquals(1, sink.callCount(), "sink must fire exactly once on quorum");
        assertEquals(SUBJECT, sink.applySubjects.get(0));
        assertEquals(11L, sink.applyEpochs.get(0)[0]);
    }

    @Test
    void onVoteReceived_afterQuorumReached_extraVotesAreNoOps() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        Set<NodeAddress> observers = observerSet(4);
        var overlay = overlayWithObservers(SUBJECT, observers);
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);
        long roundId = c.startRound(SUBJECT, 3L, 0L);

        int agreed = 0;
        List<NodeAddress> obsList = new ArrayList<>(observers);
        for (NodeAddress obs : obsList) {
            if (agreed < 3) {
                c.onVoteReceived(new SuspicionVote(roundId, obs, SUBJECT, true, 0L));
                agreed++;
            }
        }
        // 4th agree vote after quorum already reached — must be ignored (round removed).
        c.onVoteReceived(new SuspicionVote(roundId, obsList.get(3), SUBJECT, true, 0L));

        assertEquals(1, sink.callCount(), "sink must fire exactly once");
    }

    @Test
    void onVoteReceived_quorumDisagree_doesNotInvokeSink() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        Set<NodeAddress> observers = observerSet(4);
        var overlay = overlayWithObservers(SUBJECT, observers);
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);
        long roundId = c.startRound(SUBJECT, 5L, 0L);

        // With 4 observers and 75% quorum, 3 agrees are needed. 2 disagrees leave
        // max-possible agrees = 2 < 3 ⇒ QUORUM_DISAGREE immediately.
        List<NodeAddress> obsList = new ArrayList<>(observers);
        c.onVoteReceived(new SuspicionVote(roundId, obsList.get(0), SUBJECT, false, 0L));
        c.onVoteReceived(new SuspicionVote(roundId, obsList.get(1), SUBJECT, false, 0L));

        assertEquals(0, sink.callCount(), "sink must not fire on disagree");

        // Subsequent votes should be dropped as the round is removed.
        c.onVoteReceived(new SuspicionVote(roundId, obsList.get(2), SUBJECT, true, 0L));
        c.onVoteReceived(new SuspicionVote(roundId, obsList.get(3), SUBJECT, true, 0L));
        assertEquals(0, sink.callCount(), "sink still must not fire after round removal");
    }

    @Test
    void onVoteReceived_sinkInvokedOutsideLock() throws Exception {
        // Adversarial sink that re-enters the coordinator via onRefutationReceived. If the
        // coordinator held its lock during sink invocation, re-entry from the sink would
        // deadlock (or at best would mask a design flaw).
        var tx = new RecordingTransport();
        var clock = new MutableClock(0L);
        Set<NodeAddress> observers = observerSet(4);
        var overlay = overlayWithObservers(SUBJECT, observers);

        AtomicBoolean reentered = new AtomicBoolean(false);
        final ConsensusCoordinator[] holder = new ConsensusCoordinator[1];
        ConsensusCoordinator.ViewChangeSink sink = (subject, epoch) -> {
            // Re-enter the coordinator's public API from within the sink callback.
            // If the coordinator holds its lock during this call, the attempt will block
            // forever — the test's @Timeout will trip.
            holder[0].onRefutationReceived(new AliveRefutation(SUBJECT, 1L, 0L));
            reentered.set(true);
        };
        var c = new ConsensusCoordinator(LOCAL, tx, overlay, clock, Duration.ofMillis(1000), 75,
                sink);
        holder[0] = c;

        long roundId = c.startRound(SUBJECT, 0L, 0L);
        int agreed = 0;
        for (NodeAddress obs : observers) {
            if (agreed < 3) {
                c.onVoteReceived(new SuspicionVote(roundId, obs, SUBJECT, true, 0L));
                agreed++;
            }
        }
        assertTrue(reentered.get(), "sink must be invoked without holding the coordinator lock");
    }

    // ---------- onRefutationReceived ----------

    @Test
    void onRefutationReceived_cancelsOnlyRoundsForThatSubject() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        NodeAddress s1 = new NodeAddress("s1", "127.0.0.1", 10001);
        NodeAddress s2 = new NodeAddress("s2", "127.0.0.1", 10002);
        NodeAddress s3 = new NodeAddress("s3", "127.0.0.1", 10003);

        var overlay = new ExpanderGraphOverlay();
        Set<NodeAddress> all = new HashSet<>(observerSet(3));
        all.add(s1);
        all.add(s2);
        all.add(s3);
        overlay.rebuild(all, all.size() - 1, 1L);

        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        long r1 = c.startRound(s1, 1L, 0L);
        long r2 = c.startRound(s2, 1L, 0L);
        long r3 = c.startRound(s3, 1L, 0L);

        c.onRefutationReceived(new AliveRefutation(s1, 1L, 1L));

        // The overlay has 6 members total (s1, s2, s3 + 3 observers from observerSet(3)), with
        // degree = 5 (full mesh). Observers of any subject = 5 (all other members). With 75%
        // quorum, 5 × 0.75 ceil = 4 agree votes are required. Building a vote list that includes
        // every other member (except the subject itself) of size 4 exercises the quorum math.
        List<NodeAddress> s1Voters = new ArrayList<>();
        s1Voters.add(s2);
        s1Voters.add(s3);
        s1Voters.addAll(observerSet(3));
        // Feeding enough agree votes to r1 must NOT fire the sink (round cancelled by refutation).
        for (NodeAddress v : s1Voters) {
            c.onVoteReceived(new SuspicionVote(r1, v, s1, true, 0L));
        }
        assertEquals(0, sink.callCount(), "r1 cancelled → sink must not fire for s1");

        // r2 still intact: driving r2 past its 4-agree quorum fires the sink once.
        List<NodeAddress> s2Voters = new ArrayList<>();
        s2Voters.add(s1);
        s2Voters.add(s3);
        s2Voters.addAll(observerSet(3));
        for (NodeAddress v : s2Voters) {
            c.onVoteReceived(new SuspicionVote(r2, v, s2, true, 0L));
        }
        assertEquals(1, sink.callCount(), "r2 must still fire after r1 cancelled");
        assertEquals(s2, sink.applySubjects.get(0));
        // r3 should have been removed after r2 fired (it is independent). Use it for completeness.
        assertTrue(r3 >= 0);
    }

    @Test
    void onRefutationReceived_noActiveRounds_silent() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        var overlay = new ExpanderGraphOverlay();
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        // Must not throw.
        c.onRefutationReceived(new AliveRefutation(SUBJECT, 1L, 0L));
        assertEquals(0, sink.callCount());
    }

    // ---------- tick ----------

    @Test
    void tick_expiresTimedOutRounds() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(100L);
        Set<NodeAddress> observers = observerSet(4);
        var overlay = overlayWithObservers(SUBJECT, observers);
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(50), 75);

        long roundId = c.startRound(SUBJECT, 1L, 0L);

        // Past deadline: start=100, timeout=50, expire when now > 150.
        clock.advance(Duration.ofMillis(200));
        c.tick();

        // Subsequent votes must be dropped — round no longer active.
        int agreed = 0;
        for (NodeAddress obs : observers) {
            if (agreed < 3) {
                c.onVoteReceived(new SuspicionVote(roundId, obs, SUBJECT, true, 0L));
                agreed++;
            }
        }
        assertEquals(0, sink.callCount(), "expired round must not trigger sink");
    }

    @Test
    void tick_doesNotExpireUnexpiredRounds() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(100L);
        Set<NodeAddress> observers = observerSet(4);
        var overlay = overlayWithObservers(SUBJECT, observers);
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(500), 75);

        long roundId = c.startRound(SUBJECT, 1L, 0L);
        // Advance only slightly — within timeout window.
        clock.advance(Duration.ofMillis(50));
        c.tick();

        int agreed = 0;
        for (NodeAddress obs : observers) {
            if (agreed < 3) {
                c.onVoteReceived(new SuspicionVote(roundId, obs, SUBJECT, true, 0L));
                agreed++;
            }
        }
        assertEquals(1, sink.callCount(), "unexpired round must still be active");
    }

    // ---------- onViewChanged ----------

    @Test
    void onViewChanged_cancelsRoundsWhoseSubjectIsGone() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        Set<NodeAddress> observers = observerSet(4);
        var overlay = overlayWithObservers(SUBJECT, observers);
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        long roundId = c.startRound(SUBJECT, 1L, 0L);

        // New view that does NOT include SUBJECT.
        Set<Member> newMembers = aliveMembers(LOCAL, PROPOSER);
        c.onViewChanged(new MembershipView(2L, newMembers, Instant.EPOCH));

        // Feeding quorum votes must NOT fire the sink — round cancelled by view change.
        int agreed = 0;
        for (NodeAddress obs : observers) {
            if (agreed < 3) {
                c.onVoteReceived(new SuspicionVote(roundId, obs, SUBJECT, true, 0L));
                agreed++;
            }
        }
        assertEquals(0, sink.callCount(),
                "round cancelled on subject departure must not trigger sink");
    }

    @Test
    void onViewChanged_preservesRoundsWhoseSubjectIsStillAlive() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        Set<NodeAddress> observers = observerSet(4);
        var overlay = overlayWithObservers(SUBJECT, observers);
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        long roundId = c.startRound(SUBJECT, 1L, 0L);

        Set<Member> newMembers = aliveMembers(LOCAL, SUBJECT, PROPOSER);
        c.onViewChanged(new MembershipView(2L, newMembers, Instant.EPOCH));

        int agreed = 0;
        for (NodeAddress obs : observers) {
            if (agreed < 3) {
                c.onVoteReceived(new SuspicionVote(roundId, obs, SUBJECT, true, 0L));
                agreed++;
            }
        }
        assertEquals(1, sink.callCount(),
                "round must survive a view change that keeps subject ALIVE");
    }

    @Test
    void onViewChanged_subjectMarkedDead_cancelsRound() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        Set<NodeAddress> observers = observerSet(4);
        var overlay = overlayWithObservers(SUBJECT, observers);
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        long roundId = c.startRound(SUBJECT, 1L, 0L);

        Set<Member> newMembers = new HashSet<>();
        newMembers.add(new Member(LOCAL, MemberState.ALIVE, 0));
        newMembers.add(new Member(SUBJECT, MemberState.DEAD, 0));
        c.onViewChanged(new MembershipView(2L, newMembers, Instant.EPOCH));

        int agreed = 0;
        for (NodeAddress obs : observers) {
            if (agreed < 3) {
                c.onVoteReceived(new SuspicionVote(roundId, obs, SUBJECT, true, 0L));
                agreed++;
            }
        }
        assertEquals(0, sink.callCount(),
                "subject marked DEAD → round cancelled; sink must not fire");
    }

    // ---------- integration scenarios ----------

    @Test
    void endToEnd_suspicionReachesQuorumTriggersApplySuspicion() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        Set<NodeAddress> observers = observerSet(5);
        var overlay = overlayWithObservers(SUBJECT, observers);
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 60);

        long roundId = c.startRound(SUBJECT, 13L, 7L);
        // 5 × 60% = 3 agrees required.
        int agreed = 0;
        for (NodeAddress obs : observers) {
            c.onVoteReceived(new SuspicionVote(roundId, obs, SUBJECT, true, 0L));
            agreed++;
            if (agreed == 3) {
                break;
            }
        }

        assertEquals(1, sink.callCount());
        assertEquals(SUBJECT, sink.applySubjects.get(0));
        assertEquals(13L, sink.applyEpochs.get(0)[0]);
    }

    @Test
    void endToEnd_selfRefutationCancelsProposerSideRound() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        Set<NodeAddress> observers = observerSet(4);
        var overlay = overlayWithObservers(SUBJECT, observers);
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(1000), 75);

        long roundId = c.startRound(SUBJECT, 1L, 0L);
        // Subject refutes itself.
        c.onRefutationReceived(new AliveRefutation(SUBJECT, 1L, 1L));

        int agreed = 0;
        for (NodeAddress obs : observers) {
            if (agreed < 3) {
                c.onVoteReceived(new SuspicionVote(roundId, obs, SUBJECT, true, 0L));
                agreed++;
            }
        }
        assertEquals(0, sink.callCount(),
                "refutation must cancel round on proposer side before quorum");
    }

    @Test
    void endToEnd_observerDepartureDoesNotImmediatelyTrigger() {
        var tx = new RecordingTransport();
        var sink = new RecordingSink();
        var clock = new MutableClock(0L);
        Set<NodeAddress> observers = observerSet(4);
        var overlay = overlayWithObservers(SUBJECT, observers);
        var c = newCoordinator(tx, sink, clock, overlay, Duration.ofMillis(100), 75);

        long roundId = c.startRound(SUBJECT, 1L, 0L);
        // Observer set drifts — all observers depart in new view. Subject still present.
        Set<Member> newMembers = aliveMembers(LOCAL, SUBJECT);
        c.onViewChanged(new MembershipView(2L, newMembers, Instant.EPOCH));

        // No sink invocation from just view change.
        assertEquals(0, sink.callCount(), "observer departures do not auto-trigger applySuspicion");
        // Timeout should expire the round harmlessly.
        clock.advance(Duration.ofMillis(500));
        c.tick();
        assertEquals(0, sink.callCount(),
                "expiry of round whose observers departed must not call sink");

        // Late vote must be dropped.
        c.onVoteReceived(new SuspicionVote(roundId, observer(0), SUBJECT, true, 0L));
        assertEquals(0, sink.callCount());
    }

    @Test
    void onViewChanged_nullView_throwsNpe() {
        var c = new ConsensusCoordinator(LOCAL, new RecordingTransport(),
                new ExpanderGraphOverlay(), Clock.systemUTC(), Duration.ofMillis(100), 75,
                new RecordingSink());
        assertThrows(NullPointerException.class, () -> c.onViewChanged(null));
    }

    @Test
    void onProposalReceived_afterClose_throwsIllegalStateException() {
        var c = new ConsensusCoordinator(LOCAL, new RecordingTransport(),
                new ExpanderGraphOverlay(), Clock.systemUTC(), Duration.ofMillis(100), 75,
                new RecordingSink());
        c.close();
        assertThrows(IllegalStateException.class,
                () -> c.onProposalReceived(new SuspicionProposal(SUBJECT, 0L, 1L, 0L, PROPOSER),
                        addr -> true));
    }
}
