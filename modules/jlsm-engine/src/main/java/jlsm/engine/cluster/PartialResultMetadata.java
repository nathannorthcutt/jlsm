package jlsm.engine.cluster;

import java.util.Objects;
import java.util.Set;

/**
 * Metadata describing the completeness of a scatter-gather query result.
 *
 * <p>
 * Contract: Immutable value type attached to query results from partitioned tables. Tracks
 * which partitions were unavailable during query execution, allowing callers to determine
 * whether the result set is complete.
 *
 * <p>
 * Governed by: {@code .decisions/scatter-gather-query-execution/adr.md}
 *
 * @param unavailablePartitions the set of partition identifiers that could not be reached;
 *                              must not be null (empty if all partitions responded)
 * @param isComplete            {@code true} if all partitions contributed to the result
 */
public record PartialResultMetadata(Set<String> unavailablePartitions, boolean isComplete) {

    public PartialResultMetadata {
        Objects.requireNonNull(unavailablePartitions, "unavailablePartitions must not be null");
        unavailablePartitions = Set.copyOf(unavailablePartitions);
    }
}
