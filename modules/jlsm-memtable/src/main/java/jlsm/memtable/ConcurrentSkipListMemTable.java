package jlsm.memtable;

import jlsm.core.memtable.MemTable;
import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import jlsm.memtable.internal.CompositeKey;
import jlsm.memtable.internal.KeyComparator;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe {@link MemTable} backed by a {@link ConcurrentSkipListMap}.
 *
 * <p>Each entry is stored under a {@link CompositeKey} that combines the logical key with its
 * sequence number. The {@link KeyComparator} orders entries ascending by logical key, and
 * descending by sequence number within the same logical key, so the most recent version always
 * appears first.
 *
 * <p><b>Threading</b>: {@link ConcurrentSkipListMap} provides thread-safe concurrent reads and
 * writes. Iterators are weakly consistent and may miss concurrent updates, which is acceptable for
 * a MemTable.
 */
public final class ConcurrentSkipListMemTable implements MemTable {

    /** Sentinel sequence number used as a probe in point lookups and range scans. */
    private static final SequenceNumber PROBE_SEQ = new SequenceNumber(Long.MAX_VALUE);

    private final ConcurrentSkipListMap<CompositeKey, Entry> map =
            new ConcurrentSkipListMap<>(new KeyComparator());

    private final AtomicLong sizeBytes = new AtomicLong(0L);

    @Override
    public void apply(Entry entry) {
        Objects.requireNonNull(entry, "entry must not be null");

        CompositeKey compositeKey = new CompositeKey(entry.key(), entry.sequenceNumber());
        map.put(compositeKey, entry);

        // Accumulate size estimate
        long delta = switch (entry) {
            case Entry.Put p    -> p.key().byteSize() + p.value().byteSize() + Long.BYTES;
            case Entry.Delete d -> d.key().byteSize() + Long.BYTES;
        };
        sizeBytes.addAndGet(delta);

        assert map.containsKey(compositeKey) : "entry must be present after apply";
    }

    @Override
    public Optional<Entry> get(MemorySegment key) {
        Objects.requireNonNull(key, "key must not be null");

        // Probe with Long.MAX_VALUE: sorts before all real entries for this key (seqnum descending)
        CompositeKey probe = new CompositeKey(key, PROBE_SEQ);
        Map.Entry<CompositeKey, Entry> ceiling = map.ceilingEntry(probe);

        if (ceiling == null) {
            return Optional.empty();
        }

        MemorySegment foundKey = ceiling.getKey().logicalKey();
        if (foundKey.byteSize() != key.byteSize() || foundKey.mismatch(key) != -1L) {
            // The ceiling entry belongs to a different logical key
            return Optional.empty();
        }

        return Optional.of(ceiling.getValue());
    }

    @Override
    public Iterator<Entry> scan() {
        return map.values().iterator();
    }

    @Override
    public Iterator<Entry> scan(MemorySegment fromKey, MemorySegment toKey) {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");

        // fromBound (inclusive): Long.MAX_VALUE probe sorts before all real entries for fromKey
        CompositeKey fromBound = new CompositeKey(fromKey, PROBE_SEQ);
        // toBound (exclusive): Long.MAX_VALUE probe sorts before all real entries for toKey
        CompositeKey toBound = new CompositeKey(toKey, PROBE_SEQ);

        return map.subMap(fromBound, true, toBound, false).values().iterator();
    }

    @Override
    public long approximateSizeBytes() {
        return sizeBytes.get();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }
}
