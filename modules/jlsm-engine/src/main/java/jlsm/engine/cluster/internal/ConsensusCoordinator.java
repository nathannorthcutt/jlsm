package jlsm.engine.cluster.internal;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import jlsm.engine.cluster.ClusterTransport;
import jlsm.engine.cluster.Member;
import jlsm.engine.cluster.MemberState;
import jlsm.engine.cluster.MembershipView;
import jlsm.engine.cluster.Message;
import jlsm.engine.cluster.MessageType;
import jlsm.engine.cluster.NodeAddress;

/**
 * Owns all active consensus rounds for the local node; routes proposals, votes, and refutations
 * between the transport and {@link ConsensusRound} state machines.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Receives {@code (localAddress, transport, overlay, monotonicClock, roundTimeout,
 *       quorumPercent, sink)} at construction.</li>
 * <li>Uses {@link ExpanderGraphOverlay} to compute observer sets; observers for a round are
 * snapshotted at {@link #startRound} time and DO NOT change if the overlay is later rebuilt.</li>
 * <li>Uses the supplied monotonic {@link Clock} for deadline computation (expected to be WD-01's
 * {@code MonotonicClock}).</li>
 * <li>Self-refutation: {@link #onProposalReceived} with a proposal targeting {@code localAddress}
 * bumps an internal self-incarnation and broadcasts an {@link AliveRefutation}; no vote is
 * cast.</li>
 * <li>Remote proposal: {@link #onProposalReceived} evaluates the caller-supplied
 * {@code localPhiAgrees} predicate (the coordinator does not read phi directly) and unicasts a
 * {@link SuspicionVote} back to the proposer.</li>
 * <li>On {@code QUORUM_AGREE}, invokes {@code sink.applySuspicion(subject, epoch)} exactly once per
 * round. The sink is invoked OUTSIDE the coordinator's lock.</li>
 * <li>{@link #onRefutationReceived} cancels any active round targeting the refuter.</li>
 * <li>{@link #tick} expires rounds past their deadline — expired rounds do NOT trigger
 * {@code applySuspicion}.</li>
 * <li>{@link #onViewChanged} updates the locally-cached view snapshot used for self-refutation
 * broadcasting and cancels any in-flight round whose subject has departed or been marked DEAD.
 * In-flight rounds retain their snapshotted observer set.</li>
 * <li>{@link #close()} cancels all active rounds; idempotent.</li>
 * <li>Thread-safety: rounds map guarded by a single {@link ReentrantLock}; callbacks (transport
 * send, sink invocation) run outside the lock.</li>
 * </ul>
 *
 * <p>
 * Time representation: the monotonic {@link Clock} surfaces a millisecond-granularity timebase via
 * {@code clock.millis()}. This value is passed directly to {@link ConsensusRound#isExpired(long)}
 * and recorded as the round's start timebase; the constructor-parameter label on ConsensusRound
 * says "nanos" but the round itself only compares values on the same timebase, so using millis
 * throughout is internally consistent and avoids an unnecessary conversion.
 *
 * <p>
 * Governed by: F04.R34, R36, R37, R38 and .decisions/cluster-membership-protocol/adr.md.
 */
public final class ConsensusCoordinator implements AutoCloseable {

    /** Callback into the enclosing membership subsystem when a round commits. */
    public interface ViewChangeSink {
        /** Called when a round reaches {@code QUORUM_AGREE}. Must not throw. */
        void applySuspicion(NodeAddress subject, long epoch);
    }

    private final NodeAddress localAddress;
    private final ClusterTransport transport;
    private final ExpanderGraphOverlay overlay;
    private final Clock monotonicClock;
    private final long roundTimeoutMillis;
    private final int quorumPercent;
    private final ViewChangeSink sink;

    private final ReentrantLock roundsLock = new ReentrantLock();
    /** Active rounds keyed by roundId. Guarded by {@link #roundsLock}. */
    private final Map<Long, ActiveRound> activeRounds = new HashMap<>();

    private final AtomicLong roundIdGenerator = new AtomicLong(0L);
    private final AtomicLong selfIncarnation = new AtomicLong(0L);
    private final AtomicLong messageSeq = new AtomicLong(0L);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Latest view snapshot, updated via {@link #onViewChanged}. Used to determine the broadcast
     * target set for self-refutation and to validate that in-flight rounds' subjects still exist.
     */
    private volatile MembershipView currentView;

    public ConsensusCoordinator(NodeAddress localAddress, ClusterTransport transport,
            ExpanderGraphOverlay overlay, Clock monotonicClock, Duration roundTimeout,
            int quorumPercent, ViewChangeSink sink) {
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.overlay = Objects.requireNonNull(overlay, "overlay must not be null");
        this.monotonicClock = Objects.requireNonNull(monotonicClock,
                "monotonicClock must not be null");
        Objects.requireNonNull(roundTimeout, "roundTimeout must not be null");
        this.sink = Objects.requireNonNull(sink, "sink must not be null");

        if (quorumPercent < 1 || quorumPercent > 100) {
            throw new IllegalArgumentException(
                    "quorumPercent must be in [1, 100], got: " + quorumPercent);
        }
        if (roundTimeout.isZero() || roundTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "roundTimeout must be positive, got: " + roundTimeout);
        }

        this.quorumPercent = quorumPercent;
        this.roundTimeoutMillis = roundTimeout.toMillis();
    }

    /** Start a new round for {@code suspected}. Returns the roundId. */
    public long startRound(NodeAddress suspected, long epoch, long subjectIncarnation) {
        requireOpen();
        Objects.requireNonNull(suspected, "suspected must not be null");
        if (epoch < 0L) {
            throw new IllegalArgumentException("epoch must be non-negative, got: " + epoch);
        }
        if (subjectIncarnation < 0L) {
            throw new IllegalArgumentException(
                    "subjectIncarnation must be non-negative, got: " + subjectIncarnation);
        }

        // Snapshot observers outside the rounds lock. The overlay has its own lock.
        // Self-edges are impossible per WU-1 (ExpanderGraphOverlay excludes self-loops), so no
        // additional filtering of localAddress is required.
        final Set<NodeAddress> observers = overlay.observersOf(suspected);
        assert observers != null : "observersOf must not return null";

        final long roundId = roundIdGenerator.getAndIncrement();
        final long startTimebase = monotonicClock.millis();
        final ConsensusRound round = new ConsensusRound(roundId, suspected, observers,
                quorumPercent, startTimebase, roundTimeoutMillis);
        final ActiveRound active = new ActiveRound(round, epoch, subjectIncarnation);

        roundsLock.lock();
        try {
            if (closed.get()) {
                throw new IllegalStateException("Coordinator is closed");
            }
            activeRounds.put(roundId, active);
        } finally {
            roundsLock.unlock();
        }

        // Broadcast outside the lock. Errors are best-effort per F04 transport convention.
        final SuspicionProposal proposal = new SuspicionProposal(suspected, subjectIncarnation,
                roundId, epoch, localAddress);
        final byte[] payload = proposal.serialize();
        for (NodeAddress observer : observers) {
            bestEffortSend(observer, payload);
        }
        return roundId;
    }

    /**
     * Called when a SUSPICION_PROPOSAL is received from a peer. If the subject is self, triggers
     * self-refutation instead of voting. Otherwise, evaluates the local phi reading via
     * {@code localPhiAgrees} and responds with a {@link SuspicionVote}.
     */
    public void onProposalReceived(SuspicionProposal proposal,
            Predicate<NodeAddress> localPhiAgrees) {
        requireOpen();
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(localPhiAgrees, "localPhiAgrees must not be null");

        if (proposal.subject().equals(localAddress)) {
            // Self-refutation: bump incarnation and broadcast AliveRefutation. No vote cast.
            final long newIncarnation = selfIncarnation.incrementAndGet();
            final MembershipView view = currentView;
            final long refutationEpoch = view != null ? view.epoch() : proposal.epoch();
            final AliveRefutation refutation = new AliveRefutation(localAddress, newIncarnation,
                    refutationEpoch);
            final byte[] payload = refutation.serialize();

            final List<NodeAddress> targets = broadcastTargetsFor(view, proposal.proposer());
            for (NodeAddress target : targets) {
                bestEffortSend(target, payload);
            }
            return;
        }

        final boolean agree = localPhiAgrees.test(proposal.subject());
        final long voterIncarnation = 0L; // Voter incarnation: tracked by RapidMembership at wire
                                          // time (WU-4). Zero is an acceptable placeholder for
                                          // the coordinator's direct tests.
        final SuspicionVote vote = new SuspicionVote(proposal.roundId(), localAddress,
                proposal.subject(), agree, voterIncarnation);
        bestEffortSend(proposal.proposer(), vote.serialize());
    }

    /** Called when a SUSPICION_VOTE is received. */
    public void onVoteReceived(SuspicionVote vote) {
        requireOpen();
        Objects.requireNonNull(vote, "vote must not be null");

        final NodeAddress subjectToApply;
        final long epochToApply;

        roundsLock.lock();
        try {
            final ActiveRound active = activeRounds.get(vote.roundId());
            if (active == null) {
                // Unknown / expired / cancelled — silently drop.
                return;
            }
            final ConsensusRound.Outcome outcome = active.round().recordVote(vote.voter(),
                    vote.agree());
            if (outcome == ConsensusRound.Outcome.QUORUM_AGREE) {
                // Capture target+epoch before releasing the lock so the sink is invoked outside.
                subjectToApply = active.round().subject();
                epochToApply = active.epoch();
                activeRounds.remove(vote.roundId());
            } else if (outcome == ConsensusRound.Outcome.QUORUM_DISAGREE) {
                activeRounds.remove(vote.roundId());
                return;
            } else {
                return;
            }
        } finally {
            roundsLock.unlock();
        }

        // Sink invoked outside the lock — the sink (RapidMembership) holds its own viewLock.
        assert subjectToApply != null : "subjectToApply must be set when reaching sink dispatch";
        sink.applySuspicion(subjectToApply, epochToApply);
    }

    /**
     * Called when an ALIVE_REFUTATION is received; cancels any active round targeting the refuter.
     */
    public void onRefutationReceived(AliveRefutation refutation) {
        requireOpen();
        Objects.requireNonNull(refutation, "refutation must not be null");

        roundsLock.lock();
        try {
            final Iterator<Map.Entry<Long, ActiveRound>> it = activeRounds.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<Long, ActiveRound> entry = it.next();
                if (entry.getValue().round().subject().equals(refutation.subject())) {
                    entry.getValue().round().cancel();
                    it.remove();
                }
            }
        } finally {
            roundsLock.unlock();
        }
    }

    /** Called periodically (per protocol tick) to expire timed-out rounds. */
    public void tick() {
        requireOpen();
        final long now = monotonicClock.millis();

        roundsLock.lock();
        try {
            final Iterator<Map.Entry<Long, ActiveRound>> it = activeRounds.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<Long, ActiveRound> entry = it.next();
                final ConsensusRound round = entry.getValue().round();
                if (round.isExpired(now)) {
                    round.expire();
                    it.remove();
                }
            }
        } finally {
            roundsLock.unlock();
        }
    }

    /**
     * Called when the membership view changes. Cancels any in-flight round whose subject has
     * departed the view or been marked DEAD; retains rounds for subjects still ALIVE/SUSPECTED.
     * Does NOT mutate the observer set of active rounds — the snapshot taken at {@link #startRound}
     * stands, and any quorum unreachable due to observer departures will expire harmlessly on the
     * next {@link #tick()}.
     */
    public void onViewChanged(MembershipView newView) {
        requireOpen();
        Objects.requireNonNull(newView, "newView must not be null");
        this.currentView = newView;

        roundsLock.lock();
        try {
            final Iterator<Map.Entry<Long, ActiveRound>> it = activeRounds.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<Long, ActiveRound> entry = it.next();
                final NodeAddress subject = entry.getValue().round().subject();
                if (!isSubjectStillAlive(newView, subject)) {
                    entry.getValue().round().cancel();
                    it.remove();
                }
            }
        } finally {
            roundsLock.unlock();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        roundsLock.lock();
        try {
            for (final ActiveRound active : activeRounds.values()) {
                active.round().cancel();
            }
            activeRounds.clear();
        } finally {
            roundsLock.unlock();
        }
    }

    // -------------- private helpers --------------

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Coordinator is closed");
        }
    }

    /**
     * Fire-and-forget send; swallows {@link IOException} to match the best-effort broadcast
     * convention used elsewhere in the cluster subsystem (see
     * {@code RapidMembership.propagateViewChange}).
     */
    private void bestEffortSend(NodeAddress target, byte[] payload) {
        assert target != null : "target must not be null";
        assert payload != null : "payload must not be null";
        final Message msg = new Message(MessageType.VIEW_CHANGE, localAddress,
                messageSeq.getAndIncrement(), payload);
        try {
            transport.send(target, msg);
        } catch (IOException e) {
            // Best-effort: matches RapidMembership.propagateViewChange convention. The failure
            // detector is the mechanism that surfaces unreachable peers.
            assert e != null : "exception should not be null";
        } catch (RuntimeException e) {
            // Defensive: some transport implementations may throw unchecked on closed/invalid
            // state. A coordinator broadcast must not propagate such failures up to the caller
            // (e.g. a scheduler thread) where they would silently disable the protocol loop.
            assert e != null : "exception should not be null";
        }
    }

    /**
     * Computes the set of targets that should receive a self-refutation broadcast. Always includes
     * the original proposer (it must learn of the refutation to cancel its round); additionally
     * includes every ALIVE member of the current view except the local node (so other observers
     * that may have voted AGREE can cancel their downstream rounds).
     */
    private List<NodeAddress> broadcastTargetsFor(MembershipView view, NodeAddress proposer) {
        assert proposer != null : "proposer must not be null";
        final List<NodeAddress> targets = new ArrayList<>();
        // Always include the proposer even if the current view does not know them yet; the
        // proposer is the authoritative cancellation target for this specific round.
        targets.add(proposer);
        if (view == null) {
            return targets;
        }
        for (Member m : view.members()) {
            if (m.state() != MemberState.ALIVE) {
                continue;
            }
            if (m.address().equals(localAddress)) {
                continue;
            }
            if (m.address().equals(proposer)) {
                // Already added explicitly; don't duplicate.
                continue;
            }
            targets.add(m.address());
        }
        return targets;
    }

    /**
     * Returns true iff {@code subject} appears in {@code view} with a state of
     * {@link MemberState#ALIVE} or {@link MemberState#SUSPECTED}. A subject that has been marked
     * DEAD or is no longer present is treated as departed — its round should be cancelled.
     */
    private static boolean isSubjectStillAlive(MembershipView view, NodeAddress subject) {
        assert view != null : "view must not be null";
        assert subject != null : "subject must not be null";
        for (Member m : view.members()) {
            if (m.address().equals(subject)) {
                return m.state() == MemberState.ALIVE || m.state() == MemberState.SUSPECTED;
            }
        }
        return false;
    }

    /** Bundles a {@link ConsensusRound} with the epoch and subjectIncarnation captured at start. */
    private record ActiveRound(ConsensusRound round, long epoch, long subjectIncarnation) {
        ActiveRound {
            assert round != null : "round must not be null";
        }
    }
}
