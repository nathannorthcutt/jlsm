package jlsm.engine.cluster;

import jlsm.engine.Engine;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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

    /**
     * Logger for recording client-close failures from the scatter whenComplete stage — assertions
     * are disabled under -da (the default JVM mode), so the previous {@code assert} tautology was a
     * silent no-op. Logging via {@link System.Logger} ensures diagnostics survive production runs.
     */
    private static final System.Logger LOGGER = System.getLogger(ClusteredTable.class.getName());

    /** Placeholder low key (lexicographic minimum) for scatter-gather partition descriptors. */
    private static final java.lang.foreign.MemorySegment PLACEHOLDER_LOW = java.lang.foreign.MemorySegment
            .ofArray(new byte[0]);
    /** Placeholder high key (lexicographic maximum) for scatter-gather partition descriptors. */
    private static final java.lang.foreign.MemorySegment PLACEHOLDER_HIGH = java.lang.foreign.MemorySegment
            .ofArray(new byte[]{ (byte) 0xFF });

    /**
     * Virtual-thread executor used to launch per-node scatter calls in parallel (@spec F04.R77).
     * The underlying cluster transport may perform synchronous work on the calling thread (e.g. the
     * in-JVM transport's delivery-delay model), so submitting each call on a virtual thread is
     * required to prevent the fanout from serializing.
     */
    private static final Executor SCATTER_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final TableMetadata tableMetadata;
    private final ClusterTransport transport;
    private final MembershipProtocol membership;
    private final NodeAddress localAddress;
    private final RendezvousOwnership ownership;
    /** Nullable local engine handle used for in-process short-circuit routing. */
    private final Engine localEngine;
    /**
     * Per-caller-thread partial-result metadata produced by the most recent {@code scan()}
     * invocation on that thread. Stored thread-locally so concurrent scans by different callers do
     * not cross-talk — each caller's follow-up {@link #lastPartialResultMetadata()} reads the
     * metadata from its own scan rather than whichever concurrent scan happened to write last. A
     * single volatile reference cannot preserve the call-to-result association required by
     * F04.R64/R73 under concurrent callers.
     */
    private final ThreadLocal<PartialResultMetadata> lastPartialResult = new ThreadLocal<>();
    /**
     * Shared last-written partial-result metadata used as a fallback for callers on threads that
     * have not themselves performed a scan (H-CC-6 last-writer-wins coherency). Scanning threads
     * always see their own metadata via {@link #lastPartialResult}; non-scanning observer threads
     * fall back to this field. Writes to both fields are paired in {@code scan()}.
     */
    private volatile PartialResultMetadata lastPartialResultShared;
    private volatile boolean closed;
    /**
     * Tracks in-flight scatter fanout futures submitted to {@link #SCATTER_EXECUTOR} so that
     * {@link #close()} can cancel them. Without this, a caller whose scan parks on a stalled
     * transport cannot be unblocked by closing the table — the virtual thread remains parked until
     * the transport eventually completes (which may never happen). Entries self-evict via
     * {@code whenComplete} once the future settles. Addresses H-CC-2 (finding
     * F-R1.resource_lifecycle.2.2) while keeping the executor a JVM-lifetime singleton (virtual
     * threads are cheap; per-instance executors would fragment the carrier pool).
     */
    private final Set<CompletableFuture<?>> inFlightScatter = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new clustered table proxy with a shared ownership instance and a local engine for
     * in-process short-circuit routing.
     *
     * <p>
     *
     * @spec F04.R60 — when the partition owner is the local node and {@code localEngine} is
     *       non-null, CRUD and scan operations execute directly against
     *       {@code localEngine.getTable(name)} without invoking the cluster transport.
     *
     * @param tableMetadata the metadata for this table; must not be null
     * @param transport the cluster transport for remote communication; must not be null
     * @param membership the membership protocol for resolving partition owners; must not be null
     * @param localAddress the address of the local node; must not be null
     * @param ownership the shared ownership resolver; must not be null. Using a shared instance
     *            ensures that eviction events from view changes (propagated by the engine) apply to
     *            the same cache used by this table, preventing stale routing after membership
     *            changes.
     * @param localEngine the local engine used to short-circuit locally-owned operations; may be
     *            null to disable short-circuit routing (backward-compat behavior).
     */
    public ClusteredTable(TableMetadata tableMetadata, ClusterTransport transport,
            MembershipProtocol membership, NodeAddress localAddress, RendezvousOwnership ownership,
            Engine localEngine) {
        this.tableMetadata = Objects.requireNonNull(tableMetadata,
                "tableMetadata must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.membership = Objects.requireNonNull(membership, "membership must not be null");
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress must not be null");
        this.ownership = Objects.requireNonNull(ownership, "ownership must not be null");
        this.localEngine = localEngine;
    }

    /**
     * Creates a new clustered table proxy with a shared ownership instance but no local-engine
     * short-circuit. All operations route through the transport.
     *
     * @param tableMetadata the metadata for this table; must not be null
     * @param transport the cluster transport for remote communication; must not be null
     * @param membership the membership protocol for resolving partition owners; must not be null
     * @param localAddress the address of the local node; must not be null
     * @param ownership the shared ownership resolver; must not be null
     */
    public ClusteredTable(TableMetadata tableMetadata, ClusterTransport transport,
            MembershipProtocol membership, NodeAddress localAddress,
            RendezvousOwnership ownership) {
        this(tableMetadata, transport, membership, localAddress, ownership, null);
    }

    /**
     * Creates a new clustered table proxy with a private ownership instance.
     *
     * <p>
     * Prefer the 5-argument (or 6-argument) constructor when a shared {@link RendezvousOwnership}
     * is available (e.g., from {@link ClusteredEngine}) so that view-change evictions apply to the
     * same cache.
     *
     * @param tableMetadata the metadata for this table; must not be null
     * @param transport the cluster transport for remote communication; must not be null
     * @param membership the membership protocol for resolving partition owners; must not be null
     * @param localAddress the address of the local node; must not be null
     */
    public ClusteredTable(TableMetadata tableMetadata, ClusterTransport transport,
            MembershipProtocol membership, NodeAddress localAddress) {
        this(tableMetadata, transport, membership, localAddress, new RendezvousOwnership(), null);
    }

    // @spec F04.R60 — local-owner operations execute directly on the local engine.
    @Override
    public void create(String key, JlsmDocument doc) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        checkNotClosed();

        final NodeAddress owner = resolveOwner(key);
        if (isLocalOwner(owner)) {
            localTable().create(key, doc);
            return;
        }
        final RemotePartitionClient client = createClient(key, owner);
        try {
            client.create(key, doc);
        } finally {
            client.close();
        }
    }

    // @spec F04.R60 — local-owner operations execute directly on the local engine.
    @Override
    public Optional<JlsmDocument> get(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();

        final NodeAddress owner = resolveOwner(key);
        if (isLocalOwner(owner)) {
            return localTable().get(key);
        }
        final RemotePartitionClient client = createClient(key, owner);
        try {
            return client.get(key);
        } finally {
            client.close();
        }
    }

    // @spec F04.R60 — local-owner operations execute directly on the local engine.
    @Override
    public void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        checkNotClosed();

        final NodeAddress owner = resolveOwner(key);
        if (isLocalOwner(owner)) {
            localTable().update(key, doc, mode);
            return;
        }
        final RemotePartitionClient client = createClient(key, owner);
        try {
            client.update(key, doc, mode);
        } finally {
            client.close();
        }
    }

    // @spec F04.R60 — local-owner operations execute directly on the local engine.
    @Override
    public void delete(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();

        final NodeAddress owner = resolveOwner(key);
        if (isLocalOwner(owner)) {
            localTable().delete(key);
            return;
        }
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

    // @spec F04.R60 — per-node local short-circuit preserved.
    // @spec F04.R64,R67,R73,R100 — partial metadata, ordered merge, client close preserved.
    // @spec F04.R77 — fanout uses getRangeAsync + CompletableFuture.allOf; per-future timeout via
    // orTimeout; client close on whenComplete; blocking await only at the gather barrier.
    /**
     * Scans entries across all live partition owners and returns a merged, ordered iterator.
     *
     * <p>
     * Contract: Resolves the live membership view; for each live node either short-circuits to
     * {@code localTable().scan(fromKey, toKey)} (when {@link #isLocalOwner(NodeAddress)} holds) or
     * calls {@link RemotePartitionClient#getRangeAsync(String, String)}. Collects per-node futures
     * into a list, waits at a single gather barrier via
     * {@code CompletableFuture.allOf(...).join()}, treats per-future failures as unavailable and
     * accumulates them into {@link PartialResultMetadata} (F04.R64), merges surviving iterators via
     * {@link #mergeOrdered(List)} (F04.R67), and closes every remote client on {@code whenComplete}
     * (F04.R100). Per-request timeout is enforced on each future via {@code orTimeout(...)}
     * (F04.R70).
     *
     * <p>
     * Delivers: F04.R77 — parallel scatter via async transport requests.
     *
     * <p>
     * Governed by: {@code .decisions/scatter-gather-query-execution/adr.md}
     *
     * @param fromKey inclusive lower bound; must not be null
     * @param toKey exclusive upper bound; must not be null
     * @return merged ordered iterator over all responding partitions; never null
     */
    @Override
    public Iterator<TableEntry<String>> scan(String fromKey, String toKey) throws IOException {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        checkNotClosed();

        final MembershipView view = membership.currentView();
        final Set<NodeAddress> liveNodes = collectLiveNodes(view);

        if (liveNodes.isEmpty()) {
            final PartialResultMetadata meta = new PartialResultMetadata(0, 0, Set.of(), true);
            lastPartialResult.set(meta);
            lastPartialResultShared = meta;
            return Collections.emptyIterator();
        }

        final int totalQueried = liveNodes.size();
        final List<NodeFuture> perNode = new ArrayList<>(totalQueried);
        // @spec F04.R77 — fan out to every live node in parallel. Local short-circuit (@spec
        // F04.R60) runs inline; remote calls are submitted on a virtual-thread executor so the
        // transport's synchronous per-call delay doesn't serialize the fanout.
        for (final NodeAddress node : liveNodes) {
            if (isLocalOwner(node)) {
                try {
                    final Iterator<TableEntry<String>> it = localTable().scan(fromKey, toKey);
                    perNode.add(new NodeFuture(node, CompletableFuture.completedFuture(it)));
                } catch (IOException e) {
                    perNode.add(new NodeFuture(node, CompletableFuture.failedFuture(e)));
                }
                continue;
            }
            final RemotePartitionClient client;
            try {
                client = createClientForNode(node);
            } catch (RuntimeException creationFailure) {
                // @spec H-RL-7 — a creation failure on one node must not prevent clients on other
                // nodes from being closed; record as unavailable and continue.
                perNode.add(new NodeFuture(node, CompletableFuture.failedFuture(new IOException(
                        "Failed to instantiate client for " + node, creationFailure))));
                continue;
            }
            // Track the vthread that runs the supplyAsync supplier so cancellation of the
            // outer future (via inFlightScatter.cancel(true) in close()) can propagate an
            // interrupt to it. Without this, a transport whose request(...) call blocks
            // synchronously (e.g. inner queue wait with no timeout) parks the SCATTER_EXECUTOR
            // virtual thread indefinitely — CompletableFuture.cancel by design does not
            // interrupt the executing task, so the vthread leaks for JVM lifetime
            // (F-R1.shared_state.2.3 / H-CC-2). Cleared in `finally` so a post-completion
            // cancel does not fire a stray interrupt at a reused carrier thread.
            final AtomicReference<Thread> supplierThread = new AtomicReference<>();
            final CompletableFuture<Iterator<TableEntry<String>>> nodeFut = CompletableFuture
                    .supplyAsync(() -> {
                        supplierThread.set(Thread.currentThread());
                        try {
                            return client.getRangeAsync(fromKey, toKey);
                        } finally {
                            supplierThread.set(null);
                        }
                    }, SCATTER_EXECUTOR).thenCompose(Function.identity())
                    // @spec F04.R100 / H-RL-6 — close every client on both normal and exceptional
                    // completion. whenComplete runs regardless of success/failure/cancellation.
                    // Catch Throwable: the Closeable contract permits non-IOException throwables
                    // from close(), and a RuntimeException here would escape the handler and be
                    // silently swallowed by the barrier's `.handle((__,___) -> null)` wrapper.
                    // Log every failure via System.Logger — assertions are disabled under -da
                    // (default JVM), so `assert` cannot be the sole observability mechanism.
                    .whenComplete((__, ___) -> {
                        try {
                            client.close();
                        } catch (Throwable closeFailure) {
                            LOGGER.log(System.Logger.Level.WARNING,
                                    () -> "RemotePartitionClient.close() failed for node " + node
                                            + " on table " + tableMetadata.name(),
                                    closeFailure);
                        }
                    });
            // Track the fanout future so close() can cancel in-flight scatters (H-CC-2).
            // Self-evict on completion so the set does not accumulate settled entries.
            inFlightScatter.add(nodeFut);
            // On cancellation, interrupt the supplier vthread if it is still parked inside
            // transport.request(...). A well-behaved transport using interruptible blocking
            // (Object.wait, LinkedBlockingQueue.take, etc.) will unwind promptly.
            // F-R1.shared_state.2.3 — propagates close()-triggered cancellation to the
            // synchronous portion of getRangeAsync that orTimeout cannot reach.
            nodeFut.whenComplete((__, ___) -> {
                inFlightScatter.remove(nodeFut);
                if (nodeFut.isCancelled()) {
                    final Thread t = supplierThread.get();
                    if (t != null) {
                        t.interrupt();
                    }
                }
            });
            perNode.add(new NodeFuture(node, nodeFut));
        }

        // Gather barrier — wait for every per-node future. Individual failures are captured below
        // via getNow/catch; allOf itself must never fail, so swallow each failure at the handle
        // stage to keep the barrier clean.
        final CompletableFuture<?>[] waitFutures = perNode.stream()
                .map(nf -> nf.future.handle((__, ___) -> null))
                .toArray(CompletableFuture<?>[]::new);
        try {
            CompletableFuture.allOf(waitFutures).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("scan interrupted", ie);
        } catch (ExecutionException ee) {
            // Should not happen because each future was wrapped in a swallowing handle.
            throw new IOException("Unexpected error during scan fanout", ee);
        }

        final List<Iterator<TableEntry<String>>> iterators = new ArrayList<>();
        final Set<String> unavailable = new HashSet<>();
        for (final NodeFuture nf : perNode) {
            try {
                final Iterator<TableEntry<String>> it = nf.future.getNow(null);
                if (it == null) {
                    unavailable.add(nf.node.nodeId());
                } else {
                    iterators.add(it);
                }
            } catch (CancellationException | CompletionException e) {
                unavailable.add(nf.node.nodeId());
            }
        }

        final int responding = totalQueried - unavailable.size();
        final PartialResultMetadata meta = new PartialResultMetadata(totalQueried, responding,
                Set.copyOf(unavailable), unavailable.isEmpty());
        lastPartialResult.set(meta);
        lastPartialResultShared = meta;

        return mergeOrdered(iterators);
    }

    /** Per-node bundle for the scatter fanout. */
    private record NodeFuture(NodeAddress node,
            CompletableFuture<Iterator<TableEntry<String>>> future) {
        NodeFuture {
            assert node != null : "node must not be null";
            assert future != null : "future must not be null";
        }
    }

    @Override
    public TableMetadata metadata() {
        return tableMetadata;
    }

    /**
     * Returns the partial result metadata from the most recent {@code scan()} invocation on the
     * calling thread. If the calling thread has not itself performed a scan, returns the last
     * globally-written metadata as a fallback (coherent with H-CC-6 last-writer-wins), or
     * {@code null} if no scan has ever been performed on this table.
     *
     * <p>
     * Per-caller-thread storage: concurrent scans by other threads do not affect what this method
     * returns for a thread that has scanned. This preserves the call-to-result association required
     * by F04.R64 — a caller who invokes {@code scan()} and then {@code lastPartialResultMetadata()}
     * always sees the metadata from its own scan, not a different caller's.
     *
     * @return the partial result metadata for the calling thread's own most-recent scan; or, for
     *         observer threads that have not scanned, the last globally-written metadata; or null
     *         if no scan has ever executed on this table
     */
    public PartialResultMetadata lastPartialResultMetadata() {
        final PartialResultMetadata own = lastPartialResult.get();
        if (own != null) {
            return own;
        }
        return lastPartialResultShared;
    }

    @Override
    public void close() {
        closed = true;
        ownership.evictBefore(Long.MAX_VALUE);
        // Cancel any scatter fanout futures still in flight so callers parked at the gather
        // barrier unblock promptly (H-CC-2). Without this, a scan whose transport has stalled
        // past its orTimeout boundary keeps the virtual thread parked until the transport
        // completes, which may never happen. The static SCATTER_EXECUTOR intentionally remains
        // JVM-lifetime (virtual threads are cheap; shutting it down would break other
        // ClusteredTable instances that share this executor).
        for (final CompletableFuture<?> inFlight : inFlightScatter) {
            inFlight.cancel(true);
        }
        inFlightScatter.clear();
    }

    // ---- Private helpers ----

    /**
     * Resolves the owner node for a given key using rendezvous hashing. Uses the table name as the
     * partition identifier for ownership.
     */
    private NodeAddress resolveOwner(String key) throws IOException {
        assert key != null : "key must not be null";
        final MembershipView view = membership.currentView();

        // Evict stale ownership cache entries for epochs older than the current view.
        // Without this, old epoch entries accumulate without bound because ClusteredTable's
        // private RendezvousOwnership is not notified of view changes by ClusteredEngine.
        ownership.evictBefore(view.epoch());

        final Set<NodeAddress> liveNodes = collectLiveNodes(view);
        if (liveNodes.isEmpty()) {
            throw new IOException("No live members in the cluster");
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
     *
     * <p>
     * Delivers: F04.R68 — passes {@code tableMetadata.name()} so the client embeds the table name
     * in every payload header.
     */
    private RemotePartitionClient createClient(String key, NodeAddress owner) {
        assert key != null : "key must not be null";
        assert owner != null : "owner must not be null";
        final PartitionDescriptor desc = new PartitionDescriptor(0L, PLACEHOLDER_LOW,
                PLACEHOLDER_HIGH, owner.nodeId(), 0L);
        return new RemotePartitionClient(desc, owner, transport, findLocalAddress(),
                tableMetadata.schema(), tableMetadata.name());
    }

    /**
     * Creates a RemotePartitionClient for a specific target node (used in scatter).
     *
     * <p>
     * Delivers: F04.R68 — passes {@code tableMetadata.name()} so the client embeds the table name
     * in every payload header.
     */
    private RemotePartitionClient createClientForNode(NodeAddress target) {
        assert target != null : "target must not be null";
        final PartitionDescriptor desc = new PartitionDescriptor(0L, PLACEHOLDER_LOW,
                PLACEHOLDER_HIGH, target.nodeId(), 0L);
        return new RemotePartitionClient(desc, target, transport, findLocalAddress(),
                tableMetadata.schema(), tableMetadata.name());
    }

    /**
     * Returns the local node address provided at construction.
     */
    private NodeAddress findLocalAddress() {
        return localAddress;
    }

    /**
     * Returns {@code true} when {@code owner} is the local node AND a local engine is available for
     * in-process short-circuit routing (@spec F04.R60).
     */
    private boolean isLocalOwner(NodeAddress owner) {
        assert owner != null : "owner must not be null";
        return localEngine != null && owner.equals(localAddress);
    }

    /**
     * Resolves the local engine's {@link Table} handle for this clustered table's name. Must only
     * be called when {@link #isLocalOwner(NodeAddress)} returned {@code true}.
     */
    private Table localTable() throws IOException {
        assert localEngine != null : "localTable() must only be called when localEngine is set";
        return localEngine.getTable(tableMetadata.name());
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

        // Initialize min-heap with first element from each iterator. The comparator assumes
        // non-null current entries and non-null keys; a runtime guard on it.next() (below)
        // rejects malformed iterator elements so the comparator can rely on them. @spec F04.R67
        // — merge preserves ordering when every input iterator yields well-formed, sorted entries.
        final PriorityQueue<HeapEntry> heap = new PriorityQueue<>(iterators.size(),
                Comparator.comparing(he -> he.current.key()));

        for (int i = 0; i < iterators.size(); i++) {
            final Iterator<TableEntry<String>> it = iterators.get(i);
            if (it.hasNext()) {
                final TableEntry<String> first = it.next();
                // Runtime guard: a malformed input iterator that yields a null element must
                // surface as a well-typed IllegalStateException — not leak an AssertionError
                // (HeapEntry record canonical assertion under -ea) or a NullPointerException
                // (comparator dereference under -da). Assertions are disabled in production, so
                // the guard MUST be a runtime check rather than an `assert`.
                if (first == null) {
                    throw new IllegalStateException(
                            "mergeOrdered: input iterator yielded a null TableEntry at index " + i);
                }
                heap.offer(new HeapEntry(first, it));
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

                // Advance the source iterator; a null element from next() is treated as a
                // contract violation (matching the initial-load guard above).
                if (entry.source.hasNext()) {
                    final TableEntry<String> nextEntry = entry.source.next();
                    if (nextEntry == null) {
                        throw new IllegalStateException(
                                "mergeOrdered: input iterator yielded a null TableEntry on advance");
                    }
                    heap.offer(new HeapEntry(nextEntry, entry.source));
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
