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
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import java.util.Iterator;

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
                long.class, // dictOffset
                long.class, // dictLength
                long.class, // idxOffset
                long.class, // idxLength
                long.class, // fltOffset
                long.class, // fltLength
                long.class, // entryCount
                long.class // blockSize
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
                0L, // dictOffset (unused in v1)
                0L, // dictLength (unused in v1)
                100L, // idxOffset
                200L, // idxLength — idx spans [100, 300)
                200L, // fltOffset — flt starts at 200, inside idx
                100L, // fltLength — flt spans [200, 300)
                10L, // entryCount
                4096L // blockSize
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
                long.class, // dictOffset
                long.class, // dictLength
                long.class, // idxOffset
                long.class, // idxLength
                long.class, // fltOffset
                long.class, // fltLength
                long.class, // entryCount
                long.class // blockSize
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
                0L, // dictOffset (unused in v2)
                0L, // dictLength (unused in v2)
                oversizedMapLength, // idxOffset — after map section
                100L, // idxLength
                oversizedMapLength + 100L, // fltOffset — after idx section
                64L, // fltLength
                10L, // entryCount
                4096L // blockSize
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

    // Finding: F-R1.shared_state.1.2
    // Bug: TOCTOU between checkNotClosed() and readLazyChannel() in get() — get() can
    // return a valid result after close() has logically marked the reader as closed
    // Correct behavior: get() should throw IllegalStateException if the reader becomes
    // closed between the initial checkNotClosed() call and the result return
    // Fix location: TrieSSTableReader.get(MemorySegment) — add post-read closed check
    // Regression watch: Ensure single-threaded get() still returns correct results
    @Test
    void test_get_toctou_returnsDataAfterLogicalClose_shouldThrowISE() throws Exception {
        // Write a v2 SSTable with a known key, then open it eagerly.
        Path sstPath = tempDir.resolve("toctou.sst");
        MemorySegment key = MemorySegment.ofArray("testkey".getBytes());
        MemorySegment value = MemorySegment.ofArray("testvalue".getBytes());
        try (var writer = new TrieSSTableWriter(1L, new Level(0), sstPath,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.none())) {
            writer.append(new Entry.Put(key, value, new SequenceNumber(1L)));
            writer.finish();
        }

        // Open eagerly to get a valid reader with real keyIndex and eagerData.
        TrieSSTableReader baseReader = TrieSSTableReader.open(sstPath,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.none());

        // Verify get() works before the attack
        assertTrue(baseReader.get(key).isPresent(), "baseline get() should find the key");

        // Extract all internal fields from the base reader via reflection
        Constructor<TrieSSTableReader> ctor = TrieSSTableReader.class.getDeclaredConstructor(
                SSTableMetadata.class, jlsm.sstable.internal.KeyIndex.class,
                jlsm.core.bloom.BloomFilter.class, long.class, byte[].class,
                SeekableByteChannel.class, jlsm.core.cache.BlockCache.class,
                jlsm.sstable.internal.CompressionMap.class, Map.class);
        ctor.setAccessible(true);

        Field metadataField = TrieSSTableReader.class.getDeclaredField("metadata");
        metadataField.setAccessible(true);
        SSTableMetadata metadata = (SSTableMetadata) metadataField.get(baseReader);

        Field keyIndexField = TrieSSTableReader.class.getDeclaredField("keyIndex");
        keyIndexField.setAccessible(true);
        var keyIndex = (jlsm.sstable.internal.KeyIndex) keyIndexField.get(baseReader);

        Field dataEndField = TrieSSTableReader.class.getDeclaredField("dataEnd");
        dataEndField.setAccessible(true);
        long dataEnd = dataEndField.getLong(baseReader);

        Field eagerDataField = TrieSSTableReader.class.getDeclaredField("eagerData");
        eagerDataField.setAccessible(true);
        byte[] eagerData = (byte[]) eagerDataField.get(baseReader);

        Field compressionMapField = TrieSSTableReader.class.getDeclaredField("compressionMap");
        compressionMapField.setAccessible(true);
        var compressionMap = (jlsm.sstable.internal.CompressionMap) compressionMapField
                .get(baseReader);

        Field codecMapField = TrieSSTableReader.class.getDeclaredField("codecMap");
        codecMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var codecMap = (Map<Byte, jlsm.core.compression.CompressionCodec>) codecMapField
                .get(baseReader);

        baseReader.close();

        // Create a poisoned bloom filter that sets the closed flag on mightContain().
        // This deterministically injects close() between checkNotClosed() and the read:
        // 1. get() calls checkNotClosed() → passes (closed=false)
        // 2. get() calls bloomFilter.mightContain(key) → sets closed=true, returns true
        // 3. get() proceeds to read and decode the entry
        // 4. Without fix: get() returns the entry — TOCTOU
        // 5. With fix: post-read checkNotClosed() detects closed=true → throws ISE
        var poisonedReader = new AtomicReference<TrieSSTableReader>();
        jlsm.core.bloom.BloomFilter closingBloom = new jlsm.core.bloom.BloomFilter() {
            @Override
            public void add(MemorySegment k) {
            }

            @Override
            public boolean mightContain(MemorySegment k) {
                try {
                    Field f = TrieSSTableReader.class.getDeclaredField("closed");
                    f.setAccessible(true);
                    f.setBoolean(poisonedReader.get(), true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return true;
            }

            @Override
            public double falsePositiveRate() {
                return 0.0;
            }

            @Override
            public MemorySegment serialize() {
                return MemorySegment.ofArray(new byte[0]);
            }
        };

        TrieSSTableReader toctouReader = ctor.newInstance(metadata, keyIndex, closingBloom, dataEnd,
                eagerData, null, null, compressionMap, codecMap);
        poisonedReader.set(toctouReader);

        // Without the post-read checkNotClosed(), this returns Optional<Entry> — bug.
        // With the fix, this throws IllegalStateException.
        assertThrows(IllegalStateException.class, () -> toctouReader.get(key),
                "get() should throw IllegalStateException when closed flag is set during "
                        + "execution (between pre-read check and return), but it returned data "
                        + "— TOCTOU vulnerability present");
    }

    // Finding: F-R1.shared_state.1.5
    // Bug: readAndDecompressBlock writes to BlockCache after reader is logically closed —
    // blockCache.put() at line 416 executes between the poisoned bloom (which sets
    // closed=true) and the post-read checkNotClosed() in get()
    // Correct behavior: readAndDecompressBlock should skip the blockCache.put() when the
    // reader is logically closed, preventing mutations to shared external state after close
    // Fix location: TrieSSTableReader.readAndDecompressBlock — add closed guard before
    // blockCache.put()
    // Regression watch: Ensure blockCache.put() still occurs for normal (non-closed) reads
    @Test
    void test_readAndDecompressBlock_writesToCacheAfterLogicalClose_shouldSkipCachePut()
            throws Exception {
        // Write a v2 SSTable with a known key
        Path sstPath = tempDir.resolve("cache-after-close.sst");
        MemorySegment key = MemorySegment.ofArray("cachekey".getBytes());
        MemorySegment value = MemorySegment.ofArray("cacheval".getBytes());
        try (var writer = new TrieSSTableWriter(1L, new Level(0), sstPath,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.none())) {
            writer.append(new Entry.Put(key, value, new SequenceNumber(1L)));
            writer.finish();
        }

        // Open eagerly to extract internal fields
        TrieSSTableReader baseReader = TrieSSTableReader.open(sstPath,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.none());
        assertTrue(baseReader.get(key).isPresent(), "baseline get() should find the key");

        // Extract internal fields via reflection
        Constructor<TrieSSTableReader> ctor = TrieSSTableReader.class.getDeclaredConstructor(
                SSTableMetadata.class, jlsm.sstable.internal.KeyIndex.class,
                jlsm.core.bloom.BloomFilter.class, long.class, byte[].class,
                SeekableByteChannel.class, jlsm.core.cache.BlockCache.class,
                jlsm.sstable.internal.CompressionMap.class, Map.class);
        ctor.setAccessible(true);

        Field metadataField = TrieSSTableReader.class.getDeclaredField("metadata");
        metadataField.setAccessible(true);
        SSTableMetadata metadata = (SSTableMetadata) metadataField.get(baseReader);

        Field keyIndexField = TrieSSTableReader.class.getDeclaredField("keyIndex");
        keyIndexField.setAccessible(true);
        var keyIndex = (jlsm.sstable.internal.KeyIndex) keyIndexField.get(baseReader);

        Field dataEndField = TrieSSTableReader.class.getDeclaredField("dataEnd");
        dataEndField.setAccessible(true);
        long dataEnd = dataEndField.getLong(baseReader);

        Field eagerDataField = TrieSSTableReader.class.getDeclaredField("eagerData");
        eagerDataField.setAccessible(true);
        byte[] eagerData = (byte[]) eagerDataField.get(baseReader);

        Field compressionMapField = TrieSSTableReader.class.getDeclaredField("compressionMap");
        compressionMapField.setAccessible(true);
        var compressionMap = (jlsm.sstable.internal.CompressionMap) compressionMapField
                .get(baseReader);

        Field codecMapField = TrieSSTableReader.class.getDeclaredField("codecMap");
        codecMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var codecMap = (Map<Byte, jlsm.core.compression.CompressionCodec>) codecMapField
                .get(baseReader);

        baseReader.close();

        // Spy BlockCache that tracks put() calls after the reader is logically closed
        var putCalledAfterClose = new AtomicBoolean(false);
        var closedRef = new AtomicReference<TrieSSTableReader>();
        jlsm.core.cache.BlockCache spyCache = new jlsm.core.cache.BlockCache() {
            @Override
            public Optional<MemorySegment> get(long sstableId, long blockOffset) {
                return Optional.empty(); // always miss — force the read + put path
            }

            @Override
            public void put(long sstableId, long blockOffset, MemorySegment block) {
                // Check if the reader is closed at the time of this put()
                try {
                    Field f = TrieSSTableReader.class.getDeclaredField("closed");
                    f.setAccessible(true);
                    if (f.getBoolean(closedRef.get())) {
                        putCalledAfterClose.set(true);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void evict(long sstableId) {
            }

            @Override
            public long size() {
                return 0;
            }

            @Override
            public long capacity() {
                return 100;
            }

            @Override
            public void close() {
            }
        };

        // Poisoned bloom filter: sets closed=true during mightContain()
        var poisonedReader = new AtomicReference<TrieSSTableReader>();
        jlsm.core.bloom.BloomFilter closingBloom = new jlsm.core.bloom.BloomFilter() {
            @Override
            public void add(MemorySegment k) {
            }

            @Override
            public boolean mightContain(MemorySegment k) {
                try {
                    Field f = TrieSSTableReader.class.getDeclaredField("closed");
                    f.setAccessible(true);
                    f.setBoolean(poisonedReader.get(), true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return true;
            }

            @Override
            public double falsePositiveRate() {
                return 0.0;
            }

            @Override
            public MemorySegment serialize() {
                return MemorySegment.ofArray(new byte[0]);
            }
        };

        // Create reader with spy cache and poisoned bloom
        TrieSSTableReader reader = ctor.newInstance(metadata, keyIndex, closingBloom, dataEnd,
                eagerData, null, spyCache, compressionMap, codecMap);
        poisonedReader.set(reader);
        closedRef.set(reader);

        // get() will: checkNotClosed() → bloom.mightContain() [sets closed=true]
        // → readAndDecompressBlock() → blockCache.put() [BUG: writes to cache while closed]
        // → return → checkNotClosed() → throws ISE
        // The ISE is expected (from the F-R1.shared_state.1.2 fix), but the cache put
        // should NOT have happened.
        assertThrows(IllegalStateException.class, () -> reader.get(key));

        assertFalse(putCalledAfterClose.get(),
                "readAndDecompressBlock wrote to BlockCache after reader was logically closed "
                        + "— blockCache.put() should be skipped when closed=true");
    }

    // Finding: F-R1.shared_state.1.1
    // Bug: close() is not atomic — double-close race can close lazyChannel twice
    // Correct behavior: close() should use atomic CAS so only one thread ever
    // proceeds past the closed guard, ensuring lazyChannel.close() is called exactly once
    // Fix location: TrieSSTableReader.close() (~lines 331-338)
    // Regression watch: Ensure single-threaded close still works correctly
    @Test
    void test_close_atomicity_doubleCloseRace_channelClosedExactlyOnce() throws Exception {
        // Spy channel that counts close() invocations
        var closeCount = new AtomicInteger(0);
        SeekableByteChannel spyChannel = new SeekableByteChannel() {
            @Override
            public int read(ByteBuffer dst) {
                return -1;
            }

            @Override
            public int write(ByteBuffer src) {
                return 0;
            }

            @Override
            public long position() {
                return 0;
            }

            @Override
            public SeekableByteChannel position(long newPosition) {
                return this;
            }

            @Override
            public long size() {
                return 0;
            }

            @Override
            public SeekableByteChannel truncate(long size) {
                return this;
            }

            @Override
            public boolean isOpen() {
                return closeCount.get() == 0;
            }

            @Override
            public void close() {
                closeCount.incrementAndGet();
            }
        };

        MemorySegment key = MemorySegment.ofArray(new byte[]{ 0x01 });
        SSTableMetadata metadata = new SSTableMetadata(1L, tempDir.resolve("race.sst"),
                new Level(0), key, key, new SequenceNumber(1L), new SequenceNumber(1L), 0L, 0L);

        Constructor<TrieSSTableReader> ctor = TrieSSTableReader.class.getDeclaredConstructor(
                SSTableMetadata.class, jlsm.sstable.internal.KeyIndex.class,
                jlsm.core.bloom.BloomFilter.class, long.class, byte[].class,
                SeekableByteChannel.class, jlsm.core.cache.BlockCache.class,
                jlsm.sstable.internal.CompressionMap.class, Map.class);
        ctor.setAccessible(true);

        // Run many iterations to maximize chance of hitting the race window
        int iterations = 10_000;
        int raceDetected = 0;

        for (int i = 0; i < iterations; i++) {
            closeCount.set(0);

            TrieSSTableReader reader = ctor.newInstance(metadata, null, null, 0L, null, spyChannel,
                    null, null, null);

            // Reset closed field to false for re-use
            Field closedField = TrieSSTableReader.class.getDeclaredField("closed");
            closedField.setAccessible(true);
            closedField.setBoolean(reader, false);

            var barrier = new CyclicBarrier(2);
            var t1 = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    reader.close();
                } catch (Exception e) {
                    /* ignore */ }
            });
            var t2 = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    reader.close();
                } catch (Exception e) {
                    /* ignore */ }
            });

            t1.join(1000);
            t2.join(1000);

            if (closeCount.get() > 1) {
                raceDetected++;
            }
        }

        assertEquals(0, raceDetected, "close() race detected in " + raceDetected + " of "
                + iterations + " iterations — lazyChannel.close() called more than once");
    }

    // Finding: F-R1.shared_state.1.1
    // Bug: Builder.build() mutates `bloomFactory` field with a default lambda BEFORE
    // the remaining validation gates (R5a pool-closed check, R15 codec-pairing check)
    // run. When a later gate throws, the Builder is left in a state the caller did
    // not authorize: `bloomFactory` is no longer null even though the caller never
    // called `bloomFactory(...)` and the build() did not succeed.
    // Correct behavior: build() must NOT mutate the Builder's bloomFactory field
    // when validation fails. After a failed build(), the bloomFactory field should
    // retain whatever value the caller installed (or null, if none was installed).
    // Fix location: TrieSSTableWriter.java:898-935 — move the bloomFactory default
    // assignment past the validation gates, or use a local variable for the default
    // and only mutate `this.bloomFactory` on the successful path (or not at all).
    // Regression watch: Ensure a successful build() still installs the default
    // bloomFactory into the constructed writer when none was provided.
    @Test
    void test_TrieSSTableWriterBuilder_failedBuild_doesNotMutateBloomFactory() throws Exception {
        Path path = tempDir.resolve("failed-build.sst");

        // Build a builder that will fail the R15 codec-pairing validation gate
        // (non-default blockSize with null codec). Critically, do NOT set a
        // bloomFactory — we rely on its null default so we can detect whether
        // build() silently mutated it.
        TrieSSTableWriter.Builder builder = TrieSSTableWriter.builder().id(1L).level(Level.L0)
                .path(path).blockSize(16384);
        // codec intentionally NOT set → build() must throw IAE at R15 gate.

        // Read the bloomFactory field BEFORE build() — must be null.
        Field bloomFactoryField = TrieSSTableWriter.Builder.class.getDeclaredField("bloomFactory");
        bloomFactoryField.setAccessible(true);
        assertNull(bloomFactoryField.get(builder),
                "Precondition: bloomFactory must start null — test setup is wrong otherwise");

        // Attempt build() — expected to fail at the R15 codec-pairing gate.
        assertThrows(IllegalArgumentException.class, builder::build,
                "build() must reject non-default blockSize without codec");

        // Core assertion: bloomFactory must NOT have been mutated by the failed
        // build() call. The caller never invoked bloomFactory(...), so the field
        // must still be null. If the field is now non-null, build() has
        // silently committed a default lambda before the validation gate fired —
        // a commit-before-validate bug that leaks state across failed builds.
        assertNull(bloomFactoryField.get(builder),
                "bloomFactory was silently mutated by a failed build() — the builder "
                        + "now carries a default lambda the caller never authorized, "
                        + "violating the R11a atomicity pattern followed by every "
                        + "other setter on this builder");
    }

    // Finding: F-R1.shared_state.1.3
    // Bug: CompressedBlockIterator.hasNext() does not check closed flag — returns true
    // for a pre-fetched entry even after the reader is closed, unlike IndexRangeIterator
    // which checks !closed in hasNext()
    // Correct behavior: hasNext() should return false (or throw ISE) when the reader is
    // closed, consistent with IndexRangeIterator's behavior
    // Fix location: CompressedBlockIterator.hasNext() (~line 967 in TrieSSTableReader.java)
    // Regression watch: Ensure hasNext() still returns true for valid pre-fetched entries
    // when the reader is open
    @Test
    void test_CompressedBlockIterator_hasNext_returnsTrueAfterClose_shouldReturnFalse()
            throws Exception {
        // Write a v2 SSTable with multiple entries so the iterator has pre-fetched data
        Path sstPath = tempDir.resolve("closed-hasnext.sst");
        MemorySegment key1 = MemorySegment.ofArray("aaa".getBytes());
        MemorySegment key2 = MemorySegment.ofArray("bbb".getBytes());
        MemorySegment value = MemorySegment.ofArray("val".getBytes());
        try (var writer = new TrieSSTableWriter(1L, new Level(0), sstPath,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.none())) {
            writer.append(new Entry.Put(key1, value, new SequenceNumber(1L)));
            writer.append(new Entry.Put(key2, value, new SequenceNumber(2L)));
            writer.finish();
        }

        TrieSSTableReader reader = TrieSSTableReader.open(sstPath,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.none());

        // Get the full-scan iterator (CompressedBlockIterator for v2 reader)
        Iterator<Entry> iter = reader.scan();

        // Verify the iterator has a pre-fetched entry
        assertTrue(iter.hasNext(), "iterator should have entries before close");

        // Close the reader — sets closed = true
        reader.close();

        // F08.R19 (v3): hasNext() must signal the close rather than silently returning
        // false. Original finding assumed "return false" was the fix, but that lets a
        // for-each loop terminate without the caller noticing the close. The authoritative
        // signal is IllegalStateException, symmetric with next() / advance().
        assertThrows(IllegalStateException.class, iter::hasNext,
                "CompressedBlockIterator.hasNext() must throw IllegalStateException after "
                        + "reader close, not silently return true or false");
    }

}
