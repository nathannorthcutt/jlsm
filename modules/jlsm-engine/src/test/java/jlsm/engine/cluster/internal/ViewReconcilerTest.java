package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.Member;
import jlsm.engine.cluster.MemberState;
import jlsm.engine.cluster.MembershipView;
import jlsm.engine.cluster.NodeAddress;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ViewReconciler} — pure reconciliation of two {@link MembershipView} snapshots.
 *
 * <p>
 * Rules under test ({@code @spec F04.R43}):
 * <ul>
 * <li>higher incarnation wins</li>
 * <li>equal incarnation: DEAD &gt; SUSPECTED &gt; ALIVE</li>
 * <li>resulting epoch = max(local, proposed)</li>
 * <li>addresses appearing in only one view are retained unchanged</li>
 * </ul>
 */
final class ViewReconcilerTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "host-a", 8001);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "host-b", 8002);
    private static final NodeAddress NODE_C = new NodeAddress("node-c", "host-c", 8003);
    private static final Instant NOW = Instant.parse("2026-04-20T00:00:00Z");

    @Test
    void reconcile_nullLocal_throwsNPE() {
        MembershipView proposed = view(1, member(NODE_A, MemberState.ALIVE, 0));
        assertThrows(NullPointerException.class, () -> ViewReconciler.reconcile(null, proposed));
    }

    @Test
    void reconcile_nullProposed_throwsNPE() {
        MembershipView local = view(1, member(NODE_A, MemberState.ALIVE, 0));
        assertThrows(NullPointerException.class, () -> ViewReconciler.reconcile(local, null));
    }

    @Test
    void reconcile_higherIncarnationWins_proposedNewer() {
        MembershipView local = view(1, member(NODE_A, MemberState.DEAD, 5));
        MembershipView proposed = view(1, member(NODE_A, MemberState.ALIVE, 10));
        MembershipView merged = ViewReconciler.reconcile(local, proposed);
        Member a = findMember(merged, NODE_A);
        assertEquals(10L, a.incarnation(), "higher incarnation must win");
        assertEquals(MemberState.ALIVE, a.state(), "winner's state must be adopted");
    }

    @Test
    void reconcile_higherIncarnationWins_localNewer() {
        MembershipView local = view(1, member(NODE_A, MemberState.ALIVE, 10));
        MembershipView proposed = view(2, member(NODE_A, MemberState.DEAD, 5));
        MembershipView merged = ViewReconciler.reconcile(local, proposed);
        Member a = findMember(merged, NODE_A);
        assertEquals(10L, a.incarnation(), "local higher incarnation must win");
        assertEquals(MemberState.ALIVE, a.state(), "winner's state must be adopted");
    }

    @Test
    void reconcile_equalIncarnation_deadBeatsAlive() {
        MembershipView local = view(1, member(NODE_A, MemberState.ALIVE, 7));
        MembershipView proposed = view(1, member(NODE_A, MemberState.DEAD, 7));
        MembershipView merged = ViewReconciler.reconcile(local, proposed);
        Member a = findMember(merged, NODE_A);
        assertEquals(MemberState.DEAD, a.state(), "DEAD must beat ALIVE on equal incarnation");
    }

    @Test
    void reconcile_equalIncarnation_deadBeatsSuspected() {
        MembershipView local = view(1, member(NODE_A, MemberState.SUSPECTED, 3));
        MembershipView proposed = view(1, member(NODE_A, MemberState.DEAD, 3));
        MembershipView merged = ViewReconciler.reconcile(local, proposed);
        Member a = findMember(merged, NODE_A);
        assertEquals(MemberState.DEAD, a.state(), "DEAD must beat SUSPECTED on equal incarnation");
    }

    @Test
    void reconcile_equalIncarnation_suspectedBeatsAlive() {
        MembershipView local = view(1, member(NODE_A, MemberState.ALIVE, 4));
        MembershipView proposed = view(1, member(NODE_A, MemberState.SUSPECTED, 4));
        MembershipView merged = ViewReconciler.reconcile(local, proposed);
        Member a = findMember(merged, NODE_A);
        assertEquals(MemberState.SUSPECTED, a.state(),
                "SUSPECTED must beat ALIVE on equal incarnation");
    }

    @Test
    void reconcile_equalEverything_stateUnchanged() {
        MembershipView local = view(1, member(NODE_A, MemberState.ALIVE, 2));
        MembershipView proposed = view(1, member(NODE_A, MemberState.ALIVE, 2));
        MembershipView merged = ViewReconciler.reconcile(local, proposed);
        Member a = findMember(merged, NODE_A);
        assertEquals(MemberState.ALIVE, a.state());
        assertEquals(2L, a.incarnation());
    }

    @Test
    void reconcile_epochIsMaxOfInputs_proposedHigher() {
        MembershipView local = view(3, member(NODE_A, MemberState.ALIVE, 0));
        MembershipView proposed = view(7, member(NODE_A, MemberState.ALIVE, 1));
        MembershipView merged = ViewReconciler.reconcile(local, proposed);
        assertEquals(7L, merged.epoch(), "merged epoch must be max(local, proposed)");
    }

    @Test
    void reconcile_epochIsMaxOfInputs_localHigher() {
        MembershipView local = view(9, member(NODE_A, MemberState.ALIVE, 0));
        MembershipView proposed = view(4, member(NODE_A, MemberState.ALIVE, 1));
        MembershipView merged = ViewReconciler.reconcile(local, proposed);
        assertEquals(9L, merged.epoch(), "merged epoch must be max(local, proposed)");
    }

    @Test
    void reconcile_mergesAddressesOnlyInLocal() {
        MembershipView local = view(1, member(NODE_A, MemberState.ALIVE, 0),
                member(NODE_B, MemberState.ALIVE, 0));
        MembershipView proposed = view(2, member(NODE_A, MemberState.ALIVE, 0));
        MembershipView merged = ViewReconciler.reconcile(local, proposed);
        assertNotNull(findMember(merged, NODE_A));
        assertNotNull(findMember(merged, NODE_B),
                "member present only in local view must survive reconciliation");
    }

    @Test
    void reconcile_mergesAddressesOnlyInProposed() {
        MembershipView local = view(1, member(NODE_A, MemberState.ALIVE, 0));
        MembershipView proposed = view(2, member(NODE_A, MemberState.ALIVE, 0),
                member(NODE_C, MemberState.ALIVE, 0));
        MembershipView merged = ViewReconciler.reconcile(local, proposed);
        assertNotNull(findMember(merged, NODE_A));
        assertNotNull(findMember(merged, NODE_C),
                "member present only in proposed view must survive reconciliation");
    }

    @Test
    void reconcile_purity_doesNotMutateInputs() {
        MembershipView local = view(1, member(NODE_A, MemberState.ALIVE, 3));
        MembershipView proposed = view(2, member(NODE_A, MemberState.DEAD, 5));
        Set<Member> localMembersBefore = Set.copyOf(local.members());
        Set<Member> proposedMembersBefore = Set.copyOf(proposed.members());
        ViewReconciler.reconcile(local, proposed);
        assertEquals(localMembersBefore, local.members(),
                "reconciliation must not mutate the local view");
        assertEquals(proposedMembersBefore, proposed.members(),
                "reconciliation must not mutate the proposed view");
    }

    @Test
    void reconcile_returnsNewInstance() {
        MembershipView local = view(1, member(NODE_A, MemberState.ALIVE, 0));
        MembershipView proposed = view(1, member(NODE_A, MemberState.ALIVE, 0));
        MembershipView merged = ViewReconciler.reconcile(local, proposed);
        assertNotSame(local, merged);
        assertNotSame(proposed, merged);
    }

    @Test
    void reconcile_multiMember_appliesRulesPerAddress() {
        // A: local=ALIVE@5, proposed=DEAD@10 → DEAD@10 (higher incarnation wins)
        // B: local=SUSPECTED@2, proposed=ALIVE@2 → SUSPECTED@2 (equal inc, SUSPECTED > ALIVE)
        // C: only in local — survives unchanged
        MembershipView local = view(3, member(NODE_A, MemberState.ALIVE, 5),
                member(NODE_B, MemberState.SUSPECTED, 2), member(NODE_C, MemberState.ALIVE, 1));
        MembershipView proposed = view(5, member(NODE_A, MemberState.DEAD, 10),
                member(NODE_B, MemberState.ALIVE, 2));
        MembershipView merged = ViewReconciler.reconcile(local, proposed);
        assertEquals(5L, merged.epoch());
        assertEquals(MemberState.DEAD, findMember(merged, NODE_A).state());
        assertEquals(10L, findMember(merged, NODE_A).incarnation());
        assertEquals(MemberState.SUSPECTED, findMember(merged, NODE_B).state());
        assertEquals(MemberState.ALIVE, findMember(merged, NODE_C).state());
    }

    // --- helpers ---

    private static MembershipView view(long epoch, Member... members) {
        Set<Member> set = new HashSet<>();
        for (Member m : members) {
            set.add(m);
        }
        return new MembershipView(epoch, set, NOW);
    }

    private static Member member(NodeAddress addr, MemberState state, long incarnation) {
        return new Member(addr, state, incarnation);
    }

    private static Member findMember(MembershipView view, NodeAddress addr) {
        for (Member m : view.members()) {
            if (m.address().equals(addr)) {
                return m;
            }
        }
        return null;
    }
}
