package jlsm.core.compression;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ZstdDictionaryTrainer} sample collection, validation, and training.
 *
 * <p>
 * Tests are designed to work regardless of native libzstd availability. Training tests that require
 * native are guarded by {@link ZstdDictionaryTrainer#isAvailable()}.
 */
// @spec compression.zstd-dictionary.R15,R15a,R16,R17
class ZstdDictionaryTrainerTest {

    // ---- isAvailable matches tier ----

    @Test
    void isAvailableMatchesNativeTier() {
        assertEquals(ZstdNativeBindings.isNativeAvailable(), ZstdDictionaryTrainer.isAvailable(),
                "isAvailable() must match ZstdNativeBindings.isNativeAvailable()");
    }

    // ---- Construction ----

    @Test
    void newTrainerHasZeroSamples() {
        var trainer = new ZstdDictionaryTrainer();
        assertEquals(0, trainer.sampleCount());
    }

    // ---- addSample validation ----

    @Test
    void addSampleRejectsNull() {
        var trainer = new ZstdDictionaryTrainer();
        assertThrows(NullPointerException.class, () -> trainer.addSample(null));
    }

    @Test
    void addSampleRejectsEmptySegment() {
        var trainer = new ZstdDictionaryTrainer();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment empty = arena.allocate(0);
            assertThrows(IllegalArgumentException.class, () -> trainer.addSample(empty));
        }
    }

    @Test
    void addSampleIncrementsSampleCount() {
        var trainer = new ZstdDictionaryTrainer();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sample = arena.allocate(100);
            trainer.addSample(sample);
            assertEquals(1, trainer.sampleCount());
            trainer.addSample(sample);
            assertEquals(2, trainer.sampleCount());
        }
    }

    // ---- train() validation ----

    @Test
    void trainRejectsDictSizeBelowMinimum() {
        var trainer = new ZstdDictionaryTrainer();
        try (Arena arena = Arena.ofConfined()) {
            trainer.addSample(arena.allocate(100));
        }
        assertThrows(IllegalArgumentException.class,
                () -> trainer.train(ZstdDictionaryTrainer.MIN_DICT_BYTES - 1));
    }

    @Test
    void trainRejectsDictSizeAboveMaximum() {
        var trainer = new ZstdDictionaryTrainer();
        try (Arena arena = Arena.ofConfined()) {
            trainer.addSample(arena.allocate(100));
        }
        assertThrows(IllegalArgumentException.class,
                () -> trainer.train(ZstdDictionaryTrainer.MAX_DICT_BYTES + 1));
    }

    @Test
    void trainRequiresAtLeastOneSample() {
        var trainer = new ZstdDictionaryTrainer();
        assertThrows(IllegalStateException.class,
                () -> trainer.train(ZstdDictionaryTrainer.MIN_DICT_BYTES));
    }

    @Test
    void trainThrowsWhenNativeUnavailable() {
        if (ZstdDictionaryTrainer.isAvailable()) {
            return; // skip when native is available
        }
        var trainer = new ZstdDictionaryTrainer();
        try (Arena arena = Arena.ofConfined()) {
            trainer.addSample(arena.allocate(100));
        }
        assertThrows(IllegalStateException.class,
                () -> trainer.train(ZstdDictionaryTrainer.MIN_DICT_BYTES));
    }

    // ---- train() with native ----

    @Test
    void trainProducesDictionaryWhenNativeAvailable() {
        if (!ZstdDictionaryTrainer.isAvailable()) {
            return; // skip when native unavailable
        }
        var trainer = new ZstdDictionaryTrainer();
        try (Arena arena = Arena.ofConfined()) {
            // Add enough diverse samples to make training meaningful
            for (int i = 0; i < 100; i++) {
                byte[] data = ("sample block %d with some repeated content for training "
                        + "purposes, key=%d value=%d data=%d").formatted(i, i * 7, i * 13, i * 31)
                        .getBytes();
                MemorySegment sample = arena.allocate(data.length);
                sample.copyFrom(MemorySegment.ofArray(data));
                trainer.addSample(sample);
            }
        }
        MemorySegment dict = trainer.train(ZstdDictionaryTrainer.MIN_DICT_BYTES);
        assertNotNull(dict, "trained dictionary must not be null");
        assertTrue(dict.byteSize() > 0, "trained dictionary must have non-zero size");
        assertTrue(dict.byteSize() <= ZstdDictionaryTrainer.MIN_DICT_BYTES,
                "trained dictionary must not exceed maxDictBytes");
    }

    // ---- Dictionary compression round-trip (native only) ----

    @Test
    void dictionaryCompressionRoundTrip() {
        if (!ZstdDictionaryTrainer.isAvailable()) {
            return;
        }
        var trainer = new ZstdDictionaryTrainer();
        try (Arena arena = Arena.ofConfined()) {
            // Train with representative data
            for (int i = 0; i < 100; i++) {
                byte[] data = ("record key=%d value=some-payload-%d timestamp=%d".formatted(i,
                        i * 3, System.nanoTime())).getBytes();
                MemorySegment sample = arena.allocate(data.length);
                sample.copyFrom(MemorySegment.ofArray(data));
                trainer.addSample(sample);
            }
        }

        MemorySegment dict = trainer.train(1024);

        // Compress and decompress with dictionary
        try (var codec = new ZstdCodec(3, dict); Arena arena = Arena.ofConfined()) {
            byte[] input = "record key=999 value=some-payload-999 timestamp=12345".getBytes();
            MemorySegment src = arena.allocate(input.length);
            src.copyFrom(MemorySegment.ofArray(input));

            int maxLen = codec.maxCompressedLength(input.length);
            MemorySegment dst = arena.allocate(maxLen);
            MemorySegment compressed = codec.compress(src, dst);

            MemorySegment decompDst = arena.allocate(input.length);
            MemorySegment decompressed = codec.decompress(compressed, decompDst, input.length);

            byte[] result = new byte[input.length];
            MemorySegment.copy(decompressed, 0, MemorySegment.ofArray(result), 0, input.length);
            assertArrayEquals(input, result);
        }
    }

    // ---- Constants ----

    @Test
    void minDictBytesIs256() {
        assertEquals(256, ZstdDictionaryTrainer.MIN_DICT_BYTES);
    }

    @Test
    void maxDictBytesIs1MiB() {
        assertEquals(1_048_576, ZstdDictionaryTrainer.MAX_DICT_BYTES);
    }
}
