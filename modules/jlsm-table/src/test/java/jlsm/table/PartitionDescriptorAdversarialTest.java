package jlsm.table;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for {@link PartitionDescriptor}.
 */
class PartitionDescriptorAdversarialTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    // --- PD-1: MemorySegment not defensively copied ---

    /**
     * Finding PD-1: MemorySegment.ofArray wraps the backing byte[] without copying. Mutating the
     * original array after construction corrupts the descriptor's key range. KB match:
     * mutable-array-in-record
     */
    // @spec partitioning.table-partitioning.R6 — defensive copy of lowKey
    @Test
    void lowKey_mutationOfBackingArray_doesNotCorruptDescriptor() {
        final byte[] lowBytes = "aaa".getBytes(StandardCharsets.UTF_8);
        final MemorySegment lowKey = MemorySegment.ofArray(lowBytes);
        final MemorySegment highKey = seg("zzz");

        final var desc = new PartitionDescriptor(1L, lowKey, highKey, "local", 0L);

        // Capture the original lowKey byte content
        final byte[] originalLow = desc.lowKey().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);

        // Mutate the backing array after construction
        lowBytes[0] = (byte) 'z';

        // The descriptor's lowKey should be unaffected by the mutation
        final byte[] afterMutation = desc.lowKey().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        assertArrayEquals(originalLow, afterMutation,
                "PartitionDescriptor lowKey must be defensively copied — "
                        + "mutating the original backing array should not change the descriptor");
    }

    /**
     * Finding PD-1: Same as above for highKey.
     */
    // @spec partitioning.table-partitioning.R6 — defensive copy of highKey
    @Test
    void highKey_mutationOfBackingArray_doesNotCorruptDescriptor() {
        final byte[] highBytes = "zzz".getBytes(StandardCharsets.UTF_8);
        final MemorySegment lowKey = seg("aaa");
        final MemorySegment highKey = MemorySegment.ofArray(highBytes);

        final var desc = new PartitionDescriptor(1L, lowKey, highKey, "local", 0L);

        final byte[] originalHigh = desc.highKey().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);

        // Mutate the backing array after construction
        highBytes[0] = (byte) 'a';

        final byte[] afterMutation = desc.highKey()
                .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        assertArrayEquals(originalHigh, afterMutation,
                "PartitionDescriptor highKey must be defensively copied — "
                        + "mutating the original backing array should not change the descriptor");
    }

    // --- PD-2: No validation that lowKey < highKey ---

    /**
     * Finding PD-2: Constructor does not validate that lowKey is strictly less than highKey. A
     * descriptor with lowKey == highKey represents an empty range — meaningless partition.
     */
    // @spec partitioning.table-partitioning.R8 — reject lowKey == highKey
    @Test
    void constructor_lowKeyEqualsHighKey_throwsIllegalArgumentException() {
        final MemorySegment key = seg("mmm");
        assertThrows(IllegalArgumentException.class,
                () -> new PartitionDescriptor(1L, key, seg("mmm"), "local", 0L),
                "PartitionDescriptor should reject lowKey == highKey (empty range)");
    }

    /**
     * Finding PD-2: Constructor does not validate that lowKey < highKey. A descriptor with lowKey >
     * highKey represents an inverted range.
     */
    // @spec partitioning.table-partitioning.R8 — reject lowKey > highKey
    @Test
    void constructor_lowKeyGreaterThanHighKey_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new PartitionDescriptor(1L, seg("zzz"), seg("aaa"), "local", 0L),
                "PartitionDescriptor should reject lowKey > highKey (inverted range)");
    }

    // --- PD-3: ACCESSOR-MUTATION (Round 2) ---

    private static MemorySegment segOf(byte... bytes) {
        var arena = Arena.ofAuto();
        var s = arena.allocate(bytes.length, 1);
        MemorySegment.copy(bytes, 0, s, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return s;
    }

    /**
     * Finding PD-3: lowKey() returns a mutable MemorySegment. Callers can corrupt the descriptor's
     * internal state via desc.lowKey().set(...). The accessor should either return a read-only
     * segment or an independent copy.
     */
    // @spec partitioning.table-partitioning.R7 — lowKey accessor returns read-only segment
    @Test
    void lowKey_mutationViaAccessor_doesNotCorruptDescriptor() {
        var low = segOf((byte) 0x10);
        var high = segOf((byte) 0x20);
        var desc = new PartitionDescriptor(1L, low, high, "node-1", 0L);

        byte originalByte = desc.lowKey().get(ValueLayout.JAVA_BYTE, 0);

        // Attempt to mutate via the returned segment
        try {
            desc.lowKey().set(ValueLayout.JAVA_BYTE, 0, (byte) 0xFF);
        } catch (UnsupportedOperationException | IllegalStateException
                | IllegalArgumentException e) {
            // Read-only segment correctly rejected the mutation — pass
            return;
        }

        // If mutation didn't throw, verify the descriptor's state is unchanged
        byte afterByte = desc.lowKey().get(ValueLayout.JAVA_BYTE, 0);
        assertEquals(originalByte, afterByte,
                "lowKey must not be mutated via accessor — descriptor state corrupted");
    }

    /**
     * Finding PD-3: Same as above but for highKey().
     */
    // @spec partitioning.table-partitioning.R7 — highKey accessor returns read-only segment
    @Test
    void highKey_mutationViaAccessor_doesNotCorruptDescriptor() {
        var low = segOf((byte) 0x10);
        var high = segOf((byte) 0x20);
        var desc = new PartitionDescriptor(1L, low, high, "node-1", 0L);

        byte originalByte = desc.highKey().get(ValueLayout.JAVA_BYTE, 0);

        try {
            desc.highKey().set(ValueLayout.JAVA_BYTE, 0, (byte) 0x00);
        } catch (UnsupportedOperationException | IllegalStateException
                | IllegalArgumentException e) {
            return;
        }

        byte afterByte = desc.highKey().get(ValueLayout.JAVA_BYTE, 0);
        assertEquals(originalByte, afterByte,
                "highKey must not be mutated via accessor — descriptor state corrupted");
    }

    // --- PD-4: DESCRIPTOR-EQUALITY (Round 2) ---

    /**
     * Finding PD-4: Two PartitionDescriptors constructed with identical parameters must be equal.
     * Record auto-generated equals uses MemorySegment identity (address + size), not content
     * comparison. This breaks the record contract that identity is determined by fields alone.
     */
    // @spec partitioning.table-partitioning.R9 — content-based equality
    @Test
    void equals_identicalParameters_areEqual() {
        var desc1 = new PartitionDescriptor(1L, segOf((byte) 0x00), segOf((byte) 0xFF), "node-1",
                0L);
        var desc2 = new PartitionDescriptor(1L, segOf((byte) 0x00), segOf((byte) 0xFF), "node-1",
                0L);

        assertEquals(desc1, desc2,
                "Two descriptors with identical fields must be equal (content equality)");
    }

    /**
     * Finding PD-4: hashCode must be consistent with equals.
     */
    // @spec partitioning.table-partitioning.R10 — content-based hashCode consistent with equals
    @Test
    void hashCode_identicalParameters_areEqual() {
        var desc1 = new PartitionDescriptor(1L, segOf((byte) 0x00), segOf((byte) 0xFF), "node-1",
                0L);
        var desc2 = new PartitionDescriptor(1L, segOf((byte) 0x00), segOf((byte) 0xFF), "node-1",
                0L);

        assertEquals(desc1.hashCode(), desc2.hashCode(),
                "Two equal descriptors must produce the same hashCode");
    }

    /**
     * Finding PD-4: Descriptors with different key content must NOT be equal.
     */
    @Test
    void equals_differentKeys_areNotEqual() {
        var desc1 = new PartitionDescriptor(1L, segOf((byte) 0x00), segOf((byte) 0x80), "node-1",
                0L);
        var desc2 = new PartitionDescriptor(1L, segOf((byte) 0x00), segOf((byte) 0xFF), "node-1",
                0L);

        assertNotEquals(desc1, desc2, "Descriptors with different high keys must not be equal");
    }
}
