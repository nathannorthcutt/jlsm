package jlsm.table.internal;

import jlsm.table.PartitionConfig;
import jlsm.table.PartitionDescriptor;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Routes keys to partitions via binary search on range boundaries.
 *
 * <p>
 * Contract: Immutable routing structure built from a {@link PartitionConfig}. Provides O(log P)
 * key-to-partition lookup and range-overlap queries.
 *
 * <p>
 * Governed by: .decisions/table-partitioning/adr.md — range map with O(log P) routing. KB
 * reference: .kb/distributed-systems/data-partitioning/partitioning-strategies.md#routing
 */
public final class RangeMap {

    // Ordered list of partition descriptors (key order guaranteed by PartitionConfig validation)
    private final List<PartitionDescriptor> descriptors;

    /**
     * Creates a range map from the given partition configuration.
     *
     * <p>
     * Contract:
     * <ul>
     * <li>Receives: validated {@link PartitionConfig} with contiguous, non-overlapping ranges</li>
     * <li>Returns: range map ready for routing</li>
     * <li>Side effects: none</li>
     * </ul>
     *
     * @param config the partition configuration
     */
    public RangeMap(PartitionConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        this.descriptors = config.descriptors(); // already unmodifiable via PartitionConfig
        assert !this.descriptors.isEmpty() : "config must have at least one partition";
    }

    /**
     * Routes a key (as raw bytes) to the owning partition descriptor.
     *
     * <p>
     * Contract:
     * <ul>
     * <li>Receives: key as {@link MemorySegment} in lexicographic byte order</li>
     * <li>Returns: the {@link PartitionDescriptor} whose range contains the key</li>
     * <li>Error conditions: throws {@link IllegalArgumentException} if key is outside all
     * ranges</li>
     * </ul>
     *
     * @param key the key to route
     * @return the owning partition descriptor
     */
    public PartitionDescriptor routeKey(MemorySegment key) {
        Objects.requireNonNull(key, "key must not be null");
        // Binary search: find the last partition whose lowKey <= key
        int lo = 0;
        int hi = descriptors.size() - 1;
        int candidate = -1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            var d = descriptors.get(mid);
            int cmp = compareKeys(d.lowKey(), key);
            if (cmp <= 0) {
                // d.lowKey <= key — this partition is a candidate
                candidate = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (candidate == -1) {
            throw new IllegalArgumentException(
                    "Key is below all partition ranges — no owning partition found");
        }
        var d = descriptors.get(candidate);
        // Verify key < highKey (half-open range: [lowKey, highKey))
        if (compareKeys(key, d.highKey()) >= 0) {
            throw new IllegalArgumentException(
                    "Key is at or above the high bound of the last candidate partition — "
                            + "no owning partition found");
        }
        assert compareKeys(d.lowKey(), key) <= 0 : "routeKey invariant: lowKey <= key";
        assert compareKeys(key, d.highKey()) < 0 : "routeKey invariant: key < highKey";
        return d;
    }

    /**
     * Returns all partition descriptors whose ranges overlap the given key range.
     *
     * <p>
     * Contract:
     * <ul>
     * <li>Receives: half-open range [fromKey, toKey)</li>
     * <li>Returns: list of overlapping descriptors in key order</li>
     * <li>Side effects: none</li>
     * </ul>
     *
     * @param fromKey inclusive lower bound
     * @param toKey exclusive upper bound
     * @return overlapping partition descriptors
     */
    public List<PartitionDescriptor> overlapping(MemorySegment fromKey, MemorySegment toKey) {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        // Empty or inverted range [from, to) where from >= to has no overlap
        if (compareKeys(fromKey, toKey) >= 0) {
            return List.of();
        }
        // A partition [pLow, pHigh) overlaps query [from, to) iff:
        // pLow < to AND pHigh > from
        List<PartitionDescriptor> result = new ArrayList<>();
        for (var d : descriptors) {
            boolean lowBeforeTo = compareKeys(d.lowKey(), toKey) < 0;
            boolean highAfterFrom = compareKeys(d.highKey(), fromKey) > 0;
            if (lowBeforeTo && highAfterFrom) {
                result.add(d);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns all partition descriptors.
     *
     * @return all descriptors in key order
     */
    public List<PartitionDescriptor> all() {
        return descriptors;
    }

    /**
     * Compares two {@link MemorySegment} keys using unsigned byte-lexicographic order.
     *
     * @param a first key segment
     * @param b second key segment
     * @return negative if a &lt; b, 0 if equal, positive if a &gt; b
     */
    private static int compareKeys(MemorySegment a, MemorySegment b) {
        assert a != null : "a must not be null";
        assert b != null : "b must not be null";
        long lenA = a.byteSize();
        long lenB = b.byteSize();
        long mismatch = a.mismatch(b);
        if (mismatch == -1L) {
            return 0;
        }
        if (mismatch == lenA) {
            return -1; // a is a prefix of b, so a < b
        }
        if (mismatch == lenB) {
            return 1; // b is a prefix of a, so a > b
        }
        int ba = Byte.toUnsignedInt(a.get(ValueLayout.JAVA_BYTE, mismatch));
        int bb = Byte.toUnsignedInt(b.get(ValueLayout.JAVA_BYTE, mismatch));
        return Integer.compare(ba, bb);
    }
}
