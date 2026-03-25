package jlsm.table.internal;

import jlsm.table.PartitionConfig;
import jlsm.table.PartitionDescriptor;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for {@link RangeMap}.
 */
class RangeMapAdversarialTest {

    private static MemorySegment seg(int... bytes) {
        final var arena = Arena.ofAuto();
        final var s = arena.allocate(bytes.length, 1);
        for (int i = 0; i < bytes.length; i++) {
            s.set(ValueLayout.JAVA_BYTE, i, (byte) bytes[i]);
        }
        return s;
    }

    private static PartitionDescriptor desc(long id, int low, int high) {
        return new PartitionDescriptor(id, seg(low), seg(high), "node-" + id, 0L);
    }

    // --- RM-1: overlapping() with inverted range (from > to) ---

    /**
     * Finding RM-1: overlapping() does not validate fromKey < toKey. KB match:
     * between-inverted-range — inverted bounds passed without validation. An inverted range should
     * either throw IAE or return an empty list.
     */
    @Test
    void overlapping_invertedRange_returnsEmptyOrThrows() {
        final var p1 = desc(1L, 0x00, 0x40);
        final var p2 = desc(2L, 0x40, 0x80);
        final var p3 = desc(3L, 0x80, 0xFF);
        final var config = PartitionConfig.of(List.of(p1, p2, p3));
        final var rm = new RangeMap(config);

        // Inverted: from=0x80, to=0x20 (from > to)
        // Should either throw IAE or return empty list — NOT return partitions
        try {
            final var result = rm.overlapping(seg(0x80), seg(0x20));
            assertTrue(result.isEmpty(),
                    "inverted range [0x80, 0x20) should return empty result, not: " + result.size()
                            + " partitions");
        } catch (IllegalArgumentException _) {
            // Also acceptable: throw on inverted range
        }
    }

    /**
     * Finding RM-1: overlapping() with fromKey == toKey (empty range). An empty query range should
     * return no partitions.
     */
    @ Test
    void overlapping_emptyRange_returnsEmpty() {
        final var p1 = desc(1L, 0x00, 0x40);
        final var p2 = desc(2L, 0x40, 0x80);
        final var config = PartitionConfig.of(List.of(p1, p2));
        final var rm = new RangeMap(config);

        // from == to: empty range [0x20, 0x20)
        final var result = rm.overlapping(seg(0x20), seg(0x20));
        assertTrue(result.isEmpty(),
                "empty range [from, from) should return no overlapping partitions");
    }
}
