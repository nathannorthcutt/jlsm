package jlsm.sstable;

import jlsm.bloom.PassthroughBloomFilter;
import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.bloom.BloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.sstable.internal.CompressionMap;
import jlsm.sstable.internal.SSTableFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for data transformation fidelity in SSTable read/write paths.
 */
class DataTransformationAdversarialTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private List<Entry> basicEntries() {
        return List.of(put("apple", "red", 1), put("banana", "yellow", 2),
                put("cherry", "dark-red", 3), put("date", "brown", 4),
                put("elderberry", "purple", 5));
    }

    // Finding: F-R1.dt.1.1
    // Bug: blockOffset long-to-int truncation in eager read path — line 343 casts
    // mapEntry.blockOffset() (long) to int via (int), silently truncating offsets >= 2^31
    // Correct behavior: reader should detect the overflow and throw IOException, not silently
    // truncate the offset causing ArrayIndexOutOfBoundsException or wrong-data reads
    // Fix location: TrieSSTableReader.readAndDecompressBlock, line 343
    // Regression watch: ensure lazy read path (line 380) is not broken by the fix
    @Test
    void eagerReader_blockOffsetExceedingIntRange_throwsIOException_F_R1_dt_1_1(@TempDir Path dir)
            throws IOException {
        // Step 1: write a valid small v2 SSTable
        List<Entry> entries = basicEntries();
        Path path = dir.resolve("truncation.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        // Step 2: Read the file, locate the compression map, and patch blockOffset
        // to a value > Integer.MAX_VALUE (0x80000000L = 2,147,483,648)
        byte[] fileBytes = Files.readAllBytes(path);

        // Read v2 footer (last 64 bytes) to find mapOffset and mapLength
        int footerStart = fileBytes.length - SSTableFormat.FOOTER_SIZE_V2;
        long mapOffset = readLong(fileBytes, footerStart);
        long mapLength = readLong(fileBytes, footerStart + 8);

        // Compression map format: [4-byte blockCount][entries...]
        // Each entry: [8-byte blockOffset][4-byte compressedSize][4-byte uncompressedSize][1-byte
        // codecId]
        // Patch the first entry's blockOffset (at mapOffset + 4) to 0x80000000L
        int entryStart = (int) mapOffset + 4; // skip blockCount
        long poisonedOffset = 2_147_483_648L; // 0x80000000 — truncates to Integer.MIN_VALUE
        writeLong(fileBytes, entryStart, poisonedOffset);

        // Write the modified file back
        Files.write(path, fileBytes);

        // Step 3: Open eagerly and attempt to read — should throw IOException
        // The eager open() loads eagerData as a small array, but the compression map
        // entry claims blockOffset = 2^31. The buggy (int) cast on line 343 produces
        // Integer.MIN_VALUE (-2147483648), causing ArrayIndexOutOfBoundsException.
        // Correct behavior: detect the overflow and throw IOException.
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            // Trigger readAndDecompressBlock via get() — this uses the eager read path
            // with blockCache=null, exercising line 343's (int) cast
            IOException ex = assertThrows(IOException.class, () -> r.get(seg("apple")));
            // The exception message should indicate the offset overflow problem
            assertNotNull(ex.getMessage(),
                    "IOException should have a descriptive message about the overflow");
        }
    }

    // Finding: F-R1.dt.1.2
    // Bug: blockOffset long-to-int truncation in no-cache read path — line 385 casts
    // mapEntry.blockOffset() (long) to int via (int), silently truncating offsets >= 2^31
    // Correct behavior: reader should detect the overflow and throw IOException, not silently
    // truncate the offset causing ArrayIndexOutOfBoundsException or wrong-data reads
    // Fix location: TrieSSTableReader.readAndDecompressBlockNoCache, line 385
    // Regression watch: ensure eager read path (readAndDecompressBlock) still works after fix
    @Test
    void scanIterator_blockOffsetExceedingIntRange_throwsIOException_F_R1_dt_1_2(@TempDir Path dir)
            throws IOException {
        // Step 1: write a valid small v2 SSTable
        List<Entry> entries = basicEntries();
        Path path = dir.resolve("truncation-nocache.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        // Step 2: Patch the first block's blockOffset to a value > Integer.MAX_VALUE
        byte[] fileBytes = Files.readAllBytes(path);
        int footerStart = fileBytes.length - SSTableFormat.FOOTER_SIZE_V2;
        long mapOffset = readLong(fileBytes, footerStart);

        // Compression map: [4-byte blockCount][entries...]
        // Each entry: [8-byte blockOffset][4-byte compressedSize][4-byte uncompressedSize][1-byte
        // codecId]
        int entryStart = (int) mapOffset + 4; // skip blockCount
        long poisonedOffset = 2_147_483_648L; // 0x80000000 — truncates to Integer.MIN_VALUE
        writeLong(fileBytes, entryStart, poisonedOffset);

        Files.write(path, fileBytes);

        // Step 3: Open eagerly and call scan() — this uses readAndDecompressBlockNoCache
        // The scan iterator bypasses BlockCache and hits the no-cache path at line 385
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            // scan() creates a CompressedBlockIterator whose constructor calls advance(),
            // which immediately triggers readAndDecompressBlockNoCache on block 0
            // with the poisoned blockOffset. The buggy (int) cast produces Integer.MIN_VALUE.
            // The iterator wraps IOException in UncheckedIOException.
            UncheckedIOException ex = assertThrows(UncheckedIOException.class, () -> r.scan());
            assertNotNull(ex.getCause(), "UncheckedIOException should wrap an IOException");
            assertInstanceOf(IOException.class, ex.getCause());
            assertNotNull(ex.getCause().getMessage(),
                    "IOException should have a descriptive message about the overflow");
        }
    }

    // Finding: F-R1.dt.1.4
    // Bug: entryCount long-to-int truncation silently undersizes bloom filter — line 246 casts
    // (int) Math.max(1, entryCount) where entryCount is long; values > Integer.MAX_VALUE
    // wrap to negative, causing IllegalArgumentException in BlockedBloomFilter constructor
    // Correct behavior: finish() should cap the bloom filter capacity at Integer.MAX_VALUE
    // instead of truncating via (int) cast, producing a valid (if slightly undersized) filter
    // Fix location: TrieSSTableWriter.finish, line 246
    // Regression watch: ensure bloom filter still works correctly for normal entry counts
    @Test
    void finish_entryCountExceedingIntRange_doesNotCrash_F_R1_dt_1_4(@TempDir Path dir)
            throws Exception {
        // Use a capturing factory that records the capacity passed to create() and delegates
        // to PassthroughBloomFilter (zero allocation) to avoid OOM from huge bloom filter sizing
        int[] capturedCapacity = new int[1];
        BloomFilter.Factory capturingFactory = n -> {
            capturedCapacity[0] = n;
            return PassthroughBloomFilter.factory().create(n);
        };

        // Step 1: write a few valid entries so the writer has real data to flush
        Path path = dir.resolve("bloom-truncation.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path, capturingFactory,
                CompressionCodec.deflate())) {
            w.append(put("alpha", "a", 1));
            w.append(put("beta", "b", 2));
            w.append(put("gamma", "c", 3));

            // Step 2: use reflection to set entryCount to a value > Integer.MAX_VALUE
            // This simulates having written > 2^31 entries without actually doing so
            Field entryCountField = TrieSSTableWriter.class.getDeclaredField("entryCount");
            entryCountField.setAccessible(true);
            long poisonedCount = (long) Integer.MAX_VALUE + 1L; // 2_147_483_648L
            entryCountField.set(w, poisonedCount);

            // Step 3: finish() should NOT throw — and the bloom factory should receive
            // a positive capacity (Integer.MAX_VALUE), not a negative truncated value
            // The buggy (int) cast on line 246 produces -2_147_483_648 which causes
            // BlockedBloomFilter to throw IllegalArgumentException("expectedInsertions must be
            // positive")
            assertDoesNotThrow(() -> w.finish(),
                    "finish() should handle entryCount > Integer.MAX_VALUE without crashing");

            // Step 4: verify the factory received a positive, clamped value
            assertTrue(capturedCapacity[0] > 0,
                    "bloom filter capacity should be positive, got: " + capturedCapacity[0]);
            assertEquals(Integer.MAX_VALUE, capturedCapacity[0],
                    "bloom filter capacity should be clamped to Integer.MAX_VALUE");
        }
    }

    // Finding: F-R1.dt.1.5
    // Bug: blockCount is int; after Integer.MAX_VALUE blocks, blockCount++ overflows to
    // Integer.MIN_VALUE. Line 163 packs ((long) blockCount << 32) into the key index,
    // encoding a negative block index that corrupts reads.
    // Correct behavior: writer should throw IOException before blockCount overflows int range
    // Fix location: TrieSSTableWriter.flushCurrentBlock, before blockCount++ at line 221
    // Regression watch: ensure normal block counting still works after the guard is added
    @Test
    void append_blockCountAtIntMaxValue_throwsOnOverflow_F_R1_dt_1_5(@TempDir Path dir)
            throws Exception {
        // Step 1: create a v2 writer with compression
        Path path = dir.resolve("blockcount-overflow.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                PassthroughBloomFilter.factory(), CompressionCodec.deflate())) {

            // Step 2: write one entry so the writer has valid state
            w.append(put("aaa", "value1", 1));

            // Step 3: use reflection to set blockCount to Integer.MAX_VALUE
            // This simulates having written 2^31 - 1 blocks (~8 TiB of data)
            Field blockCountField = TrieSSTableWriter.class.getDeclaredField("blockCount");
            blockCountField.setAccessible(true);
            blockCountField.setInt(w, Integer.MAX_VALUE);

            // Step 4: append another entry that triggers a block flush
            // The current block already has "aaa" in it; we need enough data to exceed
            // DEFAULT_BLOCK_SIZE (4096). Write an entry with a large value to force flush.
            byte[] largeValue = new byte[SSTableFormat.DEFAULT_BLOCK_SIZE + 1];
            java.util.Arrays.fill(largeValue, (byte) 'x');
            Entry.Put bigEntry = new Entry.Put(seg("bbb"), MemorySegment.ofArray(largeValue),
                    new SequenceNumber(2));
            // The append should trigger flushCurrentBlock(), which increments blockCount.
            // With blockCount at Integer.MAX_VALUE, the increment overflows to MIN_VALUE.
            // The buggy code silently overflows; the fix should throw IOException.
            IOException ex = assertThrows(IOException.class, () -> w.append(bigEntry),
                    "append should throw IOException when blockCount would overflow int range");
            assertTrue(ex.getMessage().contains("block count"),
                    "exception message should mention block count overflow, got: "
                            + ex.getMessage());
        }
    }

    // Finding: F-R1.dt.1.7
    // Bug: CompressionMap.Entry does not validate codecId against known codec set;
    // arbitrary codecId values (e.g., 0xFF) pass through the constructor silently
    // Correct behavior: Entry should reject codecId values that are not in the known
    // built-in set (0x00=none, 0x02=deflate) to catch corrupt/malicious SSTable
    // data at deserialization time rather than deferring to decompression time
    // Fix location: CompressionMap.Entry compact constructor, around line 43-71
    // Regression watch: ensure valid codecIds (0x00, 0x02) still pass construction
    @Test
    void compressionMapEntry_unknownCodecId_throwsIllegalArgumentException_F_R1_dt_1_7() {
        // Attempt to construct an Entry with codecId 0xFF — not a known codec.
        // A corrupt or malicious SSTable file could contain any codecId value.
        // The Entry record should reject this eagerly rather than allowing it through
        // to be caught later at decompression time.
        assertThrows(IllegalArgumentException.class,
                () -> new CompressionMap.Entry(0L, 100, 200, (byte) 0xFF),
                "Entry should reject unknown codecId 0xFF");
    }

    private static long readLong(byte[] buf, int off) {
        return ((long) (buf[off] & 0xFF) << 56) | ((long) (buf[off + 1] & 0xFF) << 48)
                | ((long) (buf[off + 2] & 0xFF) << 40) | ((long) (buf[off + 3] & 0xFF) << 32)
                | ((long) (buf[off + 4] & 0xFF) << 24) | ((long) (buf[off + 5] & 0xFF) << 16)
                | ((long) (buf[off + 6] & 0xFF) << 8) | (long) (buf[off + 7] & 0xFF);
    }

    private static void writeLong(byte[] buf, int off, long v) {
        buf[off] = (byte) (v >>> 56);
        buf[off + 1] = (byte) (v >>> 48);
        buf[off + 2] = (byte) (v >>> 40);
        buf[off + 3] = (byte) (v >>> 32);
        buf[off + 4] = (byte) (v >>> 24);
        buf[off + 5] = (byte) (v >>> 16);
        buf[off + 6] = (byte) (v >>> 8);
        buf[off + 7] = (byte) v;
    }
}
