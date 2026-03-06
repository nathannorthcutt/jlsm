package jlsm.core.wal;

import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;

/**
 * Durable append-only log that provides crash recovery for mutations not yet flushed to SSTables.
 *
 * <p><b>Pipeline position</b>: Every write to the MemTable is first recorded in the WAL. On
 * restart, the WAL is replayed via {@link #replay} to reconstruct in-flight MemTable state before
 * the store is made available for new operations.
 *
 * <p><b>Key contracts</b>:
 * <ul>
 *   <li>{@link #append} must persist the entry to durable storage (e.g., {@code fsync}) before
 *       returning the assigned {@link SequenceNumber}; the MemTable must not be updated until this
 *       call succeeds.</li>
 *   <li>Sequence numbers assigned by {@link #append} are strictly increasing and survive restarts;
 *       implementations must persist the last-used sequence number.</li>
 *   <li>{@link #truncateBefore} removes log records no longer needed for recovery (i.e., all
 *       mutations up to and including the flushed MemTable's max sequence number).</li>
 *   <li>The threading model is implementation-defined; typical implementations serialize appends
 *       with a lock or channel.</li>
 * </ul>
 */
public interface WriteAheadLog extends Closeable {

    /**
     * Appends {@code entry} to the log, assigns it the next monotonically increasing sequence
     * number, and ensures the record is durable before returning.
     *
     * @param entry the mutation to record; must not be null
     * @return the {@link SequenceNumber} assigned to this entry; never null
     * @throws IOException if the record cannot be written or synced to durable storage
     */
    SequenceNumber append(Entry entry) throws IOException;

    /**
     * Returns an iterator over all log entries with sequence numbers greater than or equal to
     * {@code from}, in ascending sequence-number order. Used during crash recovery to rebuild the
     * MemTable.
     *
     * @param from the inclusive lower bound for replay; pass {@link SequenceNumber#ZERO} to replay
     *             all entries; must not be null
     * @return a non-null iterator over matching entries; {@link Iterator#next()} may throw
     *         {@link java.io.UncheckedIOException} if a read error occurs during iteration
     * @throws IOException if the log cannot be opened or its header cannot be read
     */
    Iterator<Entry> replay(SequenceNumber from) throws IOException;

    /**
     * Removes all log records with sequence numbers strictly less than {@code upTo}, freeing disk
     * space. Callers must ensure that all entries before {@code upTo} have been durably flushed to
     * SSTable before invoking this method.
     *
     * @param upTo the exclusive upper bound for truncation; must not be null
     * @throws IOException if the truncation cannot be completed
     */
    void truncateBefore(SequenceNumber upTo) throws IOException;

    /**
     * Returns the highest sequence number that has been appended to this log, or
     * {@link SequenceNumber#ZERO} if no entries have been written.
     *
     * @return the last assigned sequence number; never null
     * @throws IOException if the sequence number cannot be read from durable storage
     */
    SequenceNumber lastSequenceNumber() throws IOException;

    /**
     * Releases all resources held by this WAL, including any open file handles. Pending writes
     * that have not been synced may be lost.
     *
     * @throws IOException if an I/O error occurs while releasing resources
     */
    @Override
    void close() throws IOException;
}
