package jlsm.core.sstable;

import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * Immutable descriptor for an SSTable file, capturing its identity, location, key range, sequence
 * number range, and size statistics.
 *
 * <p><b>Pipeline position</b>: Produced by {@link SSTableWriter#finish} and consumed by
 * {@link SSTableReader}, the Compactor (for compaction selection), and the block cache (for
 * eviction). Does not hold open file handles.
 *
 * <p><b>Key contracts</b>:
 * <ul>
 *   <li>{@code id} is unique within a store instance; typically a monotonically increasing counter
 *       assigned at flush time.</li>
 *   <li>{@code smallestKey} and {@code largestKey} define the inclusive key range of this SSTable;
 *       used by compaction selection to find overlapping files.</li>
 *   <li>{@code minSequence} and {@code maxSequence} bound the sequence numbers of all entries in
 *       this file; used for snapshot isolation and WAL truncation.</li>
 * </ul>
 *
 * @param id            unique identifier for this SSTable within the store
 * @param path          absolute path to the SSTable file on disk
 * @param level         the LSM level at which this SSTable resides
 * @param smallestKey   the inclusive smallest key in this SSTable; must not be null
 * @param largestKey    the inclusive largest key in this SSTable; must not be null
 * @param minSequence   the smallest sequence number among all entries; must not be null
 * @param maxSequence   the largest sequence number among all entries; must not be null
 * @param sizeBytes     the file size in bytes; must be non-negative
 * @param entryCount    the total number of entries (including tombstones); must be non-negative
 */
public record SSTableMetadata(
        long id,
        Path path,
        Level level,
        MemorySegment smallestKey,
        MemorySegment largestKey,
        SequenceNumber minSequence,
        SequenceNumber maxSequence,
        long sizeBytes,
        long entryCount
) {}
