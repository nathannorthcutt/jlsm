package jlsm.core.tree;

import jlsm.core.model.Entry;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.Optional;

/**
 * A typed LSM-Tree interface sealed to three key-type variants: {@link StringKeyed},
 * {@link LongKeyed}, and {@link SegmentKeyed}.
 *
 * <p>
 * This interface intentionally does <em>not</em> extend {@link LsmTree}: {@link LsmTree} declares
 * {@code get(MemorySegment) → Optional<MemorySegment>}, which conflicts with {@link SegmentKeyed}'s
 * {@code get(MemorySegment) → Optional<V>} (methods may not differ only by return type). Each
 * sub-interface defines its own strongly-typed operations.
 *
 * @param <V> the value type
 */
public sealed interface TypedLsmTree<V> extends Closeable
        permits TypedLsmTree.StringKeyed, TypedLsmTree.LongKeyed, TypedLsmTree.SegmentKeyed {

    /**
     * Returns an iterator over all entries in ascending key order. Each logical key appears exactly
     * once as its most recent version.
     *
     * @return a non-null iterator; may throw {@link java.io.UncheckedIOException} during iteration
     * @throws IOException if the scan cannot be initialised
     */
    Iterator<Entry> scan() throws IOException;

    /** Flushes pending writes and releases all resources. */
    @Override
    void close() throws IOException;

    // -----------------------------------------------------------------------
    // String-keyed sub-interface
    // -----------------------------------------------------------------------

    /**
     * A {@link TypedLsmTree} whose keys are {@link String} values encoded as UTF-8 bytes.
     *
     * @param <V> the value type
     */
    non-sealed interface StringKeyed<V> extends TypedLsmTree<V> {

        /**
         * Associates {@code key} with {@code value}, replacing any prior value or tombstone.
         *
         * @param key the key; must not be null
         * @param value the value; must not be null
         * @throws IOException if the WAL cannot be written to
         */
        void put(String key, V value) throws IOException;

        /**
         * Records a tombstone for {@code key}.
         *
         * @param key the key to delete; must not be null
         * @throws IOException if the WAL cannot be written to
         */
        void delete(String key) throws IOException;

        /**
         * Returns the most recent value for {@code key}, or empty if absent/deleted.
         *
         * @param key the key to look up; must not be null
         * @return an {@link Optional} containing the deserialized value, or empty
         * @throws IOException if an I/O error occurs while reading
         */
        Optional<V> get(String key) throws IOException;

        /**
         * Returns an iterator over entries in the half-open range {@code [from, to)}, ascending.
         *
         * @param from inclusive lower bound; must not be null
         * @param to exclusive upper bound; must not be null and greater than {@code from}
         * @return a non-null iterator
         * @throws IOException if the scan cannot be initialised
         */
        Iterator<Entry> scan(String from, String to) throws IOException;
    }

    // -----------------------------------------------------------------------
    // Long-keyed sub-interface
    // -----------------------------------------------------------------------

    /**
     * A {@link TypedLsmTree} whose keys are {@code long} primitives.
     *
     * <p>
     * Keys are encoded as sign-bit-flipped big-endian 8 bytes so that byte-lexicographic order
     * matches numeric order ({@link Long#MIN_VALUE} sorts first, {@link Long#MAX_VALUE} sorts
     * last).
     *
     * @param <V> the value type
     */
    non-sealed interface LongKeyed<V> extends TypedLsmTree<V> {

        /**
         * Associates {@code key} with {@code value}, replacing any prior value or tombstone.
         *
         * @param key the key
         * @param value the value; must not be null
         * @throws IOException if the WAL cannot be written to
         */
        void put(long key, V value) throws IOException;

        /**
         * Records a tombstone for {@code key}.
         *
         * @param key the key to delete
         * @throws IOException if the WAL cannot be written to
         */
        void delete(long key) throws IOException;

        /**
         * Returns the most recent value for {@code key}, or empty if absent/deleted.
         *
         * @param key the key to look up
         * @return an {@link Optional} containing the deserialized value, or empty
         * @throws IOException if an I/O error occurs while reading
         */
        Optional<V> get(long key) throws IOException;

        /**
         * Returns an iterator over entries in the half-open range {@code [from, to)}, ascending in
         * numeric order.
         *
         * @param from inclusive lower bound
         * @param to exclusive upper bound; must be greater than {@code from}
         * @return a non-null iterator
         * @throws IOException if the scan cannot be initialised
         */
        Iterator<Entry> scan(long from, long to) throws IOException;
    }

    // -----------------------------------------------------------------------
    // MemorySegment-keyed sub-interface
    // -----------------------------------------------------------------------

    /**
     * A {@link TypedLsmTree} whose keys are raw {@link MemorySegment} values compared
     * byte-lexicographically.
     *
     * @param <V> the value type
     */
    non-sealed interface SegmentKeyed<V> extends TypedLsmTree<V> {

        /**
         * Associates {@code key} with {@code value}, replacing any prior value or tombstone.
         *
         * @param key the key; must not be null
         * @param value the value; must not be null
         * @throws IOException if the WAL cannot be written to
         */
        void put(MemorySegment key, V value) throws IOException;

        /**
         * Records a tombstone for {@code key}.
         *
         * @param key the key to delete; must not be null
         * @throws IOException if the WAL cannot be written to
         */
        void delete(MemorySegment key) throws IOException;

        /**
         * Returns the most recent value for {@code key}, or empty if absent/deleted.
         *
         * @param key the key to look up; must not be null
         * @return an {@link Optional} containing the deserialized value, or empty
         * @throws IOException if an I/O error occurs while reading
         */
        Optional<V> get(MemorySegment key) throws IOException;

        /**
         * Returns an iterator over entries in the half-open range {@code [from, to)}, ascending by
         * byte-lexicographic key order.
         *
         * @param from inclusive lower bound; must not be null
         * @param to exclusive upper bound; must not be null and greater than {@code from}
         * @return a non-null iterator
         * @throws IOException if the scan cannot be initialised
         */
        Iterator<Entry> scan(MemorySegment from, MemorySegment to) throws IOException;
    }
}
