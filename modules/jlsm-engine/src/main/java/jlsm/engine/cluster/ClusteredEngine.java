package jlsm.engine.cluster;

import jlsm.engine.Engine;
import jlsm.engine.EngineMetrics;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.GracePeriodManager;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.JlsmSchema;

import java.io.IOException;
import java.util.Collection;
import java.util.Objects;

/**
 * Cluster-aware engine that distributes tables and partitions across cluster members.
 *
 * <p>
 * Contract: Wraps a local {@link Engine} instance and adds cluster membership, ownership
 * assignment, and distributed query routing. On {@link #createTable}, creates the table
 * locally and announces it to the cluster. On {@link #getTable}, returns a
 * {@link ClusteredTable} proxy for partitioned tables or delegates to the local engine
 * for locally-owned tables. Listens for membership changes to trigger rebalancing of
 * table and partition ownership.
 *
 * <p>
 * Side effects: Starts membership protocol on build. Modifies ownership assignments
 * on membership changes. Creates and manages {@link ClusteredTable} proxies.
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
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Table getTable(String name) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void dropTable(String name) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Collection<TableMetadata> listTables() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public TableMetadata tableMetadata(String name) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public EngineMetrics metrics() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException("Not implemented");
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

        private Builder() {}

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
