package jlsm.engine.cluster;

import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.table.JlsmDocument;
import jlsm.table.TableEntry;
import jlsm.table.TableQuery;
import jlsm.table.UpdateMode;

import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

/**
 * Partition-aware proxy table that scatters queries across remote partition owners.
 *
 * <p>
 * Contract: Implements {@link Table} transparently for partitioned tables in a cluster.
 * Inspects predicates for partition pruning (O(log P) on range boundaries). Scatters
 * sub-queries concurrently via the cluster transport. Gathers results with a streaming
 * k-way merge iterator. Attaches {@link PartialResultMetadata} when some partitions are
 * unavailable. Write operations route to the single partition owner.
 *
 * <p>
 * Side effects: Sends messages via {@link ClusterTransport} to remote partition owners.
 * May return incomplete results if some owners are unavailable.
 *
 * <p>
 * Governed by: {@code .decisions/scatter-gather-query-execution/adr.md}
 */
public final class ClusteredTable implements Table {

    private final TableMetadata tableMetadata;
    private final ClusterTransport transport;
    private final MembershipProtocol membership;
    private volatile boolean closed;

    /**
     * Creates a new clustered table proxy.
     *
     * @param tableMetadata the metadata for this table; must not be null
     * @param transport     the cluster transport for remote communication; must not be null
     * @param membership    the membership protocol for resolving partition owners; must not be null
     */
    public ClusteredTable(TableMetadata tableMetadata, ClusterTransport transport,
            MembershipProtocol membership) {
        this.tableMetadata = Objects.requireNonNull(tableMetadata, "tableMetadata must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.membership = Objects.requireNonNull(membership, "membership must not be null");
    }

    @Override
    public void create(String key, JlsmDocument doc) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Optional<JlsmDocument> get(String key) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void delete(String key) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void insert(JlsmDocument doc) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public TableQuery<String> query() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Iterator<TableEntry<String>> scan(String fromKey, String toKey) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
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
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void close() {
        closed = true;
    }
}
