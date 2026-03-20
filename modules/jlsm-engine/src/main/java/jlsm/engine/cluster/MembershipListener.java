package jlsm.engine.cluster;

/**
 * Listener for cluster membership events.
 *
 * <p>
 * Contract: Receives callbacks when the cluster membership changes. All callbacks are
 * invoked on the membership protocol's internal thread; implementations must not block.
 *
 * <p>
 * Governed by: {@code .decisions/cluster-membership-protocol/adr.md}
 */
public interface MembershipListener {

    /**
     * Called when the membership view changes (new epoch).
     *
     * @param oldView the previous membership view; never null
     * @param newView the new membership view; never null
     */
    void onViewChanged(MembershipView oldView, MembershipView newView);

    /**
     * Called when a new member joins the cluster.
     *
     * @param member the member that joined; never null
     */
    void onMemberJoined(Member member);

    /**
     * Called when a member leaves the cluster (graceful or after confirmed failure).
     *
     * @param member the member that left; never null
     */
    void onMemberLeft(Member member);

    /**
     * Called when a member is suspected of failure but not yet confirmed.
     *
     * @param member the suspected member; never null
     */
    void onMemberSuspected(Member member);
}
