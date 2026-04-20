package jlsm.engine.cluster;

import java.util.List;
import java.util.Objects;

/**
 * Trivial single-partition {@link PartitionKeySpace} — every key maps to the same partition.
 *
 * <p>
 * Contract: No scan pruning is possible; {@link #partitionsForRange(String, String)} always returns
 * the sole partition ID regardless of the supplied range. Used as a backward-compat fallback for
 * tables or deployments that have not opted into range-based partitioning.
 *
 * <p>
 * Side effects: none.
 *
 * <p>
 * {@code @spec F04.R63}
 */
public final class SinglePartitionKeySpace implements PartitionKeySpace {

    private final String partitionId;

    /**
     * Creates a single-partition keyspace backed by the given partition ID.
     *
     * @param partitionId the sole partition ID; must not be null
     */
    public SinglePartitionKeySpace(String partitionId) {
        this.partitionId = Objects.requireNonNull(partitionId, "partitionId");
    }

    @Override
    public int partitionCount() {
        return 1;
    }

    @Override
    public String partitionForKey(String key) {
        Objects.requireNonNull(key, "key");
        return partitionId;
    }

    @Override
    public List<String> partitionsForRange(String fromKey, String toKey) {
        Objects.requireNonNull(fromKey, "fromKey");
        Objects.requireNonNull(toKey, "toKey");
        return List.of(partitionId);
    }

    @Override
    public List<String> allPartitions() {
        return List.of(partitionId);
    }
}
