package jlsm.table;

import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration for a {@link PartitionedTable} specifying the partition layout.
 *
 * <p>
 * Contract: Immutable configuration holding partition descriptors that must form a contiguous,
 * non-overlapping coverage of the keyspace. Validated at construction time.
 *
 * <p>
 * Governed by: .decisions/table-partitioning/adr.md — static partitions, boundaries fixed at
 * creation.
 */
// @spec F11.R14 — public final class in jlsm.table with static factory
// of(List<PartitionDescriptor>)
public final class PartitionConfig {

    private final List<PartitionDescriptor> descriptors;

    private PartitionConfig(List<PartitionDescriptor> descriptors) {
        assert descriptors != null : "descriptors must not be null";
        assert !descriptors.isEmpty() : "descriptors must not be empty";
        this.descriptors = List.copyOf(descriptors);
    }

    /**
     * Creates a partition configuration from a list of descriptors.
     *
     * <p>
     * Contract:
     * <ul>
     * <li>Receives: list of {@link PartitionDescriptor} — must be non-empty, contiguous,
     * non-overlapping, covering the full keyspace</li>
     * <li>Returns: validated {@code PartitionConfig}</li>
     * <li>Side effects: none</li>
     * <li>Error conditions: throws {@link IllegalArgumentException} if descriptors overlap, have
     * gaps, or are empty</li>
     * </ul>
     *
     * @param descriptors the partition descriptors in key order
     * @return a validated partition configuration
     */
    // @spec F11.R15,R16,R17,R18,R19 — null list→NPE (R15); empty→IAE (R16); null element→NPE with
    // index (R17); duplicate id→IAE with id+index (R18); gap/overlap→IAE identifying index (R19)
    public static PartitionConfig of(List<PartitionDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors must not be null");
        if (descriptors.isEmpty()) {
            throw new IllegalArgumentException("descriptors must contain at least one partition");
        }
        // Validate elements are non-null, IDs are unique, and boundaries are contiguous
        final Set<Long> seenIds = new HashSet<>();
        for (int i = 0; i < descriptors.size(); i++) {
            var d = descriptors.get(i);
            Objects.requireNonNull(d,
                    "descriptors must not contain null elements (index " + i + ")");
            if (!seenIds.add(d.id())) {
                throw new IllegalArgumentException(
                        "duplicate partition descriptor id: " + d.id() + " (index " + i + ")");
            }
            if (i > 0) {
                var prev = descriptors.get(i - 1);
                // prev.highKey must equal d.lowKey (contiguous, non-overlapping)
                if (!segmentsEqual(prev.highKey(), d.lowKey())) {
                    throw new IllegalArgumentException(
                            "Partition boundary gap or overlap between partition " + (i - 1)
                                    + " (highKey) and partition " + i
                                    + " (lowKey): they must be equal for contiguous coverage");
                }
            }
        }
        return new PartitionConfig(descriptors);
    }

    /**
     * Returns the unmodifiable list of partition descriptors in key order.
     *
     * @return partition descriptors
     */
    // @spec F11.R20 — returns unmodifiable list via List.copyOf in constructor
    public List<PartitionDescriptor> descriptors() {
        return descriptors;
    }

    /**
     * Returns the number of partitions.
     *
     * @return partition count
     */
    // @spec F11.R21 — returns descriptors.size()
    public int partitionCount() {
        return descriptors.size();
    }

    /**
     * Compares two {@link MemorySegment} keys for byte-lexicographic equality.
     *
     * @param a first segment
     * @param b second segment
     * @return true if the segments are byte-for-byte equal
     */
    static boolean segmentsEqual(MemorySegment a, MemorySegment b) {
        assert a != null : "a must not be null";
        assert b != null : "b must not be null";
        return a.mismatch(b) == -1L;
    }
}
