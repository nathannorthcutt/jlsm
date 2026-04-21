package jlsm.table;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
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
// @spec partitioning.table-partitioning.R1 — public record in jlsm.table with the five documented
// components
public record PartitionDescriptor(long id, MemorySegment lowKey, MemorySegment highKey,
        String nodeId, long epoch) {

    // @spec partitioning.table-partitioning.R2,R3,R4,R5,R6,R7,R8 — null-reject (R2-R4), epoch >= 0
    // (R5), defensive copy (R6),
    // read-only segments (R7), lowKey < highKey in unsigned byte-lex order (R8,R11)
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
     * Creates an independent, read-only copy of the given segment's byte content. The returned
     * segment cannot be mutated, preventing callers from corrupting the descriptor's internal state
     * via accessor methods.
     */
    private static MemorySegment copySegment(MemorySegment src) {
        final byte[] bytes = src.toArray(ValueLayout.JAVA_BYTE);
        return MemorySegment.ofArray(bytes).asReadOnly();
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

    /**
     * Returns the byte content of a segment as a byte array for content-based comparison.
     */
    private static byte[] segmentBytes(MemorySegment seg) {
        return seg.toArray(ValueLayout.JAVA_BYTE);
    }

    /**
     * Content-based equality: two descriptors are equal if all their fields contain the same
     * values. MemorySegment fields are compared by byte content, not by address identity.
     */
    // @spec partitioning.table-partitioning.R9,R10 — content-based equals (R9); hashCode consistent
    // below (R10)
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof PartitionDescriptor other && id == other.id && epoch == other.epoch
                && Arrays.equals(segmentBytes(lowKey), segmentBytes(other.lowKey))
                && Arrays.equals(segmentBytes(highKey), segmentBytes(other.highKey))
                && Objects.equals(nodeId, other.nodeId);
    }

    /**
     * Content-based hash code consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        int result = Long.hashCode(id);
        result = 31 * result + Arrays.hashCode(segmentBytes(lowKey));
        result = 31 * result + Arrays.hashCode(segmentBytes(highKey));
        result = 31 * result + nodeId.hashCode();
        result = 31 * result + Long.hashCode(epoch);
        return result;
    }
}
