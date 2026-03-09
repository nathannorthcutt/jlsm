package jlsm.compaction.internal;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;
import jlsm.core.model.Entry;

/**
 * N-way merge iterator over sorted {@link Entry} streams.
 *
 * <p>Heap ordering: key ASC (unsigned lexicographic), seqNum DESC for equal keys. This means the
 * entry with the highest sequence number for any given key surfaces first.
 */
public final class MergeIterator implements Iterator<Entry> {

    private record HeapEntry(Entry entry, Iterator<Entry> source) {}

    private static final Comparator<HeapEntry> HEAP_ORDER = (x, y) -> {
        int keyCmp = KeyRangeUtil.compareUnsigned(x.entry().key(), y.entry().key());
        if (keyCmp != 0) return keyCmp;
        // Same key: higher seqNum comes first (DESC)
        return Long.compare(y.entry().sequenceNumber().value(), x.entry().sequenceNumber().value());
    };

    private final PriorityQueue<HeapEntry> heap;

    public MergeIterator(List<Iterator<Entry>> sources) {
        Objects.requireNonNull(sources, "sources must not be null");

        this.heap = new PriorityQueue<>(Math.max(1, sources.size()), HEAP_ORDER);
        for (Iterator<Entry> src : sources) {
            if (src.hasNext()) {
                heap.offer(new HeapEntry(src.next(), src));
            }
        }
    }

    @Override
    public boolean hasNext() {
        return !heap.isEmpty();
    }

    @Override
    public Entry next() {
        if (heap.isEmpty()) throw new NoSuchElementException();
        HeapEntry min = heap.poll();
        // Advance the source iterator that yielded this entry
        if (min.source().hasNext()) {
            heap.offer(new HeapEntry(min.source().next(), min.source()));
        }
        return min.entry();
    }
}
