package jlsm.memtable.internal;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Comparator;

/**
 * Orders {@link CompositeKey} instances for use in a
 * {@link java.util.concurrent.ConcurrentSkipListMap}:
 * <ol>
 * <li>Logical key ascending — unsigned lexicographic byte order.</li>
 * <li>Sequence number descending within the same logical key — higher sequence number sorts first,
 * so the most recent version of a key is always the first map entry for that key.</li>
 * </ol>
 */
public final class KeyComparator implements Comparator<CompositeKey> {

    @Override
    public int compare(CompositeKey a, CompositeKey b) {
        assert a != null : "a must not be null";
        assert b != null : "b must not be null";

        MemorySegment aKey = a.logicalKey();
        MemorySegment bKey = b.logicalKey();

        long mismatch = aKey.mismatch(bKey);

        if (mismatch == -1L) {
            // Identical logical keys — order by sequence number descending.
            return Long.compare(b.sequenceNumber().value(), a.sequenceNumber().value());
        }

        long minLen = Math.min(aKey.byteSize(), bKey.byteSize());
        if (mismatch < minLen) {
            // Mismatch within both segments — compare the differing bytes unsigned.
            byte aByte = aKey.get(ValueLayout.JAVA_BYTE, mismatch);
            byte bByte = bKey.get(ValueLayout.JAVA_BYTE, mismatch);
            return Byte.compareUnsigned(aByte, bByte);
        }

        // One segment is a prefix of the other — shorter sorts first.
        return Long.compare(aKey.byteSize(), bKey.byteSize());
    }
}
