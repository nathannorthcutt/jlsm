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
// @spec F11.R53 — final class in jlsm.table.internal with private constructor (static utility)
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
    // @spec F11.R54,R55,R56,R57,R58,R59,R60,R108 — global top-k sorted desc (R54); null list→NPE
    // (R55); non-positive k→IAE (R56); null partition→NPE with index (R57); partial results when
    // total < k (R58); priority queue / max-heap (R59); ties included (R60); null element→NPE with
    // partition+element index (R108)
    public static <K> List<ScoredEntry<K>> mergeTopK(List<List<ScoredEntry<K>>> partitionResults,
            int k) {
        Objects.requireNonNull(partitionResults, "partitionResults must not be null");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive, got: " + k);
        }

        // Max-heap: highest finite score polled first.
        // NaN scores are ranked below all finite scores to prevent corruption of result ordering.
        final Comparator<ScoredEntry<K>> byScore = (a, b) -> {
            final boolean aNaN = Double.isNaN(a.score());
            final boolean bNaN = Double.isNaN(b.score());
            if (aNaN && bNaN) {
                return 0;
            }
            if (aNaN) {
                return 1; // NaN sorts after (lower priority than) finite values
            }
            if (bNaN) {
                return -1;
            }
            return Double.compare(b.score(), a.score()); // descending for max-heap
        };
        final PriorityQueue<ScoredEntry<K>> heap = new PriorityQueue<>(byScore);

        for (int i = 0; i < partitionResults.size(); i++) {
            final List<ScoredEntry<K>> partition = partitionResults.get(i);
            Objects.requireNonNull(partition,
                    "partition result list must not be null (index " + i + ")");
            for (int j = 0; j < partition.size(); j++) {
                Objects.requireNonNull(partition.get(j),
                        "scored entry element must not be null (partition " + i + ", element " + j
                                + ")");
            }
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
    // @spec F11.R61,R62,R63,R64,R65,R66,R104,R105 — global key-order N-way merge (R61); null
    // list→NPE (R62); min-heap O(log N) per next (R63); exhausted iterator removed (R64); all
    // exhausted→hasNext=false, next→NoSuchElementException (R65); duplicate keys emitted (R66);
    // iterator is AutoCloseable (R104); source exceptions preserve heap entry (R105)
    public static Iterator<TableEntry<String>> mergeOrdered(
            List<Iterator<TableEntry<String>>> partitionIterators) {
        Objects.requireNonNull(partitionIterators, "partitionIterators must not be null");
        for (int i = 0; i < partitionIterators.size(); i++) {
            if (partitionIterators.get(i) == null) {
                throw new NullPointerException(
                        "partition iterator at index " + i + " must not be null");
            }
        }
        return new MergingIterator(partitionIterators);
    }

    // -------------------------------------------------------------------------
    // MergingIterator — N-way merge via min-heap
    // -------------------------------------------------------------------------

    /**
     * An iterator that performs an N-way merge of pre-sorted partition iterators using a min-heap.
     * Each heap entry wraps the current head element from one partition iterator.
     */
    private static final class MergingIterator
            implements Iterator<TableEntry<String>>, AutoCloseable {

        /** Heap entry: the current head element from a single partition iterator. */
        private record HeapEntry(TableEntry<String> entry, Iterator<TableEntry<String>> source) {
        }

        private static final Comparator<HeapEntry> KEY_ORDER = Comparator
                .comparing(e -> e.entry().key());

        private final PriorityQueue<HeapEntry> heap;

        /** All source iterators — retained so close() can release resources on abandonment. */
        private final List<Iterator<TableEntry<String>>> sourceIterators;

        MergingIterator(List<Iterator<TableEntry<String>>> partitionIterators) {
            assert partitionIterators != null : "partitionIterators must not be null";
            this.sourceIterators = List.copyOf(partitionIterators);
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
            // Advance the source iterator and re-offer its next element.
            // If the source throws, re-offer the current head so the source is not
            // silently lost from the heap — callers can retry after transient errors.
            try {
                if (head.source().hasNext()) {
                    heap.offer(new HeapEntry(head.source().next(), head.source()));
                }
            } catch (final RuntimeException ex) {
                heap.offer(head);
                throw ex;
            }
            return head.entry();
        }

        /**
         * Closes any source iterators that implement {@link AutoCloseable}, releasing resources
         * held by abandoned iteration. Safe to call multiple times.
         */
        @Override
        public void close() throws Exception {
            Exception deferred = null;
            for (final Iterator<TableEntry<String>> it : sourceIterators) {
                if (it instanceof AutoCloseable ac) {
                    try {
                        ac.close();
                    } catch (final Exception e) {
                        if (deferred == null) {
                            deferred = e;
                        } else {
                            deferred.addSuppressed(e);
                        }
                    }
                }
            }
            heap.clear();
            if (deferred != null) {
                throw deferred;
            }
        }
    }
}
