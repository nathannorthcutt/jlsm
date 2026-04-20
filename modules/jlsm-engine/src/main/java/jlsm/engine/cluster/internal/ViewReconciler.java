package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.Member;
import jlsm.engine.cluster.MemberState;
import jlsm.engine.cluster.MembershipView;
import jlsm.engine.cluster.NodeAddress;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure utility for reconciling two {@link MembershipView} snapshots into a single consistent view.
 *
 * <p>
 * Contract: Reconciles two views by applying per-member rules — for each address present in either
 * view, the member with the higher incarnation number wins; on equal incarnations, severity order
 * {@code DEAD > SUSPECTED > ALIVE} wins. The resulting view adopts the maximum epoch of the two
 * inputs and a merged member set. Both inputs must be non-null. The function is pure — no side
 * effects, no shared state, no I/O. Thread-safe by virtue of being stateless.
 *
 * <p>
 * Side effects: none.
 *
 * <p>
 * {@code @spec F04.R43}
 */
public final class ViewReconciler {

    private ViewReconciler() {
        throw new AssertionError("no instances");
    }

    /**
     * Reconciles the local view with a proposed view per the per-member rules described in the
     * class contract.
     *
     * @param localView the current local view; must not be null
     * @param proposedView the incoming proposed view; must not be null
     * @return a new {@link MembershipView} with max epoch and merged member set
     */
    public static MembershipView reconcile(MembershipView localView, MembershipView proposedView) {
        Objects.requireNonNull(localView, "localView must not be null");
        Objects.requireNonNull(proposedView, "proposedView must not be null");

        final Map<NodeAddress, Member> byAddress = new HashMap<>();
        for (Member m : localView.members()) {
            byAddress.put(m.address(), m);
        }
        for (Member proposed : proposedView.members()) {
            final Member local = byAddress.get(proposed.address());
            if (local == null) {
                byAddress.put(proposed.address(), proposed);
            } else {
                byAddress.put(proposed.address(), pickWinner(local, proposed));
            }
        }

        final Set<Member> merged = new HashSet<>(byAddress.values());
        final long mergedEpoch = Math.max(localView.epoch(), proposedView.epoch());
        return new MembershipView(mergedEpoch, merged, Instant.now());
    }

    /**
     * Returns the dominant of two {@link Member} records for the same address per the rules: higher
     * incarnation wins; on ties, severity {@code DEAD > SUSPECTED > ALIVE} wins.
     */
    private static Member pickWinner(Member a, Member b) {
        assert a.address().equals(b.address()) : "callers must pass members for the same address";
        if (a.incarnation() > b.incarnation()) {
            return a;
        }
        if (b.incarnation() > a.incarnation()) {
            return b;
        }
        // Equal incarnation — pick by severity rank (DEAD > SUSPECTED > ALIVE).
        return severityRank(a.state()) >= severityRank(b.state()) ? a : b;
    }

    private static int severityRank(MemberState state) {
        return switch (state) {
            case DEAD -> 2;
            case SUSPECTED -> 1;
            case ALIVE -> 0;
        };
    }
}
