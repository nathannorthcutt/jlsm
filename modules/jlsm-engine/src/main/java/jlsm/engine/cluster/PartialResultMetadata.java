package jlsm.engine.cluster;

import java.util.Objects;
import java.util.Set;

/**
 * Metadata describing the completeness of a scatter-gather query result.
 *
 * <p>
 * Contract: Immutable value type attached to query results from partitioned tables. Exposes the
 * total number of partitions queried, the number that responded, the set of unavailable partition
 * identifiers, and whether the result is complete. Callers inspect this to decide whether a partial
 * result is acceptable or to retry against the missing partitions.
 *
 * <p>
 * Governed by: {@code .decisions/scatter-gather-query-execution/adr.md}
 *
 * @param totalPartitionsQueried total number of partitions the scatter-gather fanout targeted; must
 *            be non-negative
 * @param respondingPartitions number of partitions that returned a usable response; must be in [0,
 *            totalPartitionsQueried]
 * @param unavailablePartitions the set of partition identifiers that could not be reached; must not
 *            be null (empty if all partitions responded)
 * @param isComplete {@code true} if every targeted partition contributed a result
 */
// @spec engine.clustering.R64,R73 — expose total, responding, unavailable, completeness
public record PartialResultMetadata(int totalPartitionsQueried, int respondingPartitions,
        Set<String> unavailablePartitions, boolean isComplete) {

    public PartialResultMetadata {
        Objects.requireNonNull(unavailablePartitions, "unavailablePartitions must not be null");
        if (totalPartitionsQueried < 0) {
            throw new IllegalArgumentException(
                    "totalPartitionsQueried must be non-negative, got: " + totalPartitionsQueried);
        }
        if (respondingPartitions < 0 || respondingPartitions > totalPartitionsQueried) {
            throw new IllegalArgumentException("respondingPartitions must be in [0, "
                    + totalPartitionsQueried + "], got: " + respondingPartitions);
        }
        unavailablePartitions = Set.copyOf(unavailablePartitions);
    }

    /**
     * Legacy convenience constructor for callers that have not yet been updated to supply
     * {@code totalPartitionsQueried} and {@code respondingPartitions}. The counts are inferred
     * conservatively from {@code unavailablePartitions.size()} and {@code isComplete}: on a
     * complete result, responding equals the unavailable-set size (which is zero) and total is also
     * zero; on a partial result, total equals the unavailable-set size and responding is zero. This
     * loses the actual partition counts — prefer the canonical 4-arg constructor when the call site
     * knows them.
     *
     * @param unavailablePartitions the set of unavailable partition identifiers; must not be null
     * @param isComplete {@code true} if all targeted partitions responded
     */
    public PartialResultMetadata(Set<String> unavailablePartitions, boolean isComplete) {
        this(Objects.requireNonNull(unavailablePartitions, "unavailablePartitions must not be null")
                .size(), isComplete ? unavailablePartitions.size() : 0, unavailablePartitions,
                isComplete);
    }
}
