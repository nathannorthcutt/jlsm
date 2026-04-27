package jlsm.engine.cluster.internal;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import jlsm.cluster.NodeAddress;

/**
 * State machine for a single observer-agreement round in the RAPID consensus protocol.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Receives {@code (roundId, subject, observers, quorumPercent, startNanos, timeoutNanos)} at
 * construction; the observer set is a point-in-time snapshot and is NOT mutated by subsequent view
 * changes.</li>
 * <li>Outcome transitions: {@code PENDING → QUORUM_AGREE} when enough agree votes arrive;
 * {@code PENDING → QUORUM_DISAGREE} when remaining observers cannot possibly reach quorum;
 * {@code PENDING → EXPIRED} via {@link #expire()} after the deadline; {@code PENDING → CANCELLED}
 * via {@link #cancel()}.</li>
 * <li>Quorum math:
 * {@code requiredAgree = Math.max(1, Math.ceilDiv(observers.size() * quorumPercent, 100))}. The
 * floor of 1 is required so an empty observer set cannot satisfy quorum with zero votes (which
 * would be an immediate QUORUM_AGREE without a valid voter). An empty observer set therefore stays
 * {@code PENDING} until {@link #expire()} or {@link #cancel()} is invoked.</li>
 * <li>Voter dedup: a voter's second vote on the same round is dropped; the first vote wins.</li>
 * <li>Non-observer votes are silently dropped — the round remains in its current state without
 * mutating vote sets.</li>
 * <li>Terminal states are sticky — once the outcome leaves {@code PENDING}, further votes,
 * expirations, and cancellations do not mutate it.</li>
 * <li>Thread-safety: {@link ReentrantLock}; {@link #recordVote}, {@link #expire}, {@link #cancel},
 * {@link #currentOutcome} and {@link #agreeVoters} all serialize state access through the lock.
 * {@link #isExpired(long)} is lock-free (reads final fields + volatile outcome).</li>
 * <li>Validation: null {@code subject}/{@code observers} → {@link NullPointerException}; negative
 * {@code roundId}/{@code timeoutNanos} or {@code quorumPercent} outside {@code [1, 100]} →
 * {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * <p>
 * Governed by: F04.R34, R36, R37 and .decisions/cluster-membership-protocol/adr.md.
 */
public final class ConsensusRound {

    public enum Outcome {
        PENDING, QUORUM_AGREE, QUORUM_DISAGREE, EXPIRED, CANCELLED
    }

    private final long roundId;
    private final NodeAddress subject;
    private final Set<NodeAddress> observers;
    private final int requiredAgree;
    private final long startNanos;
    private final long timeoutNanos;

    private final ReentrantLock lock = new ReentrantLock();
    private final Set<NodeAddress> agreeVoters = new HashSet<>();
    private final Set<NodeAddress> disagreeVoters = new HashSet<>();

    /**
     * Outcome is read in {@link #isExpired(long)} without holding the lock; using {@code volatile}
     * ensures the non-PENDING check in the predicate observes recent transitions even though the
     * predicate itself does not mutate state.
     */
    private volatile Outcome outcome = Outcome.PENDING;

    public ConsensusRound(long roundId, NodeAddress subject, Set<NodeAddress> observers,
            int quorumPercent, long startNanos, long timeoutNanos) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(observers, "observers must not be null");
        if (roundId < 0L) {
            throw new IllegalArgumentException("roundId must be non-negative, got: " + roundId);
        }
        if (quorumPercent < 1 || quorumPercent > 100) {
            throw new IllegalArgumentException(
                    "quorumPercent must be in [1, 100], got: " + quorumPercent);
        }
        if (timeoutNanos < 0L) {
            throw new IllegalArgumentException(
                    "timeoutNanos must be non-negative, got: " + timeoutNanos);
        }

        this.roundId = roundId;
        this.subject = subject;
        this.observers = Set.copyOf(observers);
        this.startNanos = startNanos;
        this.timeoutNanos = timeoutNanos;
        // Floor of 1: an empty observer set would otherwise have requiredAgree == 0, triggering
        // an immediate QUORUM_AGREE on construction without any actual voter participation.
        this.requiredAgree = Math.max(1, Math.ceilDiv(this.observers.size() * quorumPercent, 100));
    }

    public long roundId() {
        return roundId;
    }

    public NodeAddress subject() {
        return subject;
    }

    /**
     * Record a vote; returns the new outcome after this vote is applied. Votes from voters not in
     * the observer set are silently dropped. Duplicate votes from the same voter are dedup'd — only
     * the first vote counts. Terminal outcomes are sticky.
     */
    public Outcome recordVote(NodeAddress voter, boolean agree) {
        Objects.requireNonNull(voter, "voter must not be null");
        lock.lock();
        try {
            if (outcome != Outcome.PENDING) {
                return outcome;
            }
            if (!observers.contains(voter)) {
                // Silently drop — the caller may be replaying a stale vote from a pre-rebuild view.
                return outcome;
            }
            if (agreeVoters.contains(voter) || disagreeVoters.contains(voter)) {
                // Dedup: first vote wins; subsequent flips are ignored for auditability.
                return outcome;
            }
            if (agree) {
                agreeVoters.add(voter);
            } else {
                disagreeVoters.add(voter);
            }

            if (agreeVoters.size() >= requiredAgree) {
                outcome = Outcome.QUORUM_AGREE;
                return outcome;
            }
            // Compute the maximum possible future agree count: current agrees plus any remaining
            // observers who have not yet voted. If that ceiling is still below requiredAgree, the
            // round can never reach quorum — transition to QUORUM_DISAGREE now.
            final int remaining = observers.size() - agreeVoters.size() - disagreeVoters.size();
            final int maxPossibleAgree = agreeVoters.size() + remaining;
            assert maxPossibleAgree >= 0 : "maxPossibleAgree must be non-negative";
            if (maxPossibleAgree < requiredAgree) {
                outcome = Outcome.QUORUM_DISAGREE;
                return outcome;
            }
            return outcome;
        } finally {
            lock.unlock();
        }
    }

    /** Returns current outcome without mutation. */
    public Outcome currentOutcome() {
        lock.lock();
        try {
            return outcome;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Predicate-only expiry check: returns true iff
     * {@code monotonicNow > startNanos + timeoutNanos} AND the round is still {@code PENDING}. Does
     * NOT mutate state — callers that want the sticky {@code EXPIRED} outcome should invoke
     * {@link #expire()}.
     */
    public boolean isExpired(long monotonicNow) {
        return outcome == Outcome.PENDING && monotonicNow > startNanos + timeoutNanos;
    }

    /**
     * Transition {@code PENDING → EXPIRED} and return the resulting outcome. Idempotent for
     * non-{@code PENDING} states — returns the existing sticky terminal outcome unchanged.
     */
    public Outcome expire() {
        lock.lock();
        try {
            if (outcome == Outcome.PENDING) {
                outcome = Outcome.EXPIRED;
            }
            return outcome;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cancel the round (e.g. on self-refutation). Idempotent — on an already-terminal round,
     * returns the existing sticky outcome instead of {@code CANCELLED}.
     */
    public Outcome cancel() {
        lock.lock();
        try {
            if (outcome == Outcome.PENDING) {
                outcome = Outcome.CANCELLED;
            }
            return outcome;
        } finally {
            lock.unlock();
        }
    }

    /** Snapshot of voters who agreed — for telemetry/test inspection. */
    public Set<NodeAddress> agreeVoters() {
        lock.lock();
        try {
            return Set.copyOf(agreeVoters);
        } finally {
            lock.unlock();
        }
    }
}
