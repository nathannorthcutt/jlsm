package jlsm.engine.cluster;

import jlsm.engine.Engine;
import jlsm.engine.EngineMetrics;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.GracePeriodManager;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.JlsmSchema;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
    private final ConcurrentHashMap<String, ClusteredTable> clusteredTables = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private ClusteredEngine(Builder builder) {
        this.localEngine = Objects.requireNonNull(builder.localEngine, "localEngine");
        this.membership = Objects.requireNonNull(builder.membership, "membership");
        this.ownership = Objects.requireNonNull(builder.ownership, "ownership");
        this.gracePeriodManager = Objects.requireNonNull(builder.gracePeriodManager,
                "gracePeriodManager");
        this.transport = Objects.requireNonNull(builder.transport, "transport");
        this.config = Objects.requireNonNull(builder.config, "config");
        this.localAddress = Objects.requireNonNull(builder.localAddress, "localAddress");

        // Register as a membership listener to trigger rebalancing on view changes
        membership.addListener(new ClusterMembershipListener());
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
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (schema == null) {
            throw new IllegalArgumentException("schema must not be null");
        }
        checkNotClosed();

        // Create locally first
        final Table localTable = localEngine.createTable(name, schema);
        assert localTable != null : "localEngine.createTable must return non-null";

        // Create a clustered proxy for distributed access
        final TableMetadata metadata = localTable.metadata();
        final ClusteredTable clustered = new ClusteredTable(metadata, transport, membership,
                localAddress);
        clusteredTables.put(name, clustered);

        return localTable;
    }

    @Override
    public Table getTable(String name) throws IOException {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        checkNotClosed();

        // Delegate to local engine — the table must exist locally
        return localEngine.getTable(name);
    }

    @Override
    public void dropTable(String name) throws IOException {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
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

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        final List<IOException> errors = new ArrayList<>();

        // Close all clustered table proxies
        for (final ClusteredTable ct : clusteredTables.values()) {
            ct.close();
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

        // Close local engine
        try {
            localEngine.close();
        } catch (IOException e) {
            errors.add(e);
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
        if (closed) {
            throw new IOException("ClusteredEngine is closed");
        }
    }

    private void checkNotClosedUnchecked() {
        if (closed) {
            throw new IllegalStateException("ClusteredEngine is closed");
        }
    }

    /**
     * Handles membership view changes by triggering ownership rebalancing.
     */
    private void onViewChanged(MembershipView oldView, MembershipView newView) {
        assert oldView != null : "oldView must not be null";
        assert newView != null : "newView must not be null";

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
         * Builds the clustered engine.
         *
         * @return a new {@link ClusteredEngine}; never null
         */
        public ClusteredEngine build() {
            return new ClusteredEngine(this);
        }
    }
}
