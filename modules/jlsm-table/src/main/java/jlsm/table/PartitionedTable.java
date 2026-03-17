package jlsm.table;

import jlsm.table.internal.RangeMap;
import jlsm.table.internal.ResultMerger;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Coordinator for a range-partitioned table.
 *
 * <p>
 * Contract: Routes key-based CRUD to the correct partition via O(log P) range map lookup. Executes
 * scatter-gather for multi-partition queries (vector, full-text, combined) and merges results. Each
 * partition is accessed through a {@link PartitionClient}, allowing future remote implementations
 * without changing the coordinator.
 *
 * <p>
 * Governed by: .decisions/table-partitioning/adr.md — range partitioning with per-partition
 * co-located indices.
 */
public final class PartitionedTable implements Closeable {

    private final PartitionConfig config;
    private final RangeMap rangeMap;
    // Ordered map: descriptor id → client (insertion order matches config order)
    private final Map<Long, PartitionClient> clients;

    private PartitionedTable(PartitionConfig config, RangeMap rangeMap,
            Map<Long, PartitionClient> clients) {
        assert config != null : "config must not be null";
        assert rangeMap != null : "rangeMap must not be null";
        assert clients != null : "clients must not be null";
        assert !clients.isEmpty() : "clients must not be empty";
        this.config = config;
        this.rangeMap = rangeMap;
        this.clients = clients;
    }

    /**
     * Returns a builder for constructing a partitioned table.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a document, routing to the correct partition by key.
     *
     * @param key the document key
     * @param doc the document to create
     * @throws IOException if the write fails
     * @throws DuplicateKeyException if the key already exists
     */
    public void create(String key, JlsmDocument doc) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        final PartitionClient client = routeKey(key);
        client.create(key, doc);
    }

    /**
     * Retrieves a document by key, routing to the correct partition.
     *
     * @param key the document key
     * @return the document, or empty if not found
     * @throws IOException if the read fails
     */
    public Optional<JlsmDocument> get(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        final PartitionClient client = routeKey(key);
        return client.get(key);
    }

    /**
     * Updates a document, routing to the correct partition by key.
     *
     * @param key the document key
     * @param doc the updated document
     * @param mode replace or patch
     * @throws IOException if the write fails
     */
    public void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        final PartitionClient client = routeKey(key);
        client.update(key, doc, mode);
    }

    /**
     * Deletes a document, routing to the correct partition by key.
     *
     * @param key the document key
     * @throws IOException if the write fails
     */
    public void delete(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        final PartitionClient client = routeKey(key);
        client.delete(key);
    }

    /**
     * Returns entries across partitions within the given key range, merged in key order.
     *
     * <p>
     * Routes to the minimal set of overlapping partitions and merges their iterators.
     *
     * @param fromKey inclusive lower bound
     * @param toKey exclusive upper bound
     * @return iterator over matching entries in key order
     * @throws IOException if any partition read fails
     */
    public Iterator<TableEntry<String>> getRange(String fromKey, String toKey) throws IOException {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        final MemorySegment fromSeg = toSegment(fromKey);
        final MemorySegment toSeg = toSegment(toKey);
        final List<PartitionDescriptor> overlapping = rangeMap.overlapping(fromSeg, toSeg);
        assert !overlapping.isEmpty() || true
                : "overlapping may be empty for non-overlapping range";

        final List<Iterator<TableEntry<String>>> iterators = new ArrayList<>(overlapping.size());
        for (final PartitionDescriptor desc : overlapping) {
            final PartitionClient client = clients.get(desc.id());
            assert client != null : "no client found for descriptor id " + desc.id();
            iterators.add(client.getRange(fromKey, toKey));
        }
        return ResultMerger.mergeOrdered(iterators);
    }

    /**
     * Executes a query across all relevant partitions and merges results.
     *
     * <p>
     * Per-partition predicate execution is not yet implemented — this method throws
     * {@link UnsupportedOperationException} until {@code InProcessPartitionClient.query()} is
     * implemented in a future work unit.
     *
     * @param predicate the query predicate
     * @param limit maximum results
     * @return scored results merged across partitions
     * @throws IOException if any partition query fails
     * @throws UnsupportedOperationException always — per-partition predicate execution is not yet
     *             implemented
     */
    public List<ScoredEntry<String>> query(Predicate predicate, int limit) throws IOException {
        Objects.requireNonNull(predicate, "predicate must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got: " + limit);
        }
        throw new UnsupportedOperationException(
                "per-partition predicate execution is not yet implemented; "
                        + "InProcessPartitionClient.query() will be wired in a future work unit");
    }

    /**
     * Returns the partition configuration for this table.
     *
     * @return the partition config
     */
    public PartitionConfig config() {
        return config;
    }

    /**
     * Closes all partition clients. Uses the deferred close pattern: all clients are closed even if
     * one or more throw, and exceptions are accumulated. If any client fails to close, the first
     * exception is thrown with remaining exceptions added as suppressed.
     *
     * @throws IOException if any partition client fails to close
     */
    @Override
    public void close() throws IOException {
        IOException firstException = null;
        for (final PartitionClient client : clients.values()) {
            try {
                client.close();
            } catch (final IOException e) {
                if (firstException == null) {
                    firstException = e;
                } else {
                    firstException.addSuppressed(e);
                }
            }
        }
        if (firstException != null) {
            throw firstException;
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Routes the given string key to its owning partition client.
     *
     * @param key the string key
     * @return the owning partition client
     * @throws IllegalArgumentException if the key falls outside all partition ranges
     */
    private PartitionClient routeKey(String key) {
        assert key != null : "key must not be null";
        final MemorySegment keySeg = toSegment(key);
        final PartitionDescriptor desc = rangeMap.routeKey(keySeg);
        final PartitionClient client = clients.get(desc.id());
        assert client != null : "no client registered for descriptor id " + desc.id();
        return client;
    }

    /**
     * Converts a string key to a {@link MemorySegment} in UTF-8 encoding for RangeMap routing.
     *
     * @param key the string key
     * @return the key as a MemorySegment
     */
    private static MemorySegment toSegment(String key) {
        assert key != null : "key must not be null";
        return MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Builder for {@link PartitionedTable}.
     */
    public static final class Builder {

        private PartitionConfig config;
        private JlsmSchema schema;
        private Function<PartitionDescriptor, PartitionClient> factory;

        private Builder() {
        }

        /**
         * Sets the partition configuration.
         *
         * @param config the partition layout
         * @return this builder
         */
        public Builder partitionConfig(PartitionConfig config) {
            this.config = Objects.requireNonNull(config, "config must not be null");
            return this;
        }

        /**
         * Sets the schema shared by all partitions.
         *
         * @param schema the document schema
         * @return this builder
         */
        public Builder schema(JlsmSchema schema) {
            this.schema = schema; // optional — may be null
            return this;
        }

        /**
         * Sets the factory for creating partition clients. Called once per partition during build.
         *
         * @param factory function from PartitionDescriptor to PartitionClient
         * @return this builder
         */
        public Builder partitionClientFactory(
                Function<PartitionDescriptor, PartitionClient> factory) {
            this.factory = Objects.requireNonNull(factory, "factory must not be null");
            return this;
        }

        /**
         * Builds the partitioned table.
         *
         * <p>
         * Invokes the partition client factory once per descriptor in the configuration. All
         * created clients are owned by the returned {@link PartitionedTable} and closed when the
         * table is closed.
         *
         * @return the configured partitioned table
         * @throws IOException if any partition client fails to initialize
         * @throws IllegalStateException if partitionConfig or partitionClientFactory is not set
         */
        public PartitionedTable build() throws IOException {
            if (config == null) {
                throw new IllegalStateException(
                        "partitionConfig must be set before calling build()");
            }
            if (factory == null) {
                throw new IllegalStateException(
                        "partitionClientFactory must be set before calling build()");
            }

            final RangeMap rangeMap = new RangeMap(config);

            // Build clients in config order, using LinkedHashMap to preserve insertion order
            final Map<Long, PartitionClient> clients = new LinkedHashMap<>();
            for (final PartitionDescriptor desc : config.descriptors()) {
                final PartitionClient client = factory.apply(desc);
                Objects.requireNonNull(client,
                        "partitionClientFactory returned null for descriptor id " + desc.id());
                clients.put(desc.id(), client);
            }

            assert clients.size() == config.partitionCount()
                    : "client count must match partition count";
            return new PartitionedTable(config, rangeMap, clients);
        }
    }
}
