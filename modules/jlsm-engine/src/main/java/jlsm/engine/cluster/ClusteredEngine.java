package jlsm.engine.cluster;

import jlsm.engine.Engine;
import jlsm.engine.EngineMetrics;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.GracePeriodManager;
import jlsm.engine.cluster.internal.QueryRequestHandler;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.JlsmSchema;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cluster-aware engine that distributes tables and partitions across cluster members.
 *
 * <p>
 * Contract: Wraps a local {@link Engine} instance and adds cluster membership, ownership
 * assignment, and distributed query routing. On {@link #createTable}, creates the table locally and
 * announces it to the cluster. On {@link #getTable}, returns a {@link ClusteredTable} proxy for
 * partitioned tables or delegates to the local engine for locally-owned tables. Listens for
 * membership changes to trigger rebalancing of table and partition ownership.
 *
 * <p>
 * Side effects: Starts membership protocol on build. Modifies ownership assignments on membership
 * changes. Creates and manages {@link ClusteredTable} proxies.
 *
 * <p>
 * Governed by: {@code .decisions/cluster-membership-protocol/adr.md},
 * {@code .decisions/partition-to-node-ownership/adr.md},
 * {@code .decisions/rebalancing-grace-period-strategy/adr.md},
 * {@code .decisions/scatter-gather-query-execution/adr.md},
 * {@code .decisions/discovery-spi-design/adr.md},
 * {@code .decisions/transport-abstraction-design/adr.md}
 */
public final class ClusteredEngine implements Engine {

    private final Engine localEngine;
    private final MembershipProtocol membership;
    private final RendezvousOwnership ownership;
    private final GracePeriodManager gracePeriodManager;
    private final ClusterTransport transport;
    private final ClusterConfig config;
    private final NodeAddress localAddress;
    private final DiscoveryProvider discovery;
    private final QueryRequestHandler queryHandler;
    private final ConcurrentHashMap<String, ClusteredTable> clusteredTables = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    /**
     * Engine-level operational mode (@spec engine.clustering.R41). Transitions between NORMAL and READ_ONLY on
     * membership-view changes based on quorum. Exposed via {@link #operationalMode()} and consumed
     * by {@link ClusteredTable} to gate mutating operations.
     */
    private volatile ClusterOperationalMode operationalMode = ClusterOperationalMode.NORMAL;

    private ClusteredEngine(Builder builder) {
        this.localEngine = Objects.requireNonNull(builder.localEngine, "localEngine");
        this.membership = Objects.requireNonNull(builder.membership, "membership");
        this.ownership = Objects.requireNonNull(builder.ownership, "ownership");
        this.gracePeriodManager = Objects.requireNonNull(builder.gracePeriodManager,
                "gracePeriodManager");
        this.transport = Objects.requireNonNull(builder.transport, "transport");
        this.config = Objects.requireNonNull(builder.config, "config");
        this.localAddress = Objects.requireNonNull(builder.localAddress, "localAddress");
        // @spec engine.clustering.R56,R79 — discovery is a mandatory builder parameter rejected at build time.
        this.discovery = Objects.requireNonNull(builder.discovery, "discovery");

        // H-RL-10 / Finding F-R1.concurrency.1.2 — publish `this` to the membership protocol
        // only after every final field has been assigned. The ClusterMembershipListener inner
        // class captures ClusteredEngine.this; a MembershipProtocol implementation that
        // dispatches the initial view on a separate thread during addListener would otherwise
        // expose fields assigned after the registration as null under the JMM because no
        // happens-before edge links the ctor's subsequent writes to the dispatcher's reads.
        // The listener body only reads `ownership` and `gracePeriodManager` (both assigned
        // above), so publishing `this` here is safe.
        this.queryHandler = new QueryRequestHandler(localEngine, localAddress);
        final MembershipListener membershipListener = new ClusterMembershipListener();
        membership.addListener(membershipListener);

        // @spec engine.clustering.R68 — register the server-side QUERY_REQUEST dispatcher so remote
        // RemotePartitionClient requests can route to local tables. H-RL-10: any failure in
        // registerHandler propagates out of the ctor so no partially-initialized engine leaks.
        //
        // Finding F-R1.concurrency.1.3 — register the handler AFTER membership.addListener so
        // a QUERY_REQUEST arriving synchronously via a shared transport is never dispatched
        // against an engine whose membership listener has not yet been installed. The handler
        // only captures final `localEngine` and `localAddress` references (assigned above), so
        // publishing it here is safe. Ordering registerHandler last ensures that any thread
        // that observes the handler in the transport also observes the listener registration
        // and every engine field that precedes it.
        //
        // Finding F-R1.resource_lifecycle.1.1 — if registerHandler throws, the listener
        // registered above is leaked (MembershipProtocol retains a strong reference to the
        // inner class, which captures ClusteredEngine.this). Roll back the listener on
        // failure via MembershipProtocol.removeListener so construction is atomic with
        // respect to listener installation.
        try {
            transport.registerHandler(MessageType.QUERY_REQUEST, queryHandler);
        } catch (RuntimeException | Error ex) {
            try {
                membership.removeListener(membershipListener);
            } catch (RuntimeException rollbackEx) {
                ex.addSuppressed(rollbackEx);
            }
            throw ex;
        }
    }

    /**
     * Returns a new builder for constructing a {@link ClusteredEngine}.
     *
     * @return a new builder; never null
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Table createTable(String name, JlsmSchema schema) throws IOException {
        // @spec engine.clustering.R78 — null arguments must be rejected with NullPointerException
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        checkNotClosed();

        // Create locally first
        final Table localTable = localEngine.createTable(name, schema);
        assert localTable != null : "localEngine.createTable must return non-null";

        // Create a clustered proxy for distributed access — roll back local table on failure
        final ClusteredTable clustered;
        try {
            final TableMetadata metadata = localTable.metadata();
            // @spec engine.clustering.R60 — supply the local engine so ClusteredTable can short-circuit
            // locally-owned partitions.
            clustered = new ClusteredTable(metadata, transport, membership, localAddress, ownership,
                    localEngine);
            final ClusteredTable previous = clusteredTables.put(name, clustered);
            if (previous != null) {
                previous.close();
            }

            // Guard against TOCTOU race with close(): if close() ran between
            // checkNotClosed() and the put above, the new entry was added after
            // close() iterated/cleared the map — it would be orphaned. Re-check
            // and clean up if the engine was closed concurrently.
            if (closed.get()) {
                final ClusteredTable orphan = clusteredTables.remove(name);
                if (orphan != null) {
                    orphan.close();
                }
                final var closedEx = new IOException("ClusteredEngine is closed");
                try {
                    localEngine.dropTable(name);
                } catch (IOException dropEx) {
                    closedEx.addSuppressed(dropEx);
                }
                throw closedEx;
            }
        } catch (RuntimeException ex) {
            try {
                localEngine.dropTable(name);
            } catch (IOException dropEx) {
                ex.addSuppressed(dropEx);
            }
            throw ex;
        }

        return clustered;
    }

    @Override
    public Table getTable(String name) throws IOException {
        // @spec engine.clustering.R78 — null arguments must be rejected with NullPointerException
        Objects.requireNonNull(name, "name must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        checkNotClosed();

        // Delegate to local engine — the table must exist locally
        return localEngine.getTable(name);
    }

    @Override
    public void dropTable(String name) throws IOException {
        // @spec engine.clustering.R78 — null arguments must be rejected with NullPointerException
        Objects.requireNonNull(name, "name must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        checkNotClosed();

        // Remove clustered proxy
        final ClusteredTable removed = clusteredTables.remove(name);
        if (removed != null) {
            removed.close();
        }

        // Delegate to local engine
        localEngine.dropTable(name);
    }

    @Override
    public Collection<TableMetadata> listTables() {
        checkNotClosedUnchecked();
        return localEngine.listTables();
    }

    @Override
    public TableMetadata tableMetadata(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return localEngine.tableMetadata(name);
    }

    @Override
    public EngineMetrics metrics() {
        checkNotClosedUnchecked();
        return localEngine.metrics();
    }

    /**
     * Joins the cluster by registering this node with the discovery provider and starting the
     * membership protocol with the supplied seeds.
     *
     * <p>
     *
     * @spec engine.clustering.R57 — the join orchestration registers with discovery first and then starts
     *       membership. If {@code membership.start(seeds)} throws, the completed discovery
     *       registration is rolled back via {@code discovery.deregister(localAddress)} and the
     *       original failure is rethrown. Any exception thrown by the rollback deregister is
     *       attached via {@link Throwable#addSuppressed(Throwable)} on the original failure.
     *
     *       <p>
     * @spec engine.clustering.R78 — null {@code seeds} is rejected with {@link NullPointerException}; seeds
     *       containing a null element are rejected with {@link IllegalArgumentException}.
     *
     * @param seeds the seed addresses forwarded to the membership protocol; must not be null,
     *            elements must not be null
     * @throws IOException if the engine is closed, if membership start fails, or if discovery
     *             registration surfaces an I/O error
     */
    public void join(List<NodeAddress> seeds) throws IOException {
        // @spec engine.clustering.R78 — eager argument validation before any side effects.
        Objects.requireNonNull(seeds, "seeds must not be null");
        for (final NodeAddress seed : seeds) {
            if (seed == null) {
                throw new IllegalArgumentException("seeds must not contain null elements");
            }
        }
        checkNotClosed();

        // Register with discovery first — if this throws, there is nothing to roll back.
        discovery.register(localAddress);

        // Start membership. On any failure, roll back the discovery registration.
        try {
            membership.start(seeds);
        } catch (IOException | RuntimeException failure) {
            // @spec engine.clustering.R57 — rollback catches any Exception from discovery.deregister (mirroring
            // the symmetric defence in close() at the deregister call site) so a non-compliant
            // DiscoveryProvider impl that leaks a checked exception cannot hide the original
            // membership.start failure. The rollback failure is attached via addSuppressed and
            // the original failure is always the primary throw.
            try {
                discovery.deregister(localAddress);
            } catch (Exception deregisterFailure) {
                failure.addSuppressed(deregisterFailure);
            }
            throw failure;
        }
    }

    /**
     * Returns the engine's current operational mode (@spec engine.clustering.R41).
     *
     * <p>
     * The mode is {@link ClusterOperationalMode#NORMAL} while the most recent membership view
     * reports quorum (via {@link MembershipView#hasQuorum(int)} at
     * {@link ClusterConfig#consensusQuorumPercent()}), and {@link ClusterOperationalMode#READ_ONLY}
     * otherwise. In READ_ONLY mode, {@link ClusteredTable} write operations throw
     * {@link QuorumLostException}.
     *
     * @return the current operational mode; never null
     */
    public ClusterOperationalMode operationalMode() {
        return operationalMode;
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        final List<IOException> errors = new ArrayList<>();

        // Close all clustered table proxies (deferred exception collection)
        for (final ClusteredTable ct : clusteredTables.values()) {
            try {
                ct.close();
            } catch (Exception e) {
                if (e instanceof IOException ioe) {
                    errors.add(ioe);
                } else {
                    errors.add(new IOException("Failed to close clustered table", e));
                }
            }
        }
        clusteredTables.clear();

        // Leave the cluster gracefully
        try {
            membership.leave();
        } catch (IOException e) {
            errors.add(e);
        }

        // Close membership protocol
        try {
            membership.close();
        } catch (Exception e) {
            if (e instanceof IOException ioe) {
                errors.add(ioe);
            } else {
                errors.add(new IOException("Failed to close membership protocol", e));
            }
        }

        // @spec engine.clustering.R58 — deregister from discovery symmetrically with register during join.
        // Deregister errors are accumulated into the deferred-exception pattern so the remaining
        // resources still close.
        try {
            discovery.deregister(localAddress);
        } catch (Exception e) {
            if (e instanceof IOException ioe) {
                errors.add(ioe);
            } else {
                errors.add(new IOException("Failed to deregister from discovery", e));
            }
        }

        // Close local engine
        try {
            localEngine.close();
        } catch (IOException e) {
            errors.add(e);
        }

        // @spec engine.clustering.R68 — deregister the QUERY_REQUEST handler symmetrically with registration.
        // H-RL-8: deregister MUST happen before transport.close so we don't call deregister on a
        // closed transport. Any exception is accumulated into the deferred-exception pattern so
        // remaining resources still close.
        try {
            transport.deregisterHandler(MessageType.QUERY_REQUEST);
        } catch (Exception e) {
            if (e instanceof IOException ioe) {
                errors.add(ioe);
            } else {
                errors.add(new IOException("Failed to deregister QUERY_REQUEST handler", e));
            }
        }

        // Close cluster transport
        try {
            transport.close();
        } catch (Exception e) {
            if (e instanceof IOException ioe) {
                errors.add(ioe);
            } else {
                errors.add(new IOException("Failed to close cluster transport", e));
            }
        }

        if (!errors.isEmpty()) {
            final IOException primary = errors.getFirst();
            for (int i = 1; i < errors.size(); i++) {
                primary.addSuppressed(errors.get(i));
            }
            throw primary;
        }
    }

    // ---- Private helpers ----

    private void checkNotClosed() throws IOException {
        if (closed.get()) {
            throw new IOException("ClusteredEngine is closed");
        }
    }

    private void checkNotClosedUnchecked() {
        if (closed.get()) {
            throw new IllegalStateException("ClusteredEngine is closed");
        }
    }

    /**
     * Handles membership view changes by triggering ownership rebalancing.
     */
    private void onViewChanged(MembershipView oldView, MembershipView newView) {
        Objects.requireNonNull(oldView, "oldView must not be null");
        Objects.requireNonNull(newView, "newView must not be null");

        // Finding F-R1.shared_state.1.1 — a membership-dispatcher callback can arrive
        // after close() has begun (enqueued before close, or from an in-flight tick that
        // races close.CAS). Mutating ownership/gracePeriodManager on a torn-down engine
        // is a contract/cleanup ordering violation: the mutations silently succeed on a
        // closed engine because the collaborators remain valid references, yielding
        // misleading post-close state. Short-circuit once close() has started.
        if (closed.get()) {
            return;
        }

        // @spec engine.clustering.R41 — transition operational mode based on quorum status of the new view.
        // Empty views (no members) cannot satisfy quorum; hasQuorum returns false in that case
        // which correctly leaves us in READ_ONLY if we enter it.
        final Set<Member> newMembers = newView.members();
        final boolean hasQuorum = !newMembers.isEmpty()
                && newView.hasQuorum(config.consensusQuorumPercent());
        operationalMode = hasQuorum ? ClusterOperationalMode.NORMAL
                : ClusterOperationalMode.READ_ONLY;

        // Evict stale ownership cache entries
        ownership.evictBefore(newView.epoch());

        // Record departures for grace period tracking.
        // A departure occurs when a member was ALIVE in the old view and is either:
        // (a) absent from the new view entirely, or
        // (b) present but no longer ALIVE (SUSPECTED or DEAD)
        for (final Member oldMember : oldView.members()) {
            if (oldMember.state() == MemberState.ALIVE) {
                boolean stillAliveInNew = false;
                for (final Member newMember : newView.members()) {
                    if (newMember.address().equals(oldMember.address())
                            && newMember.state() == MemberState.ALIVE) {
                        stillAliveInNew = true;
                        break;
                    }
                }
                if (!stillAliveInNew) {
                    gracePeriodManager.recordDeparture(oldMember.address(), Instant.now());
                }
            }
        }

        // Record returns for nodes that rejoin (transition to ALIVE in new view
        // when they were not ALIVE in old view)
        for (final Member newMember : newView.members()) {
            if (newMember.state() == MemberState.ALIVE) {
                gracePeriodManager.recordReturn(newMember.address());
            }
        }

        // Clean up expired departure entries to prevent unbounded map growth
        gracePeriodManager.expiredDepartures();
    }

    /**
     * Internal membership listener that delegates to the engine's rebalancing logic.
     */
    private final class ClusterMembershipListener implements MembershipListener {
        @Override
        public void onViewChanged(MembershipView oldView, MembershipView newView) {
            ClusteredEngine.this.onViewChanged(oldView, newView);
        }

        @Override
        public void onMemberJoined(Member member) {
            // Handled by onViewChanged
        }

        @Override
        public void onMemberLeft(Member member) {
            // Handled by onViewChanged
        }

        @Override
        public void onMemberSuspected(Member member) {
            // No action on suspicion — wait for confirmed departure
        }
    }

    /**
     * Builder for {@link ClusteredEngine}.
     */
    public static final class Builder {

        private Engine localEngine;
        private MembershipProtocol membership;
        private RendezvousOwnership ownership;
        private GracePeriodManager gracePeriodManager;
        private ClusterTransport transport;
        private ClusterConfig config;
        private NodeAddress localAddress;
        private DiscoveryProvider discovery;

        private Builder() {
        }

        public Builder localEngine(Engine localEngine) {
            this.localEngine = Objects.requireNonNull(localEngine, "localEngine must not be null");
            return this;
        }

        public Builder membership(MembershipProtocol membership) {
            this.membership = Objects.requireNonNull(membership, "membership must not be null");
            return this;
        }

        public Builder ownership(RendezvousOwnership ownership) {
            this.ownership = Objects.requireNonNull(ownership, "ownership must not be null");
            return this;
        }

        public Builder gracePeriodManager(GracePeriodManager gracePeriodManager) {
            this.gracePeriodManager = Objects.requireNonNull(gracePeriodManager,
                    "gracePeriodManager must not be null");
            return this;
        }

        public Builder transport(ClusterTransport transport) {
            this.transport = Objects.requireNonNull(transport, "transport must not be null");
            return this;
        }

        public Builder config(ClusterConfig config) {
            this.config = Objects.requireNonNull(config, "config must not be null");
            return this;
        }

        public Builder localAddress(NodeAddress localAddress) {
            this.localAddress = Objects.requireNonNull(localAddress,
                    "localAddress must not be null");
            return this;
        }

        /**
         * Sets the discovery provider used for cluster bootstrap.
         *
         * <p>
         *
         * @spec engine.clustering.R56,R79 — discovery is a mandatory builder parameter; both the setter and
         *       {@link #build()} reject a missing/null discovery with {@link NullPointerException}.
         *
         * @param discovery the discovery provider; must not be null
         * @return this builder
         */
        public Builder discovery(DiscoveryProvider discovery) {
            this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
            return this;
        }

        /**
         * Builds the clustered engine.
         *
         * @return a new {@link ClusteredEngine}; never null
         */
        public ClusteredEngine build() {
            return new ClusteredEngine(this);
        }
    }
}
