package jlsm.table;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Describes a single partition in a {@link PartitionedTable}.
 *
 * <p>
 * Contract: Immutable descriptor holding the key range boundaries, partition identity, and location
 * metadata. The key range is half-open: [{@code lowKey} inclusive, {@code highKey} exclusive).
 *
 * <p>
 * Governed by: .decisions/table-partitioning/adr.md — range partitioning with co-located indices.
 *
 * @param id unique partition identifier
 * @param lowKey inclusive lower bound of the key range (lexicographic byte order)
 * @param highKey exclusive upper bound of the key range (lexicographic byte order)
 * @param nodeId location identifier — local ID for in-process, URI for remote (future)
 * @param epoch logical clock incremented on partition config changes (split, merge, reassign)
 */
public record PartitionDescriptor(long id, MemorySegment lowKey, MemorySegment highKey,
        String nodeId, long epoch) {

    public PartitionDescriptor {
        Objects.requireNonNull(lowKey, "lowKey must not be null");
        Objects.requireNonNull(highKey, "highKey must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be non-negative, got: " + epoch);
        }
    }
}
