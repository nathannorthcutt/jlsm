package jlsm.core.compaction;

import jlsm.core.model.Level;
import jlsm.core.sstable.SSTableMetadata;

import java.util.List;

/**
 * An immutable description of a planned compaction operation: which SSTables to merge and where the
 * output should land.
 *
 * <p><b>Pipeline position</b>: Produced by {@link Compactor#selectCompaction} and consumed by
 * {@link Compactor#compact}. Carries no mutable state; it is a pure value type that describes
 * intent, not in-progress work.
 *
 * <p><b>Key contracts</b>:
 * <ul>
 *   <li>{@code sourceSSTables} is stored as a defensive copy; modifications to the original list
 *       after construction do not affect this record.</li>
 *   <li>{@code targetLevel.index()} must be greater than or equal to {@code sourceLevel.index()};
 *       compaction never moves data toward lower-indexed levels.</li>
 *   <li>The list must not be empty; a compaction of zero files is meaningless.</li>
 * </ul>
 *
 * @param sourceSSTables the SSTables selected for this compaction; stored as an unmodifiable copy
 * @param sourceLevel    the level from which the primary input SSTables are drawn
 * @param targetLevel    the level where the merged output SSTables will be placed
 */
public record CompactionTask(
        List<SSTableMetadata> sourceSSTables,
        Level sourceLevel,
        Level targetLevel
) {
    public CompactionTask {
        sourceSSTables = List.copyOf(sourceSSTables);
    }
}
