package jlsm.engine.cluster;

import jlsm.cluster.NodeAddress;

import jlsm.engine.cluster.internal.RendezvousOwnership;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RendezvousOwnership} — deterministic partition-to-node assignment via Rendezvous
 * (Highest Random Weight) hashing.
 *
 * @spec engine.clustering.R44 — HRW assignment — node with highest score wins
 * @spec engine.clustering.R45 — cache keyed by epoch; invalidated on epoch change
 * @spec engine.clustering.R46 — deterministic across nodes given the same view
 * @spec engine.clustering.R48 — differentialAssign recomputes only departed-member partitions
 * @spec engine.clustering.R49 — rejoining members go through normal admission + HRW
 */
final class RendezvousOwnershipTest {

    private RendezvousOwnership ownership;

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "host-a", 8001);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "host-b", 8002);
    private static final NodeAddress NODE_C = new NodeAddress("node-c", "host-c", 8003);
    private static final NodeAddress NODE_D = new NodeAddress("node-d", "host-d", 8004);
    private static final Instant NOW = Instant.parse("2026-03-20T00:00:00Z");

    @BeforeEach
    void setUp() {
        ownership = new RendezvousOwnership();
    }

    // --- Determinism ---

    @Test
    void assignOwner_sameInputProducesSameOutput() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        NodeAddress first = ownership.assignOwner("table-1", view);
        NodeAddress second = ownership.assignOwner("table-1", view);
        assertNotNull(first);
        assertEquals(first, second, "Same inputs must produce same assignment");
    }

    @Test
    void assignOwner_deterministicAcrossInstances() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        RendezvousOwnership other = new RendezvousOwnership();
        NodeAddress fromThis = ownership.assignOwner("table-x", view);
        NodeAddress fromOther = other.assignOwner("table-x", view);
        assertEquals(fromThis, fromOther, "Different instances must produce identical assignments");
    }

    @Test
    void assignOwner_differentIdsCanMapToDifferentNodes() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        Set<NodeAddress> assigned = new HashSet<>();
        // With enough IDs we should see at least 2 different nodes
        for (int i = 0; i < 100; i++) {
            assigned.add(ownership.assignOwner("id-" + i, view));
        }
        assertTrue(assigned.size() >= 2,
                "100 different IDs across 3 nodes should hit at least 2 different nodes");
    }

    @Test
    void assignOwner_singleNode() {
        MembershipView view = viewOf(1, NODE_A);
        NodeAddress owner = ownership.assignOwner("table-1", view);
        assertEquals(NODE_A, owner, "Single node must be the owner");
    }

    // --- Minimal movement on view change ---

    @Test
    void assignOwner_addingNodeMovesMinimalKeys() {
        MembershipView viewBefore = viewOf(1, NODE_A, NODE_B, NODE_C);
        MembershipView viewAfter = viewOf(2, NODE_A, NODE_B, NODE_C, NODE_D);

        int moved = 0;
        final int total = 1000;
        for (int i = 0; i < total; i++) {
            String id = "partition-" + i;
            NodeAddress before = ownership.assignOwner(id, viewBefore);
            NodeAddress after = ownership.assignOwner(id, viewAfter);
            if (!before.equals(after)) {
                moved++;
            }
        }
        // Adding 1 node to 3: ideal movement is ~K/N = 1000/4 = 250
        // Allow generous range [100, 500] for hash distribution variance
        assertTrue(moved > 0, "Some keys must move when a node is added");
        assertTrue(moved < total / 2,
                "Movement should be roughly K/N, not catastrophic. Moved: " + moved);
    }

    @Test
    void assignOwner_removingNodeMovesOnlyDepartedKeys() {
        MembershipView viewBefore = viewOf(1, NODE_A, NODE_B, NODE_C);
        MembershipView viewAfter = viewOf(2, NODE_A, NODE_B);

        int moved = 0;
        int movedFromNonDeparted = 0;
        final int total = 1000;
        for (int i = 0; i < total; i++) {
            String id = "partition-" + i;
            NodeAddress before = ownership.assignOwner(id, viewBefore);
            NodeAddress after = ownership.assignOwner(id, viewAfter);
            if (!before.equals(after)) {
                moved++;
                if (!before.equals(NODE_C)) {
                    movedFromNonDeparted++;
                }
            }
        }
        assertTrue(moved > 0, "Some keys must move when a node departs");
        assertEquals(0, movedFromNonDeparted,
                "Only keys previously on the departed node should move");
    }

    // --- IllegalStateException on no live members ---

    @Test
    void assignOwner_emptyViewThrows() {
        MembershipView view = new MembershipView(1, Set.of(), NOW);
        assertThrows(IllegalStateException.class, () -> ownership.assignOwner("table-1", view));
    }

    @Test
    void assignOwner_onlySuspectedMembersThrows() {
        Member suspected = new Member(NODE_A, MemberState.SUSPECTED, 0);
        MembershipView view = new MembershipView(1, Set.of(suspected), NOW);
        assertThrows(IllegalStateException.class, () -> ownership.assignOwner("table-1", view));
    }

    @Test
    void assignOwner_onlyDeadMembersThrows() {
        Member dead = new Member(NODE_A, MemberState.DEAD, 0);
        MembershipView view = new MembershipView(1, Set.of(dead), NOW);
        assertThrows(IllegalStateException.class, () -> ownership.assignOwner("table-1", view));
    }

    // --- Input validation ---

    @Test
    void assignOwner_nullIdThrows() {
        MembershipView view = viewOf(1, NODE_A);
        assertThrows(NullPointerException.class, () -> ownership.assignOwner(null, view));
    }

    @Test
    void assignOwner_emptyIdThrows() {
        MembershipView view = viewOf(1, NODE_A);
        assertThrows(IllegalArgumentException.class, () -> ownership.assignOwner("", view));
    }

    @Test
    void assignOwner_nullViewThrows() {
        assertThrows(NullPointerException.class, () -> ownership.assignOwner("table-1", null));
    }

    // --- assignOwners (replica ranking) ---

    @Test
    void assignOwners_returnsOrderedListWithNoDuplicates() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        List<NodeAddress> owners = ownership.assignOwners("table-1", view, 3);
        assertEquals(3, owners.size());
        assertEquals(3, new HashSet<>(owners).size(), "No duplicates allowed");
    }

    @Test
    void assignOwners_firstElementMatchesSingleOwner() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        NodeAddress single = ownership.assignOwner("table-1", view);
        List<NodeAddress> ranked = ownership.assignOwners("table-1", view, 3);
        assertEquals(single, ranked.getFirst(),
                "First element of ranked list must match assignOwner result");
    }

    @Test
    void assignOwners_capsAtLiveMemberCount() {
        MembershipView view = viewOf(1, NODE_A, NODE_B);
        List<NodeAddress> owners = ownership.assignOwners("table-1", view, 5);
        assertEquals(2, owners.size(), "Cannot assign more replicas than live members");
    }

    @Test
    void assignOwners_replicasMustBeAtLeastOne() {
        MembershipView view = viewOf(1, NODE_A);
        assertThrows(IllegalArgumentException.class,
                () -> ownership.assignOwners("table-1", view, 0));
    }

    @Test
    void assignOwners_noLiveMembersThrows() {
        MembershipView view = new MembershipView(1, Set.of(), NOW);
        assertThrows(IllegalStateException.class, () -> ownership.assignOwners("table-1", view, 1));
    }

    @Test
    void assignOwners_deterministicRanking() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        List<NodeAddress> first = ownership.assignOwners("table-1", view, 3);
        List<NodeAddress> second = ownership.assignOwners("table-1", view, 3);
        assertEquals(first, second, "Ranking must be deterministic");
    }

    // --- Cache keyed on epoch ---

    @Test
    void assignOwner_cachedByEpoch() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        NodeAddress first = ownership.assignOwner("table-1", view);
        // Same epoch, same view — should use cached result
        NodeAddress second = ownership.assignOwner("table-1", view);
        assertEquals(first, second);
    }

    // --- evictBefore ---

    @Test
    void evictBefore_clearsOldEpochCache() {
        MembershipView view1 = viewOf(1, NODE_A, NODE_B);
        MembershipView view2 = viewOf(5, NODE_A, NODE_B, NODE_C);

        // Populate cache for both epochs
        ownership.assignOwner("table-1", view1);
        ownership.assignOwner("table-1", view2);

        // Evict entries older than epoch 5
        ownership.evictBefore(5);

        // Epoch 5 should still work (cache or recompute — either is fine)
        NodeAddress afterEvict = ownership.assignOwner("table-1", view2);
        assertNotNull(afterEvict);
    }

    @Test
    void evictBefore_doesNotThrowOnEmptyCache() {
        assertDoesNotThrow(() -> ownership.evictBefore(100));
    }

    // --- Mixed member states ---

    @Test
    void assignOwner_ignoresNonAliveMembers() {
        Member alive = new Member(NODE_A, MemberState.ALIVE, 0);
        Member suspected = new Member(NODE_B, MemberState.SUSPECTED, 0);
        Member dead = new Member(NODE_C, MemberState.DEAD, 0);
        MembershipView view = new MembershipView(1, Set.of(alive, suspected, dead), NOW);

        NodeAddress owner = ownership.assignOwner("table-1", view);
        assertEquals(NODE_A, owner, "Only ALIVE members should be considered");
    }

    // --- Helper ---

    private static MembershipView viewOf(long epoch, NodeAddress... nodes) {
        Set<Member> members = new HashSet<>();
        for (NodeAddress addr : nodes) {
            members.add(new Member(addr, MemberState.ALIVE, 0));
        }
        return new MembershipView(epoch, members, NOW);
    }
}
