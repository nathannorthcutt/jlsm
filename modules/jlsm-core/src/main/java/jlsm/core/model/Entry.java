package jlsm.core.model;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Represents a single mutation in the LSM-Tree — either a value insertion or a tombstone deletion.
 *
 * <p>
 * <b>Pipeline position</b>: Entries flow through every stage of the write path (WAL → MemTable →
 * SSTable) and are produced by the read path during scans and point lookups. The sealed hierarchy
 * makes tombstones explicit; callers use {@code switch} pattern matching rather than null checks.
 *
 * <p>
 * <b>Key contracts</b>:
 * <ul>
 * <li>Keys and values are represented as {@link MemorySegment} — compatible with both heap and
 * off-heap allocations; byte ordering is the responsibility of the caller.</li>
 * <li>The {@link SequenceNumber} establishes total ordering for versions of the same key; higher
 * sequence numbers take precedence during compaction.</li>
 * <li>All implementing records are immutable value types.</li>
 * </ul>
 */
public sealed interface Entry permits Entry.Put, Entry.Delete {

    /**
     * Returns the key associated with this entry.
     *
     * @return non-null key segment
     */
    MemorySegment key();

    /**
     * Returns the sequence number that orders this entry among all mutations to the same key.
     *
     * @return non-null sequence number; higher values represent more recent mutations
     */
    SequenceNumber sequenceNumber();

    /**
     * A value-bearing mutation: associates {@code key} with {@code value} at the given sequence
     * number.
     *
     * @param key the key being written; must not be null
     * @param value the value to associate with the key; must not be null
     * @param sequenceNumber the write-order stamp assigned by the WAL; must not be null
     */
    record Put(MemorySegment key, MemorySegment value,
            SequenceNumber sequenceNumber) implements Entry {
        public Put {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            Objects.requireNonNull(sequenceNumber, "sequenceNumber must not be null");
        }
    }

    /**
     * A tombstone mutation: marks {@code key} as deleted at the given sequence number. During
     * compaction, a {@code Delete} entry supersedes all {@link Put} entries for the same key with
     * lower sequence numbers.
     *
     * @param key the key being deleted; must not be null
     * @param sequenceNumber the write-order stamp assigned by the WAL; must not be null
     */
    record Delete(MemorySegment key, SequenceNumber sequenceNumber) implements Entry {
        public Delete {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(sequenceNumber, "sequenceNumber must not be null");
        }
    }
}
