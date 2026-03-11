package jlsm.bloom.blocked;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import jlsm.bloom.hash.Murmur3Hash;
import jlsm.core.bloom.BloomFilter;

/**
 * A cache-friendly bloom filter that partitions its bit array into 512-bit (one cache-line) blocks.
 * All {@code k} hash probes for a given key are confined to a single block, improving cache
 * locality compared to a standard bloom filter that may probe across many cache lines.
 *
 * <p>
 * <b>Pipeline position:</b> constructed during SSTable flush (one filter per SSTable), then
 * serialized into the SSTable footer. On the read path, the filter is deserialized and consulted
 * before performing random I/O.
 *
 * <p>
 * <b>Algorithm:</b> uses MurmurHash3 x64-128 to derive two 64-bit seeds {@code (h1, h2)}, then
 * applies double hashing: {@code bitPos = (h1 + i * h2) & 511} for {@code i} in {@code [0, k)}. All
 * probes for a single key land within the same 512-bit block, selected by
 * {@code blockIndex = (h1 & Long.MAX_VALUE) % numBlocks}.
 *
 * <p>
 * <b>Why {@code h2} must be forced odd:</b> the bit positions form an arithmetic sequence
 * {@code h1, h1+h2, h1+2·h2, …} modulo 512. When {@code h2} is even, the sequence has period
 * {@code 512 / gcd(h2, 512) < 512}, so the {@code k} probes collapse onto a small subset of bit
 * positions rather than spreading across the full block. Empirically this raises the false-positive
 * rate roughly 6× above the target (e.g., ~7% instead of ~1%). Forcing {@code h2 |= 1} makes it
 * coprime to 512, guaranteeing period 512 and keeping the empirical FPR within the expected range.
 * <strong>Do not remove the {@code | 1L} in {@link #add} and {@link #mightContain}.</strong>
 *
 * <p>
 * <b>Serialization format (big-endian):</b>
 *
 * <pre>
 *   [int  numBlocks       ]  4 bytes
 *   [int  numHashFunctions]  4 bytes
 *   [long insertionCount  ]  8 bytes
 *   [long[] bits          ]  numBlocks * 64 bytes (8 longs per block)
 * </pre>
 *
 * Total: {@code 16 + numBlocks * 64} bytes.
 *
 * <p>
 * <b>Thread-safety:</b> not thread-safe during the construction ({@link #add}) phase. Once
 * serialized and deserialized for reads, {@link #mightContain} is safe for concurrent use because
 * the bit array is read-only after deserialization.
 */
public final class BlockedBloomFilter implements BloomFilter {

    private static final double LN2 = Math.log(2.0);
    private static final double LN2_SQUARED = LN2 * LN2;

    /** Number of longs per 512-bit block. */
    private static final int LONGS_PER_BLOCK = 8;

    private final long[] bits;
    private final int numBlocks;
    private final int numHashFunctions;
    private long insertionCount;

    /**
     * Constructs a new, empty {@code BlockedBloomFilter} sized for {@code expectedInsertions} keys
     * at the given {@code targetFpr} false-positive rate.
     *
     * @param expectedInsertions the anticipated number of distinct keys to be added; must be
     *            positive
     * @param targetFpr the desired false-positive probability in {@code (0.0, 1.0)}
     * @throws IllegalArgumentException if {@code expectedInsertions <= 0} or {@code targetFpr} is
     *             not in {@code (0.0, 1.0)}
     */
    public BlockedBloomFilter(int expectedInsertions, double targetFpr) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException(
                    "expectedInsertions must be positive, got: " + expectedInsertions);
        }
        if (targetFpr <= 0.0 || targetFpr >= 1.0) {
            throw new IllegalArgumentException(
                    "targetFpr must be in (0.0, 1.0), got: " + targetFpr);
        }

        // m/n = -ln(fpr) / ln2^2
        double bitsPerElement = -Math.log(targetFpr) / LN2_SQUARED;
        long totalBits = Math.max(512L, (long) Math.ceil(bitsPerElement * expectedInsertions));

        this.numBlocks = (int) Math.max(1L, (totalBits + 511) / 512);
        this.bits = new long[numBlocks * LONGS_PER_BLOCK];

        // k = (m/n) * ln2
        int k = (int) Math.round(bitsPerElement * LN2);
        this.numHashFunctions = Math.max(1, Math.min(30, k));
        this.insertionCount = 0;
    }

    /** Private constructor used during deserialization. */
    private BlockedBloomFilter(int numBlocks, int numHashFunctions, long insertionCount,
            long[] bits) {
        assert numBlocks > 0 : "numBlocks must be positive";
        assert numHashFunctions >= 1 && numHashFunctions <= 30
                : "numHashFunctions must be in [1,30]";
        assert insertionCount >= 0 : "insertionCount must be non-negative";
        assert bits != null && bits.length == numBlocks * LONGS_PER_BLOCK
                : "bits array size mismatch";
        this.numBlocks = numBlocks;
        this.numHashFunctions = numHashFunctions;
        this.insertionCount = insertionCount;
        this.bits = bits;
    }

    /** {@inheritDoc} */
    @Override
    public void add(MemorySegment key) {
        Objects.requireNonNull(key, "key must not be null");
        long[] hashes = Murmur3Hash.hash128(key);
        assert hashes.length == 2 : "hash128 must return exactly 2 longs";
        long h1 = hashes[0];
        // Force h2 odd so it is coprime to the 512-bit block size, ensuring the double-hashing
        // sequence (h1 + i*h2) mod 512 visits all 512 positions before repeating.
        long h2 = hashes[1] | 1L;

        int blockIndex = (int) ((h1 & Long.MAX_VALUE) % numBlocks);
        assert blockIndex >= 0 && blockIndex < numBlocks : "blockIndex out of range";
        int blockBase = blockIndex * LONGS_PER_BLOCK;

        for (int i = 0; i < numHashFunctions; i++) {
            int bitPos = (int) ((h1 + (long) i * h2) & 511);
            assert bitPos >= 0 && bitPos < 512 : "bitPos out of [0,512)";
            bits[blockBase + (bitPos >>> 6)] |= (1L << (bitPos & 63));
        }
        insertionCount++;
    }

    /** {@inheritDoc} */
    @Override
    public boolean mightContain(MemorySegment key) {
        Objects.requireNonNull(key, "key must not be null");
        long[] hashes = Murmur3Hash.hash128(key);
        assert hashes.length == 2 : "hash128 must return exactly 2 longs";
        long h1 = hashes[0];
        // Must match the | 1L in add(); see class-level Javadoc for why h2 must be odd.
        long h2 = hashes[1] | 1L;

        int blockIndex = (int) ((h1 & Long.MAX_VALUE) % numBlocks);
        assert blockIndex >= 0 && blockIndex < numBlocks : "blockIndex out of range";
        int blockBase = blockIndex * LONGS_PER_BLOCK;

        for (int i = 0; i < numHashFunctions; i++) {
            int bitPos = (int) ((h1 + (long) i * h2) & 511);
            assert bitPos >= 0 && bitPos < 512 : "bitPos out of [0,512)";
            if ((bits[blockBase + (bitPos >>> 6)] & (1L << (bitPos & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Computes the theoretical false-positive rate given the number of insertions so far:
     * {@code (1 - e^(-k * n / m))^k} where {@code m} is the total bit count, {@code n} is
     * {@link #insertionCount}, and {@code k} is the number of hash functions.
     */
    @Override
    public double falsePositiveRate() {
        long m = (long) numBlocks * LONGS_PER_BLOCK * 64L;
        if (insertionCount == 0 || m == 0) {
            return 0.0;
        }
        double exponent = -(double) numHashFunctions * insertionCount / m;
        double prob = Math.pow(1.0 - Math.exp(exponent), numHashFunctions);
        return Math.max(0.0, Math.min(1.0, prob));
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Serialized layout (big-endian):
     *
     * <pre>
     *   [int  numBlocks       ]  4 bytes
     *   [int  numHashFunctions]  4 bytes
     *   [long insertionCount  ]  8 bytes
     *   [long[] bits          ]  numBlocks * 64 bytes
     * </pre>
     */
    @Override
    public MemorySegment serialize() {
        assert numBlocks > 0 : "numBlocks must be positive";
        assert bits.length == numBlocks * LONGS_PER_BLOCK : "bits array size mismatch";
        long byteSize = 16L + (long) numBlocks * LONGS_PER_BLOCK * Long.BYTES;
        MemorySegment seg = Arena.ofAuto().allocate(byteSize);
        seg.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 0, numBlocks);
        seg.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 4, numHashFunctions);
        seg.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), 8, insertionCount);
        for (int i = 0; i < bits.length; i++) {
            seg.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), 16L + i * 8L, bits[i]);
        }
        return seg;
    }

    /**
     * Returns a {@link BloomFilter.Deserializer} that reconstructs a {@code BlockedBloomFilter}
     * from bytes produced by {@link #serialize}.
     *
     * @return a deserializer for this filter type; never null
     */
    public static BloomFilter.Deserializer deserializer() {
        return bytes -> {
            Objects.requireNonNull(bytes, "bytes must not be null");
            if (bytes.byteSize() < 16) {
                throw new IllegalArgumentException(
                        "serialized bloom filter must be at least 16 bytes, got: "
                                + bytes.byteSize());
            }
            int numBlocks = bytes.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 0);
            int numHashFunctions = bytes.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN),
                    4);
            if (numBlocks <= 0) {
                throw new IllegalArgumentException("numBlocks must be positive, got: " + numBlocks);
            }
            if (numHashFunctions < 1 || numHashFunctions > 30) {
                throw new IllegalArgumentException(
                        "numHashFunctions must be in [1,30], got: " + numHashFunctions);
            }
            long expectedSize = 16L + (long) numBlocks * LONGS_PER_BLOCK * Long.BYTES;
            if (bytes.byteSize() != expectedSize) {
                throw new IllegalArgumentException(
                        "serialized size %d does not match expected %d for numBlocks=%d"
                                .formatted(bytes.byteSize(), expectedSize, numBlocks));
            }
            long insertionCount = bytes.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN),
                    8);
            long[] bits = new long[numBlocks * LONGS_PER_BLOCK];
            for (int i = 0; i < bits.length; i++) {
                bits[i] = bytes.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN),
                        16L + i * 8L);
            }
            return new BlockedBloomFilter(numBlocks, numHashFunctions, insertionCount, bits);
        };
    }
}
