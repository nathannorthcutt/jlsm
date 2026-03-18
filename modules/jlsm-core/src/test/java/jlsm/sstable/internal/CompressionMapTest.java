package jlsm.sstable.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CompressionMap} serialization, deserialization, and access methods.
 */
class CompressionMapTest {

    @Test
    void testRoundTripSerialization() {
        var entries = List.of(new CompressionMap.Entry(0L, 3900, 4096, (byte) 0x02),
                new CompressionMap.Entry(3900L, 4096, 4096, (byte) 0x00),
                new CompressionMap.Entry(7996L, 3800, 4096, (byte) 0x02));
        var map = new CompressionMap(entries);
        byte[] serialized = map.serialize();
        CompressionMap deserialized = CompressionMap.deserialize(serialized);

        assertEquals(3, deserialized.blockCount());
        for (int i = 0; i < entries.size(); i++) {
            CompressionMap.Entry expected = entries.get(i);
            CompressionMap.Entry actual = deserialized.entry(i);
            assertEquals(expected.blockOffset(), actual.blockOffset());
            assertEquals(expected.compressedSize(), actual.compressedSize());
            assertEquals(expected.uncompressedSize(), actual.uncompressedSize());
            assertEquals(expected.codecId(), actual.codecId());
        }
    }

    @Test
    void testSingleEntry() {
        var entries = List.of(new CompressionMap.Entry(0L, 4096, 4096, (byte) 0x00));
        var map = new CompressionMap(entries);

        assertEquals(1, map.blockCount());
        assertEquals(0L, map.entry(0).blockOffset());
        assertEquals(4096, map.entry(0).compressedSize());
        assertEquals((byte) 0x00, map.entry(0).codecId());

        byte[] serialized = map.serialize();
        // 4 bytes blockCount + 1 * 17 bytes entry = 21 bytes
        assertEquals(4 + CompressionMap.ENTRY_SIZE, serialized.length);

        CompressionMap roundTripped = CompressionMap.deserialize(serialized);
        assertEquals(1, roundTripped.blockCount());
        assertEquals(map.entry(0), roundTripped.entry(0));
    }

    @Test
    void testManyEntries() {
        List<CompressionMap.Entry> entries = new ArrayList<>();
        long offset = 0;
        for (int i = 0; i < 200; i++) {
            int compressedSize = 3000 + (i % 1000);
            entries.add(new CompressionMap.Entry(offset, compressedSize, 4096, (byte) 0x02));
            offset += compressedSize;
        }
        var map = new CompressionMap(entries);
        assertEquals(200, map.blockCount());

        byte[] serialized = map.serialize();
        assertEquals(4 + 200 * CompressionMap.ENTRY_SIZE, serialized.length);

        CompressionMap deserialized = CompressionMap.deserialize(serialized);
        assertEquals(200, deserialized.blockCount());
        for (int i = 0; i < 200; i++) {
            assertEquals(entries.get(i), deserialized.entry(i));
        }
    }

    @Test
    void testEmptyMap() {
        var map = new CompressionMap(List.of());
        assertEquals(0, map.blockCount());

        byte[] serialized = map.serialize();
        assertEquals(4, serialized.length); // just the blockCount field

        CompressionMap deserialized = CompressionMap.deserialize(serialized);
        assertEquals(0, deserialized.blockCount());
    }

    @Test
    void testEntryAccess() {
        var entries = List.of(new CompressionMap.Entry(0L, 100, 200, (byte) 0x00),
                new CompressionMap.Entry(100L, 150, 200, (byte) 0x02));
        var map = new CompressionMap(entries);

        assertEquals(2, map.blockCount());
        assertEquals(entries, map.entries());
        assertEquals(entries.get(0), map.entry(0));
        assertEquals(entries.get(1), map.entry(1));
        assertThrows(IndexOutOfBoundsException.class, () -> map.entry(2));
        assertThrows(IndexOutOfBoundsException.class, () -> map.entry(-1));
    }

    @Test
    void testEntriesListIsUnmodifiable() {
        var entries = List.of(new CompressionMap.Entry(0L, 100, 200, (byte) 0x00));
        var map = new CompressionMap(entries);
        assertThrows(UnsupportedOperationException.class,
                () -> map.entries().add(new CompressionMap.Entry(0L, 0, 0, (byte) 0)));
    }

    @Test
    void testDeserializeMalformedData() {
        // Too short to contain even the block count
        assertThrows(IllegalArgumentException.class, () -> CompressionMap.deserialize(new byte[2]));
    }

    @Test
    void testDeserializeNullThrows() {
        assertThrows(NullPointerException.class, () -> CompressionMap.deserialize(null));
    }

    @Test
    void testConstructorNullThrows() {
        assertThrows(NullPointerException.class, () -> new CompressionMap(null));
    }
}
