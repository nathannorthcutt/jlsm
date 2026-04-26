package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.Member;
import jlsm.engine.cluster.MemberState;
import jlsm.engine.cluster.MembershipView;
import jlsm.cluster.NodeAddress;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RendezvousOwnership#differentialAssign(MembershipView, MembershipView, Set)} —
 * recompute HRW ownership only for the supplied partition IDs, updating cache entries for the new
 * epoch.
 */
final class RendezvousOwnershipDifferentialTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "host-a", 8001);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "host-b", 8002);
    private static final NodeAddress NODE_C = new NodeAddress("node-c", "host-c", 8003);
    private static final Instant NOW = Instant.parse("2026-04-20T00:00:00Z");

    @Test
    void differentialAssign_nullOldView_throws() {
        RendezvousOwnership ownership = new RendezvousOwnership();
        assertThrows(NullPointerException.class,
                () -> ownership.differentialAssign(null, view(2, NODE_A), Set.of("p1")));
    }

    @Test
    void differentialAssign_nullNewView_throws() {
        RendezvousOwnership ownership = new RendezvousOwnership();
        assertThrows(NullPointerException.class,
                () -> ownership.differentialAssign(view(1, NODE_A), null, Set.of("p1")));
    }

    @Test
    void differentialAssign_nullPartitionSet_throws() {
        RendezvousOwnership ownership = new RendezvousOwnership();
        assertThrows(NullPointerException.class,
                () -> ownership.differentialAssign(view(1, NODE_A), view(2, NODE_A), null));
    }

    @Test
    void differentialAssign_returnsEmptyForEmptyInput() {
        RendezvousOwnership ownership = new RendezvousOwnership();
        Set<String> changed = ownership.differentialAssign(view(1, NODE_A, NODE_B), view(2, NODE_B),
                Set.of());
        assertNotNull(changed);
        assertTrue(changed.isEmpty());
    }

    @Test
    void differentialAssign_reassignsOnlySpecifiedPartitions() {
        RendezvousOwnership ownership = new RendezvousOwnership();
        MembershipView before = view(1, NODE_A, NODE_B, NODE_C);
        MembershipView after = view(2, NODE_A, NODE_B);

        // Prime the cache under the OLD view epoch for a control partition that is NOT in the
        // affected set. After differentialAssign, cache for the unaffected partition must still
        // be present at OLD epoch (untouched).
        NodeAddress oldOwnerOfCtrl = ownership.assignOwner("ctrl-partition", before);

        // Affected partition: force an initial cache entry at the old epoch.
        NodeAddress oldOwnerOfP1 = ownership.assignOwner("p1-affected", before);

        ownership.differentialAssign(before, after, Set.of("p1-affected"));

        // Under the NEW view, p1-affected's cached owner must reflect recomputation (same or
        // different — but must be valid under after's live set).
        NodeAddress newOwnerOfP1 = ownership.assignOwner("p1-affected", after);
        Set<NodeAddress> liveAfter = Set.of(NODE_A, NODE_B);
        assertTrue(liveAfter.contains(newOwnerOfP1),
                "new owner of affected partition must come from after-view live set");

        // Control partition: assignOwner at the old epoch returns the cached value because we
        // did not touch it.
        assertEquals(oldOwnerOfCtrl, ownership.assignOwner("ctrl-partition", before),
                "ctrl-partition must retain its old-epoch cached owner — differentialAssign must only"
                        + " touch affected IDs");

        // Sanity: the pre-recorded owner values are non-null
        assertNotNull(oldOwnerOfP1);
    }

    @Test
    void differentialAssign_returnedSubsetIsChangedPartitions() {
        // Force a case where an affected partition's owner definitely changes: the old owner
        // is dropped entirely in the new view, so assignment must move.
        RendezvousOwnership ownership = new RendezvousOwnership();
        MembershipView before = view(1, NODE_A, NODE_B, NODE_C);
        MembershipView after = view(2, NODE_B); // A and C are gone
        // Find a partition whose old-view owner is NOT NODE_B; it must change under after.
        String pid = null;
        NodeAddress oldOwner = null;
        for (int i = 0; i < 100; i++) {
            String candidate = "pid-" + i;
            NodeAddress owner = ownership.assignOwner(candidate, before);
            if (!owner.equals(NODE_B)) {
                pid = candidate;
                oldOwner = owner;
                break;
            }
        }
        assertNotNull(pid, "must find a partition whose old owner is not the survivor");

        Set<String> changed = ownership.differentialAssign(before, after, Set.of(pid));
        assertEquals(Set.of(pid), changed, "owner changed → partition ID must be in return set");
        NodeAddress newOwner = ownership.assignOwner(pid, after);
        assertNotEquals(oldOwner, newOwner,
                "owner must have changed for this partition under after-view");
        assertEquals(NODE_B, newOwner);
    }

    @Test
    void differentialAssign_unchangedOwners_notInReturn() {
        // A partition whose owner stays the same across views must not appear in the returned set.
        RendezvousOwnership ownership = new RendezvousOwnership();
        MembershipView before = view(1, NODE_A, NODE_B, NODE_C);
        // after-view keeps all live nodes — no owner of any partition should change
        MembershipView after = view(2, NODE_A, NODE_B, NODE_C);

        Set<String> changed = ownership.differentialAssign(before, after, Set.of("p1", "p2", "p3"));
        assertTrue(changed.isEmpty(),
                "partitions whose owners are unchanged must not be reported as changed; got="
                        + changed);
    }

    // --- helper ---

    private static MembershipView view(long epoch, NodeAddress... addrs) {
        Set<Member> members = new HashSet<>();
        for (NodeAddress a : addrs) {
            members.add(new Member(a, MemberState.ALIVE, 0));
        }
        return new MembershipView(epoch, members, NOW);
    }
}
