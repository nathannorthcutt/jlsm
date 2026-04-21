package jlsm.table.internal;

import jlsm.table.PartitionConfig;
import jlsm.table.PartitionDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RangeMapTest {

    // Three partitions: [0x00,0x40), [0x40,0x80), [0x80,0xFF)
    private PartitionConfig config;
    private PartitionDescriptor p1;
    private PartitionDescriptor p2;
    private PartitionDescriptor p3;

    private static MemorySegment seg(int... bytes) {
        var arena = Arena.ofAuto();
        var s = arena.allocate(bytes.length, 1);
        for (int i = 0; i < bytes.length; i++) {
            s.set(ValueLayout.JAVA_BYTE, i, (byte) bytes[i]);
        }
        return s;
    }

    private static PartitionDescriptor desc(long id, int low, int high) {
        return new PartitionDescriptor(id, seg(low), seg(high), "node-" + id, 0L);
    }

    @BeforeEach
    void setUp() {
        p1 = desc(1L, 0x00, 0x40);
        p2 = desc(2L, 0x40, 0x80);
        p3 = desc(3L, 0x80, 0xFF);
        config = PartitionConfig.of(List.of(p1, p2, p3));
    }

    // --- constructor ---

    @Test
    void constructsFromConfig() {
        assertDoesNotThrow(() -> new RangeMap(config));
    }

    // @spec partitioning.table-partitioning.R27
    @Test
    void rejectsNullConfig() {
        assertThrows(NullPointerException.class, () -> new RangeMap(null));
    }

    // --- all() ---

    // @spec partitioning.table-partitioning.R37
    @Test
    void allReturnsAllDescriptorsInOrder() {
        var rm = new RangeMap(config);
        var all = rm.all();
        assertEquals(3, all.size());
        assertEquals(p1, all.get(0));
        assertEquals(p2, all.get(1));
        assertEquals(p3, all.get(2));
    }

    @Test
    void allListIsUnmodifiable() {
        var rm = new RangeMap(config);
        assertThrows(UnsupportedOperationException.class, () -> rm.all().clear());
    }

    // --- routeKey() ---

    @Test
    void routesKeyInFirstPartition() {
        var rm = new RangeMap(config);
        assertEquals(p1, rm.routeKey(seg(0x00)));
    }

    @Test
    void routesKeyAtFirstPartitionLowBound() {
        var rm = new RangeMap(config);
        assertEquals(p1, rm.routeKey(seg(0x00)));
    }

    @Test
    void routesKeyJustBelowMidBoundary() {
        var rm = new RangeMap(config);
        assertEquals(p1, rm.routeKey(seg(0x3F)));
    }

    // @spec partitioning.table-partitioning.R32 — boundary routes to partition N+1
    @Test
    void routesKeyAtMidBoundary() {
        // 0x40 is the inclusive start of p2
        var rm = new RangeMap(config);
        assertEquals(p2, rm.routeKey(seg(0x40)));
    }

    @Test
    void routesKeyInSecondPartition() {
        var rm = new RangeMap(config);
        assertEquals(p2, rm.routeKey(seg(0x60)));
    }

    @Test
    void routesKeyAtSecondBoundary() {
        // 0x80 is the inclusive start of p3
        var rm = new RangeMap(config);
        assertEquals(p3, rm.routeKey(seg(0x80)));
    }

    @Test
    void routesKeyInThirdPartition() {
        var rm = new RangeMap(config);
        assertEquals(p3, rm.routeKey(seg(0xC0)));
    }

    @Test
    void routesKeyJustBelowHighBound() {
        // 0xFE is within p3 (high is exclusive 0xFF)
        var rm = new RangeMap(config);
        assertEquals(p3, rm.routeKey(seg(0xFE)));
    }

    // @spec partitioning.table-partitioning.R30
    @Test
    void throwsForKeyBelowAllPartitions() {
        // Keys before 0x00 are impossible with byte values, but test multi-byte
        // Create config with [0x10,0x40) and [0x40,0x80) — key 0x05 is before
        var d1 = desc(1L, 0x10, 0x40);
        var d2 = desc(2L, 0x40, 0x80);
        var cfg = PartitionConfig.of(List.of(d1, d2));
        var rm = new RangeMap(cfg);
        assertThrows(IllegalArgumentException.class, () -> rm.routeKey(seg(0x05)));
    }

    // @spec partitioning.table-partitioning.R31
    @Test
    void throwsForKeyAtOrAboveHighBound() {
        // 0xFF is the exclusive upper bound of p3 — should not be in any partition
        var rm = new RangeMap(config);
        assertThrows(IllegalArgumentException.class, () -> rm.routeKey(seg(0xFF)));
    }

    // @spec partitioning.table-partitioning.R29
    @Test
    void rejectsNullKeyInRouteKey() {
        var rm = new RangeMap(config);
        assertThrows(NullPointerException.class, () -> rm.routeKey(null));
    }

    // --- overlapping() ---

    // @spec partitioning.table-partitioning.R33 — overlap across full range
    @Test
    void overlappingEntireRangeReturnsAll() {
        var rm = new RangeMap(config);
        var result = rm.overlapping(seg(0x00), seg(0xFF));
        assertEquals(3, result.size());
    }

    @Test
    void overlappingFirstPartitionOnly() {
        var rm = new RangeMap(config);
        // [0x00, 0x30) only overlaps p1
        var result = rm.overlapping(seg(0x00), seg(0x30));
        assertEquals(1, result.size());
        assertEquals(p1, result.get(0));
    }

    @Test
    void overlappingSpansTwoPartitions() {
        var rm = new RangeMap(config);
        // [0x30, 0x60) spans p1 and p2
        var result = rm.overlapping(seg(0x30), seg(0x60));
        assertEquals(2, result.size());
        assertEquals(p1, result.get(0));
        assertEquals(p2, result.get(1));
    }

    @Test
    void overlappingExactBoundaryIsExclusive() {
        var rm = new RangeMap(config);
        // [0x00, 0x40) exactly spans only p1 because p2 starts at 0x40
        var result = rm.overlapping(seg(0x00), seg(0x40));
        assertEquals(1, result.size());
        assertEquals(p1, result.get(0));
    }

    @Test
    void overlappingResultListIsUnmodifiable() {
        var rm = new RangeMap(config);
        var result = rm.overlapping(seg(0x00), seg(0xFF));
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    // @spec partitioning.table-partitioning.R36
    @Test
    void rejectsNullFromKeyInOverlapping() {
        var rm = new RangeMap(config);
        assertThrows(NullPointerException.class, () -> rm.overlapping(null, seg(0xFF)));
    }

    // @spec partitioning.table-partitioning.R36
    @Test
    void rejectsNullToKeyInOverlapping() {
        var rm = new RangeMap(config);
        assertThrows(NullPointerException.class, () -> rm.overlapping(seg(0x00), null));
    }
}
