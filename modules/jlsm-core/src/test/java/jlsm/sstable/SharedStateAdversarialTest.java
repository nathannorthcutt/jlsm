package jlsm.sstable;

import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for shared-state concerns in SSTable writer.
 */
class SharedStateAdversarialTest {

    @TempDir
    Path tempDir;

    // Finding: F-R1.shared_state.1.4
    // Bug: writeKeyIndexV1 indexSize computation overflows int for very large key counts
    // Correct behavior: Should throw IOException when the computed index size exceeds
    // Integer.MAX_VALUE
    // Fix location: TrieSSTableWriter.writeKeyIndexV1 (~lines 328-345)
    // Regression watch: Ensure normal-sized key indexes still work correctly
    @Test
    void test_writeKeyIndexV1_indexSize_overflow_throws_IOException() throws Exception {
        Path file = tempDir.resolve("overflow.sst");
        try (var writer = new TrieSSTableWriter(1L, new Level(0), file)) {

            // Each key of length 1 contributes 4 + 1 + 8 = 13 bytes to indexSize.
            // Starting at indexSize = 4, after N keys: indexSize = 4 + 13*N.
            // Overflow occurs when 4 + 13*N > Integer.MAX_VALUE, i.e. N > ~165_191_049.
            // Use 170_000_000 keys to guarantee overflow.
            int keyCount = 170_000_000;
            byte[] sharedKey = new byte[]{ 0x01 };

            List<byte[]> fakeKeys = new AbstractList<>() {
                @Override
                public byte[] get(int index) {
                    return sharedKey;
                }

                @Override
                public int size() {
                    return keyCount;
                }
            };

            List<Long> fakeOffsets = new AbstractList<>() {
                @Override
                public Long get(int index) {
                    return (long) index;
                }

                @Override
                public int size() {
                    return keyCount;
                }
            };

            // Inject fake lists via reflection
            Field indexKeysField = TrieSSTableWriter.class.getDeclaredField("indexKeys");
            indexKeysField.setAccessible(true);
            indexKeysField.set(writer, fakeKeys);

            Field indexOffsetsField = TrieSSTableWriter.class.getDeclaredField("indexOffsets");
            indexOffsetsField.setAccessible(true);
            indexOffsetsField.set(writer, fakeOffsets);

            // Invoke private writeKeyIndexV1() via reflection
            Method writeKeyIndexV1 = TrieSSTableWriter.class.getDeclaredMethod("writeKeyIndexV1");
            writeKeyIndexV1.setAccessible(true);

            // The bug: indexSize overflows int, producing a negative value,
            // which causes NegativeArraySizeException (unchecked) instead of IOException.
            // Correct behavior: detect the overflow and throw IOException.
            InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                    () -> writeKeyIndexV1.invoke(writer));

            Throwable cause = ex.getCause();
            assertInstanceOf(IOException.class, cause,
                    "Expected IOException for index size overflow, but got: "
                            + cause.getClass().getName() + ": " + cause.getMessage());
        }
    }

    // Finding: F-R1.shared_state.1.5
    // Bug: writeKeyIndexV2 indexSize computation overflows int for very large key counts
    // Correct behavior: Should throw IOException when the computed index size exceeds
    // Integer.MAX_VALUE
    // Fix location: TrieSSTableWriter.writeKeyIndexV2 (~lines 355-376)
    // Regression watch: Ensure normal-sized v2 key indexes still work correctly
    @Test
    void test_writeKeyIndexV2_indexSize_overflow_throws_IOException() throws Exception {
        Path file = tempDir.resolve("overflow-v2.sst");
        try (var writer = new TrieSSTableWriter(1L, new Level(0), file)) {

            // Each key of length 1 contributes 4 + 1 + 4 + 4 = 13 bytes to indexSize.
            // Starting at indexSize = 4, after N keys: indexSize = 4 + 13*N.
            // Overflow occurs when 4 + 13*N > Integer.MAX_VALUE, i.e. N > ~165_191_049.
            // Use 170_000_000 keys to guarantee overflow.
            int keyCount = 170_000_000;
            byte[] sharedKey = new byte[]{ 0x01 };

            List<byte[]> fakeKeys = new AbstractList<>() {
                @Override
                public byte[] get(int index) {
                    return sharedKey;
                }

                @Override
                public int size() {
                    return keyCount;
                }
            };

            List<Long> fakeOffsets = new AbstractList<>() {
                @Override
                public Long get(int index) {
                    return (long) index;
                }

                @Override
                public int size() {
                    return keyCount;
                }
            };

            // Inject fake lists via reflection
            Field indexKeysField = TrieSSTableWriter.class.getDeclaredField("indexKeys");
            indexKeysField.setAccessible(true);
            indexKeysField.set(writer, fakeKeys);

            Field indexOffsetsField = TrieSSTableWriter.class.getDeclaredField("indexOffsets");
            indexOffsetsField.setAccessible(true);
            indexOffsetsField.set(writer, fakeOffsets);

            // Invoke private writeKeyIndexV2() via reflection
            Method writeKeyIndexV2 = TrieSSTableWriter.class.getDeclaredMethod("writeKeyIndexV2");
            writeKeyIndexV2.setAccessible(true);

            // The bug: indexSize overflows int, producing a negative value,
            // which causes NegativeArraySizeException (unchecked) instead of IOException.
            // Correct behavior: detect the overflow and throw IOException.
            InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                    () -> writeKeyIndexV2.invoke(writer));

            Throwable cause = ex.getCause();
            assertInstanceOf(IOException.class, cause,
                    "Expected IOException for index size overflow, but got: "
                            + cause.getClass().getName() + ": " + cause.getMessage());
        }
    }

    // Finding: F-R1.shared_state.3.9
    // Bug: Footer.validate checks each section independently but never checks sections don't
    // overlap
    // Correct behavior: Should throw IOException when sections overlap (e.g., fltOffset < idxOffset
    // + idxLength)
    // Fix location: TrieSSTableReader.Footer.validate (~lines 516-582)
    // Regression watch: Ensure non-overlapping sections still pass validation
    @Test
    void test_FooterValidate_overlappingSections_throws_IOException() throws Exception {
        // Access the private Footer record via reflection
        Class<?> footerClass = Class.forName("jlsm.sstable.TrieSSTableReader$Footer");
        Constructor<?> footerCtor = footerClass.getDeclaredConstructor(int.class, // version
                long.class, // mapOffset
                long.class, // mapLength
                long.class, // idxOffset
                long.class, // idxLength
                long.class, // fltOffset
                long.class, // fltLength
                long.class // entryCount
        );
        footerCtor.setAccessible(true);

        Method validate = footerClass.getDeclaredMethod("validate", long.class);
        validate.setAccessible(true);

        // v1 footer: idx section overlaps flt section
        // idxOffset=100, idxLength=200 → idx ends at 300
        // fltOffset=200, fltLength=100 → flt starts at 200, which is inside idx region
        // fileSize=10000 (large enough that bounds-vs-fileSize checks pass)
        Object overlappingV1Footer = footerCtor.newInstance(1, // version (v1)
                0L, // mapOffset (unused in v1)
                0L, // mapLength (unused in v1)
                100L, // idxOffset
                200L, // idxLength — idx spans [100, 300)
                200L, // fltOffset — flt starts at 200, inside idx
                100L, // fltLength — flt spans [200, 300)
                10L // entryCount
        );

        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> validate.invoke(overlappingV1Footer, 10000L));

        Throwable cause = ex.getCause();
        assertInstanceOf(IOException.class, cause,
                "Expected IOException for overlapping idx/flt sections, but got: "
                        + cause.getClass().getName() + ": " + cause.getMessage());
        assertTrue(cause.getMessage().contains("overlap"),
                "IOException message should mention overlap, but was: " + cause.getMessage());
    }

    // Finding: F-R1.shared_state.3.10
    // Bug: Footer.validate checks mapOffset > Integer.MAX_VALUE but not mapLength >
    // Integer.MAX_VALUE
    // Correct behavior: Should throw IOException when mapLength > Integer.MAX_VALUE (v2)
    // Fix location: TrieSSTableReader.Footer.validate (~lines 488-493)
    // Regression watch: Ensure valid mapLength values still pass validation
    @Test
    void test_FooterValidate_mapLengthExceedsIntMaxValue_throws_IOException() throws Exception {
        Class<?> footerClass = Class.forName("jlsm.sstable.TrieSSTableReader$Footer");
        Constructor<?> footerCtor = footerClass.getDeclaredConstructor(int.class, // version
                long.class, // mapOffset
                long.class, // mapLength
                long.class, // idxOffset
                long.class, // idxLength
                long.class, // fltOffset
                long.class, // fltLength
                long.class // entryCount
        );
        footerCtor.setAccessible(true);

        Method validate = footerClass.getDeclaredMethod("validate", long.class);
        validate.setAccessible(true);

        // v2 footer with mapLength exceeding Integer.MAX_VALUE
        // mapOffset=0, mapLength=Integer.MAX_VALUE + 1L → truncation to negative int
        // This causes (int) footer.mapLength at readBytes calls (lines 180, 233) to silently
        // truncate
        long oversizedMapLength = (long) Integer.MAX_VALUE + 1L;
        Object footer = footerCtor.newInstance(2, // version (v2)
                0L, // mapOffset
                oversizedMapLength, // mapLength — exceeds int range
                oversizedMapLength, // idxOffset — after map section
                100L, // idxLength
                oversizedMapLength + 100L, // fltOffset — after idx section
                64L, // fltLength
                10L // entryCount
        );

        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> validate.invoke(footer, Long.MAX_VALUE));

        Throwable cause = ex.getCause();
        assertInstanceOf(IOException.class, cause,
                "Expected IOException for mapLength > Integer.MAX_VALUE, but got: "
                        + cause.getClass().getName() + ": " + cause.getMessage());
    }

    // Finding: F-R1.shared_state.3.7
    // Bug: readDataAtV1 does not validate fileOffset >= 0 before casting to int for arraycopy
    // Correct behavior: Should throw IOException for negative fileOffset
    // Fix location: TrieSSTableReader.readDataAtV1 (~line 471-485)
    // Regression watch: Ensure valid fileOffset values still work correctly
    @Test
    void test_readDataAtV1_negativeFileOffset_throws_IOException() throws Exception {
        // Build a TrieSSTableReader via reflection with eagerData set and a known dataEnd.
        // Then invoke readDataAtV1 with a negative fileOffset.
        // The bug: (int) fileOffset is negative → ArrayIndexOutOfBoundsException from
        // System.arraycopy
        // instead of a proper IOException.

        byte[] eagerData = new byte[1024];
        long dataEnd = 1024L;

        // Construct metadata (minimal valid instance)
        MemorySegment key = MemorySegment.ofArray(new byte[]{ 0x01 });
        SSTableMetadata metadata = new SSTableMetadata(1L, tempDir.resolve("test.sst"),
                new Level(0), key, key, new SequenceNumber(1L), new SequenceNumber(1L), 2048L, 1L);

        // Use the private constructor via reflection
        Constructor<TrieSSTableReader> ctor = TrieSSTableReader.class.getDeclaredConstructor(
                SSTableMetadata.class, // metadata
                jlsm.sstable.internal.KeyIndex.class, // keyIndex
                jlsm.core.bloom.BloomFilter.class, // bloomFilter
                long.class, // dataEnd
                byte[].class, // eagerData
                SeekableByteChannel.class, // lazyChannel
                jlsm.core.cache.BlockCache.class, // blockCache
                jlsm.sstable.internal.CompressionMap.class, // compressionMap
                Map.class // codecMap
        );
        ctor.setAccessible(true);

        TrieSSTableReader reader = ctor.newInstance(metadata, null, // keyIndex — not needed for
                                                                    // direct readDataAtV1 call
                null, // bloomFilter
                dataEnd, eagerData, null, // lazyChannel — not needed when eagerData is set
                null, // blockCache
                null, // compressionMap — null means v1
                null // codecMap
        );

        // Invoke private readDataAtV1(long fileOffset, int maxBytes)
        Method readDataAtV1 = TrieSSTableReader.class.getDeclaredMethod("readDataAtV1", long.class,
                int.class);
        readDataAtV1.setAccessible(true);

        // Negative fileOffset: simulates corrupt key index entry
        long negativeOffset = -100L;

        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> readDataAtV1.invoke(reader, negativeOffset, 4096));

        Throwable cause = ex.getCause();
        assertInstanceOf(IOException.class, cause,
                "Expected IOException for negative fileOffset, but got: "
                        + cause.getClass().getName() + ": " + cause.getMessage());
    }

    // Finding: F-R1.shared_state.3.11
    // Bug: CompressedBlockIterator trusts block entry count without bounds validation
    // Correct behavior: Should throw UncheckedIOException wrapping IOException when entry count
    // exceeds what the decompressed block can physically hold
    // Fix location: CompressedBlockIterator.advance() (~lines 799-800 in TrieSSTableReader.java)
    // Regression watch: Ensure valid blocks with correct entry counts still iterate successfully
    @Test
    void test_CompressedBlockIterator_corruptEntryCount_throws_IOException() throws Exception {
        // Build a decompressed block where the 4-byte entry count claims 1000 entries
        // but the block only has 20 bytes total — far too small for even one entry (min 17 bytes).
        // NoneCodec returns the input unchanged, so eagerData IS the decompressed block.
        byte[] eagerData = new byte[20];
        // Write entry count = 1000 in big-endian at offset 0
        eagerData[0] = (byte) 0x00;
        eagerData[1] = (byte) 0x00;
        eagerData[2] = (byte) 0x03;
        eagerData[3] = (byte) 0xE8;

        // CompressionMap with one block: offset=0, compressedSize=20, uncompressedSize=20,
        // codecId=0x00 (none)
        var mapEntry = new jlsm.sstable.internal.CompressionMap.Entry(0L, 20, 20, (byte) 0x00);
        var compressionMap = new jlsm.sstable.internal.CompressionMap(List.of(mapEntry));

        // Codec map with NoneCodec
        var noneCodec = jlsm.core.compression.CompressionCodec.none();
        Map<Byte, jlsm.core.compression.CompressionCodec> codecMap = Map.of((byte) 0x00, noneCodec);

        // Construct metadata
        MemorySegment key = MemorySegment.ofArray(new byte[]{ 0x01 });
        SSTableMetadata metadata = new SSTableMetadata(1L, tempDir.resolve("corrupt-count.sst"),
                new Level(0), key, key, new SequenceNumber(1L), new SequenceNumber(1L), 2048L, 1L);

        // Use private constructor via reflection
        Constructor<TrieSSTableReader> ctor = TrieSSTableReader.class.getDeclaredConstructor(
                SSTableMetadata.class, jlsm.sstable.internal.KeyIndex.class,
                jlsm.core.bloom.BloomFilter.class, long.class, byte[].class,
                SeekableByteChannel.class, jlsm.core.cache.BlockCache.class,
                jlsm.sstable.internal.CompressionMap.class, Map.class);
        ctor.setAccessible(true);

        TrieSSTableReader reader = ctor.newInstance(metadata, null, // keyIndex — not needed for
                                                                    // scan()
                null, // bloomFilter
                20L, // dataEnd
                eagerData, // eager mode
                null, // lazyChannel — not needed in eager mode
                null, // blockCache
                compressionMap, // v2 reader (non-null compressionMap)
                codecMap);

        // scan() creates CompressedBlockIterator, whose constructor calls advance().
        // advance() reads count = 1000 from the corrupt block, then tries to decode
        // 1000 entries from a 20-byte block. EntryCodec.decode reads past the buffer
        // causing ArrayIndexOutOfBoundsException — an unchecked exception that propagates
        // uncontrolled. The bug is that count is not validated against the block size.
        // Correct behavior: detect that count is too large for the block and throw
        // UncheckedIOException (wrapping IOException).
        var ex = assertThrows(UncheckedIOException.class, () -> reader.scan(),
                "Expected UncheckedIOException for corrupt block entry count, but no exception or wrong type");

        assertInstanceOf(java.io.IOException.class, ex.getCause(),
                "UncheckedIOException should wrap an IOException");
    }

}
