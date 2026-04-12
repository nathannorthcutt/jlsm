package jlsm.sstable.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for v3 extensions to {@link CompressionMap}.
 *
 * <p>
 * The v3 format adds a 4-byte CRC32C checksum field to each compression map entry, increasing the
 * entry size from 17 to 21 bytes. These tests reference a 5-component {@code Entry} record and a
 * version-aware {@code deserialize(data, version)} overload — both do not exist yet and will cause
 * compilation failures until implemented.
 * </p>
 */
class CompressionMapV3Test {

    @Test
    void v3EntryHasFiveFields() {
        // v3 Entry adds a checksum field (5th component)
        var entry = new CompressionMap.Entry(0L, 4096, 4096, (byte) 0x00, 0x12345678);
        assertEquals(0L, entry.blockOffset());
        assertEquals(4096, entry.compressedSize());
        assertEquals(4096, entry.uncompressedSize());
        assertEquals((byte) 0x00, entry.codecId());
        assertEquals(0x12345678, entry.checksum());
    }

    @Test
    void v3EntrySerializesTo21Bytes() {
        var entries = List.of(new CompressionMap.Entry(0L, 4096, 4096, (byte) 0x00, 0xAABBCCDD));
        var map = new CompressionMap(entries);
        byte[] serialized = map.serializeV3();

        // 4 bytes blockCount + 1 * 21 bytes entry = 25 bytes
        assertEquals(25, serialized.length);
    }

    @Test
    void v3SerializeDeserializeRoundTrip() {
        var entries = List.of(new CompressionMap.Entry(0L, 3900, 4096, (byte) 0x02, 0x11223344),
                new CompressionMap.Entry(3900L, 4096, 4096, (byte) 0x00, 0x55667788),
                new CompressionMap.Entry(7996L, 3800, 4096, (byte) 0x02, 0x99AABBCC));
        var map = new CompressionMap(entries);
        byte[] serialized = map.serializeV3();
        CompressionMap deserialized = CompressionMap.deserialize(serialized, 3);

        assertEquals(3, deserialized.blockCount());
        for (int i = 0; i < entries.size(); i++) {
            CompressionMap.Entry expected = entries.get(i);
            CompressionMap.Entry actual = deserialized.entry(i);
            assertEquals(expected.blockOffset(), actual.blockOffset());
            assertEquals(expected.compressedSize(), actual.compressedSize());
            assertEquals(expected.uncompressedSize(), actual.uncompressedSize());
            assertEquals(expected.codecId(), actual.codecId());
            assertEquals(expected.checksum(), actual.checksum());
        }
    }

    @Test
    void deserializeV2ReturnsZeroChecksum() {
        // Create v2 data (17-byte entries, no checksum)
        var v2Entries = List.of(new CompressionMap.Entry(0L, 4096, 4096, (byte) 0x00),
                new CompressionMap.Entry(4096L, 3800, 4096, (byte) 0x02));
        var map = new CompressionMap(v2Entries);
        byte[] v2Data = map.serialize();

        // Deserialize with version=2 — checksum should default to 0
        CompressionMap deserialized = CompressionMap.deserialize(v2Data, 2);
        assertEquals(2, deserialized.blockCount());
        for (int i = 0; i < deserialized.blockCount(); i++) {
            assertEquals(0, deserialized.entry(i).checksum(), "v2 entries should have checksum=0");
        }
    }

    @Test
    void deserializeV3ReadsChecksum() {
        // Manually construct v3 binary data: 4 bytes count + 21 bytes per entry
        byte[] data = new byte[4 + 21]; // 1 entry
        // blockCount = 1
        data[0] = 0;
        data[1] = 0;
        data[2] = 0;
        data[3] = 1;
        // blockOffset = 0
        // (bytes 4-11 all zero)
        // compressedSize = 4096 = 0x00001000
        data[12] = 0x00;
        data[13] = 0x00;
        data[14] = 0x10;
        data[15] = 0x00;
        // uncompressedSize = 4096
        data[16] = 0x00;
        data[17] = 0x00;
        data[18] = 0x10;
        data[19] = 0x00;
        // codecId = 0x00
        data[20] = 0x00;
        // checksum = 0xDEADBEEF
        data[21] = (byte) 0xDE;
        data[22] = (byte) 0xAD;
        data[23] = (byte) 0xBE;
        data[24] = (byte) 0xEF;

        CompressionMap deserialized = CompressionMap.deserialize(data, 3);
        assertEquals(1, deserialized.blockCount());
        assertEquals(0xDEADBEEF, deserialized.entry(0).checksum());
    }

    @Test
    void v3OverflowCheckUsesLongArithmetic() {
        // A block count that would overflow with int * 21
        // Integer.MAX_VALUE * 21 overflows int but should be caught
        byte[] data = new byte[4];
        data[0] = (byte) 0x7F;
        data[1] = (byte) 0xFF;
        data[2] = (byte) 0xFF;
        data[3] = (byte) 0xFF; // Integer.MAX_VALUE

        assertThrows(IllegalArgumentException.class, () -> CompressionMap.deserialize(data, 3),
                "should reject block count that causes overflow");
    }

    @Test
    void fullIntRangeForChecksum() {
        // CRC32C values span the full unsigned 32-bit range.
        // When stored in a Java int, some values are negative.
        assertDoesNotThrow(() -> new CompressionMap.Entry(0L, 4096, 4096, (byte) 0x00, 0));
        assertDoesNotThrow(
                () -> new CompressionMap.Entry(0L, 4096, 4096, (byte) 0x00, Integer.MIN_VALUE));
        assertDoesNotThrow(
                () -> new CompressionMap.Entry(0L, 4096, 4096, (byte) 0x00, Integer.MAX_VALUE));
        assertDoesNotThrow(() -> new CompressionMap.Entry(0L, 4096, 4096, (byte) 0x00, -1));
    }

    @Test
    void emptyV3Map() {
        var map = new CompressionMap(List.of());
        byte[] serialized = map.serializeV3();
        // 4 bytes for blockCount only
        assertEquals(4, serialized.length);

        CompressionMap deserialized = CompressionMap.deserialize(serialized, 3);
        assertEquals(0, deserialized.blockCount());
    }

    @Test
    void v2DeserializeShorthandStillWorks() {
        // The existing single-param deserialize(data) should continue to
        // work for v2 data (backward compatibility).
        var entries = List.of(new CompressionMap.Entry(0L, 4096, 4096, (byte) 0x00),
                new CompressionMap.Entry(4096L, 3500, 4096, (byte) 0x02));
        var map = new CompressionMap(entries);
        byte[] v2Data = map.serialize();

        CompressionMap deserialized = CompressionMap.deserialize(v2Data);
        assertEquals(2, deserialized.blockCount());
        assertEquals(entries.get(0), deserialized.entry(0));
        assertEquals(entries.get(1), deserialized.entry(1));
    }
}
