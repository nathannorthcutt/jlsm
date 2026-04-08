package jlsm.table.internal;

import jlsm.table.JlsmDocument;
import jlsm.table.JlsmTable;
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
 * In-process implementation of {@link PartitionClient} that wraps a {@link JlsmTable.StringKeyed}.
 *
 * <p>
 * Contract: Delegates all operations directly to the wrapped table instance. No serialization or
 * network overhead. This is the only implementation for the initial (in-process) deployment.
 *
 * <p>
 * Null-rejection is handled at the interface level via default methods. The {@code doXxx} methods
 * receive guaranteed non-null parameters.
 *
 * <p>
 * Governed by: .decisions/table-partitioning/adr.md — in-process execution, remote-capable
 * interface.
 */
public final class InProcessPartitionClient implements PartitionClient {

    private final PartitionDescriptor descriptor;
    private final JlsmTable.StringKeyed table;
    private volatile boolean closed;

    /**
     * Creates an in-process partition client wrapping the given table.
     *
     * <p>
     * Contract:
     * <ul>
     * <li>Receives: descriptor for this partition, the backing JlsmTable.StringKeyed</li>
     * <li>Returns: partition client ready for operations</li>
     * <li>Side effects: none</li>
     * </ul>
     *
     * @param descriptor the partition descriptor; must not be null
     * @param table the backing table for this partition; must not be null
     */
    public InProcessPartitionClient(PartitionDescriptor descriptor, JlsmTable.StringKeyed table) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(table, "table must not be null");
        this.descriptor = descriptor;
        this.table = table;
    }

    @Override
    public PartitionDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void doCreate(String key, JlsmDocument doc) throws IOException {
        assert key != null : "key must not be null";
        assert doc != null : "doc must not be null";
        table.create(key, doc);
    }

    @Override
    public Optional<JlsmDocument> doGet(String key) throws IOException {
        assert key != null : "key must not be null";
        return table.get(key);
    }

    @Override
    public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
        assert key != null : "key must not be null";
        assert doc != null : "doc must not be null";
        assert mode != null : "mode must not be null";
        table.update(key, doc, mode);
    }

    @Override
    public void doDelete(String key) throws IOException {
        assert key != null : "key must not be null";
        table.delete(key);
    }

    @Override
    public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey)
            throws IOException {
        assert fromKey != null : "fromKey must not be null";
        assert toKey != null : "toKey must not be null";
        if (fromKey.compareTo(toKey) >= 0) {
            throw new IllegalArgumentException(
                    "fromKey must be strictly less than toKey — got fromKey=\"" + fromKey
                            + "\", toKey=\"" + toKey + "\"");
        }
        return table.getAllInRange(fromKey, toKey);
    }

    /**
     * Not yet implemented — query execution through predicates requires QueryExecutor integration
     * which is handled by the coordinator in WU-3.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got: " + limit);
        }
        assert predicate != null : "predicate must not be null";
        throw new UnsupportedOperationException(
                "query execution is not implemented in InProcessPartitionClient; "
                        + "use the PartitionedTable coordinator (WU-3)");
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        table.close();
    }
}
