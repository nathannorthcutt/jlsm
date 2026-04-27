package jlsm.engine.cluster;

import jlsm.cluster.NodeAddress;
import jlsm.cluster.Message;
import jlsm.cluster.MessageType;

import jlsm.engine.cluster.internal.CatalogClusteredTable;

import jlsm.engine.TableMetadata;
import jlsm.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import jlsm.table.TableEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for F04.R63 — partition-key-driven scan pruning in
 * {@link CatalogClusteredTable#scan(String, String)}.
 *
 * <p>
 * The test uses an {@link InJvmTransport} per remote node and counts {@code QUERY_REQUEST} messages
 * received by each node. With a {@link LexicographicPartitionKeySpace} configured, a scan whose
 * range does not overlap a partition must not dispatch a scatter request to the owner of that
 * partition.
 *
 * @spec engine.clustering.R63 — scan fans out only to owners of partitions intersecting key range
 */
final class ClusteredTableScanPruningTest {

    private static final NodeAddress LOCAL = new NodeAddress("local", "localhost", 9401);
    private static final NodeAddress REMOTE_A = new NodeAddress("remote-a", "localhost", 9402);
    private static final NodeAddress REMOTE_B = new NodeAddress("remote-b", "localhost", 9403);
    private static final NodeAddress REMOTE_C = new NodeAddress("remote-c", "localhost", 9404);
    private static final Instant NOW = Instant.parse("2026-04-20T00:00:00Z");
    private static final JlsmSchema SCHEMA = JlsmSchema.builder("users", 1)
            .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
            .build();
    private static final TableMetadata TABLE_META = new TableMetadata("users", SCHEMA, NOW,
            TableMetadata.TableState.READY);

    private InJvmTransport localTransport;
    private Map<NodeAddress, InJvmTransport> remotes;
    private Map<NodeAddress, AtomicInteger> requestCounts;
    private StubMembershipProtocol membership;

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        localTransport = new InJvmTransport(LOCAL);
        remotes = new HashMap<>();
        requestCounts = new HashMap<>();
        for (NodeAddress addr : List.of(REMOTE_A, REMOTE_B, REMOTE_C)) {
            InJvmTransport t = new InJvmTransport(addr);
            remotes.put(addr, t);
            AtomicInteger counter = new AtomicInteger();
            requestCounts.put(addr, counter);
            registerCountingEmptyEntriesHandler(t, addr, counter);
        }
        membership = new StubMembershipProtocol();
        membership.view = new MembershipView(1,
                Set.of(new Member(REMOTE_A, MemberState.ALIVE, 0),
                        new Member(REMOTE_B, MemberState.ALIVE, 0),
                        new Member(REMOTE_C, MemberState.ALIVE, 0)),
                NOW);
    }

    @AfterEach
    void tearDown() {
        localTransport.close();
        for (InJvmTransport t : remotes.values()) {
            t.close();
        }
        InJvmTransport.clearRegistry();
    }

    // --- Backward-compat: SinglePartitionKeySpace fans out to every live node ---

    @Test
    void scan_singlePartitionKeyspace_fansOutToAllLiveNodes() throws IOException {
        // Constructor without a keyspace defaults to SinglePartitionKeySpace. The contract for a
        // single-partition keyspace is that every live member is treated as a replica of the sole
        // partition, so the scan must fan out to every live member — preserving the pre-R63
        // behavior for un-partitioned tables (F04.R62).
        final RendezvousOwnership ownership = new RendezvousOwnership();
        final CatalogClusteredTable table = CatalogClusteredTable.forEngine(TABLE_META,
                localTransport, membership, LOCAL, ownership);
        try {
            final Iterator<TableEntry<String>> iter = table.scan("a", "z");
            drain(iter);
            // Every live remote must have received exactly one QUERY_REQUEST.
            for (Map.Entry<NodeAddress, AtomicInteger> entry : requestCounts.entrySet()) {
                assertEquals(1, entry.getValue().get(),
                        "default single-partition keyspace must scatter to every live member — node "
                                + entry.getKey() + " received " + entry.getValue().get());
            }
        } finally {
            table.close();
        }
    }

    // --- Lexicographic keyspace — pruning ---

    @Test
    void scan_lexicographicKeyspace_prunesNonOverlappingOwners() throws IOException {
        // 3 partitions: p0=[*, m), p1=[m, s), p2=[s, *).
        final LexicographicPartitionKeySpace keyspace = new LexicographicPartitionKeySpace(
                List.of("m", "s"), List.of("p0", "p1", "p2"));
        final RendezvousOwnership ownership = new RendezvousOwnership();
        final CatalogClusteredTable table = newPartitionedTable(ownership, keyspace);

        try {
            // Scan range [a, c) overlaps only p0 — only one owner should be contacted.
            final Iterator<TableEntry<String>> iter = table.scan("a", "c");
            drain(iter);

            NodeAddress p0Owner = ownership.assignOwner("users/p0", membership.view);
            NodeAddress p1Owner = ownership.assignOwner("users/p1", membership.view);
            NodeAddress p2Owner = ownership.assignOwner("users/p2", membership.view);

            // Count scatter requests sent to each remote. Only p0's owner must have received
            // a request; non-overlapping partition owners must have received zero.
            int p0Requests = requestCounts.get(p0Owner).get();
            assertEquals(1, p0Requests,
                    "p0 owner must receive exactly one QUERY_REQUEST for a range covering only p0");

            // If p0Owner == p1Owner (HRW collision, rare but possible), exclude it from
            // the "must be zero" check. Otherwise assert the non-overlapping owners received
            // zero requests.
            if (!p1Owner.equals(p0Owner)) {
                assertEquals(0, requestCounts.get(p1Owner).get(),
                        "p1 owner must not receive a scatter request for a range covering only p0");
            }
            if (!p2Owner.equals(p0Owner) && !p2Owner.equals(p1Owner)) {
                assertEquals(0, requestCounts.get(p2Owner).get(),
                        "p2 owner must not receive a scatter request for a range covering only p0");
            }
        } finally {
            table.close();
        }
    }

    @Test
    void scan_lexicographicKeyspace_fullRangeScattersToAllPartitionOwners() throws IOException {
        final LexicographicPartitionKeySpace keyspace = new LexicographicPartitionKeySpace(
                List.of("m", "s"), List.of("p0", "p1", "p2"));
        final RendezvousOwnership ownership = new RendezvousOwnership();
        final CatalogClusteredTable table = newPartitionedTable(ownership, keyspace);

        try {
            final Iterator<TableEntry<String>> iter = table.scan("", "\uFFFF");
            drain(iter);

            NodeAddress p0Owner = ownership.assignOwner("users/p0", membership.view);
            NodeAddress p1Owner = ownership.assignOwner("users/p1", membership.view);
            NodeAddress p2Owner = ownership.assignOwner("users/p2", membership.view);

            // The set of distinct owners for the three partitions — each must receive at
            // least one QUERY_REQUEST. Two partitions may co-locate on the same owner under HRW,
            // so de-duplicate via a HashSet rather than Set.of (which rejects duplicates).
            Set<NodeAddress> owners = new java.util.HashSet<>();
            owners.add(p0Owner);
            owners.add(p1Owner);
            owners.add(p2Owner);
            for (NodeAddress owner : owners) {
                assertTrue(requestCounts.get(owner).get() >= 1, "owner " + owner
                        + " must receive at least one scatter request for a full range");
            }
            // Non-owners receive zero requests.
            for (Map.Entry<NodeAddress, AtomicInteger> entry : requestCounts.entrySet()) {
                if (!owners.contains(entry.getKey())) {
                    assertEquals(0, entry.getValue().get(),
                            "non-owner " + entry.getKey() + " must not receive scatter requests");
                }
            }
        } finally {
            table.close();
        }
    }

    @Test
    void scan_lexicographicKeyspace_degenerateRangeDispatchesNoRequests() throws IOException {
        final LexicographicPartitionKeySpace keyspace = new LexicographicPartitionKeySpace(
                List.of("m", "s"), List.of("p0", "p1", "p2"));
        final RendezvousOwnership ownership = new RendezvousOwnership();
        final CatalogClusteredTable table = newPartitionedTable(ownership, keyspace);

        try {
            // Empty range — no partition overlaps, so no scatter request must be sent.
            final Iterator<TableEntry<String>> iter = table.scan("r", "r");
            drain(iter);
            int totalRequests = requestCounts.values().stream().mapToInt(AtomicInteger::get).sum();
            assertEquals(0, totalRequests,
                    "empty/degenerate range must not dispatch any scatter request");
            // Iterator is drained; result set is empty.
            assertFalse(iter.hasNext());
        } finally {
            table.close();
        }
    }

    // --- Helpers ---

    private CatalogClusteredTable newPartitionedTable(RendezvousOwnership ownership,
            PartitionKeySpace keyspace) {
        return CatalogClusteredTable.forEngine(TABLE_META, localTransport, membership, LOCAL,
                ownership, null, keyspace);
    }

    private static void drain(Iterator<TableEntry<String>> iter) {
        while (iter.hasNext()) {
            iter.next();
        }
    }

    private static void registerCountingEmptyEntriesHandler(InJvmTransport transport,
            NodeAddress addr, AtomicInteger counter) {
        final ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(0);
        final byte[] payload = buf.array();
        transport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            counter.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new Message(MessageType.QUERY_RESPONSE, addr, msg.sequenceNumber(), payload));
        });
    }

    private static final class StubMembershipProtocol implements MembershipProtocol {
        volatile MembershipView view = new MembershipView(0, Set.of(), NOW);

        @Override
        public void start(List<NodeAddress> seeds) {
        }

        @Override
        public MembershipView currentView() {
            return view;
        }

        @Override
        public void addListener(MembershipListener listener) {
        }

        @Override
        public void leave() {
        }

        @Override
        public void close() {
        }
    }
}
