package jlsm.core.model;

import java.util.Objects;

/**
 * A monotonically increasing counter that establishes total ordering over all mutations written to
 * the LSM-Tree.
 *
 * <p><b>Pipeline position</b>: Assigned by the WAL on each {@code append}, stored in every
 * {@link Entry}, and used by the Compactor to determine which version of a key survives a merge.
 *
 * <p><b>Key contracts</b>:
 * <ul>
 *   <li>Sequence numbers are strictly increasing; the WAL must never reuse a number across restarts.</li>
 *   <li>{@link #ZERO} is a sentinel representing "before any write"; it is never assigned to a real
 *       entry.</li>
 *   <li>The {@link #compareTo} total order is consistent with the underlying {@code long} value.</li>
 * </ul>
 *
 * @param value the raw 64-bit counter value; must be non-negative
 */

public record SequenceNumber(long value) implements Comparable<SequenceNumber> {

    public SequenceNumber {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative, got: " + value);
        }
    }

    /** Sentinel value representing the state before any mutation has been written. */
    public static final SequenceNumber ZERO = new SequenceNumber(0L);

    /**
     * Returns the next sequence number in the monotonic sequence.
     *
     * @return a new {@code SequenceNumber} with {@code value + 1}
     */
    public SequenceNumber next() {
        assert value < Long.MAX_VALUE : "sequence number overflow";
        return new SequenceNumber(value + 1);
    }

    /**
     * Compares this sequence number to another by raw value.
     *
     * @param other the sequence number to compare against; must not be null
     * @return a negative integer, zero, or a positive integer as this value is less than, equal to,
     *         or greater than {@code other}
     */
    @Override
    public int compareTo(SequenceNumber other) {
        Objects.requireNonNull(other, "other must not be null");
        return Long.compare(this.value, other.value);
    }
}
