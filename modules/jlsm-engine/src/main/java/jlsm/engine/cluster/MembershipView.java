package jlsm.engine.cluster;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable snapshot of the cluster membership at a specific epoch.
 *
 * <p>
 * Contract: Captures the set of known members, the view epoch (monotonically increasing), and the
 * timestamp when this view was created. Provides convenience methods for querying membership state.
 * Comparable by epoch for ordering views chronologically.
 *
 * <p>
 * Governed by: {@code .decisions/cluster-membership-protocol/adr.md}
 */
public final class MembershipView implements Comparable<MembershipView> {

    private final long epoch;
    private final Set<Member> members;
    private final Instant timestamp;

    /**
     * Creates a new membership view.
     *
     * @param epoch the view epoch; must be non-negative
     * @param members the set of members in this view; must not be null
     * @param timestamp the instant this view was created; must not be null
     */
    public MembershipView(long epoch, Set<Member> members, Instant timestamp) {
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be non-negative, got: " + epoch);
        }
        Objects.requireNonNull(members, "members must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        this.epoch = epoch;
        this.members = Set.copyOf(members);
        this.timestamp = timestamp;
    }

    /**
     * Returns the view epoch.
     *
     * @return the epoch; always non-negative
     */
    public long epoch() {
        return epoch;
    }

    /**
     * Returns the immutable set of members in this view.
     *
     * @return the members; never null
     */
    public Set<Member> members() {
        return members;
    }

    /**
     * Returns the timestamp when this view was created.
     *
     * @return the timestamp; never null
     */
    public Instant timestamp() {
        return timestamp;
    }

    /**
     * Returns the number of members in {@link MemberState#ALIVE} state.
     *
     * @return the count of live members
     */
    public int liveMemberCount() {
        int count = 0;
        for (Member m : members) {
            if (m.state() == MemberState.ALIVE) {
                count++;
            }
        }
        return count;
    }

    // @spec engine.clustering.R17,R82 — current member = ALIVE or SUSPECTED; DEAD is a departed non-member
    /**
     * Returns whether the given address is a current member (ALIVE or SUSPECTED) of this view.
     *
     * <p>
     * DEAD members are treated as departed and are not reported as current. Use
     * {@link #isKnown(NodeAddress)} to check whether the view has any record of the address in any
     * state (including DEAD).
     *
     * @param address the node address to check; must not be null
     * @return {@code true} if the address is present and in ALIVE or SUSPECTED state
     */
    public boolean isMember(NodeAddress address) {
        Objects.requireNonNull(address, "address must not be null");
        for (Member m : members) {
            if (m.address().equals(address) && m.state() != MemberState.DEAD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the given address is known to this view in any state, including DEAD. This is
     * distinct from {@link #isMember(NodeAddress)}: a DEAD record signals a departed node that has
     * not yet been reaped from the view.
     *
     * @param address the node address to check; must not be null
     * @return {@code true} if the address appears in any member record
     */
    public boolean isKnown(NodeAddress address) {
        Objects.requireNonNull(address, "address must not be null");
        for (Member m : members) {
            if (m.address().equals(address)) {
                return true;
            }
        }
        return false;
    }

    // @spec engine.clustering.R16 — quorum excludes DEAD from the denominator (count ALIVE + SUSPECTED only)
    /**
     * Returns whether the live members form a quorum at the given percentage threshold.
     *
     * <p>
     * Quorum is computed as live (ALIVE) members against total known members (ALIVE plus
     * SUSPECTED). DEAD members are excluded from the denominator because they represent confirmed
     * departures that must not keep the cluster in a minority state.
     *
     * @param quorumPercent the required percentage of live members; must be in [1, 100]
     * @return {@code true} if the live member count meets or exceeds the quorum threshold
     */
    public boolean hasQuorum(int quorumPercent) {
        if (quorumPercent < 1 || quorumPercent > 100) {
            throw new IllegalArgumentException(
                    "quorumPercent must be in [1, 100], got: " + quorumPercent);
        }
        int liveCount = 0;
        int totalCount = 0;
        for (Member m : members) {
            if (m.state() == MemberState.DEAD) {
                continue;
            }
            totalCount++;
            if (m.state() == MemberState.ALIVE) {
                liveCount++;
            }
        }
        if (totalCount == 0) {
            return false;
        }
        return (liveCount * 100) >= (quorumPercent * totalCount);
    }

    @Override
    public int compareTo(MembershipView other) {
        return Long.compare(this.epoch, other.epoch);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof MembershipView that))
            return false;
        return epoch == that.epoch && members.equals(that.members)
                && timestamp.equals(that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(epoch, members, timestamp);
    }

    @Override
    public String toString() {
        return "MembershipView[epoch=" + epoch + ", members=" + members.size() + ", timestamp="
                + timestamp + "]";
    }
}
