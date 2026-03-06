package jlsm.core.model;

/**
 * Identifies a level in the LSM-Tree's tiered SSTable hierarchy.
 *
 * <p><b>Pipeline position</b>: Used by the Compactor to select which SSTables to merge and to
 * decide where the output SSTables are placed. L0 receives fresh flushes from the MemTable;
 * compaction moves data toward higher-indexed levels.
 *
 * <p><b>Key contracts</b>:
 * <ul>
 *   <li>{@link #L0} is the landing zone for MemTable flushes; SSTables at L0 may have overlapping
 *       key ranges.</li>
 *   <li>Levels with {@code index > 0} (L1 and beyond) hold non-overlapping key ranges within a
 *       level, as enforced by leveled compaction strategies.</li>
 *   <li>The index is a non-negative integer; no upper bound is imposed by the model.</li>
 * </ul>
 *
 * @param index zero-based level number; must be non-negative
 */
public record Level(int index) {

    /** The topmost level: receives flushed MemTable data and may contain overlapping key ranges. */
    public static final Level L0 = new Level(0);

    /**
     * Returns the next level down in the hierarchy (higher index = further from the MemTable).
     *
     * @return a new {@code Level} with {@code index + 1}
     */
    public Level next() {
        return new Level(index + 1);
    }
}
