package jlsm.table.internal;

import jlsm.table.ScoredEntry;
import jlsm.table.TableEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Merges results from multiple partitions into a single result set.
 *
 * <p>
 * Contract: Stateless utility providing merge strategies for different query types. Each method
 * takes per-partition results and produces a globally correct merged result.
 *
 * <p>
 * Governed by:
 * .kb/distributed-systems/data-partitioning/vector-search-partitioning.md#result-fusion
 */
public final class ResultMerger {

    private ResultMerger() {
    }

    /**
     * Merges top-k scored results from multiple partitions by score (descending).
     *
     * <p>
     * Contract:
     * <ul>
     * <li>Receives: per-partition scored entry lists, global k limit</li>
     * <li>Returns: global top-k entries sorted by score descending</li>
     * <li>Side effects: none</li>
     * </ul>
     * Used for: vector similarity queries, full-text queries.
     *
     * @param partitionResults per-partition top-k results; must not be null
     * @param k global result limit; must be positive
     * @param <K> key type
     * @return merged top-k results sorted by score descending
     * @throws NullPointerException if partitionResults is null
     * @throws IllegalArgumentException if k is not positive
     */
    public static <K> List<ScoredEntry<K>> mergeTopK(List<List<ScoredEntry<K>>> partitionResults,
            int k) {
        Objects.requireNonNull(partitionResults, "partitionResults must not be null");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive, got: " + k);
        }

        // Max-heap: highest score polled first
        final PriorityQueue<ScoredEntry<K>> heap = new PriorityQueue<>(
                Comparator.<ScoredEntry<K>>comparingDouble(e -> e.score()).reversed());

        for (final List<ScoredEntry<K>> partition : partitionResults) {
            assert partition != null : "individual partition result list must not be null";
            heap.addAll(partition);
        }

        final List<ScoredEntry<K>> result = new ArrayList<>(Math.min(k, heap.size()));
        int remaining = k;
        while (remaining > 0 && !heap.isEmpty()) {
            result.add(heap.poll());
            remaining--;
        }
        assert result.size() <= k : "result must contain at most k entries";
        return result;
    }

    /**
     * Merges range iteration results from multiple partitions in key order.
     *
     * <p>
     * Contract:
     * <ul>
     * <li>Receives: per-partition iterators (each already in key order)</li>
     * <li>Returns: single iterator producing entries in global key order</li>
     * <li>Side effects: none</li>
     * </ul>
     * Used for: property range queries, getAllInRange.
     *
     * @param partitionIterators per-partition iterators in key order; must not be null
     * @return merged iterator in global key order
     * @throws NullPointerException if partitionIterators is null
     */
    public static Iterator<TableEntry<String>> mergeOrdered(
            List<Iterator<TableEntry<String>>> partitionIterators) {
        Objects.requireNonNull(partitionIterators, "partitionIterators must not be null");
        return new MergingIterator(partitionIterators);
    }

    // -------------------------------------------------------------------------
    // MergingIterator — N-way merge via min-heap
    // -------------------------------------------------------------------------

    /**
     * An iterator that performs an N-way merge of pre-sorted partition iterators using a min-heap.
     * Each heap entry wraps the current head element from one partition iterator.
     */
    private static final class MergingIterator implements Iterator<TableEntry<String>> {

        /** Heap entry: the current head element from a single partition iterator. */
        private record HeapEntry(TableEntry<String> entry, Iterator<TableEntry<String>> source) {
        }

        private static final Comparator<HeapEntry> KEY_ORDER = Comparator
                .comparing(e -> e.entry().key());

        private final PriorityQueue<HeapEntry> heap;

        MergingIterator(List<Iterator<TableEntry<String>>> partitionIterators) {
            assert partitionIterators != null : "partitionIterators must not be null";
            this.heap = new PriorityQueue<>(Math.max(1, partitionIterators.size()), KEY_ORDER);
            for (final Iterator<TableEntry<String>> it : partitionIterators) {
                assert it != null : "individual partition iterator must not be null";
                if (it.hasNext()) {
                    heap.offer(new HeapEntry(it.next(), it));
                }
            }
        }

        @Override
        public boolean hasNext() {
            return !heap.isEmpty();
        }

        @Override
        public TableEntry<String> next() {
            if (heap.isEmpty()) {
                throw new NoSuchElementException("no more entries in merged iterator");
            }
            final HeapEntry head = heap.poll();
            assert head != null : "polled entry must not be null";
            // Advance the source iterator and re-offer its next element
            if (head.source().hasNext()) {
                heap.offer(new HeapEntry(head.source().next(), head.source()));
            }
            return head.entry();
        }
    }
}
