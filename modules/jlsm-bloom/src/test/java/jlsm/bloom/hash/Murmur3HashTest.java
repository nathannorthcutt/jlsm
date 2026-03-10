package jlsm.bloom.hash;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Murmur3HashTest {

    private static MemorySegment segment(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    private static MemorySegment segment(byte[] bytes) {
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    @Test
    void deterministicSameKeyReturnsSameHash() {
        MemorySegment key = segment("hello-world");
        long[] h1 = Murmur3Hash.hash128(key);
        long[] h2 = Murmur3Hash.hash128(key);
        assertArrayEquals(h1, h2);
    }

    @Test
    void emptyKeyReturnsNonNullResult() {
        MemorySegment empty = Arena.ofAuto().allocate(0);
        long[] result = Murmur3Hash.hash128(empty);
        assertNotNull(result);
        assertEquals(2, result.length);
    }

    @Test
    void singleByteKeyDoesNotThrow() {
        MemorySegment key = segment(new byte[]{42});
        long[] result = assertDoesNotThrow(() -> Murmur3Hash.hash128(key));
        assertNotNull(result);
        assertEquals(2, result.length);
    }

    @Test
    void knownVector() {
        // Reference values computed via canonical MurmurHash3_x64_128 with seed=0.
        // Input: "The quick brown fox" (19 bytes UTF-8).
        // Cross-verified against independent Python reference implementation.
        // h1=0x85a60ea92caa4a2a, h2=0xfde55440169b939e
        MemorySegment key = segment("The quick brown fox");
        long[] result = Murmur3Hash.hash128(key);
        assertEquals(0x85a60ea92caa4a2aL, result[0], "h1 mismatch for known vector");
        assertEquals(0xfde55440169b939eL, result[1], "h2 mismatch for known vector");
    }

    @Test
    void hash128RejectsNullKey() {
        assertThrows(NullPointerException.class, () -> Murmur3Hash.hash128(null));
    }

    @Test
    void distinctKeysProduceDistinctHashes() {
        String[] keys = {"apple", "banana", "cherry", "date", "elderberry"};
        var hashes = new java.util.HashSet<Long>();
        for (String k : keys) {
            long[] h = Murmur3Hash.hash128(segment(k));
            hashes.add(h[0]);
        }
        assertEquals(keys.length, hashes.size(), "expected no collisions on h1 for distinct short keys");
    }
}
