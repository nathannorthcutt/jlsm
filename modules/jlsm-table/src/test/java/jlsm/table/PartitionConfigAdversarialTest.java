package jlsm.table;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for {@link PartitionConfig}.
 *
 * <p>
 * Targets finding PC-3 (duplicate partition ID validation) from the table-partitioning adversarial
 * audit round 2.
 */
class PartitionConfigAdversarialTest {

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

    // --- PC-3: DUPLICATE-ID-NO-VALIDATION (Round 2) ---

    /**
     * Finding PC-3: PartitionConfig.of() does not validate unique partition IDs. If two descriptors
     * share an ID, downstream consumers (PartitionedTable's LinkedHashMap) silently overwrite the
     * first client, leaking it. The builder catches this, but PartitionConfig itself should reject
     * it at the validation layer.
     */
    // @spec F11.R18 — duplicate partition IDs rejected
    @Test
    void of_duplicatePartitionIds_throwsIllegalArgumentException() {
        // Two contiguous descriptors with the SAME ID (1L)
        var d1 = desc(1L, 0x00, 0x80);
        var d2 = new PartitionDescriptor(1L, seg(0x80), seg(0xFF), "node-2", 0L);
        assertThrows(IllegalArgumentException.class, () -> PartitionConfig.of(List.of(d1, d2)),
                "PartitionConfig should reject descriptors with duplicate IDs");
    }
}
