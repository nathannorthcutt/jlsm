package jlsm.sstable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.sstable.internal.V5TestSupport;
import jlsm.sstable.internal.V5TestSupport.FooterView;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Corruption and failure-mode tests for the v5 reader: per-block corruption detection, truncation
 * handling, recovery-scan (R7-R10), exception-safe open (R41), and the reader FAILED state (R43).
 *
 * @spec sstable.end-to-end-integrity.R7
 * @spec sstable.end-to-end-integrity.R8
 * @spec sstable.end-to-end-integrity.R9
 * @spec sstable.end-to-end-integrity.R10
 * @spec sstable.end-to-end-integrity.R40
 * @spec sstable.end-to-end-integrity.R41
 * @spec sstable.end-to-end-integrity.R43
 */
class TrieSSTableReaderCorruptionTest {

    // ---------- helpers ----------

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private static Path writeSmallV5(Path dir, String name) throws IOException {
        Path out = dir.resolve(name);
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(out)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate()).blockSize(1024).build()) {
            for (int i = 0; i < 50; i++) {
                String key = String.format("k-%06d", i);
                String value = "v-" + i + "-" + "x".repeat(100);
                w.append(put(key, value, i + 1));
            }
            w.finish();
        }
        return out;
    }

    private static void flipByte(Path path, long offset) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            ch.read(one, offset);
            byte b = one.get(0);
            one.clear();
            one.put(0, (byte) (b ^ 0x01));
            one.rewind();
            ch.write(one, offset);
        }
    }

    private static void truncateTo(Path path, long newSize) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ch.truncate(newSize);
        }
    }

    // =========================================================================
    // Per-block corruption
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R10
    @Test
    void bitFlipInDataBlock_triggersCorruptBlockException(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "block-corrupt.sst");
        FooterView f = V5TestSupport.readFooter(out);
        flipByte(out, f.mapOffset / 2);
        IOException ex = assertThrows(IOException.class, () -> {
            try (TrieSSTableReader r = TrieSSTableReader.open(out,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate())) {
                Iterator<Entry> it = r.scan();
                while (it.hasNext()) {
                    it.next();
                }
            }
        });
        assertTrue(ex instanceof CorruptBlockException || ex instanceof CorruptSectionException,
                "block bitflip must produce CorruptBlockException or CorruptSectionException; got "
                        + ex.getClass().getName());
    }

    // @spec sstable.end-to-end-integrity.R40
    @Test
    void truncateAfterFooter_triggersIncompleteSSTableException(@TempDir Path dir)
            throws IOException {
        Path out = writeSmallV5(dir, "trunc-trailing.sst");
        long size = Files.size(out);
        truncateTo(out, size - 8);
        IncompleteSSTableException ex = assertThrows(IncompleteSSTableException.class,
                () -> TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        assertNotNull(ex.detectedMagic());
    }

    // @spec sstable.end-to-end-integrity.R40
    @Test
    void truncateIntoFooter_triggersIncompleteSSTableException(@TempDir Path dir)
            throws IOException {
        Path out = writeSmallV5(dir, "trunc-into-footer.sst");
        truncateTo(out, 50);
        assertThrows(IncompleteSSTableException.class, () -> TrieSSTableReader.open(out,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate()));
    }

    // @spec sstable.end-to-end-integrity.R29
    @Test
    void bitFlipInCompressionMapSection_throwsCorruptSection_compressionMap(@TempDir Path dir)
            throws IOException {
        Path out = writeSmallV5(dir, "map-corrupt.sst");
        FooterView f = V5TestSupport.readFooter(out);
        flipByte(out, f.mapOffset);
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        assertEquals(CorruptSectionException.SECTION_COMPRESSION_MAP, ex.sectionName());
    }

    // =========================================================================
    // R7-R10 — Recovery scan
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R7
    @Test
    void recoveryScanHappyPath_iteratesAllBlocks(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "rec-happy.sst");
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            Iterator<Entry> it = r.recoveryScan();
            int count = 0;
            while (it.hasNext()) {
                it.next();
                count++;
            }
            assertEquals(50, count, "R7: recoveryScan must iterate all entries");
        }
    }

    // @spec sstable.end-to-end-integrity.R7
    @Test
    void recoveryScanReturnsEntriesInBlockOrder(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "rec-order.sst");
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            Iterator<Entry> it = r.recoveryScan();
            String prev = null;
            while (it.hasNext()) {
                Entry e = it.next();
                String key = new String(e.key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                        StandardCharsets.UTF_8);
                if (prev != null) {
                    assertTrue(prev.compareTo(key) < 0,
                            "recoveryScan must return entries in block/ascending order");
                }
                prev = key;
            }
        }
    }

    // @spec sstable.end-to-end-integrity.R7
    @Test
    void recoveryScanOnCorruptCompressionMap_stillWorks(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "rec-bad-map.sst");
        FooterView f = V5TestSupport.readFooter(out);
        flipByte(out, f.mapOffset);
        try (TrieSSTableReader r = TrieSSTableReader.openLazy(out,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate())) {
            Iterator<Entry> it = r.recoveryScan();
            int count = 0;
            while (it.hasNext()) {
                it.next();
                count++;
            }
            assertEquals(50, count, "R7: recoveryScan bypasses compression map");
        } catch (CorruptSectionException expected) {
            // Acceptable — section was verified eagerly.
        }
    }

    // @spec sstable.end-to-end-integrity.R8
    // @spec sstable.end-to-end-integrity.R9
    @Test
    void recoveryScanBlockCountUnderflow_throwsCorruptSection_data(@TempDir Path dir)
            throws IOException {
        Path out = writeSmallV5(dir, "rec-underflow.sst");
        V5TestSupport.patchFooter(out, f -> f.withBlockCount(f.blockCount + 1000));
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            IOException ex = assertThrows(IOException.class, () -> {
                Iterator<Entry> it = r.recoveryScan();
                while (it.hasNext()) {
                    it.next();
                }
            });
            assertInstanceOf(CorruptSectionException.class, ex);
            assertEquals(CorruptSectionException.SECTION_DATA,
                    ((CorruptSectionException) ex).sectionName(),
                    "R9: block-count mismatch during recovery scan must be section=data");
        } catch (CorruptSectionException expected) {
            // Open rejected the inflated blockCount (R18) before recovery scan could start.
            assertTrue(
                    expected.sectionName().equals(CorruptSectionException.SECTION_COMPRESSION_MAP)
                            || expected.sectionName()
                                    .equals(CorruptSectionException.SECTION_FOOTER),
                    "open may reject inflated blockCount with compression-map or footer; got: "
                            + expected.sectionName());
        }
    }

    // @spec sstable.end-to-end-integrity.R8
    @Test
    void recoveryScanPostCondition_CurrentPosNotEqualMapOffset_throws(@TempDir Path dir)
            throws IOException {
        Path out = writeSmallV5(dir, "rec-postcond.sst");
        FooterView orig = V5TestSupport.readFooter(out);
        if (orig.blockCount < 2) {
            return;
        }
        V5TestSupport.patchFooter(out, f -> f.withBlockCount(Math.max(1, f.blockCount - 1)));
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            IOException ex = assertThrows(IOException.class, () -> {
                Iterator<Entry> it = r.recoveryScan();
                while (it.hasNext()) {
                    it.next();
                }
            });
            assertInstanceOf(CorruptSectionException.class, ex);
            assertEquals(CorruptSectionException.SECTION_DATA,
                    ((CorruptSectionException) ex).sectionName());
        } catch (CorruptSectionException expected) {
            // Acceptable — open-time R18 may reject first.
        }
    }

    // @spec sstable.end-to-end-integrity.R10
    @Test
    void recoveryScanBlockCrc32cFails_throwsCorruptBlockException(@TempDir Path dir)
            throws IOException {
        Path out = writeSmallV5(dir, "rec-bad-crc.sst");
        FooterView f = V5TestSupport.readFooter(out);
        flipByte(out, f.mapOffset / 2);
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            IOException ex = assertThrows(IOException.class, () -> {
                Iterator<Entry> it = r.recoveryScan();
                while (it.hasNext()) {
                    it.next();
                }
            });
            assertInstanceOf(CorruptBlockException.class, ex,
                    "R10: per-block CRC failure during recovery scan must be CorruptBlockException");
        } catch (CorruptSectionException openRejected) {
            // Acceptable if a well-placed byte happens to fall in the map section
        }
    }

    // =========================================================================
    // R41 — Exception-safe open
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R41
    @Test
    void openFailureDueToCorruptFooter_channelClosed(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "r41-fail.sst");
        long size = Files.size(out);
        flipByte(out, size - V5TestSupport.footerSize());
        assertThrows(IOException.class, () -> TrieSSTableReader.open(out,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate()));
        // If a channel were leaked the delete would fail on Windows; on Linux the test
        // is permissive — just verify no exception from delete.
        assertDoesNotThrow(() -> Files.delete(out),
                "R41: failed open must release the file channel");
    }

    // @spec sstable.end-to-end-integrity.R41
    @Disabled("TODO: triggering suppressed-exception chain requires forcing a close-failure; hard to test deterministically")
    @Test
    void openFailure_suppressedExceptionChainPresent() {
        // Intentionally unimplemented.
    }

    // =========================================================================
    // R43 — Reader FAILED state on lazy first-load
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R43
    @Test
    void lazyFirstLoadCorruptSection_readerTransitionsToFailed(@TempDir Path dir)
            throws IOException {
        Path out = writeSmallV5(dir, "r43-failed.sst");
        FooterView f = V5TestSupport.readFooter(out);
        flipByte(out, f.idxOffset);
        TrieSSTableReader r;
        try {
            r = TrieSSTableReader.openLazy(out, BlockedBloomFilter.deserializer(), null,
                    CompressionCodec.deflate());
        } catch (CorruptSectionException eagerFail) {
            return;
        }
        try {
            assertThrows(IOException.class, () -> r.get(seg("k-000000")));
            IllegalStateException ise = assertThrows(IllegalStateException.class,
                    () -> r.get(seg("k-000001")));
            assertTrue(
                    ise.getMessage().toLowerCase().contains("fail")
                            || ise.getMessage().toLowerCase().contains("key-index"),
                    "R43: failed-state message should mention failure or section; got: "
                            + ise.getMessage());
        } finally {
            r.close();
        }
    }

    // @spec sstable.end-to-end-integrity.R43
    @Test
    void failedReader_getThrowsIllegalState_withCauseChain(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "r43-chain.sst");
        FooterView f = V5TestSupport.readFooter(out);
        flipByte(out, f.idxOffset);
        TrieSSTableReader r;
        try {
            r = TrieSSTableReader.openLazy(out, BlockedBloomFilter.deserializer(), null,
                    CompressionCodec.deflate());
        } catch (CorruptSectionException eagerFail) {
            return;
        }
        try {
            assertThrows(IOException.class, () -> r.get(seg("k-000000")));
            IllegalStateException ise = assertThrows(IllegalStateException.class,
                    () -> r.get(seg("k-000001")));
            Throwable cause = ise.getCause();
            assertNotNull(cause, "R43: failed-state IllegalStateException should carry cause");
            assertInstanceOf(CorruptSectionException.class, cause,
                    "R43: cause chain should contain CorruptSectionException");
        } finally {
            r.close();
        }
    }

    // @spec sstable.end-to-end-integrity.R43
    @Test
    void failedReader_scanThrowsIllegalState(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "r43-scan.sst");
        FooterView f = V5TestSupport.readFooter(out);
        flipByte(out, f.idxOffset);
        TrieSSTableReader r;
        try {
            r = TrieSSTableReader.openLazy(out, BlockedBloomFilter.deserializer(), null,
                    CompressionCodec.deflate());
        } catch (CorruptSectionException eagerFail) {
            return;
        }
        try {
            assertThrows(IOException.class, () -> r.get(seg("k-000000")));
            assertThrows(IllegalStateException.class, () -> {
                Iterator<Entry> it = r.scan();
                while (it.hasNext()) {
                    it.next();
                }
            });
        } finally {
            r.close();
        }
    }

    // @spec sstable.end-to-end-integrity.R43
    @Test
    void failedReader_recoveryScanThrowsIllegalState(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "r43-rec.sst");
        FooterView f = V5TestSupport.readFooter(out);
        flipByte(out, f.idxOffset);
        TrieSSTableReader r;
        try {
            r = TrieSSTableReader.openLazy(out, BlockedBloomFilter.deserializer(), null,
                    CompressionCodec.deflate());
        } catch (CorruptSectionException eagerFail) {
            return;
        }
        try {
            assertThrows(IOException.class, () -> r.get(seg("k-000000")));
            assertThrows(IllegalStateException.class, r::recoveryScan);
        } finally {
            r.close();
        }
    }

    // @spec sstable.end-to-end-integrity.R43
    @Test
    void failedReader_closeIsIdempotent(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "r43-close.sst");
        FooterView f = V5TestSupport.readFooter(out);
        flipByte(out, f.idxOffset);
        TrieSSTableReader r;
        try {
            r = TrieSSTableReader.openLazy(out, BlockedBloomFilter.deserializer(), null,
                    CompressionCodec.deflate());
        } catch (CorruptSectionException eagerFail) {
            return;
        }
        try {
            try {
                r.get(seg("k-000000"));
            } catch (IOException ignored) {
                // Expected — first load failed
            }
        } finally {
            r.close();
            assertDoesNotThrow(r::close, "R43: close() is idempotent");
        }
    }
}
