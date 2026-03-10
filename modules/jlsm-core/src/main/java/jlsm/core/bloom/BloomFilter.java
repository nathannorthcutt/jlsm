package jlsm.core.bloom;

import java.lang.foreign.MemorySegment;

/**
 * A probabilistic membership filter that quickly determines whether a key <em>might</em> exist in
 * an SSTable, avoiding unnecessary disk reads for missing keys.
 *
 * <p><b>Pipeline position</b>: Consulted during the read path before performing a random I/O into
 * an SSTable. A negative result ({@code mightContain} returns {@code false}) guarantees the key is
 * absent; a positive result means the key <em>probably</em> exists but may be a false positive.
 *
 * <p><b>Key contracts</b>:
 * <ul>
 *   <li>{@link #add} is called once per key during SSTable construction; the filter is then
 *       serialized and embedded in the SSTable footer via {@link #serialize}.</li>
 *   <li>{@link #mightContain} never returns a false negative — if the key was added, this method
 *       always returns {@code true}.</li>
 *   <li>No checked exceptions are thrown; filters are in-memory structures.</li>
 *   <li>Implementations are not required to be thread-safe during construction ({@link #add}
 *       phase); once serialized and deserialized for reads, thread-safety depends on the
 *       implementation.</li>
 * </ul>
 */
public interface BloomFilter {

    /**
     * Records {@code key} as a member of this filter. Must be called for every key written to the
     * SSTable before {@link #serialize} is called.
     *
     * @param key the key to add; must not be null
     */
    void add(MemorySegment key);

    /**
     * Returns {@code true} if {@code key} might be a member of this filter, or {@code false} if it
     * is definitely not a member.
     *
     * @param key the key to test; must not be null
     * @return {@code false} guarantees the key is absent; {@code true} means it probably exists
     */
    boolean mightContain(MemorySegment key);

    /**
     * Returns the current empirical or theoretical false-positive probability for this filter,
     * given the number of keys added so far.
     *
     * @return a value in {@code [0.0, 1.0]}; lower is better
     */
    double falsePositiveRate();

    /**
     * Serializes the filter to a {@link MemorySegment} suitable for embedding in an SSTable footer.
     * The format is implementation-defined; the corresponding {@link Deserializer} must be able to
     * reconstruct an equivalent filter from the returned bytes.
     *
     * @return a non-null, non-empty segment containing the serialized filter
     */
    MemorySegment serialize();

    /**
     * Factory for constructing a new {@link BloomFilter} sized for an expected number of elements.
     */
    @FunctionalInterface
    interface Factory {

        /**
         * Creates a new, empty {@link BloomFilter} sized for {@code expectedElements} insertions.
         *
         * @param expectedElements the anticipated number of keys that will be added; must be &gt; 0
         * @return a freshly constructed, empty filter; never null
         */
        BloomFilter create(int expectedElements);
    }

    /**
     * Factory for reconstructing a {@link BloomFilter} from bytes previously produced by
     * {@link BloomFilter#serialize}.
     */
    @FunctionalInterface
    interface Deserializer {

        /**
         * Reconstructs a {@link BloomFilter} from its serialized representation.
         *
         * @param bytes the bytes produced by {@link BloomFilter#serialize}; must not be null
         * @return a fully initialized, read-ready {@link BloomFilter}; never null
         */
        BloomFilter deserialize(MemorySegment bytes);
    }
}
