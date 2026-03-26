package jlsm.engine.cluster;

import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.RemotePartitionClient;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.JlsmDocument;
import jlsm.table.PartitionDescriptor;
import jlsm.table.TableEntry;
import jlsm.table.TableQuery;
import jlsm.table.UpdateMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Partition-aware proxy table that scatters queries across remote partition owners.
 *
 * <p>
 * Contract: Implements {@link Table} transparently for partitioned tables in a cluster. Inspects
 * predicates for partition pruning (O(log P) on range boundaries). Scatters sub-queries
 * concurrently via the cluster transport. Gathers results with a streaming k-way merge iterator.
 * Attaches {@link PartialResultMetadata} when some partitions are unavailable. Write operations
 * route to the single partition owner.
 *
 * <p>
 * Side effects: Sends messages via {@link ClusterTransport} to remote partition owners. May return
 * incomplete results if some owners are unavailable.
 *
 * <p>
 * Governed by: {@code .decisions/scatter-gather-query-execution/adr.md}
 */
public final class ClusteredTable implements Table {

    /** Placeholder low key (lexicographic minimum) for scatter-gather partition descriptors. */
    private static final java.lang.foreign.MemorySegment PLACEHOLDER_LOW = java.lang.foreign.MemorySegment
            .ofArray(new byte[0]);
    /** Placeholder high key (lexicographic maximum) for scatter-gather partition descriptors. */
    private static final java.lang.foreign.MemorySegment PLACEHOLDER_HIGH = java.lang.foreign.MemorySegment
            .ofArray(new byte[]{ (byte) 0xFF });

    private final TableMetadata tableMetadata;
    private final ClusterTransport transport;
    private final MembershipProtocol membership;
    private final NodeAddress localAddress;
    private final RendezvousOwnership ownership = new RendezvousOwnership();
    private volatile PartialResultMetadata lastPartialResult;
    private volatile boolean closed;

    /**
     * Creates a new clustered table proxy.
     *
     * @param tableMetadata the metadata for this table; must not be null
     * @param transport the cluster transport for remote communication; must not be null
     * @param membership the membership protocol for resolving partition owners; must not be null
     * @param localAddress the address of the local node; must not be null
     */
    public ClusteredTable(TableMetadata tableMetadata, ClusterTransport transport,
            MembershipProtocol membership, NodeAddress localAddress) {
        this.tableMetadata = Objects.requireNonNull(tableMetadata,
                "tableMetadata must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.membership = Objects.requireNonNull(membership, "membership must not be null");
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress must not be null");
    }

    @Override
    public void create(String key, JlsmDocument doc) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        checkNotClosed();

        final NodeAddress owner = resolveOwner(key);
        final RemotePartitionClient client = createClient(key, owner);
        try {
            client.create(key, doc);
        } finally {
            client.close();
        }
    }

    @Override
    public Optional<JlsmDocument> get(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();

        final NodeAddress owner = resolveOwner(key);
        final RemotePartitionClient client = createClient(key, owner);
        try {
            return client.get(key);
        } finally {
            client.close();
        }
    }

    @Override
    public void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        checkNotClosed();

        final NodeAddress owner = resolveOwner(key);
        final RemotePartitionClient client = createClient(key, owner);
        try {
            client.update(key, doc, mode);
        } finally {
            client.close();
        }
    }

    @Override
    public void delete(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();

        final NodeAddress owner = resolveOwner(key);
        final RemotePartitionClient client = createClient(key, owner);
        try {
            client.delete(key);
        } finally {
            client.close();
        }
    }

    @Override
    public void insert(JlsmDocument doc) throws IOException {
        Objects.requireNonNull(doc, "doc must not be null");
        checkNotClosed();
        // Insert without an explicit key requires the schema-defined primary key.
        // For now, delegate to the first live node.
        throw new UnsupportedOperationException(
                "insert without explicit key is not yet supported in clustered mode");
    }

    @Override
    public TableQuery<String> query() {
        checkNotClosedUnchecked();
        throw new UnsupportedOperationException(
                "TableQuery is not yet supported in clustered mode; use scan() instead");
    }

    @Override
    public Iterator<TableEntry<String>> scan(String fromKey, String toKey) throws IOException {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        checkNotClosed();

        final MembershipView view = membership.currentView();
        final Set<NodeAddress> liveNodes = collectLiveNodes(view);

        if (liveNodes.isEmpty()) {
            lastPartialResult = new PartialResultMetadata(Set.of("all"), false);
            return Collections.emptyIterator();
        }

        final List<Iterator<TableEntry<String>>> iterators = new ArrayList<>();
        final Set<String> unavailable = new HashSet<>();

        // Scatter: send range query to all live nodes
        for (final NodeAddress node : liveNodes) {
            final RemotePartitionClient client = createClientForNode(node);
            try {
                final Iterator<TableEntry<String>> it = client.getRange(fromKey, toKey);
                iterators.add(it);
            } catch (IOException e) {
                unavailable.add(node.nodeId());
            }
        }

        lastPartialResult = new PartialResultMetadata(Set.copyOf(unavailable),
                unavailable.isEmpty());

        // K-way merge the iterators by key order
        return mergeOrdered(iterators);
    }

    @Override
    public TableMetadata metadata() {
        return tableMetadata;
    }

    /**
     * Returns the partial result metadata from the most recent query, or null if none.
     *
     * @return the partial result metadata, or null if the last operation was complete
     */
    public PartialResultMetadata lastPartialResultMetadata() {
        return lastPartialResult;
    }

    @Override
    public void close() {
        closed = true;
    }

    // ---- Private helpers ----

    /**
     * Resolves the owner node for a given key using rendezvous hashing. Uses the table name as the
     * partition identifier for ownership.
     */
    private NodeAddress resolveOwner(String key) {
        assert key != null : "key must not be null";
        final MembershipView view = membership.currentView();
        final Set<NodeAddress> liveNodes = collectLiveNodes(view);
        if (liveNodes.isEmpty()) {
            throw new IllegalStateException("No live members in the cluster");
        }
        // Use rendezvous hashing to pick the owner from live nodes
        return ownership.assignOwner(tableMetadata.name() + "/" + key, view);
    }

    /**
     * Collects all live node addresses from the current view.
     */
    private Set<NodeAddress> collectLiveNodes(MembershipView view) {
        assert view != null : "view must not be null";
        final Set<NodeAddress> result = new HashSet<>();
        for (final Member m : view.members()) {
            if (m.state() == MemberState.ALIVE) {
                result.add(m.address());
            }
        }
        return result;
    }

    /**
     * Creates a RemotePartitionClient for a key-specific operation routed to the owner.
     */
    private RemotePartitionClient createClient(String key, NodeAddress owner) {
        assert key != null : "key must not be null";
        assert owner != null : "owner must not be null";
        final PartitionDescriptor desc = new PartitionDescriptor(0L, PLACEHOLDER_LOW,
                PLACEHOLDER_HIGH, owner.nodeId(), 0L);
        return new RemotePartitionClient(desc, owner, transport, findLocalAddress());
    }

    /**
     * Creates a RemotePartitionClient for a specific target node (used in scatter).
     */
    private RemotePartitionClient createClientForNode(NodeAddress target) {
        assert target != null : "target must not be null";
        final PartitionDescriptor desc = new PartitionDescriptor(0L, PLACEHOLDER_LOW,
                PLACEHOLDER_HIGH, target.nodeId(), 0L);
        return new RemotePartitionClient(desc, target, transport, findLocalAddress());
    }

    /**
     * Returns the local node address provided at construction.
     */
    private NodeAddress findLocalAddress() {
        return localAddress;
    }

    /**
     * Performs a k-way merge of sorted iterators using a min-heap. Each iterator must be sorted by
     * key in natural order.
     *
     * @param iterators the sorted iterators to merge
     * @return a merged iterator producing entries in global key order
     */
    private static Iterator<TableEntry<String>> mergeOrdered(
            List<Iterator<TableEntry<String>>> iterators) {
        assert iterators != null : "iterators must not be null";

        if (iterators.isEmpty()) {
            return Collections.emptyIterator();
        }
        if (iterators.size() == 1) {
            return iterators.getFirst();
        }

        // Initialize min-heap with first element from each iterator
        final PriorityQueue<HeapEntry> heap = new PriorityQueue<>(iterators.size(),
                Comparator.comparing(he -> he.current.key()));

        for (int i = 0; i < iterators.size(); i++) {
            final Iterator<TableEntry<String>> it = iterators.get(i);
            if (it.hasNext()) {
                heap.offer(new HeapEntry(it.next(), it));
            }
        }

        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return !heap.isEmpty();
            }

            @Override
            public TableEntry<String> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                final HeapEntry entry = heap.poll();
                assert entry != null : "heap entry must not be null";
                final TableEntry<String> result = entry.current;

                // Advance the source iterator
                if (entry.source.hasNext()) {
                    heap.offer(new HeapEntry(entry.source.next(), entry.source));
                }
                return result;
            }
        };
    }

    private void checkNotClosed() throws IOException {
        if (closed) {
            throw new IOException("ClusteredTable is closed");
        }
    }

    private void checkNotClosedUnchecked() {
        if (closed) {
            throw new IllegalStateException("ClusteredTable is closed");
        }
    }

    /**
     * Entry in the k-way merge heap.
     */
    private record HeapEntry(TableEntry<String> current, Iterator<TableEntry<String>> source) {
        HeapEntry {
            assert current != null : "current must not be null";
            assert source != null : "source must not be null";
        }
    }
}
