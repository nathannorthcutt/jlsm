package jlsm.table;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PartitionConfigTest {

    // Helper: create a MemorySegment from a single byte value
    private static MemorySegment seg(int... bytes) {
        var arena = Arena.ofAuto();
        var seg = arena.allocate(bytes.length, 1);
        for (int i = 0; i < bytes.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, (byte) bytes[i]);
        }
        return seg;
    }

    // Helper: build a descriptor with a single-byte low/high
    private static PartitionDescriptor desc(long id, int low, int high) {
        return new PartitionDescriptor(id, seg(low), seg(high), "node-" + id, 0L);
    }

    // Two contiguous descriptors: [0x00, 0x80) and [0x80, 0xFF)
    private List<PartitionDescriptor> twoPartitions() {
        return List.of(desc(1L, 0x00, 0x80), desc(2L, 0x80, 0xFF));
    }

    // Three contiguous descriptors
    private List<PartitionDescriptor> threePartitions() {
        return List.of(desc(1L, 0x00, 0x40), desc(2L, 0x40, 0x80), desc(3L, 0x80, 0xFF));
    }

    @Test
    void constructsValidConfig() {
        var config = PartitionConfig.of(twoPartitions());
        assertNotNull(config);
        assertEquals(2, config.partitionCount());
    }

    @Test
    void descriptorsReturnedInOrder() {
        var input = twoPartitions();
        var config = PartitionConfig.of(input);
        var descs = config.descriptors();
        assertEquals(2, descs.size());
        assertEquals(input.get(0), descs.get(0));
        assertEquals(input.get(1), descs.get(1));
    }

    // @spec F11.R20 — returned list is unmodifiable
    @Test
    void descriptorListIsUnmodifiable() {
        var config = PartitionConfig.of(twoPartitions());
        assertThrows(UnsupportedOperationException.class, () -> config.descriptors().clear());
    }

    @Test
    void acceptsThreeContiguousPartitions() {
        assertDoesNotThrow(() -> PartitionConfig.of(threePartitions()));
    }

    // @spec F11.R15
    @Test
    void rejectsNullList() {
        assertThrows(NullPointerException.class, () -> PartitionConfig.of(null));
    }

    // @spec F11.R16
    @Test
    void rejectsEmptyList() {
        var ex = assertThrows(IllegalArgumentException.class, () -> PartitionConfig.of(List.of()));
        assertTrue(ex.getMessage().toLowerCase().contains("empty")
                || ex.getMessage().toLowerCase().contains("at least"));
    }

    // @spec F11.R19 — gap rejected as boundary mismatch
    @Test
    void rejectsGapBetweenPartitions() {
        // Gap: first ends at 0x40, second starts at 0x80
        var gapped = List.of(desc(1L, 0x00, 0x40), desc(2L, 0x80, 0xFF));
        assertThrows(IllegalArgumentException.class, () -> PartitionConfig.of(gapped));
    }

    // @spec F11.R19 — overlap rejected as boundary mismatch
    @Test
    void rejectsOverlappingPartitions() {
        // Overlap: first ends at 0x80, second starts at 0x40
        var overlapping = List.of(desc(1L, 0x00, 0x80), desc(2L, 0x40, 0xFF));
        assertThrows(IllegalArgumentException.class, () -> PartitionConfig.of(overlapping));
    }

    // @spec F11.R17
    @Test
    void rejectsNullElementInList() {
        var withNull = new ArrayList<PartitionDescriptor>();
        withNull.add(desc(1L, 0x00, 0x80));
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> PartitionConfig.of(withNull));
    }

    @Test
    void acceptsSinglePartition() {
        var single = List.of(desc(1L, 0x00, 0xFF));
        var config = PartitionConfig.of(single);
        assertEquals(1, config.partitionCount());
    }

    // @spec F11.R21
    @Test
    void partitionCountMatchesDescriptorListSize() {
        var config = PartitionConfig.of(threePartitions());
        assertEquals(config.descriptors().size(), config.partitionCount());
    }
}
