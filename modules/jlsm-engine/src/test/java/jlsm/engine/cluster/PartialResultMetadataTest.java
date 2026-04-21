package jlsm.engine.cluster;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PartialResultMetadata} record — defensive copy and null check.
 *
 * @spec engine.clustering.R64 — partial result metadata indicates completeness + unavailable partitions
 * @spec engine.clustering.R73 — exposes isComplete + unavailablePartitions to caller
 */
class PartialResultMetadataTest {

    @Test
    void completeResult() {
        var meta = new PartialResultMetadata(Set.of(), true);
        assertTrue(meta.isComplete());
        assertTrue(meta.unavailablePartitions().isEmpty());
    }

    @Test
    void incompleteResult() {
        var meta = new PartialResultMetadata(Set.of("p1", "p2"), false);
        assertFalse(meta.isComplete());
        assertEquals(Set.of("p1", "p2"), meta.unavailablePartitions());
    }

    @Test
    void nullUnavailablePartitionsThrows() {
        assertThrows(NullPointerException.class, () -> new PartialResultMetadata(null, true));
    }

    @Test
    void defensiveCopyOfSet() {
        var mutableSet = new HashSet<>(Set.of("p1"));
        var meta = new PartialResultMetadata(mutableSet, false);
        mutableSet.add("p2");
        // Should not affect the record
        assertEquals(1, meta.unavailablePartitions().size());
    }

    @Test
    void returnedSetIsImmutable() {
        var meta = new PartialResultMetadata(Set.of("p1"), false);
        assertThrows(UnsupportedOperationException.class,
                () -> meta.unavailablePartitions().add("p2"));
    }

    // @spec engine.clustering.R73 — metadata must expose total queried and responding counts
    @Test
    void canonicalConstructorExposesCounts() {
        var meta = new PartialResultMetadata(5, 3, Set.of("p4", "p5"), false);
        assertEquals(5, meta.totalPartitionsQueried());
        assertEquals(3, meta.respondingPartitions());
        assertEquals(Set.of("p4", "p5"), meta.unavailablePartitions());
        assertFalse(meta.isComplete());
    }

    @Test
    void negativeTotalPartitionsQueriedThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new PartialResultMetadata(-1, 0, Set.of(), true));
    }

    @Test
    void respondingExceedsTotalThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new PartialResultMetadata(3, 5, Set.of(), true));
    }

    @Test
    void negativeRespondingThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new PartialResultMetadata(3, -1, Set.of(), true));
    }
}
