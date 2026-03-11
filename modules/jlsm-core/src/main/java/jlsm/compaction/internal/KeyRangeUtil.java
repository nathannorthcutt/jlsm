package jlsm.compaction.internal;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import jlsm.core.sstable.SSTableMetadata;

/** Static utilities for comparing key ranges. */
public final class KeyRangeUtil {

    private KeyRangeUtil() {
    }

    /**
     * Unsigned lexicographic comparison of two MemorySegments.
     *
     * @return negative if a &lt; b, zero if equal, positive if a &gt; b
     */
    public static int compareUnsigned(MemorySegment a, MemorySegment b) {
        assert a != null : "a must not be null";
        assert b != null : "b must not be null";

        long mismatch = a.mismatch(b);
        if (mismatch == -1L)
            return 0;

        long lenA = a.byteSize();
        long lenB = b.byteSize();

        if (mismatch == lenA)
            return -1; // a is prefix of b → a < b
        if (mismatch == lenB)
            return 1; // b is prefix of a → a > b

        byte ba = a.get(ValueLayout.JAVA_BYTE, mismatch);
        byte bb = b.get(ValueLayout.JAVA_BYTE, mismatch);
        return Byte.compareUnsigned(ba, bb);
    }

    /**
     * Returns true iff the two segments contain identical bytes.
     */
    public static boolean keysEqual(MemorySegment a, MemorySegment b) {
        assert a != null : "a must not be null";
        assert b != null : "b must not be null";

        return a.mismatch(b) == -1L;
    }

    /**
     * Returns true iff the key ranges of {@code a} and {@code b} overlap (inclusive on both ends).
     *
     * <p>
     * Overlap condition: {@code a.smallest <= b.largest && b.smallest <= a.largest}
     */
    public static boolean overlaps(SSTableMetadata a, SSTableMetadata b) {
        assert a != null : "a must not be null";
        assert b != null : "b must not be null";

        return compareUnsigned(a.smallestKey(), b.largestKey()) <= 0
                && compareUnsigned(b.smallestKey(), a.largestKey()) <= 0;
    }
}
