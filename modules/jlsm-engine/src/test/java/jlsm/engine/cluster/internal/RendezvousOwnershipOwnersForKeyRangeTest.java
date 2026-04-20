package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.LexicographicPartitionKeySpace;
import jlsm.engine.cluster.Member;
import jlsm.engine.cluster.MemberState;
import jlsm.engine.cluster.MembershipView;
import jlsm.engine.cluster.NodeAddress;
import jlsm.engine.cluster.SinglePartitionKeySpace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for
 * {@link RendezvousOwnership#ownersForKeyRange(String, String, String, MembershipView, jlsm.engine.cluster.PartitionKeySpace)}
 * — owner resolution for partition-pruned scans.
 *
 * <p>
 * Delivers: F04.R63.
 */
final class RendezvousOwnershipOwnersForKeyRangeTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "host-a", 8001);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "host-b", 8002);
    private static final NodeAddress NODE_C = new NodeAddress("node-c", "host-c", 8003);
    private static final Instant NOW = Instant.parse("2026-04-20T00:00:00Z");

    private RendezvousOwnership ownership;

    @BeforeEach
    void setUp() {
        ownership = new RendezvousOwnership();
    }

    // --- Null / empty validation ---

    @Test
    void ownersForKeyRange_nullTableNameThrowsNPE() {
        MembershipView view = viewOf(1, NODE_A);
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("default");
        assertThrows(NullPointerException.class,
                () -> ownership.ownersForKeyRange(null, "a", "z", view, ks));
    }

    @Test
    void ownersForKeyRange_nullFromKeyThrowsNPE() {
        MembershipView view = viewOf(1, NODE_A);
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("default");
        assertThrows(NullPointerException.class,
                () -> ownership.ownersForKeyRange("users", null, "z", view, ks));
    }

    @Test
    void ownersForKeyRange_nullToKeyThrowsNPE() {
        MembershipView view = viewOf(1, NODE_A);
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("default");
        assertThrows(NullPointerException.class,
                () -> ownership.ownersForKeyRange("users", "a", null, view, ks));
    }

    @Test
    void ownersForKeyRange_nullViewThrowsNPE() {
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("default");
        assertThrows(NullPointerException.class,
                () -> ownership.ownersForKeyRange("users", "a", "z", null, ks));
    }

    @Test
    void ownersForKeyRange_nullKeyspaceThrowsNPE() {
        MembershipView view = viewOf(1, NODE_A);
        assertThrows(NullPointerException.class,
                () -> ownership.ownersForKeyRange("users", "a", "z", view, null));
    }

    // --- Single-partition keyspace (backward-compat) ---

    @Test
    void ownersForKeyRange_singlePartition_returnsSingleOwner() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("default");
        Set<NodeAddress> owners = ownership.ownersForKeyRange("users", "a", "z", view, ks);
        assertEquals(1, owners.size(),
                "single-partition keyspace must yield exactly one owner for any range");
    }

    @Test
    void ownersForKeyRange_singlePartition_ownerMatchesAssignOwnerForId() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("default");
        // The partition id passed to HRW must be a stable function of (tableName, partitionId).
        // Discover the convention by asserting the returned owner equals
        // assignOwner("users/default").
        NodeAddress expected = ownership.assignOwner("users/default", view);
        Set<NodeAddress> owners = ownership.ownersForKeyRange("users", "a", "z", view, ks);
        assertTrue(owners.contains(expected),
                "owner set for the sole partition must match assignOwner(tableName + '/' + partitionId)");
    }

    // --- Lexicographic keyspace — pruning ---

    @Test
    void ownersForKeyRange_lexicographic_prunesToIntersectingPartitions() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));

        // Range [a, c) intersects only p0 → at most one owner.
        Set<NodeAddress> prunedOwners = ownership.ownersForKeyRange("users", "a", "c", view, ks);
        Set<NodeAddress> expected = Set.of(ownership.assignOwner("users/p0", view));
        assertEquals(expected, prunedOwners);
    }

    @Test
    void ownersForKeyRange_lexicographic_fullRangeReturnsAllPartitionOwners() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));

        Set<NodeAddress> owners = ownership.ownersForKeyRange("users", "", "\uFFFF", view, ks);
        Set<NodeAddress> expected = new HashSet<>();
        expected.add(ownership.assignOwner("users/p0", view));
        expected.add(ownership.assignOwner("users/p1", view));
        expected.add(ownership.assignOwner("users/p2", view));
        assertEquals(expected, owners, "full-range scan must resolve owners for every partition");
    }

    @Test
    void ownersForKeyRange_lexicographic_nonOverlappingRangeReturnsEmptySet() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        // Degenerate range (toKey == fromKey) must yield an empty owner set.
        Set<NodeAddress> owners = ownership.ownersForKeyRange("users", "r", "r", view, ks);
        assertTrue(owners.isEmpty(),
                "a range that overlaps no partition must produce an empty owner set");
    }

    // --- Determinism ---

    @Test
    void ownersForKeyRange_isDeterministicAcrossInstances() {
        MembershipView view = viewOf(1, NODE_A, NODE_B, NODE_C);
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m"),
                List.of("p0", "p1"));
        RendezvousOwnership other = new RendezvousOwnership();

        Set<NodeAddress> first = ownership.ownersForKeyRange("users", "a", "z", view, ks);
        Set<NodeAddress> second = other.ownersForKeyRange("users", "a", "z", view, ks);
        assertEquals(first, second,
                "two ownership resolvers with the same view/keyspace must agree on owners");
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
