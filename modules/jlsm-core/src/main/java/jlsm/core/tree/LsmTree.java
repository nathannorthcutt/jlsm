package jlsm.core.tree;

import jlsm.core.model.Entry;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.Optional;

/**
 * Top-level contract for an LSM-Tree store.
 *
 * <p>
 * <b>Write path</b>: WAL → MemTable → SSTable flush (when MemTable exceeds threshold). <b>Read
 * path</b>: MemTable (newest) → SSTable levels (L0 newest-first).
 *
 * <p>
 * <b>Key contracts</b>:
 * <ul>
 * <li>{@link #put} and {@link #delete} are durable: the WAL is synced before the MemTable is
 * updated.</li>
 * <li>{@link #get} returns the most recent value for a key; a deleted key returns
 * {@link Optional#empty()}.</li>
 * <li>{@link #scan} iterates entries in ascending key order, yielding one entry per logical key
 * (the most recent version). Tombstones ({@link Entry.Delete}) are included so that callers can
 * distinguish "key was deleted" from "key was never written".</li>
 * <li>All operations may throw {@link IOException} because they interact with durable storage.</li>
 * </ul>
 */
public interface LsmTree extends Closeable {

    /**
     * Associates {@code key} with {@code value}, replacing any prior value or tombstone.
     *
     * @param key the key to write; must not be null
     * @param value the value to associate with the key; must not be null
     * @throws IOException if the WAL cannot be written to
     */
    void put(MemorySegment key, MemorySegment value) throws IOException;

    /**
     * Records a tombstone for {@code key}. Subsequent {@link #get} calls for this key will return
     * {@link Optional#empty()} until a new {@link #put} is issued.
     *
     * @param key the key to delete; must not be null
     * @throws IOException if the WAL cannot be written to
     */
    void delete(MemorySegment key) throws IOException;

    /**
     * Returns the most recent value associated with {@code key}, or {@link Optional#empty()} if the
     * key does not exist or has been deleted.
     *
     * @param key the key to look up; must not be null
     * @return an {@link Optional} containing the value if the key has a live {@link Entry.Put}, or
     *         empty if the key is absent or has a {@link Entry.Delete} as its most recent entry
     * @throws IOException if an I/O error occurs while reading from disk
     */
    Optional<MemorySegment> get(MemorySegment key) throws IOException;

    /**
     * Returns an iterator over all entries in ascending key order. Each logical key appears exactly
     * once, as its most recent version (either {@link Entry.Put} or {@link Entry.Delete}).
     *
     * @return a non-null iterator; {@link Iterator#next()} may throw
     *         {@link java.io.UncheckedIOException} if a read error occurs during iteration
     * @throws IOException if the scan cannot be initialised
     */
    Iterator<Entry> scan() throws IOException;

    /**
     * Returns an iterator over entries whose keys fall within the half-open range
     * {@code [from, to)}, in ascending key order.
     *
     * @param from the inclusive lower bound; must not be null
     * @param to the exclusive upper bound; must not be null and must be greater than {@code from}
     * @return a non-null iterator over matching entries in ascending key order
     * @throws IOException if the scan cannot be initialised
     */
    Iterator<Entry> scan(MemorySegment from, MemorySegment to) throws IOException;

    /**
     * Flushes pending writes and releases all resources (WAL file handles, SSTable readers, etc.).
     *
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    void close() throws IOException;
}
