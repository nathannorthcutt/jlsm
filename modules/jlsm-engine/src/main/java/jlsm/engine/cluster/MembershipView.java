package jlsm.engine.cluster;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable snapshot of the cluster membership at a specific epoch.
 *
 * <p>
 * Contract: Captures the set of known members, the view epoch (monotonically increasing),
 * and the timestamp when this view was created. Provides convenience methods for querying
 * membership state. Comparable by epoch for ordering views chronologically.
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
     * @param epoch     the view epoch; must be non-negative
     * @param members   the set of members in this view; must not be null
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

    /**
     * Returns whether the given address is a member of this view (in any state).
     *
     * @param address the node address to check; must not be null
     * @return {@code true} if the address is present in this view
     */
    public boolean isMember(NodeAddress address) {
        Objects.requireNonNull(address, "address must not be null");
        for (Member m : members) {
            if (m.address().equals(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the live members form a quorum at the given percentage threshold.
     *
     * @param quorumPercent the required percentage of live members; must be in [1, 100]
     * @return {@code true} if the live member count meets or exceeds the quorum threshold
     */
    public boolean hasQuorum(int quorumPercent) {
        if (quorumPercent < 1 || quorumPercent > 100) {
            throw new IllegalArgumentException(
                    "quorumPercent must be in [1, 100], got: " + quorumPercent);
        }
        if (members.isEmpty()) {
            return false;
        }
        final int liveCount = liveMemberCount();
        final int totalCount = members.size();
        return (liveCount * 100) >= (quorumPercent * totalCount);
    }

    @Override
    public int compareTo(MembershipView other) {
        return Long.compare(this.epoch, other.epoch);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MembershipView that)) return false;
        return epoch == that.epoch && members.equals(that.members)
                && timestamp.equals(that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(epoch, members, timestamp);
    }

    @Override
    public String toString() {
        return "MembershipView[epoch=" + epoch + ", members=" + members.size()
                + ", timestamp=" + timestamp + "]";
    }
}
