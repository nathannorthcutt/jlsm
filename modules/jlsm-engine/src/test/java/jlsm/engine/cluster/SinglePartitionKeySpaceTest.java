package jlsm.engine.cluster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SinglePartitionKeySpace} — fallback keyspace where every key maps to the sole
 * partition.
 *
 * <p>
 * Delivers: F04.R63.
 */
final class SinglePartitionKeySpaceTest {

    // --- Construction ---

    @Test
    void constructor_nullPartitionId_throwsNPE() {
        assertThrows(NullPointerException.class, () -> new SinglePartitionKeySpace(null));
    }

    @Test
    void constructor_acceptsEmptyString() {
        // Empty string is a valid (if unusual) partition ID.
        assertDoesNotThrow(() -> new SinglePartitionKeySpace(""));
    }

    // --- partitionCount ---

    @Test
    void partitionCount_returnsOne() {
        assertEquals(1, new SinglePartitionKeySpace("only").partitionCount());
    }

    // --- partitionForKey ---

    @Test
    void partitionForKey_returnsConfiguredPartitionIdForAnyKey() {
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("p-default");
        assertEquals("p-default", ks.partitionForKey("a"));
        assertEquals("p-default", ks.partitionForKey("z"));
        assertEquals("p-default", ks.partitionForKey(""));
        assertEquals("p-default", ks.partitionForKey("\uFFFF"));
    }

    @Test
    void partitionForKey_nullKeyThrowsNPE() {
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("p-default");
        assertThrows(NullPointerException.class, () -> ks.partitionForKey(null));
    }

    // --- partitionsForRange ---

    @Test
    void partitionsForRange_returnsSingletonListForAnyRange() {
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("p-default");
        assertEquals(List.of("p-default"), ks.partitionsForRange("a", "z"));
        assertEquals(List.of("p-default"), ks.partitionsForRange("", "\uFFFF"));
        assertEquals(List.of("p-default"), ks.partitionsForRange("x", "x"));
    }

    @Test
    void partitionsForRange_nullFromKeyThrowsNPE() {
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("p-default");
        assertThrows(NullPointerException.class, () -> ks.partitionsForRange(null, "z"));
    }

    @Test
    void partitionsForRange_nullToKeyThrowsNPE() {
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("p-default");
        assertThrows(NullPointerException.class, () -> ks.partitionsForRange("a", null));
    }

    // --- allPartitions ---

    @Test
    void allPartitions_returnsSingletonList() {
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("p-default");
        assertEquals(List.of("p-default"), ks.allPartitions());
    }

    @Test
    void allPartitions_returnedListIsImmutable() {
        SinglePartitionKeySpace ks = new SinglePartitionKeySpace("p-default");
        List<String> partitions = ks.allPartitions();
        assertThrows(UnsupportedOperationException.class, () -> partitions.add("p-other"));
    }
}
