package jlsm.table;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
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
    @Test
    void constructor_lowKeyGreaterThanHighKey_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new PartitionDescriptor(1L, seg("zzz"), seg("aaa"), "local", 0L),
                "PartitionDescriptor should reject lowKey > highKey (inverted range)");
    }
}
