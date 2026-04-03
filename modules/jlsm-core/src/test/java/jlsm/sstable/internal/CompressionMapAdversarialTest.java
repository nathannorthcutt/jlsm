package jlsm.sstable.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for {@link CompressionMap} and {@link CompressionMap.Entry}.
 *
 * <p>
 * Targets findings from block-compression spec-analysis.md.
 */
class CompressionMapAdversarialTest {

    // ---- CONTRACT-GAP-1: Entry record lacks validation ----

    @Test
    void entryRejectsNegativeCompressedSize() {
        // CONTRACT-GAP-1: negative compressedSize should be rejected
        assertThrows(IllegalArgumentException.class,
                () -> new CompressionMap.Entry(0L, -1, 100, (byte) 0x00));
    }

    @Test
    void entryRejectsNegativeUncompressedSize() {
        // CONTRACT-GAP-1: negative uncompressedSize should be rejected
        assertThrows(IllegalArgumentException.class,
                () -> new CompressionMap.Entry(0L, 100, -1, (byte) 0x00));
    }

    @Test
    void entryRejectsNegativeBlockOffset() {
        // CONTRACT-GAP-1: negative blockOffset should be rejected
        assertThrows(IllegalArgumentException.class,
                () -> new CompressionMap.Entry(-1L, 100, 100, (byte) 0x00));
    }

    @Test
    void entryAcceptsZeroSizes() {
        // Boundary: zero is valid (empty block edge case) — both zero is OK
        CompressionMap.Entry entry = new CompressionMap.Entry(0L, 0, 0, (byte) 0x00);
        assertEquals(0, entry.compressedSize());
        assertEquals(0, entry.uncompressedSize());
    }

    // C1-F8: Entry with compressedSize=0 but uncompressedSize>0 — physically impossible.
    // Cannot decompress 0 bytes into 4096 bytes.
    @Test
    void entryRejectsZeroCompressedWithNonZeroUncompressed_C1F8() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompressionMap.Entry(0L, 0, 4096, (byte) 0x02),
                "C1-F8: compressedSize=0 with uncompressedSize>0 is physically impossible");
    }

    // C1-F8: Entry with uncompressedSize=0 but compressedSize>0 and codec is NOT none —
    // also suspicious. A non-none codec compressing to >0 bytes that decompresses to 0 is wrong.
    @Test
    void entryRejectsNonZeroCompressedWithZeroUncompressedNonNoneCodec_C1F8() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompressionMap.Entry(0L, 4096, 0, (byte) 0x02),
                "C1-F8: compressedSize>0 with uncompressedSize=0 and non-none codec is invalid");
    }

    // ---- IMPL-RISK-3: deserialize accepts negative block count ----

    @Test
    void deserializeRejectsNegativeBlockCount() {
        // IMPL-RISK-3: first 4 bytes encode -1 (0xFFFFFFFF), should throw IAE
        byte[] data = new byte[]{ (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        assertThrows(IllegalArgumentException.class, () -> CompressionMap.deserialize(data));
    }

    @Test
    void deserializeRejectsLargeBlockCountExceedingDataLength() {
        // Boundary: blockCount = 1 but data is only 4 bytes (missing entry data)
        byte[] data = new byte[]{ 0, 0, 0, 1 };
        assertThrows(IllegalArgumentException.class, () -> CompressionMap.deserialize(data));
    }

    // C1-F7: Integer overflow in deserialize with huge blockCount.
    // blockCount = Integer.MAX_VALUE (0x7FFFFFFF): expectedLength = 4 + MAX_VALUE * 17
    // which overflows to a negative number, making data.length < expectedLength pass incorrectly.
    @Test
    void deserializeRejectsHugeBlockCountIntegerOverflow_C1F7() {
        // Encode blockCount = Integer.MAX_VALUE in first 4 bytes, with 17 extra bytes
        byte[] data = new byte[21]; // just enough to not be "too short for header"
        data[0] = 0x7F;
        data[1] = (byte) 0xFF;
        data[2] = (byte) 0xFF;
        data[3] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> CompressionMap.deserialize(data),
                "C1-F7: blockCount=MAX_VALUE causes expectedLength overflow — must throw IAE");
    }

    // C1-F7: Moderate blockCount that still causes overflow: 126_322_568
    // 4 + 126_322_568 * 17 = 4 + 2_147_483_656 overflows int
    @Test
    void deserializeRejectsModerateBlockCountOverflow_C1F7() {
        int blockCount = 126_322_568;
        byte[] data = new byte[21];
        data[0] = (byte) (blockCount >>> 24);
        data[1] = (byte) (blockCount >>> 16);
        data[2] = (byte) (blockCount >>> 8);
        data[3] = (byte) blockCount;
        assertThrows(IllegalArgumentException.class, () -> CompressionMap.deserialize(data),
                "C1-F7: moderate blockCount causing overflow must throw IAE");
    }

    // C1-F6: deserialize now rejects trailing bytes (fixed by F-R1.cb.2.4)
    @Test
    void deserializeRejectsTrailingBytes_C1F6() {
        var map = new CompressionMap(List.of(new CompressionMap.Entry(0L, 100, 200, (byte) 0x00)));
        byte[] serialized = map.serialize();
        byte[] withTrailing = new byte[serialized.length + 50];
        System.arraycopy(serialized, 0, withTrailing, 0, serialized.length);
        for (int i = serialized.length; i < withTrailing.length; i++) {
            withTrailing[i] = (byte) 0xAB;
        }
        assertThrows(IllegalArgumentException.class, () -> CompressionMap.deserialize(withTrailing),
                "deserialize should reject trailing bytes");
    }

    @Test
    void deserializeRejectsTrailingGarbageExactly() {
        // blockCount claims 0 entries but there's trailing data
        byte[] data = new byte[]{ 0, 0, 0, 0, 99, 99 };
        assertThrows(IllegalArgumentException.class, () -> CompressionMap.deserialize(data),
                "deserialize should reject trailing bytes after zero-entry map");
    }
}
