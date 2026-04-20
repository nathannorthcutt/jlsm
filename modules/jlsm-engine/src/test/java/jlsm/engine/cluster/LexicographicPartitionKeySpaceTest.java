package jlsm.engine.cluster;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LexicographicPartitionKeySpace} — bucketed keyspace with contiguous
 * {@code [splitKeys[i-1], splitKeys[i])} ranges.
 *
 * <p>
 * Delivers: F04.R63.
 */
final class LexicographicPartitionKeySpaceTest {

    // --- Construction — validation ---

    @Test
    void constructor_nullSplitKeysThrowsNPE() {
        assertThrows(NullPointerException.class,
                () -> new LexicographicPartitionKeySpace(null, List.of("p0")));
    }

    @Test
    void constructor_nullPartitionIdsThrowsNPE() {
        assertThrows(NullPointerException.class,
                () -> new LexicographicPartitionKeySpace(List.of(), null));
    }

    @Test
    void constructor_nullSplitKeyElementThrowsNPE() {
        List<String> splits = new ArrayList<>();
        splits.add("m");
        splits.add(null);
        assertThrows(NullPointerException.class,
                () -> new LexicographicPartitionKeySpace(splits, List.of("p0", "p1", "p2")));
    }

    @Test
    void constructor_nullPartitionIdElementThrowsNPE() {
        List<String> ids = new ArrayList<>();
        ids.add("p0");
        ids.add(null);
        assertThrows(NullPointerException.class,
                () -> new LexicographicPartitionKeySpace(List.of("m"), ids));
    }

    @Test
    void constructor_sizeMismatchThrowsIAE() {
        // splitKeys.size() + 1 != partitionIds.size()
        assertThrows(IllegalArgumentException.class,
                () -> new LexicographicPartitionKeySpace(List.of("m"), List.of("p0", "p1", "p2")));
        assertThrows(IllegalArgumentException.class,
                () -> new LexicographicPartitionKeySpace(List.of("m", "s"), List.of("p0", "p1")));
    }

    @Test
    void constructor_nonAscendingSplitKeysThrowsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> new LexicographicPartitionKeySpace(List.of("s", "m"),
                        List.of("p0", "p1", "p2")));
    }

    @Test
    void constructor_duplicateSplitKeysThrowsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> new LexicographicPartitionKeySpace(List.of("m", "m"),
                        List.of("p0", "p1", "p2")));
    }

    @Test
    void constructor_duplicatePartitionIdsThrowsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> new LexicographicPartitionKeySpace(List.of("m"), List.of("p0", "p0")));
    }

    @Test
    void constructor_emptySplitsYieldsSinglePartition() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of(),
                List.of("only"));
        assertEquals(1, ks.partitionCount());
        assertEquals(List.of("only"), ks.allPartitions());
    }

    // --- partitionCount / allPartitions ---

    @Test
    void partitionCount_matchesPartitionIdsSize() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        assertEquals(3, ks.partitionCount());
    }

    @Test
    void allPartitions_returnsConfiguredIdsInOrder() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        assertEquals(List.of("p0", "p1", "p2"), ks.allPartitions());
    }

    @Test
    void allPartitions_returnedListIsImmutable() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m"),
                List.of("p0", "p1"));
        List<String> partitions = ks.allPartitions();
        assertThrows(UnsupportedOperationException.class, () -> partitions.add("p2"));
    }

    // --- partitionForKey — boundary behaviour ---

    @Test
    void partitionForKey_nullKeyThrowsNPE() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m"),
                List.of("p0", "p1"));
        assertThrows(NullPointerException.class, () -> ks.partitionForKey(null));
    }

    @Test
    void partitionForKey_beforeFirstSplitMapsToBucketZero() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        assertEquals("p0", ks.partitionForKey(""));
        assertEquals("p0", ks.partitionForKey("a"));
        assertEquals("p0", ks.partitionForKey("l"));
    }

    @Test
    void partitionForKey_exactSplitKeyBelongsToHigherBucket() {
        // Half-open: partition i covers [splits[i-1], splits[i]), so key == split[i] belongs
        // to partition i+1.
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        assertEquals("p1", ks.partitionForKey("m"));
        assertEquals("p2", ks.partitionForKey("s"));
    }

    @Test
    void partitionForKey_betweenSplitsMapsToMiddleBucket() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        assertEquals("p1", ks.partitionForKey("n"));
        assertEquals("p1", ks.partitionForKey("r"));
    }

    @Test
    void partitionForKey_afterLastSplitMapsToHighestBucket() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        assertEquals("p2", ks.partitionForKey("t"));
        assertEquals("p2", ks.partitionForKey("z"));
        assertEquals("p2", ks.partitionForKey("\uFFFF"));
    }

    @Test
    void partitionForKey_singlePartitionReturnsOnlyId() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of(),
                List.of("only"));
        assertEquals("only", ks.partitionForKey("a"));
        assertEquals("only", ks.partitionForKey("z"));
    }

    // --- partitionsForRange ---

    @Test
    void partitionsForRange_nullFromKeyThrowsNPE() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m"),
                List.of("p0", "p1"));
        assertThrows(NullPointerException.class, () -> ks.partitionsForRange(null, "z"));
    }

    @Test
    void partitionsForRange_nullToKeyThrowsNPE() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m"),
                List.of("p0", "p1"));
        assertThrows(NullPointerException.class, () -> ks.partitionsForRange("a", null));
    }

    @Test
    void partitionsForRange_fullRangeReturnsAllPartitions() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        assertEquals(List.of("p0", "p1", "p2"), ks.partitionsForRange("", "\uFFFF"));
    }

    @Test
    void partitionsForRange_rangeWithinSinglePartitionReturnsOnlyThatPartition() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        // [a, c) falls entirely within p0
        assertEquals(List.of("p0"), ks.partitionsForRange("a", "c"));
        // [n, r) falls entirely within p1
        assertEquals(List.of("p1"), ks.partitionsForRange("n", "r"));
        // [t, z) falls entirely within p2
        assertEquals(List.of("p2"), ks.partitionsForRange("t", "z"));
    }

    @Test
    void partitionsForRange_rangeSpansTwoPartitionsReturnsBoth() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        // [a, n) overlaps p0 and p1
        assertEquals(List.of("p0", "p1"), ks.partitionsForRange("a", "n"));
        // [n, t) overlaps p1 and p2
        assertEquals(List.of("p1", "p2"), ks.partitionsForRange("n", "t"));
    }

    @Test
    void partitionsForRange_rangeSpansAllPartitionsReturnsAll() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        // [a, z) overlaps all three
        assertEquals(List.of("p0", "p1", "p2"), ks.partitionsForRange("a", "z"));
    }

    @Test
    void partitionsForRange_boundaryFromKeyBelongsToHigherPartition() {
        // fromKey == split key: the key belongs to partition i+1, so the range starts at p1.
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        assertEquals(List.of("p1", "p2"), ks.partitionsForRange("m", "z"));
    }

    @Test
    void partitionsForRange_exclusiveToKeyEqualSplitDoesNotIncludeHigherPartition() {
        // [a, m) covers [a, m) which is entirely within p0.
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        assertEquals(List.of("p0"), ks.partitionsForRange("a", "m"));
    }

    @Test
    void partitionsForRange_emptyRangeReturnsEmptyList() {
        // toKey <= fromKey is degenerate; implementations may return empty or the bucket
        // containing the point. Tests the "non-overlapping" contract by choosing a degenerate
        // range where no bucket can overlap.
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(List.of("m", "s"),
                List.of("p0", "p1", "p2"));
        assertEquals(List.of(), ks.partitionsForRange("r", "r"));
        // Strict inversion.
        assertEquals(List.of(), ks.partitionsForRange("z", "a"));
    }

    @Test
    void partitionsForRange_returnsContiguousSublist() {
        LexicographicPartitionKeySpace ks = new LexicographicPartitionKeySpace(
                Arrays.asList("c", "g", "m", "s", "w"),
                Arrays.asList("p0", "p1", "p2", "p3", "p4", "p5"));
        // [d, t) overlaps p1 (covers [c, g)), p2 (covers [g, m)), p3 (covers [m, s)), and p4
        // (starts at s because toKey=t > s).
        assertEquals(List.of("p1", "p2", "p3", "p4"), ks.partitionsForRange("d", "t"));
    }
}
