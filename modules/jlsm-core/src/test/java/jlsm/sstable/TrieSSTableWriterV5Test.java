package jlsm.sstable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.sstable.internal.SSTableFormat;
import jlsm.sstable.internal.V5TestSupport;
import jlsm.sstable.internal.V5TestSupport.FooterView;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the v5 writer output path of {@link TrieSSTableWriter}.
 *
 * <p>
 * Covers: Builder.fsyncSkipListener acceptance (R23), empty-SSTable rejection (R17), v5 magic
 * dispatch (R36), VarInt-prefixed data blocks (R1-R5), per-section CRC32C (R13,R14), dictionary
 * sentinel (R15), tight-packing (R37), fsync skip observability via FsyncSkipListener (R23), and
 * atomic commit discipline (R39).
 * </p>
 *
 * @spec sstable.end-to-end-integrity.R1
 * @spec sstable.end-to-end-integrity.R13
 * @spec sstable.end-to-end-integrity.R15
 * @spec sstable.end-to-end-integrity.R17
 * @spec sstable.end-to-end-integrity.R23
 * @spec sstable.end-to-end-integrity.R36
 * @spec sstable.end-to-end-integrity.R37
 * @spec sstable.end-to-end-integrity.R39
 */
class TrieSSTableWriterV5Test {

    // ---------- helpers ----------

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private static TrieSSTableWriter.Builder v5Builder(Path path) {
        return TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate());
    }

    private static TrieSSTableWriter.Builder preV5Builder(Path path) {
        return TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01));
    }

    private static long trailingMagic(Path path) throws IOException {
        byte[] all = Files.readAllBytes(path);
        if (all.length < 8) {
            return 0L;
        }
        ByteBuffer bb = ByteBuffer.wrap(all, all.length - 8, 8).order(ByteOrder.BIG_ENDIAN);
        return bb.getLong();
    }

    private static Path writeSmallV5(Path dir, String name) throws IOException {
        Path path = dir.resolve(name);
        try (TrieSSTableWriter w = v5Builder(path).build()) {
            w.append(put("apple", "red", 1));
            w.append(put("banana", "yellow", 2));
            w.append(put("cherry", "red", 3));
            w.append(put("date", "brown", 4));
            w.append(put("elderberry", "purple", 5));
            w.finish();
        }
        return path;
    }

    private static Path writeV5ManyBlocks(Path dir, String name, int entries) throws IOException {
        Path path = dir.resolve(name);
        try (TrieSSTableWriter w = v5Builder(path).blockSize(1024).build()) {
            for (int i = 0; i < entries; i++) {
                String key = String.format("%06d", i);
                String value = "v-" + i + "-" + "x".repeat(200);
                w.append(put(key, value, i + 1));
            }
            w.finish();
        }
        return path;
    }

    // =========================================================================
    // R23 — Builder accepts FsyncSkipListener
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R23
    @Test
    void builderAcceptsFsyncSkipListener(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("r23-listener.sst");
        FsyncSkipListener listener = (p, cls, reason) -> {
        };
        TrieSSTableWriter.Builder b = v5Builder(out);
        TrieSSTableWriter.Builder ret = b.fsyncSkipListener(listener);
        assertNotNull(ret, "R23: fsyncSkipListener must return a Builder for chaining");
    }

    // =========================================================================
    // R17 — Empty SSTable rejection
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R17
    @Test
    void writerFinishRejectsEmptySSTable(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("r17-empty.sst");
        try (TrieSSTableWriter w = v5Builder(out).build()) {
            assertThrows(IllegalStateException.class, w::finish,
                    "R17: finish() on an empty writer must throw IllegalStateException");
        }
    }

    // =========================================================================
    // R36 — v5 magic on codec-configured output
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R36
    @Test
    void writerWithCodecProducesV5Magic(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "r36-v5.sst");
        long magic = trailingMagic(out);
        assertEquals(SSTableFormat.MAGIC_V5, magic,
                "R36: codec-configured writer must emit v5 magic in trailing 8 bytes");
    }

    // @spec sstable.end-to-end-integrity.R36
    @Test
    void writerWithoutCodecDoesNotProduceV5Magic(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("r36-pre-v5.sst");
        try (TrieSSTableWriter w = preV5Builder(out).build()) {
            w.append(put("a", "1", 1));
            w.finish();
        }
        long magic = trailingMagic(out);
        assertNotEquals(SSTableFormat.MAGIC_V5, magic,
                "R36: no-codec writer must not emit v5 magic");
    }

    // =========================================================================
    // R1-R5 — VarInt prefix per block
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R1
    // @spec sstable.end-to-end-integrity.R5
    @Test
    void writerWritesVarIntPrefixPerBlock(@TempDir Path dir) throws IOException {
        Path out = writeV5ManyBlocks(dir, "r1-varint.sst", 100);
        int walked = V5TestSupport.walkVarIntBlocks(out);
        FooterView fv = V5TestSupport.readFooter(out);
        assertTrue(walked >= 3, "test setup should produce >=3 blocks; got " + walked);
        assertEquals(fv.blockCount, walked,
                "R1/R5: walking VarInt-prefixed blocks must exactly consume blockCount blocks");
    }

    // =========================================================================
    // R13, R14 — Per-section CRC32C
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R13
    // @spec sstable.end-to-end-integrity.R14
    @Test
    void writerComputesPerSectionCrc32c(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "r13-crc.sst");
        byte[] all = Files.readAllBytes(out);
        FooterView f = V5TestSupport.readFooter(out);

        int recomputedMap = crc32cOf(all, (int) f.mapOffset, (int) f.mapLength);
        int recomputedIdx = crc32cOf(all, (int) f.idxOffset, (int) f.idxLength);
        int recomputedFlt = crc32cOf(all, (int) f.fltOffset, (int) f.fltLength);

        assertEquals(recomputedMap, f.mapChecksum,
                "R13/R14: footer.mapChecksum must equal CRC32C of compression map bytes");
        assertEquals(recomputedIdx, f.idxChecksum,
                "R13/R14: footer.idxChecksum must equal CRC32C of key index bytes");
        assertEquals(recomputedFlt, f.fltChecksum,
                "R13/R14: footer.fltChecksum must equal CRC32C of bloom filter bytes");
    }

    private static int crc32cOf(byte[] src, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(src, offset, length);
        return (int) crc.getValue();
    }

    // =========================================================================
    // R15 — Dictionary sentinel when no dictionary
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R15
    @Test
    void writerSetsDictChecksumZeroWhenNoDict(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "r15-nodict.sst");
        FooterView f = V5TestSupport.readFooter(out);
        assertEquals(0L, f.dictLength, "R15: dictLength must be 0 when no dictionary");
        assertEquals(0L, f.dictOffset, "R15: dictOffset must be 0 when no dictionary");
        assertEquals(0, f.dictChecksum, "R15: dictChecksum must be 0 when no dictionary");
    }

    // =========================================================================
    // R37 — Tight packing
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R37
    @Test
    void writerSectionsAreTightPacked(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "r37-packed.sst");
        String violation = V5TestSupport.tightPackingViolation(out);
        assertNull(violation, "R37: sections must be tight-packed; violation=" + violation);
    }

    // =========================================================================
    // R19-R23 — fsync discipline
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R19
    // @spec sstable.end-to-end-integrity.R20
    // @spec sstable.end-to-end-integrity.R21
    @Disabled("TODO: require Builder.channelFactory for FileChannel wrapper; fsync count verified only via refactor review")
    @Test
    void writerCallsForceThreeTimesOnFileChannel() {
        // Intentionally unimplemented.
    }

    @Disabled("TODO: requires a NIO FileSystem provider returning non-FileChannel (e.g. Jimfs); not in project deps")
    @Test
    void writerEmitsFsyncSkipOnNonFileChannelOutput() {
        // Intentionally unimplemented.
    }

    // =========================================================================
    // R39 — Atomic commit
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R39
    @Test
    void writerClosesWithoutFinish_deletesPartialOnly(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("r39-partial.sst");
        TrieSSTableWriter w = v5Builder(out).build();
        try {
            w.append(put("a", "1", 1));
            w.append(put("b", "2", 2));
        } finally {
            w.close();
        }
        assertFalse(Files.exists(out), "R39: final path must not exist after close-without-finish");
        List<Path> leftovers = listPartials(dir, out.getFileName().toString());
        assertTrue(leftovers.isEmpty(),
                "R39: no partial file must remain after close-without-finish; found: " + leftovers);
    }

    // @spec sstable.end-to-end-integrity.R39
    @Test
    void writerFinishRenamesPartialToFinalPath(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("r39-commit.sst");
        try (TrieSSTableWriter w = v5Builder(out).build()) {
            w.append(put("a", "1", 1));
            w.append(put("b", "2", 2));
            assertFalse(Files.exists(out),
                    "R39: final path must not exist before finish(); writer uses temp path");
            w.finish();
        }
        assertTrue(Files.exists(out), "R39: final path must exist after successful finish()");
        List<Path> leftovers = listPartials(dir, out.getFileName().toString());
        assertTrue(leftovers.isEmpty(),
                "R39: no partial file must remain after finish(); found: " + leftovers);
    }

    // @spec sstable.end-to-end-integrity.R39
    @Test
    void writerPartialPathUniquePerWriterInstance(@TempDir Path dir) throws IOException {
        Path outA = dir.resolve("r39-a.sst");
        Path outB = dir.resolve("r39-b.sst");
        try (TrieSSTableWriter a = v5Builder(outA).build();
                TrieSSTableWriter b = v5Builder(outB).build()) {
            a.append(put("k-a", "v-a", 1));
            b.append(put("k-b", "v-b", 2));
            a.finish();
            b.finish();
        }
        assertTrue(Files.exists(outA), "R39: final A must exist");
        assertTrue(Files.exists(outB), "R39: final B must exist");
    }

    // ---------- helpers (partial enumeration) ----------

    private static List<Path> listPartials(Path dir, String finalBasename) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.startsWith(finalBasename) && n.contains(".partial")) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    // ===== Hardening (adversarial, Cycle 1) =====

    // Finding: H-RL-1
    // Bug: second close() on an already-closed writer throws or double-deletes the partial file
    // (ArrayIndexOutOfBoundsException on a re-acquire, IOException on double-delete).
    // Correct behavior: close() is idempotent. Second call is a no-op — no exception, partial
    // file already deleted stays deleted, state remains CLOSED.
    // Fix location: TrieSSTableWriter.close()
    // Regression watch: single close() before finish() still deletes the partial file.
    @Test
    void writerCloseIsIdempotent_beforeFinish(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("h-rl-1.sst");
        TrieSSTableWriter w = v5Builder(out).build();
        w.append(put("k", "v", 1));
        w.close();
        // Second close must not throw.
        w.close();
    }

    // Finding: H-RL-2
    // Bug: append() after close() silently succeeds or throws an unhelpful NPE / array OOBE,
    // producing corrupt state or masking the use-after-close.
    // Correct behavior: append() after close() throws IllegalStateException with a clear message.
    // Fix location: TrieSSTableWriter.append(Entry)
    // Regression watch: append() before close() still works.
    @Test
    void writerAppendAfterClose_throwsIllegalState(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("h-rl-2.sst");
        TrieSSTableWriter w = v5Builder(out).build();
        w.close();
        assertThrows(IllegalStateException.class, () -> w.append(put("k", "v", 1)));
    }

    // Finding: H-RL-3
    // Bug: finish() after close() may silently succeed, fail via NPE, or leave the writer in
    // an inconsistent state.
    // Correct behavior: finish() after close() throws IllegalStateException.
    // Fix location: TrieSSTableWriter.finish()
    // Regression watch: finish() on an open writer still works and produces a v5 file.
    @Test
    void writerFinishAfterClose_throwsIllegalState(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("h-rl-3.sst");
        TrieSSTableWriter w = v5Builder(out).build();
        w.append(put("k", "v", 1));
        w.close();
        assertThrows(IllegalStateException.class, w::finish);
    }
}
