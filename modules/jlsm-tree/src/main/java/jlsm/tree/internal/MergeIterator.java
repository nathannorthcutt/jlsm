package jlsm.tree.internal;

import jlsm.core.model.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * N-way merge iterator that deduplicates by logical key.
 *
 * <p>Heap ordering: key ASC (unsigned lexicographic), seqNum DESC for equal keys — the entry with
 * the highest sequence number for any given key surfaces first. After polling the winner, all
 * stale versions of the same logical key from other sources are drained and discarded so that each
 * logical key appears exactly once in the output.
 *
 * <p>Optionally bounded to a half-open range {@code [from, to)}: entries outside the range are
 * skipped during iteration.
 */
public final class MergeIterator implements Iterator<Entry> {

    private record HeapEntry(Entry entry, Iterator<Entry> source) {}

    private static final Comparator<HeapEntry> HEAP_ORDER = (x, y) -> {
        int keyCmp = compareUnsigned(x.entry().key(), y.entry().key());
        if (keyCmp != 0) return keyCmp;
        // Same key: higher seqNum comes first (DESC)
        return Long.compare(y.entry().sequenceNumber().value(), x.entry().sequenceNumber().value());
    };

    private final PriorityQueue<HeapEntry> heap;
    // Optional upper bound (exclusive); null means unbounded
    private final MemorySegment to;

    private Entry nextEntry;  // pre-fetched next result, or null if not yet fetched / exhausted
    private boolean fetched = false;

    /**
     * Creates an unbounded merge iterator.
     *
     * @param sources list of pre-sorted entry iterators; must not be null
     */
    public MergeIterator(List<Iterator<Entry>> sources) {
        this(sources, null, null);
    }

    /**
     * Creates a range-bounded merge iterator.
     *
     * @param sources list of pre-sorted entry iterators; must not be null
     * @param from    inclusive lower bound (entries below this key are skipped), or null for
     *                unbounded
     * @param to      exclusive upper bound (entries at or above this key are excluded), or null for
     *                unbounded
     */
    public MergeIterator(List<Iterator<Entry>> sources, MemorySegment from, MemorySegment to) {
        Objects.requireNonNull(sources, "sources must not be null");

        this.to = to;
        this.heap = new PriorityQueue<>(Math.max(1, sources.size()), HEAP_ORDER);

        for (Iterator<Entry> src : sources) {
            advanceSource(src, from);
        }
    }

    /**
     * Advances a source iterator, offering its next in-range entry to the heap.
     * Entries below {@code lowerBound} are skipped.
     */
    private void advanceSource(Iterator<Entry> src, MemorySegment lowerBound) {
        while (src.hasNext()) {
            Entry candidate = src.next();
            // Skip entries below the lower bound
            if (lowerBound != null && compareUnsigned(candidate.key(), lowerBound) < 0) {
                continue;
            }
            // Skip entries at or above the upper bound
            if (to != null && compareUnsigned(candidate.key(), to) >= 0) {
                return; // source is sorted; no need to look further
            }
            heap.offer(new HeapEntry(candidate, src));
            return;
        }
    }

    /** Re-fills the heap from a source after it has already been initialised. */
    private void advanceSource(Iterator<Entry> src) {
        advanceSource(src, null);
    }

    @Override
    public boolean hasNext() {
        if (!fetched) prefetch();
        return nextEntry != null;
    }

    @Override
    public Entry next() {
        if (!fetched) prefetch();
        if (nextEntry == null) throw new NoSuchElementException();
        fetched = false;
        Entry result = nextEntry;
        nextEntry = null;
        return result;
    }

    private void prefetch() {
        fetched = true;
        nextEntry = null;

        if (heap.isEmpty()) return;

        HeapEntry min = heap.poll();
        advanceSource(min.source());

        // Drain stale (older) versions of the same logical key from all other sources
        while (!heap.isEmpty() && keysEqual(heap.peek().entry().key(), min.entry().key())) {
            HeapEntry stale = heap.poll();
            advanceSource(stale.source());
        }

        nextEntry = min.entry();
    }

    // -----------------------------------------------------------------------
    // Key comparison utilities (inlined — jlsm.compaction.internal not exported)
    // -----------------------------------------------------------------------

    public static int compareUnsigned(MemorySegment a, MemorySegment b) {
        assert a != null : "a must not be null";
        assert b != null : "b must not be null";

        long mismatch = a.mismatch(b);
        if (mismatch == -1L) return 0;

        long lenA = a.byteSize();
        long lenB = b.byteSize();

        if (mismatch == lenA) return -1; // a is prefix of b → a < b
        if (mismatch == lenB) return 1;  // b is prefix of a → a > b

        byte ba = a.get(ValueLayout.JAVA_BYTE, mismatch);
        byte bb = b.get(ValueLayout.JAVA_BYTE, mismatch);
        return Byte.compareUnsigned(ba, bb);
    }

    public static boolean keysEqual(MemorySegment a, MemorySegment b) {
        assert a != null : "a must not be null";
        assert b != null : "b must not be null";

        return a.mismatch(b) == -1L;
    }
}
