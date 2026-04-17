package jlsm.table;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.*;

class PartitionDescriptorTest {

    private static MemorySegment segOf(byte... bytes) {
        var arena = Arena.ofAuto();
        var seg = arena.allocate(bytes.length, 1);
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    // @spec F11.R1 — valid record construction
    @Test
    void constructsValidDescriptor() {
        var low = segOf((byte) 0x00);
        var high = segOf((byte) 0x10);
        var d = new PartitionDescriptor(1L, low, high, "node-1", 0L);
        assertEquals(1L, d.id());
        assertEquals("node-1", d.nodeId());
        assertEquals(0L, d.epoch());
    }

    // @spec F11.R2
    @Test
    void rejectsNullLowKey() {
        var high = segOf((byte) 0x10);
        assertThrows(NullPointerException.class,
                () -> new PartitionDescriptor(1L, null, high, "node-1", 0L));
    }

    // @spec F11.R3
    @Test
    void rejectsNullHighKey() {
        var low = segOf((byte) 0x00);
        assertThrows(NullPointerException.class,
                () -> new PartitionDescriptor(1L, low, null, "node-1", 0L));
    }

    // @spec F11.R4
    @Test
    void rejectsNullNodeId() {
        var low = segOf((byte) 0x00);
        var high = segOf((byte) 0x10);
        assertThrows(NullPointerException.class,
                () -> new PartitionDescriptor(1L, low, high, null, 0L));
    }

    // @spec F11.R5
    @Test
    void rejectsNegativeEpoch() {
        var low = segOf((byte) 0x00);
        var high = segOf((byte) 0x10);
        var ex = assertThrows(IllegalArgumentException.class,
                () -> new PartitionDescriptor(1L, low, high, "node-1", -1L));
        assertTrue(ex.getMessage().contains("epoch"));
    }

    @Test
    void allowsZeroEpoch() {
        var low = segOf((byte) 0x00);
        var high = segOf((byte) 0x10);
        assertDoesNotThrow(() -> new PartitionDescriptor(1L, low, high, "node-1", 0L));
    }

    @Test
    void allowsPositiveEpoch() {
        var low = segOf((byte) 0x00);
        var high = segOf((byte) 0x10);
        assertDoesNotThrow(() -> new PartitionDescriptor(1L, low, high, "node-1", 42L));
    }
}
