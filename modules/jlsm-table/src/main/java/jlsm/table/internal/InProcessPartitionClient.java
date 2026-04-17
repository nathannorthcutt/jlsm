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
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
// @spec F11.R48 — final class in jlsm.table.internal implementing PartitionClient
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
    // @spec F11.R49 — accepts descriptor + table, rejects null for either with NPE
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

    // @spec F11.R50 — doCreate delegates to wrapped JlsmTable.StringKeyed
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
    // @spec F11.R51 — getRange delegates to wrapped table's getAllInRange
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
     * Scans the partition's key range and returns matching entries, up to {@code limit}, with a
     * uniform relevance score of {@code 1.0} for each match. Vector and full-text predicates throw
     * {@link UnsupportedOperationException} — those query kinds require per-partition indices that
     * are not wired through the in-process client's {@link JlsmTable.StringKeyed} reference.
     *
     * @param predicate the query predicate; must not be null
     * @param limit maximum matches to return; must be positive
     * @return matching entries with score 1.0, in scan order
     * @throws UnsupportedOperationException if the predicate contains a FullTextMatch or
     *             VectorNearest leaf
     * @throws IOException on scan failure
     */
    // @spec F11.R46 — PartitionClient.query returns List<ScoredEntry<String>> of at most limit
    // @spec F11.R48 — scan-and-filter via wrapped JlsmTable.StringKeyed within partition range
    @Override
    public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) throws IOException {
        assert predicate != null : "predicate must not be null";
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got: " + limit);
        }
        final String fromKey = decodeKey(descriptor.lowKey());
        final String toKey = decodeKey(descriptor.highKey());
        final List<ScoredEntry<String>> matches = new ArrayList<>();
        final Iterator<TableEntry<String>> scan = table.getAllInRange(fromKey, toKey);
        while (scan.hasNext() && matches.size() < limit) {
            final TableEntry<String> entry = scan.next();
            if (PartitionPredicateEvaluator.matches(entry.document(), predicate)) {
                matches.add(new ScoredEntry<>(entry.key(), entry.document(), 1.0));
            }
        }
        return matches;
    }

    private static String decodeKey(MemorySegment seg) {
        return new String(seg.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    // @spec F11.R52,R102 — close() closes wrapped table (R52) and is idempotent (R102)
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        table.close();
    }
}
