package jlsm.core.compression;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Collects block samples and trains ZSTD dictionaries via native {@code ZDICT_trainFromBuffer}.
 *
 * <p>
 * Usage pattern:
 * <ol>
 * <li>Create a trainer instance</li>
 * <li>Call {@link #addSample(MemorySegment)} for each training sample (typically uncompressed
 * SSTable data blocks)</li>
 * <li>Call {@link #train(int)} to produce a trained dictionary</li>
 * </ol>
 *
 * <p>
 * The trainer concatenates all samples into a contiguous memory region and maintains a parallel
 * array of sample sizes, as required by the {@code ZDICT_trainFromBuffer} native API.
 *
 * <p>
 * Dictionary training is available only when native libzstd is detected (Tier 1). The
 * {@link #isAvailable()} static method allows callers to check availability before constructing a
 * trainer. Calling {@link #train(int)} when native libzstd is unavailable throws
 * {@link IllegalStateException}.
 *
 * <p>
 * This class is not thread-safe. Callers must ensure that {@link #addSample} and {@link #train} are
 * not called concurrently.
 *
 * @see ZstdNativeBindings
 * @see ZstdCodec
 * @see <a href="../../../../.spec/domains/serialization/F18-zstd-dictionary-compression.md">F18
 *      R15-R17</a>
 */
// @spec compression.zstd-dictionary.R15 — trainer: addSample, train(maxDictBytes), static isAvailable()
// @spec compression.zstd-dictionary.R15a — samples concatenated into contiguous region with parallel size array
// @spec compression.zstd-dictionary.R16 — train() invokes native ZDICT_trainFromBuffer, validates [256, 1 MiB]
// @spec compression.zstd-dictionary.R17 — at least one sample required; train() with zero samples throws ISE
public final class ZstdDictionaryTrainer {

    /** Minimum dictionary size in bytes (inclusive). */
    static final int MIN_DICT_BYTES = 256;

    /** Maximum dictionary size in bytes (inclusive): 1 MiB. */
    static final int MAX_DICT_BYTES = 1_048_576;

    private final List<byte[]> samples;

    /**
     * Creates a new dictionary trainer.
     *
     * <p>
     * The trainer is initially empty (zero samples). Samples must be added via
     * {@link #addSample(MemorySegment)} before calling {@link #train(int)}.
     */
    public ZstdDictionaryTrainer() {
        this.samples = new ArrayList<>();
    }

    /**
     * Returns whether dictionary training is available.
     *
     * <p>
     * Dictionary training requires native libzstd (Tier 1). Returns {@code true} only when
     * {@link ZstdNativeBindings#activeTier()} is {@link ZstdNativeBindings.Tier#NATIVE}.
     *
     * @return {@code true} if native libzstd is available for training
     */
    public static boolean isAvailable() {
        return ZstdNativeBindings.isNativeAvailable();
    }

    /**
     * Adds a training sample to this trainer.
     *
     * <p>
     * The sample bytes are copied into an internal buffer. The sample size is recorded for the
     * {@code ZDICT_trainFromBuffer} API.
     *
     * @param sample the sample data to add; must not be null and must have non-zero byte size
     * @throws NullPointerException if {@code sample} is null
     * @throws IllegalArgumentException if {@code sample.byteSize()} is zero
     */
    public void addSample(MemorySegment sample) {
        Objects.requireNonNull(sample, "sample must not be null");
        if (sample.byteSize() == 0) {
            throw new IllegalArgumentException("sample must have non-zero byte size");
        }
        byte[] copy = new byte[(int) sample.byteSize()];
        MemorySegment.copy(sample, 0, MemorySegment.ofArray(copy), 0, copy.length);
        samples.add(copy);
    }

    /**
     * Trains a ZSTD dictionary from the collected samples via native {@code ZDICT_trainFromBuffer}.
     *
     * @param maxDictBytes maximum size of the trained dictionary in bytes; must be between
     *            {@value #MIN_DICT_BYTES} and {@value #MAX_DICT_BYTES} (inclusive)
     * @return a {@link MemorySegment} containing the trained dictionary bytes; never null
     * @throws IllegalArgumentException if {@code maxDictBytes} is outside the allowed range
     * @throws IllegalStateException if no samples have been added, or if native libzstd is not
     *             available
     * @throws UncheckedIOException if native {@code ZDICT_trainFromBuffer} returns an error code
     */
    public MemorySegment train(int maxDictBytes) {
        if (maxDictBytes < MIN_DICT_BYTES || maxDictBytes > MAX_DICT_BYTES) {
            throw new IllegalArgumentException("maxDictBytes must be between %d and %d, got: %d"
                    .formatted(MIN_DICT_BYTES, MAX_DICT_BYTES, maxDictBytes));
        }
        if (samples.isEmpty()) {
            throw new IllegalStateException("At least one sample must be added before training");
        }
        if (!ZstdNativeBindings.isNativeAvailable()) {
            throw new IllegalStateException(
                    "Dictionary training requires native libzstd (Tier 1), but active tier is: "
                            + ZstdNativeBindings.activeTier());
        }

        // Concatenate all samples into a contiguous region and build parallel size array
        long totalSampleBytes = 0;
        for (byte[] s : samples) {
            totalSampleBytes += s.length;
        }

        try (Arena arena = Arena.ofConfined()) {
            // Contiguous sample buffer
            MemorySegment samplesBuf = arena.allocate(totalSampleBytes);
            // Parallel sizes array (size_t per sample = 8 bytes on 64-bit)
            MemorySegment sizesBuf = arena
                    .allocate((long) samples.size() * ValueLayout.JAVA_LONG.byteSize());

            long offset = 0;
            for (int i = 0; i < samples.size(); i++) {
                byte[] s = samples.get(i);
                MemorySegment.copy(MemorySegment.ofArray(s), 0, samplesBuf, offset, s.length);
                sizesBuf.setAtIndex(ValueLayout.JAVA_LONG, i, s.length);
                offset += s.length;
            }

            // Dictionary output buffer
            MemorySegment dictBuf = arena.allocate(maxDictBytes);

            // ZDICT_trainFromBuffer(dictBuffer, dictCapacity, samplesBuffer, samplesSizes,
            // nbSamples) -> size_t
            long result = (long) ZstdNativeBindings.trainFromBuffer().invokeExact(dictBuf,
                    (long) maxDictBytes, samplesBuf, sizesBuf, samples.size());

            // Check for error
            int isErr = (int) ZstdNativeBindings.isError().invokeExact(result);
            if (isErr != 0) {
                MemorySegment namePtr = (MemorySegment) ZstdNativeBindings.getErrorName()
                        .invokeExact(result);
                String errorName = namePtr.reinterpret(256).getString(0);
                throw new UncheckedIOException(
                        new IOException("ZDICT_trainFromBuffer failed: " + errorName));
            }

            assert result > 0 && result <= maxDictBytes
                    : "trained dict size %d out of bounds [1, %d]".formatted(result, maxDictBytes);

            // Copy the trained dictionary to a heap-backed segment that outlives the arena
            byte[] dictBytes = new byte[(int) result];
            MemorySegment.copy(dictBuf, 0, MemorySegment.ofArray(dictBytes), 0, (int) result);
            return MemorySegment.ofArray(dictBytes);
        } catch (UncheckedIOException e) {
            throw e;
        } catch (Throwable t) {
            throw new UncheckedIOException(new IOException("Dictionary training failed", t));
        }
    }

    /**
     * Returns the number of samples currently collected.
     *
     * @return the sample count; always {@code >= 0}
     */
    public int sampleCount() {
        return samples.size();
    }
}
