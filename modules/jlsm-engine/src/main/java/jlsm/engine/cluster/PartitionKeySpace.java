package jlsm.engine.cluster;

import java.util.List;

/**
 * SPI mapping keys to partition IDs.
 *
 * <p>
 * Contract: Implementations may be hash-based (no range pruning), lexicographic-range-based (prunes
 * scans to overlapping partitions), or single-partition fallback. All methods are pure functions
 * over the keyspace configuration fixed at construction time; implementations must be thread-safe
 * for concurrent callers. Input keys are non-null. Range boundaries follow half-open semantics
 * {@code [fromKey, toKey)} where applicable.
 *
 * <p>
 * Side effects: none.
 *
 * <p>
 * {@code @spec F04.R63}
 */
public interface PartitionKeySpace {

    /**
     * Returns the total number of partitions in this keyspace.
     *
     * @return a positive integer
     */
    int partitionCount();

    /**
     * Maps a single key to its owning partition ID.
     *
     * @param key the key to map; must not be null
     * @return the partition ID that owns {@code key}
     */
    String partitionForKey(String key);

    /**
     * Returns the partition IDs whose ranges overlap the given key range.
     *
     * @param fromKey inclusive lower bound; must not be null
     * @param toKey exclusive upper bound; must not be null
     * @return list of partition IDs overlapping {@code [fromKey, toKey)}; never null, never empty
     */
    List<String> partitionsForRange(String fromKey, String toKey);

    /**
     * Returns the full list of partition IDs managed by this keyspace.
     *
     * @return an immutable list of partition IDs; never null, never empty
     */
    List<String> allPartitions();
}
