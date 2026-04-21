package jlsm.table.internal;

import jlsm.table.PartitionConfig;
import jlsm.table.PartitionDescriptor;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
// @spec partitioning.table-partitioning.R26,R96 — final class in jlsm.table.internal; immutable after construction, safe for
// multi-threaded use without synchronization
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
    // @spec partitioning.table-partitioning.R27 — null config→NPE
    public RangeMap(PartitionConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        this.descriptors = config.descriptors(); // already unmodifiable via PartitionConfig
        if (this.descriptors.isEmpty()) {
            throw new IllegalArgumentException("config must have at least one partition");
        }
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
    // @spec partitioning.table-partitioning.R28,R29,R30,R31,R32 — O(log P) binary search (R28); null key→NPE (R29);
    // below first→IAE (R30); at/above last highKey→IAE (R31); boundary routes to N+1 (R32)
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
    // @spec partitioning.table-partitioning.R33,R34,R35,R36,R38 — overlapping descriptors in key order (R33); empty/inverted
    // range→empty list (R34); no intersection→empty (R35); null from/to→NPE (R36); pLow < to AND
    // pHigh > from (R38)
    public List<PartitionDescriptor> overlapping(MemorySegment fromKey, MemorySegment toKey) {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        // Empty or inverted range [from, to) where from >= to has no overlap
        if (compareKeys(fromKey, toKey) >= 0) {
            return List.of();
        }
        // Partitions are contiguous and non-overlapping in key order, so overlapping
        // descriptors form a contiguous subsequence. Use binary search (O(log P)) to
        // find the first and last overlapping indices.

        // Binary search for the first partition whose highKey > fromKey (start of overlap).
        int startIdx = binarySearchFirstHighKeyAfter(fromKey);
        if (startIdx >= descriptors.size()) {
            return List.of();
        }
        // Binary search for the last partition whose lowKey < toKey (end of overlap).
        int endIdx = binarySearchLastLowKeyBefore(toKey);
        if (endIdx < 0 || endIdx < startIdx) {
            return List.of();
        }
        return List.copyOf(descriptors.subList(startIdx, endIdx + 1));
    }

    /**
     * Returns all partition descriptors.
     *
     * @return all descriptors in key order
     */
    // @spec partitioning.table-partitioning.R37 — returns all descriptors in key order
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
    // @spec partitioning.table-partitioning.R11,R12,R13 — unsigned byte-lex compare (R11) via MemorySegment.mismatch (R12);
    // prefix rule: mismatch == lenA → a < b (R13)
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

    /**
     * Binary search for the first partition index whose highKey > key. Since partitions are sorted
     * by lowKey (and contiguous, so also sorted by highKey), this finds the first partition that
     * could overlap a query starting at key.
     *
     * @param key the fromKey of the query range
     * @return the index of the first overlapping partition, or descriptors.size() if none
     */
    private int binarySearchFirstHighKeyAfter(MemorySegment key) {
        int lo = 0;
        int hi = descriptors.size();
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            // highKey > key means this partition (and all after) could overlap
            if (compareKeys(descriptors.get(mid).highKey(), key) > 0) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /**
     * Binary search for the last partition index whose lowKey < key. Since partitions are sorted by
     * lowKey, this finds the last partition whose range could overlap a query ending at key.
     *
     * @param key the toKey of the query range
     * @return the index of the last overlapping partition, or -1 if none
     */
    private int binarySearchLastLowKeyBefore(MemorySegment key) {
        int lo = 0;
        int hi = descriptors.size() - 1;
        int result = -1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            if (compareKeys(descriptors.get(mid).lowKey(), key) < 0) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }
}
