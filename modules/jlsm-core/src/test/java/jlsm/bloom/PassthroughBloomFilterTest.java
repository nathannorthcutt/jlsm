package jlsm.bloom;

import jlsm.core.bloom.BloomFilter;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PassthroughBloomFilterTest {

    private static MemorySegment key(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void mightContainAlwaysTrue() {
        BloomFilter filter = PassthroughBloomFilter.factory().create(100);
        assertTrue(filter.mightContain(key("any-key")));
        assertTrue(filter.mightContain(key("never-added")));
        assertTrue(filter.mightContain(MemorySegment.ofArray(new byte[0])));
    }

    @Test
    void addIsNoOp() {
        BloomFilter filter = PassthroughBloomFilter.factory().create(100);
        // Add should not throw and should have no observable effect
        assertDoesNotThrow(() -> filter.add(key("some-key")));
        assertTrue(filter.mightContain(key("some-key")));
        assertTrue(filter.mightContain(key("not-added")));
    }

    @Test
    void falsePositiveRateIsOne() {
        BloomFilter filter = PassthroughBloomFilter.factory().create(100);
        assertEquals(1.0, filter.falsePositiveRate());
    }

    @Test
    void serializeRoundTrip() {
        BloomFilter filter = PassthroughBloomFilter.factory().create(100);
        MemorySegment serialized = filter.serialize();

        // Serialized form must be non-null and non-empty (contract)
        assertNotNull(serialized);
        assertTrue(serialized.byteSize() > 0);

        // Deserializer reconstructs a filter that still works correctly
        BloomFilter deserialized = PassthroughBloomFilter.deserializer().deserialize(serialized);
        assertNotNull(deserialized);
        assertTrue(deserialized.mightContain(key("any")));
    }

    @Test
    void factoryReturnsSingleton() {
        BloomFilter a = PassthroughBloomFilter.factory().create(10);
        BloomFilter b = PassthroughBloomFilter.factory().create(10000);
        assertSame(a, b);
    }

    @Test
    void deserializerReturnsSingleton() {
        MemorySegment bytes = MemorySegment.ofArray(new byte[]{ 0x00 });
        BloomFilter a = PassthroughBloomFilter.deserializer().deserialize(bytes);
        BloomFilter b = PassthroughBloomFilter.deserializer().deserialize(bytes);
        assertSame(a, b);
    }
}
