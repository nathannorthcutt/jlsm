package jlsm.core.memtable;

import jlsm.core.model.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.Optional;

/**
 * An in-memory write buffer that accumulates recent mutations before they are flushed to an
 * immutable SSTable on disk.
 *
 * <p><b>Pipeline position</b>: Sits between the WAL (which provides durability) and the SSTable
 * layer (which provides sorted, persistent storage). Reads consult the MemTable first, before
 * falling through to SSTable levels, so the MemTable always reflects the most recent state.
 *
 * <p><b>Key contracts</b>:
 * <ul>
 *   <li>Entries are stored and returned in ascending key order for scan operations.</li>
 *   <li>If multiple {@link Entry} values exist for the same key, the one with the highest
 *       {@link jlsm.core.model.SequenceNumber} is authoritative for point lookups.</li>
 *   <li>The threading model is left to implementations; callers should consult implementation
 *       documentation for concurrency guarantees.</li>
 *   <li>No checked exceptions are thrown; this is a pure in-memory structure.</li>
 * </ul>
 */
public interface MemTable {

    /**
     * Applies an entry to the MemTable. Both {@link Entry.Put} and {@link Entry.Delete} (tombstone)
     * entries are accepted.
     *
     * @param entry the mutation to apply; must not be null
     */
    void apply(Entry entry);

    /**
     * Returns the most recent entry for the given key, or {@link Optional#empty()} if the key is
     * not present. A returned {@link Entry.Delete} indicates the key has been explicitly deleted.
     *
     * @param key the key to look up; must not be null
     * @return an {@link Optional} containing the most recent {@link Entry} for this key, or empty
     *         if no entry exists
     */
    Optional<Entry> get(MemorySegment key);

    /**
     * Returns an iterator over all entries in ascending key order. Entries with the same key are
     * returned in descending sequence-number order (most recent first).
     *
     * @return a non-null iterator over all entries; may be empty if the MemTable is empty
     */
    Iterator<Entry> scan();

    /**
     * Returns an iterator over entries whose keys fall within the half-open range
     * {@code [fromKey, toKey)}, in ascending key order.
     *
     * @param fromKey the inclusive lower bound; must not be null
     * @param toKey   the exclusive upper bound; must not be null; must be greater than
     *                {@code fromKey}
     * @return a non-null iterator over matching entries; may be empty
     */
    Iterator<Entry> scan(MemorySegment fromKey, MemorySegment toKey);

    /**
     * Returns a best-effort estimate of the memory consumed by this MemTable in bytes. Used by the
     * flush policy to decide when to rotate the MemTable.
     *
     * @return estimated size in bytes; always non-negative
     */
    long approximateSizeBytes();

    /**
     * Returns {@code true} if no entries have been applied to this MemTable.
     *
     * @return {@code true} if empty
     */
    boolean isEmpty();
}
