package jlsm.core.compaction;

import jlsm.core.sstable.SSTableMetadata;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Selects and executes compaction operations that merge SSTable files across levels, bounding read
 * amplification and reclaiming space occupied by overwritten or deleted keys.
 *
 * <p><b>Pipeline position</b>: Runs as a background process, periodically inspecting the level
 * manifest to find compaction opportunities. After a successful compaction, the old input SSTables
 * are removed from the manifest and replaced by the new output SSTables.
 *
 * <p><b>Key contracts</b>:
 * <ul>
 *   <li>{@link #selectCompaction} is pure and non-blocking; it only reads level statistics and
 *       returns a task description. It does not perform I/O.</li>
 *   <li>{@link #compact} performs the actual merge, resolving key conflicts using
 *       {@link jlsm.core.model.SequenceNumber}: the entry with the highest sequence number for a
 *       given key survives. {@link jlsm.core.model.Entry.Delete} tombstones are dropped only when
 *       the compaction reaches the bottom level and no earlier copies can exist.</li>
 *   <li>The returned {@link SSTableMetadata} list describes the newly written output files; the
 *       caller is responsible for updating the manifest atomically.</li>
 *   <li>The threading model is implementation-defined; typically one compaction runs at a time per
 *       level.</li>
 * </ul>
 */
public interface Compactor {

    /**
     * Examines the current level metadata and returns a compaction task if one is warranted by the
     * implementation's strategy (e.g., size-tiered, leveled), or {@link Optional#empty()} if no
     * compaction is needed at this time.
     *
     * @param levelMetadata a list indexed by level number, where each element is the list of
     *                      SSTable metadata descriptors at that level; must not be null
     * @return an {@link Optional} containing the selected {@link CompactionTask}, or empty if no
     *         compaction is needed
     */
    Optional<CompactionTask> selectCompaction(List<List<SSTableMetadata>> levelMetadata);

    /**
     * Executes the given compaction task: reads all source SSTables, performs a sorted merge,
     * resolves conflicting versions of the same key, writes the output SSTables, and returns their
     * metadata.
     *
     * <p>The caller must remove the source SSTables from the manifest and add the returned output
     * SSTables atomically after this method returns successfully.
     *
     * @param task describes which SSTables to merge and where to place the output; must not be null
     * @return a non-null, non-empty list of metadata for the newly written output SSTables
     * @throws IOException if an I/O error occurs while reading source files or writing output files
     */
    List<SSTableMetadata> compact(CompactionTask task) throws IOException;
}
