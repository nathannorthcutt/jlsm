package jlsm.bloom;

import jlsm.core.bloom.BloomFilter;

import java.lang.foreign.MemorySegment;

/**
 * A no-op {@link BloomFilter} that always reports membership ({@code mightContain} always returns
 * {@code true}). Useful for SSTable types where point-lookup pruning is irrelevant — for example,
 * IVF range-scan tables in a vector index, where every SSTable is scanned sequentially by prefix
 * rather than probed by key.
 *
 * <p>Because this filter never produces false negatives and never allocates bit storage, it has
 * zero memory overhead and constant-time operations.
 *
 * <p>The serialized form is a single marker byte {@code [0x00]}. The corresponding
 * {@link #deserializer()} reconstructs the singleton instance from any input.
 */
public final class PassthroughBloomFilter implements BloomFilter {

    private static final PassthroughBloomFilter INSTANCE = new PassthroughBloomFilter();
    private static final MemorySegment SERIALIZED = MemorySegment.ofArray(new byte[]{0x00});

    private PassthroughBloomFilter() {}

    /** Returns a {@link BloomFilter.Factory} that always returns this singleton. */
    public static BloomFilter.Factory factory() {
        return expectedElements -> INSTANCE;
    }

    /** Returns a {@link BloomFilter.Deserializer} that always returns this singleton. */
    public static BloomFilter.Deserializer deserializer() {
        return bytes -> INSTANCE;
    }

    @Override
    public void add(MemorySegment key) {
        // no-op: passthrough filter does not track keys
    }

    @Override
    public boolean mightContain(MemorySegment key) {
        return true;
    }

    @Override
    public double falsePositiveRate() {
        return 1.0;
    }

    @Override
    public MemorySegment serialize() {
        return SERIALIZED;
    }
}
