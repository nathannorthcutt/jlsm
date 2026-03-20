package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.ClusterTransport;
import jlsm.engine.cluster.NodeAddress;
import jlsm.table.JlsmDocument;
import jlsm.table.PartitionClient;
import jlsm.table.PartitionDescriptor;
import jlsm.table.Predicate;
import jlsm.table.ScoredEntry;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Remote partition client that communicates with a partition owner via the cluster transport.
 *
 * <p>
 * Contract: Implements {@link PartitionClient} by serializing CRUD and query operations as
 * {@code QUERY_REQUEST} messages, sending them to the remote partition owner via
 * {@link ClusterTransport#request}, and deserializing the {@code QUERY_RESPONSE}.
 *
 * <p>
 * Side effects: Sends messages via the cluster transport. Blocks on the response future
 * with a configurable timeout.
 *
 * <p>
 * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
 */
public final class RemotePartitionClient implements PartitionClient {

    private final PartitionDescriptor descriptor;
    private final NodeAddress owner;
    private final ClusterTransport transport;
    private final NodeAddress localAddress;
    private volatile boolean closed;

    /**
     * Creates a remote partition client.
     *
     * @param descriptor   the partition descriptor; must not be null
     * @param owner        the address of the partition owner; must not be null
     * @param transport    the cluster transport; must not be null
     * @param localAddress the local node address (for message sender field); must not be null
     */
    public RemotePartitionClient(PartitionDescriptor descriptor, NodeAddress owner,
            ClusterTransport transport, NodeAddress localAddress) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress must not be null");
    }

    @Override
    public PartitionDescriptor descriptor() {
        return descriptor;
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
    public Iterator<TableEntry<String>> getRange(String fromKey, String toKey) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public List<ScoredEntry<String>> query(Predicate predicate, int limit) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void close() throws IOException {
        closed = true;
    }
}
