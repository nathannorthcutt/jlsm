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
import jlsm.sstable.internal.V5Footer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
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

    // Findings F-R1.dt.1.1 and F-R1.dt.1.2 (blockOffset long-to-int truncation in the v3-style
    // reader paths) are no longer reachable post v1-v4 collapse. The v5 reader verifies the
    // compression-map CRC32C at open time, so a tampered blockOffset surfaces as
    // CorruptSectionException(SECTION_COMPRESSION_MAP) before the (int) cast at the eager or
    // no-cache read paths is ever reached. The downstream truncation paths the original
    // findings targeted no longer exist on the v5 read path.

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
                .codec(CompressionCodec.deflate()).build()) {
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
    // Finding: F-R1.dt.1.4 (v5 CRC must not be gated on v3 flag) is no longer reachable
    // post v1-v4 collapse — the `v3` field was removed alongside the v3/v4 emit paths, so
    // the orthogonal-flag scenario the test exercised cannot exist.

    // Finding F-R2.dt.1.1 (eager path arraycopy bounds violation from a corrupt compression map
    // entry) is no longer reachable post v1-v4 collapse. The v5 reader verifies the
    // compression-map CRC32C at open time, so a tampered compressedSize surfaces as
    // CorruptSectionException(SECTION_COMPRESSION_MAP) before the eager arraycopy runs.

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
    // @spec sstable.end-to-end-integrity.R46
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
                .blockSize(maxBlockSize).build()) {
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

    // Finding: F-R1.data_transformation.1.6 was deleted as part of the SSTable v1–v4 collapse
    // (per pre-ga-format-deprecation-policy). The dictionary-buffer-abandon path it exercised
    // lived only in the v3/v4 writer's dictionary-training lifecycle, which was removed
    // alongside the v3/v4 emit paths.

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
    // @spec sstable.end-to-end-integrity.R48
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
    // @spec sstable.end-to-end-integrity.R49
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
                .codec(CompressionCodec.deflate()).build()) {
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
    // @spec sstable.end-to-end-integrity.R51
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
    // @spec sstable.end-to-end-integrity.R50
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
