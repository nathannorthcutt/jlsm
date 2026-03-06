package jlsm.bloom.blocked;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import jlsm.core.bloom.BloomFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlockedBloomFilterTest {

    private static MemorySegment key(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    private static MemorySegment key(long n) {
        return key("key-" + n);
    }

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Test
    void constructorRejectsZeroInsertions() {
        assertThrows(IllegalArgumentException.class, () -> new BlockedBloomFilter(0, 0.01));
    }

    @Test
    void constructorRejectsNegativeInsertions() {
        assertThrows(IllegalArgumentException.class, () -> new BlockedBloomFilter(-1, 0.01));
    }

    @Test
    void constructorRejectsZeroFpr() {
        assertThrows(IllegalArgumentException.class, () -> new BlockedBloomFilter(100, 0.0));
    }

    @Test
    void constructorRejectsOneFpr() {
        assertThrows(IllegalArgumentException.class, () -> new BlockedBloomFilter(100, 1.0));
    }

    @Test
    void constructorRejectsNegativeFpr() {
        assertThrows(IllegalArgumentException.class, () -> new BlockedBloomFilter(100, -0.5));
    }

    // -------------------------------------------------------------------------
    // No false negatives
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {100, 1000, 10000})
    void noFalseNegatives(int n) {
        BloomFilter filter = new BlockedBloomFilter(n, 0.01);
        for (int i = 0; i < n; i++) {
            filter.add(key(i));
        }
        for (int i = 0; i < n; i++) {
            assertTrue(filter.mightContain(key(i)), "false negative for key-" + i);
        }
    }

    // -------------------------------------------------------------------------
    // False-positive rate
    // -------------------------------------------------------------------------

    @Test
    void falsePositiveRateBelowTwiceTarget() {
        int n = 10_000;
        double targetFpr = 0.01;
        BloomFilter filter = new BlockedBloomFilter(n, targetFpr);
        for (int i = 0; i < n; i++) {
            filter.add(key(i));
        }
        int falsePositives = 0;
        int probes = 100_000;
        for (int i = n; i < n + probes; i++) {
            if (filter.mightContain(key(i))) {
                falsePositives++;
            }
        }
        double empiricalFpr = (double) falsePositives / probes;
        assertTrue(empiricalFpr <= 2 * targetFpr,
                "empirical FPR %.4f exceeds 2× target %.4f".formatted(empiricalFpr, targetFpr));
    }

    @Test
    void falsePositiveRateMethodReturnsValueInRange() {
        BloomFilter filter = new BlockedBloomFilter(1000, 0.01);
        for (int i = 0; i < 1000; i++) {
            filter.add(key(i));
        }
        double fpr = filter.falsePositiveRate();
        assertTrue(fpr >= 0.0 && fpr <= 1.0, "FPR out of [0,1]: " + fpr);
    }

    @Test
    void falsePositiveRateMethodIsWithinOrderOfMagnitude() {
        double targetFpr = 0.01;
        BloomFilter filter = new BlockedBloomFilter(1000, targetFpr);
        for (int i = 0; i < 1000; i++) {
            filter.add(key(i));
        }
        double fpr = filter.falsePositiveRate();
        // within one order of magnitude: targetFpr/10 <= fpr <= targetFpr*10
        assertTrue(fpr >= targetFpr / 10.0 && fpr <= targetFpr * 10.0,
                "FPR %.6f not within order of magnitude of target %.6f".formatted(fpr, targetFpr));
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    void emptyKeyDoesNotThrow() {
        BloomFilter filter = new BlockedBloomFilter(100, 0.01);
        MemorySegment empty = Arena.ofAuto().allocate(0);
        assertDoesNotThrow(() -> filter.add(empty));
        assertDoesNotThrow(() -> filter.mightContain(empty));
    }

    @Test
    void emptyKeyRoundTrip() {
        BloomFilter filter = new BlockedBloomFilter(100, 0.01);
        MemorySegment empty = Arena.ofAuto().allocate(0);
        filter.add(empty);
        assertTrue(filter.mightContain(empty), "empty key should be found after add");
    }

    @Test
    void singleInsertionIsRecognized() {
        BloomFilter filter = new BlockedBloomFilter(1, 0.01);
        MemorySegment k = key("only-key");
        filter.add(k);
        assertTrue(filter.mightContain(k));
    }

    // -------------------------------------------------------------------------
    // Serialize / deserialize round-trip
    // -------------------------------------------------------------------------

    @Test
    void serializeDeserializeRoundTrip() {
        int n = 500;
        BlockedBloomFilter original = new BlockedBloomFilter(n, 0.01);
        for (int i = 0; i < n; i++) {
            original.add(key(i));
        }

        MemorySegment bytes = original.serialize();
        BloomFilter restored = BlockedBloomFilter.deserializer().deserialize(bytes);

        for (int i = 0; i < n; i++) {
            assertTrue(restored.mightContain(key(i)),
                    "deserialized filter has false negative for key-" + i);
        }
    }

    @Test
    void serializedSizeStructuralInvariant() {
        // Size must be 16 + numBlocks*64; so (byteSize - 16) % 64 == 0
        BlockedBloomFilter filter = new BlockedBloomFilter(1000, 0.01);
        long byteSize = filter.serialize().byteSize();
        assertEquals(0, (byteSize - 16) % 64,
                "serialized size invariant broken: size=" + byteSize);
    }
}
