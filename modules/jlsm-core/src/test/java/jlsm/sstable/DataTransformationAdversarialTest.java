package jlsm.sstable;

import jlsm.bloom.PassthroughBloomFilter;
import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.bloom.BloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.compression.ZstdDictionaryTrainer;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.sstable.internal.CompressionMap;
import jlsm.sstable.internal.SSTableFormat;
import jlsm.sstable.internal.V5Footer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

        // Step 2: Patch the first block's blockOffset to a value > Integer.MAX_VALUE.
        // Writer now produces v3 (72-byte footer) when a codec is configured, per F16 R16.
        byte[] fileBytes = Files.readAllBytes(path);
        int footerStart = fileBytes.length - SSTableFormat.FOOTER_SIZE_V3;
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

    // Finding: F-R1.data_transformation.1.1
    // Bug: ATOMIC_MOVE path in commitFromPartial silently overwrites an existing outputPath on
    // POSIX (rename(2) replaces), but the non-atomic fallback fails with
    // FileAlreadyExistsException — inconsistent commit behavior that can silently destroy a
    // pre-existing committed SSTable at outputPath.
    // Correct behavior: commitFromPartial must reject a pre-existing outputPath consistently
    // regardless of filesystem support for ATOMIC_MOVE, throwing FileAlreadyExistsException,
    // and MUST NOT destroy the pre-existing file.
    // Fix location: TrieSSTableWriter.commitFromPartial, lines 559-573
    // Regression watch: legitimate (non-colliding) commits must still succeed, and the fallback
    // path semantics must remain intact on AtomicMoveNotSupportedException without collisions.
    @Test
    void commitFromPartial_outputPathAlreadyExists_failsAndPreservesExistingFile_F_R1_dt_1_1(
            @TempDir Path dir) throws IOException {
        Path outputPath = dir.resolve("commit-collision.sst");

        // Step 1: Write a committed file at outputPath representing a pre-existing SSTable
        // (e.g., a stray file from a prior crashed run or a live committed table).
        byte[] preExistingContent = "pre-existing-committed-sstable-content".getBytes();
        Files.write(outputPath, preExistingContent);

        // Step 2: Configure a v5 writer that targets the same outputPath. The writer writes to
        // a partial file ("outputPath.partial.<uuid>") and renames to outputPath at finish().
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(42L).level(Level.L0)
                .path(outputPath).bloomFactory(PassthroughBloomFilter.factory())
                .codec(CompressionCodec.deflate()).formatVersion(5).build()) {
            w.append(put("alpha", "a", 1));
            w.append(put("beta", "b", 2));

            // Step 3: finish() invokes commitFromPartial. On POSIX (ATOMIC_MOVE supported), the
            // buggy path silently overwrites outputPath, destroying the pre-existing file.
            // Correct behavior: fail with FileAlreadyExistsException.
            assertThrows(FileAlreadyExistsException.class, w::finish,
                    "commitFromPartial must reject a pre-existing outputPath "
                            + "consistently across atomic and non-atomic fallbacks");
        } catch (IOException expectedOnClose) {
            // close() after a failed finish() may attempt cleanup of workingPath; any IOException
            // during close is not part of the assertion. The critical invariant is checked below.
        }

        // Step 4: The pre-existing file must still be on disk with its original content.
        // Under the bug, the ATOMIC_MOVE path on POSIX destroys this file silently.
        assertTrue(Files.exists(outputPath), "pre-existing outputPath must not be deleted");
        byte[] actualContent = Files.readAllBytes(outputPath);
        assertArrayEquals(preExistingContent, actualContent,
                "pre-existing outputPath content must not be overwritten silently");
    }

    private static long readLong(byte[] buf, int off) {
        return ((long) (buf[off] & 0xFF) << 56) | ((long) (buf[off + 1] & 0xFF) << 48)
                | ((long) (buf[off + 2] & 0xFF) << 40) | ((long) (buf[off + 3] & 0xFF) << 32)
                | ((long) (buf[off + 4] & 0xFF) << 24) | ((long) (buf[off + 5] & 0xFF) << 16)
                | ((long) (buf[off + 6] & 0xFF) << 8) | (long) (buf[off + 7] & 0xFF);
    }

    // Finding: F-R1.data_transformation.1.4
    // Bug: compressAndWriteBlock computes the per-block CRC32C only when the `v3` flag is true
    // (line 408 `if (v3) { ... checksum = ... }`). The v5 write path (line 413 `formatVersion ==
    // 5`) unconditionally stores the checksum into the CompressionMap.Entry, but the checksum
    // variable defaults to zero (line 407) and is only populated inside the v3 gate. If a v5
    // writer is ever constructed with v3=false, every block's recorded checksum is zero, which
    // silently disables the per-block integrity check required by the per-block-checksums ADR.
    // Correct behavior: for v5 writes the per-block CRC32C must be computed unconditionally;
    // integrity coverage must not be gated on an orthogonal legacy flag.
    // Fix location: TrieSSTableWriter.compressAndWriteBlock, line 407-412 (remove the `if (v3)`
    // gate for v5 writes, or fold the v5 gate into the CRC branch).
    // Regression watch: v3 writers must continue to compute checksums; v1/v2 writers must
    // continue to record checksum=0 (legacy 17-byte entry layout).
    @Test
    void compressAndWriteBlock_v5CrcMustNotBeGatedOnV3Flag_F_R1_dt_1_4(@TempDir Path dir)
            throws Exception {
        // Step 1: Build a v5 writer with deflate so each block goes through compressAndWriteBlock.
        Path path = dir.resolve("v5-crc-unconditional.sst");
        TrieSSTableWriter w = TrieSSTableWriter.builder().id(7L).level(Level.L0).path(path)
                .bloomFactory(PassthroughBloomFilter.factory()).codec(CompressionCodec.deflate())
                .formatVersion(5).build();
        try {
            // Step 2: Flip the legacy `v3` flag to false via reflection — this simulates the
            // fragile future-refactor scenario called out by the finding. The field is final,
            // so we need Field.setAccessible + Field.setBoolean. Java 25 permits this for
            // instance fields when the package is exported to the unnamed module (which it is
            // via --add-exports), though some JVMs restrict writes to final fields — in that
            // case the test records IMPOSSIBLE.
            Field v3Field = TrieSSTableWriter.class.getDeclaredField("v3");
            v3Field.setAccessible(true);
            try {
                v3Field.setBoolean(w, false);
            } catch (IllegalAccessException | InaccessibleObjectException structural) {
                // If the JVM refuses to mutate the final primitive, surface as an assertion
                // failure so the orchestrator classifies this finding as IMPOSSIBLE (cannot
                // exercise the buggy branch without a structural reachability change).
                fail("Unable to mutate final `v3` field via reflection: " + structural);
            }

            // Step 3: Write entries large enough to force multiple block flushes so the
            // compression map has several entries to inspect.
            byte[] filler = new byte[SSTableFormat.DEFAULT_BLOCK_SIZE / 2];
            java.util.Arrays.fill(filler, (byte) 'a');
            w.append(new Entry.Put(seg("key-01"), MemorySegment.ofArray(filler),
                    new SequenceNumber(1)));
            w.append(new Entry.Put(seg("key-02"), MemorySegment.ofArray(filler),
                    new SequenceNumber(2)));
            w.append(new Entry.Put(seg("key-03"), MemorySegment.ofArray(filler),
                    new SequenceNumber(3)));
            w.append(new Entry.Put(seg("key-04"), MemorySegment.ofArray(filler),
                    new SequenceNumber(4)));
            w.finish();
        } finally {
            w.close();
        }

        // Step 4: Decode the v5 footer to locate the compression map section.
        byte[] fileBytes = Files.readAllBytes(path);
        int footerStart = fileBytes.length - SSTableFormat.FOOTER_SIZE_V5;
        byte[] footerBytes = new byte[SSTableFormat.FOOTER_SIZE_V5];
        System.arraycopy(fileBytes, footerStart, footerBytes, 0, SSTableFormat.FOOTER_SIZE_V5);
        jlsm.sstable.internal.V5Footer footer = jlsm.sstable.internal.V5Footer.decode(footerBytes,
                0);

        // Step 5: Read and deserialize the compression map (v3 entry layout — 21 bytes per entry
        // with per-block CRC32C in the trailing 4 bytes).
        byte[] mapBytes = new byte[(int) footer.mapLength()];
        System.arraycopy(fileBytes, (int) footer.mapOffset(), mapBytes, 0, mapBytes.length);
        CompressionMap map = CompressionMap.deserialize(mapBytes, 3);

        // Step 6: Every block's recorded checksum must be non-zero. Non-empty blocks under
        // CRC32C never produce a checksum of exactly zero in practice, so a zero value is the
        // smoking-gun signal that the CRC branch was skipped. With the bug present (v3=false
        // via reflection), the writer records checksum=0 for every block. The fix must compute
        // the CRC32C unconditionally for v5 writes.
        assertTrue(map.blockCount() >= 2,
                "expected multiple blocks to exercise the CRC path; got " + map.blockCount());
        for (int i = 0; i < map.blockCount(); i++) {
            CompressionMap.Entry entry = map.entry(i);
            assertNotEquals(0, entry.checksum(),
                    "v5 block " + i + " must have a non-zero per-block CRC32C even when "
                            + "the legacy `v3` flag is false (per-block-checksums ADR); "
                            + "got checksum=0 which indicates the CRC branch was skipped");
        }
    }

    // Finding: F-R2.dt.1.1
    // Bug: Eager path System.arraycopy does not validate offset+size against eagerData bounds
    // — throws ArrayIndexOutOfBoundsException instead of IOException
    // Correct behavior: reader should catch or pre-check arraycopy bounds and throw IOException
    // Fix location: TrieSSTableReader.readAndDecompressBlock, lines 367-368
    // Regression watch: ensure normal eager reads with valid compression maps still work
    @Test
    void eagerReader_compressedSizeExceedsEagerData_throwsIOException_F_R2_dt_1_1(@TempDir Path dir)
            throws IOException {
        // Step 1: write a valid small v2 SSTable with compression
        List<Entry> entries = basicEntries();
        Path path = dir.resolve("arraycopy-bounds.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        // Step 2: Read the file and patch the compression map's first entry's compressedSize
        // to a value that makes intOffset + compressedSize > eagerData.length.
        // This simulates a corrupt on-disk compression map.
        byte[] fileBytes = Files.readAllBytes(path);

        // Read v2 footer to find mapOffset
        int footerStart = fileBytes.length - SSTableFormat.FOOTER_SIZE_V2;
        long mapOffset = readLong(fileBytes, footerStart);

        // Compression map entry layout: [8-byte blockOffset][4-byte compressedSize][4-byte
        // uncompressedSize][1-byte codecId]
        // Patch compressedSize (at mapOffset + 4 + 8 = mapOffset + 12) to fileBytes.length + 100
        int compressedSizeOffset = (int) mapOffset + 4 + 8; // skip blockCount(4) + blockOffset(8)
        int poisonedSize = fileBytes.length + 100; // guaranteed to exceed eagerData bounds
        writeInt(fileBytes, compressedSizeOffset, poisonedSize);

        // Also patch uncompressedSize to match (at compressedSizeOffset + 4) so
        // CompressionMap.Entry
        // constructor doesn't reject the compressedSize=0/uncompressedSize>0 combination
        writeInt(fileBytes, compressedSizeOffset + 4, poisonedSize);

        Files.write(path, fileBytes);

        // Step 3: Open eagerly — the reader loads all bytes into eagerData.
        // When get() triggers readAndDecompressBlock, the System.arraycopy at line 368
        // will attempt to copy poisonedSize bytes from eagerData starting at a valid offset.
        // Bug: this throws ArrayIndexOutOfBoundsException (unchecked) instead of IOException.
        // Per R41, corrupted compressed blocks must produce IOException.
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            IOException ex = assertThrows(IOException.class, () -> r.get(seg("apple")),
                    "corrupted compressedSize should produce IOException, not ArrayIndexOutOfBoundsException");
            assertNotNull(ex.getMessage(),
                    "IOException should have a descriptive message about the bounds violation");
        }
    }

    // Finding: F-R2.dt.1.2
    // Bug: Decompression output size not validated against declared uncompressedSize —
    // a custom codec may return fewer bytes than declared, silently propagating truncated data
    // Correct behavior: readAndDecompressBlock should validate decompressed.length ==
    // mapEntry.uncompressedSize() and throw IOException on mismatch
    // Fix location: TrieSSTableReader.readAndDecompressBlock, after line 386 (post-decompress)
    // Regression watch: ensure normal decompression with matching sizes still works
    @Test
    void eagerReader_decompressionSizeMismatch_throwsIOException_F_R2_dt_1_2(@TempDir Path dir)
            throws IOException {
        // A custom codec that wraps deflate but returns a truncated result from decompress().
        // The CompressionCodec interface is open (non-sealed), so consumers can provide
        // arbitrary implementations. This codec simulates one that does not honour the
        // uncompressedLength contract — it returns fewer bytes than declared.
        CompressionCodec truncatingCodec = new CompressionCodec() {
            private final CompressionCodec delegate = CompressionCodec.deflate();

            @Override
            public byte codecId() {
                return delegate.codecId(); // 0x02 — same as deflate
            }

            @Override
            public MemorySegment compress(MemorySegment src, MemorySegment dst) {
                return delegate.compress(src, dst);
            }

            @Override
            public MemorySegment decompress(MemorySegment src, MemorySegment dst,
                    int uncompressedLength) {
                MemorySegment full = delegate.decompress(src, dst, uncompressedLength);
                // Return a truncated slice — fewer bytes than declared uncompressedLength
                int truncatedLen = Math.max(1, (int) full.byteSize() / 2);
                return full.asSlice(0, truncatedLen);
            }
        };

        // Step 1: write a valid small v2 SSTable using the real deflate codec
        List<Entry> entries = basicEntries();
        Path path = dir.resolve("decompress-size-mismatch.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        // Step 2: Open eagerly with the truncating codec. The SSTable on disk was written with
        // real deflate, and the truncating codec has the same codecId (0x02). When the reader
        // calls codec.decompress(), it gets back fewer bytes than mapEntry.uncompressedSize().
        // Without the fix, the reader silently returns the short buffer, poisoning the cache
        // and corrupting downstream EntryCodec.decode calls.
        // Per R41, corrupted compressed blocks must produce IOException.
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, truncatingCodec)) {
            IOException ex = assertThrows(IOException.class, () -> r.get(seg("apple")),
                    "decompression size mismatch should produce IOException, not silent truncation");
            assertNotNull(ex.getMessage(),
                    "IOException should have a descriptive message about the size mismatch");
        }
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

    private static void writeInt(byte[] buf, int off, int v) {
        buf[off] = (byte) (v >>> 24);
        buf[off + 1] = (byte) (v >>> 16);
        buf[off + 2] = (byte) (v >>> 8);
        buf[off + 3] = (byte) v;
    }

    // Finding: F-R1.data_transformation.1.5
    // Bug: compressAndWriteBlock invokes VarInt.encode(dataToWrite.length, ...) without any
    // upstream guarantee that dataToWrite.length <= SSTableFormat.MAX_BLOCK_SIZE. A single
    // entry whose encoded size exceeds MAX_BLOCK_SIZE produces a DataBlock whose serialized
    // byte[] also exceeds MAX_BLOCK_SIZE; when the incompressible-fallback branch kicks in
    // (compressedLen >= blockBytes.length), dataToWrite == blockBytes and VarInt.encode
    // throws the cryptic IOException "VarInt value out of range" from deep inside the
    // write pipeline — no clear producer-side error.
    // Correct behavior: the writer must reject an oversized entry up front with a clear,
    // descriptive IOException (or IllegalArgumentException) identifying the entry size vs.
    // MAX_BLOCK_SIZE, before VarInt.encode is even reached.
    // Fix location: TrieSSTableWriter.append (upstream entry-size guard) or
    // flushCurrentBlock (block-size guard before compressAndWriteBlock).
    // Regression watch: normal-sized appends must continue to succeed; the guard must trigger
    // only when the produced block/entry would exceed MAX_BLOCK_SIZE.
    @Test
    void append_entryExceedsMaxBlockSize_throwsDescriptiveException_F_R1_data_transformation_1_5(
            @TempDir Path dir) throws IOException {
        // Use a v5 writer with deflate — the v5 path is the only one that calls VarInt.encode.
        Path path = dir.resolve("oversized-entry-varint.sst");
        // Use the maximum configurable blockSize (MAX_BLOCK_SIZE) so the block-flush threshold
        // does not trigger until the block is at least MAX_BLOCK_SIZE bytes. Build a single
        // value whose encoded size pushes DataBlock.serialize past MAX_BLOCK_SIZE.
        int maxBlockSize = SSTableFormat.MAX_BLOCK_SIZE;
        // Encoded entry layout in EntryCodec: 1 (type) + 4 (keyLen) + keyLen + 8 (seq) + 4
        // (valLen) + valLen = 17 + keyLen + valLen. DataBlock.serialize prepends a 4-byte
        // block count, so blockBytes.length = 4 + 17 + keyLen + valLen.
        // Choose valLen so blockBytes.length = MAX_BLOCK_SIZE + 64 (clearly over the limit).
        byte[] keyBytes = "k".getBytes();
        int valLen = maxBlockSize + 64 - (4 + 17 + keyBytes.length);
        // Fill with pseudo-random, non-compressible bytes so deflate cannot shrink the block
        // below MAX_BLOCK_SIZE — this forces the incompressible-fallback branch at
        // compressAndWriteBlock line 398-401 where dataToWrite = blockBytes (oversized).
        byte[] largeValue = new byte[valLen];
        java.util.Random rng = new java.util.Random(0xC0FFEEL);
        rng.nextBytes(largeValue);

        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(PassthroughBloomFilter.factory()).codec(CompressionCodec.deflate())
                .formatVersion(5).blockSize(maxBlockSize).build()) {
            Entry.Put oversized = new Entry.Put(seg("k"), MemorySegment.ofArray(largeValue),
                    new SequenceNumber(1));

            // append() may tolerate the oversize (current code only flushes when
            // currentBlock.byteSize() >= blockSize, i.e., at MAX_BLOCK_SIZE). finish() forces a
            // flush of the accumulated block via flushCurrentBlock → compressAndWriteBlock
            // → VarInt.encode. Expect a clear IOException identifying the root cause.
            IOException ex = assertThrows(IOException.class, () -> {
                w.append(oversized);
                w.finish();
            }, "writer must reject an entry that produces a block > MAX_BLOCK_SIZE with a "
                    + "descriptive IOException, not the deep VarInt 'value out of range' error");

            String msg = ex.getMessage();
            assertNotNull(msg, "IOException must carry a descriptive message");
            // The message must name the offending quantity — block size, entry size, or
            // MAX_BLOCK_SIZE — so an operator can diagnose the issue without reading source.
            String lower = msg.toLowerCase();
            assertTrue(
                    lower.contains("block") || lower.contains("entry")
                            || lower.contains("max_block_size") || lower.contains("too large")
                            || lower.contains("exceeds"),
                    "exception message must identify the oversized block/entry; got: " + msg);
            // Regression guard: the cryptic VarInt message must not leak through as the sole
            // diagnostic. If the fix only improves wording, it may still mention VarInt, but
            // it must also mention block/entry/size so the operator can act.
            if (lower.contains("varint")) {
                assertTrue(
                        lower.contains("block") || lower.contains("entry")
                                || lower.contains("max_block_size"),
                        "if the message mentions VarInt it must also identify the offending "
                                + "block/entry; got: " + msg);
            }
        } catch (RuntimeException wrapped) {
            // Some fix strategies may throw IllegalArgumentException from append() rather than
            // IOException — accept either path as long as the message is descriptive.
            fail("writer threw RuntimeException instead of IOException: " + wrapped);
        }
    }

    // Finding: F-R1.data_transformation.1.6
    // Bug: flushCurrentBlock abandons dictionary buffering mid-flush (sets
    // dictBufferAbandoned=true,
    // clears dictBufferedBlocks=null) but does NOT reset dictBufferedBytes to zero. The counter
    // becomes stale — it retains the accumulated pre-abandon byte total even though the buffer
    // it counted has been released.
    // Correct behavior: post-abandon, the writer must produce a complete, valid, readable SSTable;
    // every entry written before AND after the abandon point must round-trip losslessly. The
    // stale counter must not introduce any observable drift into the on-disk layout.
    // Fix location: TrieSSTableWriter.flushCurrentBlock, lines 356-361 — reset dictBufferedBytes
    // to zero on the abandon branch to keep the counter truthful.
    // Regression watch: normal (non-abandon) dictionary training and the below-threshold graceful
    // degradation must continue to work unchanged.
    @Test
    void test_flushCurrentBlock_bufferAbandonPostStateIsTruthful_F_R1_data_transformation_1_6(
            @TempDir Path dir) throws Exception {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        // Configure a v3 writer with dictionary training enabled and a TINY buffer limit so the
        // first few blocks exhaust the buffer and trigger the abandon branch at lines 353-363.
        // Block size is small (1 KiB) so many small blocks are produced. dictionaryMaxBufferBytes
        // is 1 KiB — the abandon branch fires after the second or third buffered block.
        Path path = dir.resolve("abandon-stale-counter.sst");
        CompressionCodec codec = CompressionCodec.zstd();

        // Produce entries with non-compressible values so blocks fully flush (no tight packing
        // that would keep us under the buffer limit for many blocks). We need MANY entries so
        // that the abandon happens early and then MANY more blocks are written through the
        // "abandoned" post-branch. Those post-abandon writes are the ones that could observe
        // drift from the stale dictBufferedBytes counter if it had any downstream reader.
        java.util.Random rng = new java.util.Random(0xABCDEFL);
        List<Entry.Put> allEntries = new java.util.ArrayList<>();
        final int totalEntries = 400;
        for (int i = 0; i < totalEntries; i++) {
            byte[] valueBytes = new byte[128];
            rng.nextBytes(valueBytes);
            Entry.Put p = new Entry.Put(
                    MemorySegment
                            .ofArray(("key-%05d".formatted(i)).getBytes(StandardCharsets.UTF_8)),
                    MemorySegment.ofArray(valueBytes), new SequenceNumber(i + 1));
            allEntries.add(p);
        }

        Field dictBufferedBytesField = TrieSSTableWriter.class
                .getDeclaredField("dictBufferedBytes");
        dictBufferedBytesField.setAccessible(true);
        Field dictBufferAbandonedField = TrieSSTableWriter.class
                .getDeclaredField("dictBufferAbandoned");
        dictBufferAbandonedField.setAccessible(true);

        long bytesAfterAbandon;
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec).blockSize(1024)
                .dictionaryTraining(true).dictionaryMaxBufferBytes(4096L).formatVersion(3)
                .build()) {
            for (Entry.Put p : allEntries) {
                w.append(p);
            }
            // At this point many blocks have been written; the abandon branch should have fired.
            assertTrue(dictBufferAbandonedField.getBoolean(w),
                    "test precondition: buffer-limit abandon branch must have fired");
            // Correct behavior (post-fix): after abandon, dictBufferedBytes is zero because the
            // buffered list has been released. Buggy code: counter retains the stale pre-abandon
            // total. This assertion exercises the finding's claim that the counter is not reset.
            bytesAfterAbandon = dictBufferedBytesField.getLong(w);
            w.finish();
        }

        // The file must exist and be a valid v3 SSTable.
        assertTrue(Files.exists(path), "writer must produce a committed SSTable file");

        // Round-trip: every entry must read back with its exact original value. Any drift caused
        // by the stale counter (e.g., a future refactor that consults dictBufferedBytes to decide
        // some layout parameter) would surface as a corrupted round-trip here.
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, codec)) {
            for (Entry.Put expected : allEntries) {
                java.util.Optional<Entry> maybe = r.get(expected.key());
                assertTrue(maybe.isPresent(),
                        "entry must be present after abandon-path write: " + new String(
                                expected.key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                                StandardCharsets.UTF_8));
                Entry got = maybe.get();
                assertInstanceOf(Entry.Put.class, got);
                Entry.Put gotPut = (Entry.Put) got;
                assertArrayEquals(expected.value().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                        gotPut.value().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                        "value must round-trip losslessly across the abandon-mid-flush boundary");
            }
        }

        // Finding-specific invariant: post-abandon the counter MUST be zero. The buffered list
        // was released at line 361 (dictBufferedBlocks = null) and no blocks are buffered any
        // longer; the counter therefore represents a non-existent buffer and must be reset to
        // reflect that truth. The buggy code leaves it at the pre-abandon total.
        assertEquals(0L, bytesAfterAbandon,
                "dictBufferedBytes must be reset to zero when the dictionary buffer is abandoned "
                        + "and released at lines 356-361; the stale counter misrepresents the "
                        + "writer's buffered-bytes state even though the backing list is null");
    }

    // Finding: F-R1.data_transformation.C2.01
    // Bug: readFooter's MAGIC_V5 branch returns without calling footer.validate(fileSize), so the
    // Integer.MAX_VALUE guards on mapOffset/mapLength/idxLength/fltLength (lines 1397-1423 in
    // Footer.validate) never run for v5 files. A crafted v5 file with mapLength > 2^31 then
    // triggers (int) footer.mapLength truncation at the open() call site
    // `readBytes(ch, footer.mapOffset, (int) footer.mapLength)` — producing
    // `ByteBuffer.allocate(negative)` and an `IllegalArgumentException` that escapes the
    // factory's IOException contract (or, for merely-truncated lengths, a short read that
    // silently corrupts downstream decoding).
    // Correct behavior: the v5 footer path MUST enforce the same Integer.MAX_VALUE guards as
    // pre-v5 paths — either by calling footer.validate(fileSize) or by performing the
    // equivalent inline checks. The open() factory must surface the violation as an
    // IOException (specifically a CorruptSectionException on the FOOTER section per R29),
    // not leak an IllegalArgumentException.
    // Fix location: TrieSSTableReader.readFooter MAGIC_V5 branch, ~lines 1534-1597 — add the
    // Integer.MAX_VALUE guards for mapOffset, mapLength, idxLength, fltLength before returning
    // the Footer record, so downstream `(int) ...Length` casts cannot truncate.
    // Regression watch: legitimate v5 files with section lengths < 2 GiB must continue to open;
    // the guard must fire only when a v5 file claims a section length > Integer.MAX_VALUE.
    @Test
    void open_v5FooterMapLengthExceedsIntRange_throwsIOException_F_R1_data_transformation_C2_01(
            @TempDir Path dir) throws IOException {
        // Build a synthetic v5 SSTable where mapLength > Integer.MAX_VALUE. The file is a sparse
        // 3 GiB file: everything before the footer is a sparse hole (zero disk usage) and only
        // the trailing 112-byte v5 footer carries meaningful bytes. The bug is triggered during
        // readFooter → the v5 inline checks (maxSectionEnd <= footerStart, blockCount >= 1,
        // mapLength >= 1, tight-packing, blockSize range) all pass, then the method returns
        // WITHOUT calling footer.validate(fileSize). Downstream, `(int) footer.mapLength`
        // truncates to a negative value and `readBytes` invokes `ByteBuffer.allocate(negative)`
        // → IllegalArgumentException, which the open() catch(RuntimeException) block rethrows
        // unchanged, leaking past the factory's declared IOException contract.

        // File layout (all offsets/lengths chosen so the v5 inline checks pass):
        // fileSize = 3 GiB (3_221_225_472)
        // footerStart = fileSize - 112 (3_221_225_360)
        // mapOffset = 8 (data region >= 1 byte — R37)
        // mapLength = 2^31 (truncates to Integer.MIN_VALUE)
        // idxOffset = mapOffset + mapLength (tight-packing)
        // idxLength = 8
        // fltOffset = idxOffset + idxLength (tight-packing)
        // fltLength = 8
        // dict = absent (offset=0,length=0,checksum=0 per R15)
        // blockSize = DEFAULT_BLOCK_SIZE
        // blockCount = 1
        // all section checksums = 0 (never reached — the bug fires on readBytes first)
        Path path = dir.resolve("v5-maplength-overflow.sst");

        final long fileSize = 3L * 1024L * 1024L * 1024L; // 3 GiB
        final long footerStart = fileSize - SSTableFormat.FOOTER_SIZE_V5;
        final long mapOffset = 8L;
        final long mapLength = (long) Integer.MAX_VALUE + 1L; // 2_147_483_648
        final long idxOffset = mapOffset + mapLength;
        final long idxLength = 8L;
        final long fltOffset = idxOffset + idxLength;
        final long fltLength = 8L;

        // Sanity: the v5 inline maxSectionEnd <= footerStart check must pass with these values.
        assertTrue(
                Math.max(Math.max(mapOffset + mapLength, idxOffset + idxLength),
                        fltOffset + fltLength) <= footerStart,
                "test precondition: maxSectionEnd must be <= footerStart so the inline check "
                        + "passes and the bug is reachable");

        V5Footer v5 = new V5Footer(mapOffset, mapLength, 0L, 0L, idxOffset, idxLength, fltOffset,
                fltLength, 0L, SSTableFormat.DEFAULT_BLOCK_SIZE, 1, 0, 0, 0, 0, 0,
                SSTableFormat.MAGIC_V5);
        byte[] footerBytes = new byte[SSTableFormat.FOOTER_SIZE_V5];
        V5Footer.encode(v5, footerBytes, 0);
        // Recompute the footer-self-checksum (R16) over bytes [0,100) ++ [104,112) and patch it in.
        int footerChecksum = V5Footer.computeFooterChecksum(footerBytes);
        V5Footer v5Checked = new V5Footer(mapOffset, mapLength, 0L, 0L, idxOffset, idxLength,
                fltOffset, fltLength, 0L, SSTableFormat.DEFAULT_BLOCK_SIZE, 1, 0, 0, 0, 0,
                footerChecksum, SSTableFormat.MAGIC_V5);
        V5Footer.encode(v5Checked, footerBytes, 0);

        // Create the sparse file: truncate to fileSize (creates a hole), then write the footer.
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            // ftruncate-equivalent: produces a sparse hole with near-zero disk usage.
            ch.position(fileSize - 1L);
            ch.write(ByteBuffer.wrap(new byte[]{ 0 }));
            // Overwrite the final FOOTER_SIZE_V5 bytes with the crafted footer.
            ch.position(footerStart);
            ch.write(ByteBuffer.wrap(footerBytes));
        }

        // Confirm the file is actually fileSize bytes long (sparse) — if the FS does not support
        // sparse files large enough, the test cannot exercise the bug and we must skip.
        long actualSize = Files.size(path);
        assumeTrue(actualSize == fileSize,
                "sparse-file support required to craft a > 2 GiB v5 SSTable; got size="
                        + actualSize);

        // Open the file. Correct behavior (post-fix): IOException with a descriptive message
        // identifying the oversize length. Buggy behavior: either IllegalArgumentException from
        // `ByteBuffer.allocate(negative)` leaks through the factory's RuntimeException catch, or
        // a silent short-read corrupts downstream decoding. Either way, catching IOException on
        // a test that previously leaked IllegalArgumentException confirms the fix.
        IOException ex = assertThrows(IOException.class,
                () -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()),
                "v5 reader must reject a footer whose mapLength exceeds Integer.MAX_VALUE with "
                        + "an IOException — not leak an IllegalArgumentException from "
                        + "ByteBuffer.allocate(negative), nor silently truncate the section read");
        assertNotNull(ex.getMessage(),
                "IOException must carry a descriptive message identifying the oversize length");
        String lower = ex.getMessage().toLowerCase();
        assertTrue(
                lower.contains("2 gib") || lower.contains("integer.max_value")
                        || lower.contains("exceeds") || lower.contains("maplength")
                        || lower.contains("too large"),
                "exception message must identify the oversize section length; got: "
                        + ex.getMessage());
    }

    // Finding: F-R1.data_transformation.C2.02
    // Bug: readBloomFilterWithCrc accepts fltLength=0: readBytes returns an empty byte[0],
    // verifySectionCrc32c(empty, fltChecksum=0) passes (CRC32C of empty input is 0x00000000),
    // and deserializer.deserialize(emptySegment) is invoked — BlockedBloomFilter.deserializer()
    // then throws IllegalArgumentException ("serialized bloom filter must be at least 16
    // bytes, got: 0") which escapes the factory's IOException/CorruptSectionException
    // contract. An attacker crafting a v5 SSTable with fltLength=0 + fltChecksum=0 (with
    // matching footerChecksum) thereby either (a) leaks an unchecked exception through
    // open()/openLazy() or (b) — for deserializers that tolerate empty input — silently
    // degrades the reader to a zero-signal bloom that defeats negative-lookup skipping for
    // that SSTable.
    // Correct behavior: open() must reject an empty bloom section on a v5 SSTable as
    // corruption. The defense is either to refuse fltLength==0 outright during footer
    // validation (v5 SSTables produced by the writer always emit a non-empty bloom) or to
    // fail inside readBloomFilterWithCrc when the section is zero-length before invoking
    // the deserializer. Either way the caller must see an IOException (ideally a
    // CorruptSectionException on the bloom section), never an IllegalArgumentException.
    // Fix location: TrieSSTableReader.readBloomFilterWithCrc (lines 1833-1844) — reject
    // fltLength==0 for v5 files before reading/verifying/deserializing; or equivalently
    // guard in readFooter's MAGIC_V5 branch after the tight-packing/max-section-end checks.
    // Regression watch: v1/v2/v3/v4 files may legitimately have zero-sized bloom sections in
    // corner cases (writer sentinel paths); the guard must only fire on v5 where verifyCrc
    // is true and the section is expected to carry a real bloom filter.
    @Test
    void open_v5FooterBloomFltLengthZeroWithZeroChecksum_throwsIOException_F_R1_dt_C2_02(
            @TempDir Path dir) throws IOException {
        // Step 1: Build a real, committed v5 SSTable with a small payload. The writer produces
        // all sections correctly — map, idx, bloom, with matching per-section CRCs and a valid
        // footer self-checksum.
        List<Entry> entries = basicEntries();
        Path path = dir.resolve("v5-empty-bloom.sst");
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate()).formatVersion(5).build()) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        // Step 2: Decode the v5 footer and patch fltLength=0 + fltChecksum=0, then recompute
        // the footer self-checksum. The on-disk bloom bytes between idxOffset+idxLength and
        // footerStart become a dead gap that readBytes never touches (since length=0 short-
        // circuits at line 1786-1787). All pre-bloom checks still pass because we only touch
        // the two bloom fields.
        byte[] fileBytes = Files.readAllBytes(path);
        int footerStart = fileBytes.length - SSTableFormat.FOOTER_SIZE_V5;
        byte[] footerBytes = new byte[SSTableFormat.FOOTER_SIZE_V5];
        System.arraycopy(fileBytes, footerStart, footerBytes, 0, SSTableFormat.FOOTER_SIZE_V5);
        V5Footer original = V5Footer.decode(footerBytes, 0);

        // Tight-packing note: validateTightPacking (V5Footer.java:172) skips sections with
        // length==0, so setting fltLength=0 does NOT violate tight-packing. The
        // maxSectionEnd check at TrieSSTableReader.java:1583-1591 uses fltOffset + fltLength,
        // which with fltLength=0 is just fltOffset; that remains <= footerStart. All
        // MAX_VALUE guards (F-R1.dt.C2.01 fix, lines 1598-1613) pass trivially with 0.
        V5Footer patched = new V5Footer(original.mapOffset(), original.mapLength(),
                original.dictOffset(), original.dictLength(), original.idxOffset(),
                original.idxLength(), original.fltOffset(), /* fltLength= */ 0L,
                original.entryCount(), original.blockSize(), original.blockCount(),
                original.mapChecksum(), original.dictChecksum(), original.idxChecksum(),
                /* fltChecksum= */ 0, /* footerChecksum placeholder */ 0, original.magic());

        V5Footer.encode(patched, footerBytes, 0);
        int recomputedFooterChecksum = V5Footer.computeFooterChecksum(footerBytes);
        V5Footer finalFooter = new V5Footer(patched.mapOffset(), patched.mapLength(),
                patched.dictOffset(), patched.dictLength(), patched.idxOffset(),
                patched.idxLength(), patched.fltOffset(), patched.fltLength(), patched.entryCount(),
                patched.blockSize(), patched.blockCount(), patched.mapChecksum(),
                patched.dictChecksum(), patched.idxChecksum(), patched.fltChecksum(),
                recomputedFooterChecksum, patched.magic());
        V5Footer.encode(finalFooter, footerBytes, 0);
        System.arraycopy(footerBytes, 0, fileBytes, footerStart, SSTableFormat.FOOTER_SIZE_V5);
        Files.write(path, fileBytes);

        // Step 3: Open the patched file. The CRC32C of an empty byte array is 0x00000000, so
        // the verifySectionCrc32c gate in readBloomFilterWithCrc passes (computed=expected=0).
        // The deserializer is then invoked on a zero-byte MemorySegment; the v5 path's declared
        // contract is R29 (CorruptSectionException on corruption). Correct behavior: open()
        // surfaces the zero-length bloom section as an IOException rather than leaking the
        // deserializer's IllegalArgumentException through the RuntimeException catch at
        // TrieSSTableReader.java:376.
        IOException ex = assertThrows(IOException.class,
                () -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()),
                "v5 reader must reject an empty bloom section (fltLength=0) as corruption "
                        + "rather than leak the deserializer's IllegalArgumentException "
                        + "through the factory's declared IOException contract");
        assertNotNull(ex.getMessage(),
                "IOException must carry a descriptive message identifying the empty bloom section");
    }

    // Finding: F-R1.data_transformation.C2.04
    // Bug: TrieSSTableReader.readBytes does not guard against a negative `length` argument.
    // When a caller narrows a long section-length (e.g. (int) footer.dictLength) that exceeds
    // Integer.MAX_VALUE, the narrowed int becomes negative. readBytes then invokes
    // `ByteBuffer.allocate(length)` at line 1963 which throws IllegalArgumentException —
    // leaking past the factory's declared IOException / CorruptSectionException contract and
    // preventing callers from dispatching on "corrupt SSTable input" vs. "programming error."
    // The attack surface is concrete: Footer.validate (lines 1376-1464) has no
    // Integer.MAX_VALUE guard on dictLength (only a negative check and a fileSize bound), so
    // a v4 SSTable with dictLength > Integer.MAX_VALUE and a sufficiently large fileSize
    // passes validate() and the `(int) footer.dictLength` cast at line 1141 produces a
    // negative value that reaches readBytes unfiltered.
    // Correct behavior: readBytes must reject a negative length with an IOException (specifically
    // a descriptive message identifying the invalid length), so all upstream narrowing bugs
    // surface as IOException rather than IllegalArgumentException. This is a defense-in-depth
    // guard — upstream sites should also be tightened, but readBytes must not leak the
    // unchecked exception from ByteBuffer.allocate even when misused.
    // Fix location: TrieSSTableReader.readBytes, lines 1959-1963 — add a `length < 0` guard that
    // throws IOException before reaching ByteBuffer.allocate(length).
    // Regression watch: length==0 early-return must continue to produce new byte[0]; positive
    // lengths must continue to allocate and read as before.
    @Test
    void readBytes_negativeLength_throwsIOException_F_R1_data_transformation_C2_04(
            @TempDir Path dir) throws Exception {
        // Create a tiny real file so we have a valid SeekableByteChannel to hand to readBytes.
        // The read itself never touches the channel when length<0 under the correct behavior;
        // under the bug, ByteBuffer.allocate(negative) fires before the channel is read.
        Path path = dir.resolve("negative-length-probe.bin");
        Files.write(path, new byte[]{ 1, 2, 3, 4, 5, 6, 7, 8 });

        Method readBytes = TrieSSTableReader.class.getDeclaredMethod("readBytes",
                SeekableByteChannel.class, long.class, int.class);
        readBytes.setAccessible(true);

        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ)) {
            // Simulate the `(int) footer.dictLength` cast on a long value > Integer.MAX_VALUE —
            // the cast produces Integer.MIN_VALUE (0x80000000). Under the bug, readBytes invokes
            // ByteBuffer.allocate(Integer.MIN_VALUE) which throws IllegalArgumentException. The
            // reflective call wraps the underlying throwable in InvocationTargetException.
            InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                    () -> readBytes.invoke(null, ch, 0L, Integer.MIN_VALUE),
                    "readBytes must throw (not return) when given a negative length");

            Throwable cause = wrapped.getCause();
            assertNotNull(cause, "InvocationTargetException must wrap the underlying failure");
            assertInstanceOf(IOException.class, cause,
                    "negative length must surface as IOException to honor the factory's declared "
                            + "contract — not as IllegalArgumentException leaked from "
                            + "ByteBuffer.allocate(negative); got: " + cause.getClass().getName()
                            + ": " + cause.getMessage());
            assertNotNull(cause.getMessage(),
                    "IOException must carry a descriptive message identifying the invalid length");
        }
    }

    // Finding: F-R1.data_transformation.C2.03
    // Bug: TrieSSTableReader.readBytes loops while buf.hasRemaining(), exiting only on read<0
    // (EOF) or when the buffer is full. A conforming SeekableByteChannel that returns 0 from
    // read(ByteBuffer) — permitted by the NIO contract and observed on stalled remote NIO
    // providers (S3, HTTP-range) — causes the loop to spin forever without progress.
    // This violates the project's "Every blocking operation must have an explicit upper
    // bound" and "Every iteration must terminate" coding guidelines.
    // Correct behavior: readBytes must detect zero-progress reads and fail with an IOException
    // rather than spin indefinitely. A single zero-return does not need to fail immediately
    // (spurious zero is permitted), but an unbounded run of zero-returns must terminate with
    // an IOException describing the stall.
    // Fix location: TrieSSTableReader.readBytes, lines 1945-1958 — add a zero-progress counter
    // (or similar bound) inside the loop so that an infinite stream of 0-returns terminates.
    // Regression watch: legitimate short reads (partial fills via multiple ch.read calls each
    // returning > 0) must continue to work; EOF detection (read < 0) must still throw the
    // "unexpected EOF" IOException without waiting for the stall threshold.
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void readBytes_zeroProgressChannel_failsRatherThanSpins_F_R1_data_transformation_C2_03()
            throws Exception {
        // A SeekableByteChannel that always returns 0 from read() — a conforming NIO return.
        // Local FileChannel never does this for blocking reads, but remote NIO providers may.
        SeekableByteChannel stallingChannel = new SeekableByteChannel() {
            private long position;
            private boolean open = true;

            @Override
            public int read(ByteBuffer dst) {
                // Pretend the channel is alive but cannot make progress — valid per NIO
                // contract. The buggy readBytes loop spins on this forever.
                return 0;
            }

            @Override
            public int write(ByteBuffer src) {
                throw new UnsupportedOperationException("read-only");
            }

            @Override
            public long position() {
                return position;
            }

            @Override
            public SeekableByteChannel position(long newPosition) {
                this.position = newPosition;
                return this;
            }

            @Override
            public long size() {
                // Claim a large size so callers don't pre-truncate the request.
                return Long.MAX_VALUE;
            }

            @Override
            public SeekableByteChannel truncate(long size) {
                throw new UnsupportedOperationException("read-only");
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() {
                open = false;
            }
        };

        // Invoke the private static readBytes(SeekableByteChannel, long, int) directly.
        // The @Timeout on this test bounds the spin so a buggy implementation fails fast
        // instead of hanging the suite.
        Method readBytes = TrieSSTableReader.class.getDeclaredMethod("readBytes",
                SeekableByteChannel.class, long.class, int.class);
        readBytes.setAccessible(true);

        // Request 32 bytes. The stalling channel returns 0 on every read. The correct
        // behavior is to detect zero-progress and throw IOException. The buggy behavior
        // is to spin indefinitely (the @Timeout will cut it off and fail the test).
        InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                () -> readBytes.invoke(null, stallingChannel, 0L, 32),
                "readBytes must terminate on a zero-progress channel — a hang here means the "
                        + "loop at lines 1952-1956 has no bound on consecutive 0-returns");

        Throwable cause = wrapped.getCause();
        assertNotNull(cause, "InvocationTargetException must wrap the underlying failure");
        assertInstanceOf(IOException.class, cause,
                "zero-progress stalls must surface as IOException — not a RuntimeException "
                        + "and not an indefinite hang; got: " + cause.getClass().getName());
        assertNotNull(cause.getMessage(),
                "IOException must carry a descriptive message identifying the zero-progress stall");
    }
}
