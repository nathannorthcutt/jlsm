package jlsm.table;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
        // Defensive copy: MemorySegment.ofArray wraps the backing byte[] without copying,
        // so callers could mutate the original array and corrupt this descriptor's range.
        lowKey = copySegment(lowKey);
        highKey = copySegment(highKey);
        // Validate range: lowKey must be strictly less than highKey (non-empty range)
        if (compareKeys(lowKey, highKey) >= 0) {
            throw new IllegalArgumentException(
                    "lowKey must be strictly less than highKey (half-open range [low, high))");
        }
    }

    /**
     * Creates an independent copy of the given segment's byte content.
     */
    private static MemorySegment copySegment(MemorySegment src) {
        final byte[] bytes = src.toArray(ValueLayout.JAVA_BYTE);
        return MemorySegment.ofArray(bytes);
    }

    /**
     * Unsigned byte-lexicographic comparison of two segments.
     */
    private static int compareKeys(MemorySegment a, MemorySegment b) {
        final long lenA = a.byteSize();
        final long lenB = b.byteSize();
        final long mismatch = a.mismatch(b);
        if (mismatch == -1L) {
            return 0;
        }
        if (mismatch == lenA) {
            return -1;
        }
        if (mismatch == lenB) {
            return 1;
        }
        return Integer.compare(Byte.toUnsignedInt(a.get(ValueLayout.JAVA_BYTE, mismatch)),
                Byte.toUnsignedInt(b.get(ValueLayout.JAVA_BYTE, mismatch)));
    }
}
