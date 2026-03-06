package jlsm.core.sstable;

import jlsm.core.model.Entry;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.Optional;

/**
 * Read-only view of an immutable SSTable file. Instances are typically long-lived and shared across
 * many concurrent reads within a single process.
 *
 * <p><b>Pipeline position</b>: Consulted during the read path after the MemTable returns no result.
 * SSTable levels are searched from newest (L0) to oldest (Ln); the first matching entry wins.
 *
 * <p><b>Key contracts</b>:
 * <ul>
 *   <li>The underlying file is opened when the reader is created and closed by {@link #close}.</li>
 *   <li>Scan iterators produce entries in ascending key order. Where multiple entries share a key
 *       (possible in L0), they appear in descending sequence-number order (most recent first).</li>
 *   <li>{@link Iterator#next()} wraps any underlying {@link IOException} as
 *       {@link java.io.UncheckedIOException} in implementations, since {@code Iterator} cannot
 *       declare checked exceptions.</li>
 *   <li>The threading model is implementation-defined; callers should assume reads are safe to
 *       perform concurrently unless documented otherwise.</li>
 * </ul>
 */
public interface SSTableReader extends Closeable {

    /**
     * Returns the metadata descriptor for this SSTable, including key range, level, and size
     * statistics. Does not perform I/O.
     *
     * @return non-null metadata; valid for the lifetime of this reader
     */
    SSTableMetadata metadata();

    /**
     * Performs a point lookup for {@code key}.
     *
     * @param key the key to search for; must not be null
     * @return an {@link Optional} containing the most recent {@link Entry} for this key if found,
     *         or {@link Optional#empty()} if the key does not exist in this SSTable
     * @throws IOException if an I/O error occurs while reading the SSTable
     */
    Optional<Entry> get(MemorySegment key) throws IOException;

    /**
     * Returns an iterator over all entries in this SSTable in ascending key order.
     *
     * @return a non-null iterator; {@link Iterator#next()} may throw
     *         {@link java.io.UncheckedIOException} if a read error occurs
     * @throws IOException if the scan cannot be initialized (e.g., index read fails)
     */
    Iterator<Entry> scan() throws IOException;

    /**
     * Returns an iterator over entries whose keys fall in the half-open range
     * {@code [fromKey, toKey)}, in ascending key order.
     *
     * @param fromKey the inclusive lower bound; must not be null
     * @param toKey   the exclusive upper bound; must not be null and must be greater than
     *                {@code fromKey}
     * @return a non-null iterator over matching entries; {@link Iterator#next()} may throw
     *         {@link java.io.UncheckedIOException} if a read error occurs
     * @throws IOException if the scan cannot be initialized
     */
    Iterator<Entry> scan(MemorySegment fromKey, MemorySegment toKey) throws IOException;

    /**
     * Releases all resources held by this reader, including any open file handles or mapped memory.
     * After this call, all other methods on this reader produce undefined behavior.
     *
     * @throws IOException if an I/O error occurs while releasing resources
     */
    @Override
    void close() throws IOException;
}
