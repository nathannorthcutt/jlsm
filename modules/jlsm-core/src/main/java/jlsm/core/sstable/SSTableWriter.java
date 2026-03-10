package jlsm.core.sstable;

import jlsm.core.model.Entry;

import java.io.Closeable;
import java.io.IOException;

/**
 * Single-use builder that writes entries to a new, immutable SSTable file. Each instance writes
 * exactly one SSTable; callers must call {@link #finish} to obtain a valid, persisted file.
 *
 * <p><b>Pipeline position</b>: Used during MemTable flushes and compaction to produce new
 * SSTable files. The resulting {@link SSTableMetadata} from {@link #finish} is registered with
 * the LSM-Tree's level manifest.
 *
 * <p><b>Key contracts</b>:
 * <ul>
 *   <li>Entries must be appended in strictly ascending key order; implementations may enforce this
 *       with a runtime check.</li>
 *   <li>{@link #finish} flushes all buffered data, writes the index and footer (including Bloom
 *       filter), and {@code fsync}s the file before returning.</li>
 *   <li>If the writer is {@link #close}d without calling {@link #finish}, the incomplete file must
 *       be deleted to avoid leaving corrupt artifacts on disk.</li>
 *   <li>After {@link #finish} or {@link #close} is called, the writer must not be used again.</li>
 * </ul>
 */
public interface SSTableWriter extends Closeable {

    /**
     * Appends an entry to the SSTable being built. Entries must be supplied in ascending key order.
     *
     * @param entry the entry to write; must not be null; key must be greater than the previously
     *              appended key
     * @throws IOException              if an I/O error occurs while buffering or flushing the entry
     * @throws IllegalArgumentException if the entry's key is not strictly greater than the
     *                                  previously appended key
     * @throws IllegalStateException    if {@link #finish} or {@link #close} has already been called
     */
    void append(Entry entry) throws IOException;

    /**
     * Finalizes the SSTable: flushes all buffered entries, writes the block index and footer
     * (including the serialized Bloom filter), syncs the file to durable storage, and returns the
     * completed file's metadata.
     *
     * @return non-null metadata describing the finalized SSTable
     * @throws IOException           if an I/O error occurs during finalization or sync
     * @throws IllegalStateException if no entries have been appended, or if this method has already
     *                               been called
     */
    SSTableMetadata finish() throws IOException;

    /**
     * Returns the number of entries appended so far via {@link #append}.
     *
     * @return entry count; always non-negative
     */
    long entryCount();

    /**
     * Returns a best-effort estimate of the number of bytes written to disk so far, excluding
     * index and footer overhead.
     *
     * @return estimated size in bytes; always non-negative
     */
    long approximateSizeBytes();

    /**
     * Releases all resources held by this writer. If {@link #finish} has not been called, the
     * partially-written file is deleted before returning.
     *
     * @throws IOException if an I/O error occurs while releasing resources or deleting the
     *                     incomplete file
     */
    @Override
    void close() throws IOException;
}
