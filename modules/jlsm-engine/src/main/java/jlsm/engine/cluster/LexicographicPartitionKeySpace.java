package jlsm.engine.cluster;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Lexicographically-bucketed {@link PartitionKeySpace}.
 *
 * <p>
 * Contract: Partitions are defined by an ordered list of {@code splitKeys}; partition {@code i}
 * covers {@code [splitKeys.get(i-1), splitKeys.get(i))}, partition {@code 0} covers
 * {@code [-∞, splitKeys.get(0))}, and partition {@code N} covers {@code [splitKeys.get(N-1), +∞)}.
 * Exactly one partition ID per bucket must be supplied, so
 * {@code partitionIds.size() == splitKeys.size() + 1}. Split keys must be strictly ascending (no
 * duplicates), and partition IDs must be unique. Enables scan pruning to only the overlapping
 * partitions for a {@code [fromKey, toKey)} range.
 *
 * <p>
 * Side effects: none.
 *
 * <p>
 * {@code @spec F04.R63}
 */
public final class LexicographicPartitionKeySpace implements PartitionKeySpace {

    private final List<String> splitKeys;
    private final List<String> partitionIds;

    /**
     * Creates a lexicographic keyspace.
     *
     * @param splitKeys strictly ascending list of split boundaries; must not be null, and no
     *            element may be null
     * @param partitionIds list of partition IDs whose size equals {@code splitKeys.size() + 1}; all
     *            IDs must be unique and non-null
     * @throws NullPointerException if any argument or element is null
     * @throws IllegalArgumentException if sizes mismatch, split keys are not strictly ascending, or
     *             partition IDs contain duplicates
     */
    public LexicographicPartitionKeySpace(List<String> splitKeys, List<String> partitionIds) {
        Objects.requireNonNull(splitKeys, "splitKeys");
        Objects.requireNonNull(partitionIds, "partitionIds");
        if (partitionIds.size() != splitKeys.size() + 1) {
            throw new IllegalArgumentException(
                    "partitionIds.size() must equal splitKeys.size() + 1 (got "
                            + partitionIds.size() + " vs " + splitKeys.size() + ")");
        }
        for (int i = 0; i < splitKeys.size(); i++) {
            Objects.requireNonNull(splitKeys.get(i), "splitKeys[" + i + "]");
            if (i > 0 && splitKeys.get(i - 1).compareTo(splitKeys.get(i)) >= 0) {
                throw new IllegalArgumentException(
                        "splitKeys must be strictly ascending; failed at index " + i);
            }
        }
        for (int i = 0; i < partitionIds.size(); i++) {
            Objects.requireNonNull(partitionIds.get(i), "partitionIds[" + i + "]");
        }
        if (partitionIds.stream().distinct().count() != partitionIds.size()) {
            throw new IllegalArgumentException("partitionIds must be unique");
        }
        this.splitKeys = List.copyOf(splitKeys);
        this.partitionIds = List.copyOf(partitionIds);
    }

    @Override
    public int partitionCount() {
        return partitionIds.size();
    }

    @Override
    public String partitionForKey(String key) {
        Objects.requireNonNull(key, "key");
        return partitionIds.get(bucketIndex(key));
    }

    @Override
    public List<String> partitionsForRange(String fromKey, String toKey) {
        Objects.requireNonNull(fromKey, "fromKey");
        Objects.requireNonNull(toKey, "toKey");
        // Degenerate/empty ranges overlap no partition.
        if (fromKey.compareTo(toKey) >= 0) {
            return List.of();
        }
        final int first = bucketIndex(fromKey);
        // toKey is exclusive: the last overlapping bucket contains the key one step below
        // toKey. Computing it as the bucket of toKey itself overshoots by one when toKey
        // exactly equals a split — in that case the bucket at index(toKey) does NOT overlap
        // [fromKey, toKey).
        int last = bucketIndex(toKey);
        if (last > 0 && splitKeys.get(last - 1).compareTo(toKey) == 0) {
            last--;
        }
        if (last < first) {
            return List.of();
        }
        return List.copyOf(partitionIds.subList(first, last + 1));
    }

    @Override
    public List<String> allPartitions() {
        return partitionIds;
    }

    /**
     * Returns the index of the bucket that contains {@code key}. Uses the half-open convention
     * {@code [splitKeys[i-1], splitKeys[i])}: a key equal to a split key belongs to the higher
     * bucket.
     */
    private int bucketIndex(String key) {
        assert key != null : "bucketIndex caller must have null-checked key";
        final int searchResult = Collections.binarySearch(splitKeys, key);
        if (searchResult >= 0) {
            // Exact match on splitKeys[searchResult]; key belongs to partition (searchResult + 1).
            return searchResult + 1;
        }
        // insertionPoint = -(searchResult + 1) is the first index where splitKey > key;
        // key < splitKeys[insertionPoint], so key belongs to partition insertionPoint.
        return -(searchResult + 1);
    }
}
