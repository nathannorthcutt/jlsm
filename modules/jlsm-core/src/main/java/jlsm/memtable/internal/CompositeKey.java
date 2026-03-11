package jlsm.memtable.internal;

import jlsm.core.model.SequenceNumber;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Internal composite key combining a logical key and a sequence number. Used as the map key in
 * {@link jlsm.memtable.ConcurrentSkipListMemTable} to support multi-version storage.
 *
 * <p>Ordering is defined by {@link KeyComparator}: ascending by logical key, then descending by
 * sequence number so the most recent version of a key sorts first.
 */
public record CompositeKey(MemorySegment logicalKey, SequenceNumber sequenceNumber) {

    public CompositeKey {
        Objects.requireNonNull(logicalKey, "logicalKey must not be null");
        Objects.requireNonNull(sequenceNumber, "sequenceNumber must not be null");
    }
}
