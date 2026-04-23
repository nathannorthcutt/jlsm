package jlsm.sstable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Optional;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.sstable.internal.SSTableFormat;
import jlsm.sstable.internal.V5TestSupport;
import jlsm.sstable.internal.V5TestSupport.FooterView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the v5 reader path of {@link TrieSSTableReader}: happy-path round-trip, magic-first
 * open (R25, R34, R40), footer verification (R25, R26), section verification (R27-R30), footer
 * field validation (R18), tight-packing (R37), and version compatibility (R34, R35).
 *
 * @spec sstable.end-to-end-integrity.R18
 * @spec sstable.end-to-end-integrity.R25
 * @spec sstable.end-to-end-integrity.R26
 * @spec sstable.end-to-end-integrity.R27
 * @spec sstable.end-to-end-integrity.R28
 * @spec sstable.end-to-end-integrity.R29
 * @spec sstable.end-to-end-integrity.R30
 * @spec sstable.end-to-end-integrity.R34
 * @spec sstable.end-to-end-integrity.R35
 * @spec sstable.end-to-end-integrity.R37
 * @spec sstable.end-to-end-integrity.R40
 */
class TrieSSTableReaderV5Test {

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
                .codec(CompressionCodec.deflate()).build()) {
            for (int i = 0; i < 10; i++) {
                String key = String.format("k-%03d", i);
                String value = "v-" + i;
                w.append(put(key, value, i + 1));
            }
            w.finish();
        }
        return out;
    }

    private static Path writeV1SSTable(Path dir, String name) throws IOException {
        Path out = dir.resolve(name);
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out)) {
            w.append(put("a", "1", 1));
            w.append(put("b", "2", 2));
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

    // =========================================================================
    // Happy-path round-trip
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R36
    @Test
    void writeV5ThenReadV5_entriesMatch(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "roundtrip-get.sst");
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            for (int i = 0; i < 10; i++) {
                String key = String.format("k-%03d", i);
                Optional<Entry> got = r.get(seg(key));
                assertTrue(got.isPresent(), "key " + key + " must be present");
                assertInstanceOf(Entry.Put.class, got.get());
            }
        }
    }

    // @spec sstable.end-to-end-integrity.R36
    @Test
    void writeV5ThenReadV5_scanMatches(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "roundtrip-scan.sst");
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            Iterator<Entry> it = r.scan();
            int count = 0;
            String prevKey = null;
            while (it.hasNext()) {
                Entry e = it.next();
                String key = new String(e.key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                        StandardCharsets.UTF_8);
                if (prevKey != null) {
                    assertTrue(prevKey.compareTo(key) < 0,
                            "scan must return keys in ascending order");
                }
                prevKey = key;
                count++;
            }
            assertEquals(10, count, "scan must yield all 10 entries");
        }
    }

    // @spec sstable.end-to-end-integrity.R36
    @Test
    void openReaderReturnsCorrectMetadata(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "metadata.sst");
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            assertNotNull(r.metadata());
            assertEquals(10L, r.metadata().entryCount());
        }
    }

    // =========================================================================
    // R25, R34, R40 — Short file / unknown magic / under-size footer
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R25
    // @spec sstable.end-to-end-integrity.R40
    @Test
    void readerShortFileLessThan8Bytes_throwsIncomplete(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("short-5b.sst");
        Files.write(out, new byte[]{ 1, 2, 3, 4, 5 });
        IncompleteSSTableException ex = assertThrows(IncompleteSSTableException.class,
                () -> TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        assertEquals("no-magic", ex.detectedMagic(),
                "R25/R40: sub-8-byte file must report detectedMagic=no-magic");
    }

    // @spec sstable.end-to-end-integrity.R34
    // @spec sstable.end-to-end-integrity.R40
    @Test
    void readerTrailingBytesNotAKnownMagic_throwsIncomplete(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("bad-magic.sst");
        byte[] bytes = new byte[100];
        long weird = 0xDEADBEEFCAFEBABEL;
        ByteBuffer bb = ByteBuffer.wrap(bytes, bytes.length - 8, 8).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(weird);
        Files.write(out, bytes);
        assertThrows(IncompleteSSTableException.class, () -> TrieSSTableReader.open(out,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate()));
    }

    // @spec sstable.end-to-end-integrity.R25
    // @spec sstable.end-to-end-integrity.R40
    @Test
    void readerFileSmallerThanFooterSize_throwsIncomplete(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("under-footer.sst");
        byte[] bytes = new byte[50];
        ByteBuffer bb = ByteBuffer.wrap(bytes, bytes.length - 8, 8).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(SSTableFormat.MAGIC_V5);
        Files.write(out, bytes);
        IncompleteSSTableException ex = assertThrows(IncompleteSSTableException.class,
                () -> TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        assertEquals(SSTableFormat.FOOTER_SIZE_V5, (int) ex.expectedMinimumSize(),
                "R25: expectedMinimumSize must be the v5 footer size (112)");
    }

    // =========================================================================
    // R25, R26 — Footer verification
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R26
    @Test
    void readerFlipBitInFooterChecksumScope_throwsCorruptSection_footer(@TempDir Path dir)
            throws IOException {
        Path out = writeSmallV5(dir, "footer-bitflip.sst");
        long fileSize = Files.size(out);
        long footerStart = fileSize - V5TestSupport.footerSize();
        flipByte(out, footerStart);
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        assertEquals(CorruptSectionException.SECTION_FOOTER, ex.sectionName(),
                "R26: footer CRC mismatch must throw with section=footer");
    }

    // @spec sstable.end-to-end-integrity.R16
    // @spec sstable.end-to-end-integrity.R26
    @Test
    void readerFlipBitInMagic_throwsCorruptSection_footer(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "magic-bitflip.sst");
        long fileSize = Files.size(out);
        // Flip any bit in magic will either change it to a non-known value (treated as
        // incomplete) or to a known value with CRC mismatch (treated as corrupt footer).
        // We target the second-to-last byte to avoid turning magic into "no magic".
        flipByte(out, fileSize - 2);
        IOException ex = assertThrows(IOException.class, () -> TrieSSTableReader.open(out,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate()));
        assertTrue(
                ex instanceof CorruptSectionException
                        && ((CorruptSectionException) ex).sectionName()
                                .equals(CorruptSectionException.SECTION_FOOTER)
                        || ex instanceof IncompleteSSTableException,
                "R16/R26: magic bit flip must produce CorruptSection(footer) or IncompleteSSTable; got "
                        + ex.getClass().getName());
    }

    // =========================================================================
    // R27-R30 — Section verification
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R27
    @Test
    void readerEagerMode_verifiesAllSections(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "eager-smoke.sst");
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            assertNotNull(r);
        }
    }

    // @spec sstable.end-to-end-integrity.R27
    // @spec sstable.end-to-end-integrity.R29
    @Test
    void readerEagerMode_corruptBloomFilter_throwsCorruptSection_bloomFilter(@TempDir Path dir)
            throws IOException {
        Path out = writeSmallV5(dir, "eager-bloom-corrupt.sst");
        FooterView f = V5TestSupport.readFooter(out);
        flipByte(out, f.fltOffset);
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        assertEquals(CorruptSectionException.SECTION_BLOOM_FILTER, ex.sectionName(),
                "R27/R29: bloom-filter corruption in eager mode → section=bloom-filter");
    }

    // @spec sstable.end-to-end-integrity.R28
    // @spec sstable.end-to-end-integrity.R29
    @Test
    void readerLazyMode_firstLoadOnCorruptKeyIndex_throwsCorruptSection_keyIndex(@TempDir Path dir)
            throws IOException {
        Path out = writeSmallV5(dir, "lazy-idx-corrupt.sst");
        FooterView f = V5TestSupport.readFooter(out);
        flipByte(out, f.idxOffset);
        TrieSSTableReader r;
        try {
            r = TrieSSTableReader.openLazy(out, BlockedBloomFilter.deserializer(), null,
                    CompressionCodec.deflate());
        } catch (CorruptSectionException e) {
            assertEquals(CorruptSectionException.SECTION_KEY_INDEX, e.sectionName());
            return;
        }
        try {
            IOException ex = assertThrows(IOException.class, () -> r.get(seg("k-000")),
                    "R28: first get() triggers key-index load → CorruptSectionException");
            if (ex instanceof CorruptSectionException cse) {
                assertEquals(CorruptSectionException.SECTION_KEY_INDEX, cse.sectionName());
            } else {
                Throwable cause = ex.getCause();
                assertNotNull(cause, "expected cause chain on failed lazy load");
                assertInstanceOf(CorruptSectionException.class, cause);
            }
        } finally {
            r.close();
        }
    }

    // @spec sstable.end-to-end-integrity.R28
    // @spec sstable.end-to-end-integrity.R29
    @Test
    void readerLazyMode_firstLoadOnCorruptCompressionMap_throwsCorruptSection_compressionMap(
            @TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "lazy-map-corrupt.sst");
        FooterView f = V5TestSupport.readFooter(out);
        flipByte(out, f.mapOffset);
        TrieSSTableReader r;
        try {
            r = TrieSSTableReader.openLazy(out, BlockedBloomFilter.deserializer(), null,
                    CompressionCodec.deflate());
        } catch (CorruptSectionException e) {
            assertEquals(CorruptSectionException.SECTION_COMPRESSION_MAP, e.sectionName());
            return;
        }
        try {
            IOException ex = assertThrows(IOException.class, () -> r.get(seg("k-000")));
            if (ex instanceof CorruptSectionException cse) {
                assertEquals(CorruptSectionException.SECTION_COMPRESSION_MAP, cse.sectionName());
            } else {
                Throwable cause = ex.getCause();
                assertNotNull(cause);
                assertInstanceOf(CorruptSectionException.class, cause);
            }
        } finally {
            r.close();
        }
    }

    // @spec sstable.end-to-end-integrity.R30
    @Test
    void readerDictLengthZero_skipsDictChecksumVerify(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "no-dict.sst");
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            assertNotNull(r);
        }
    }

    // =========================================================================
    // R18 — blockCount + mapLength validation (hand-crafted)
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R18
    @Test
    void readerBlockCountZero_throwsCorruptSection_footer(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "bc-zero.sst");
        V5TestSupport.patchFooter(out, f -> f.withBlockCount(0));
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        assertEquals(CorruptSectionException.SECTION_FOOTER, ex.sectionName(),
                "R18: blockCount=0 must throw section=footer");
    }

    // @spec sstable.end-to-end-integrity.R18
    @Test
    void readerMapLengthZero_throwsCorruptSection_footer(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "ml-zero.sst");
        V5TestSupport.patchFooter(out, f -> f.withMapLength(0L));
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        assertEquals(CorruptSectionException.SECTION_FOOTER, ex.sectionName(),
                "R18: mapLength=0 must throw section=footer");
    }

    // @spec sstable.end-to-end-integrity.R18
    @Test
    void readerMapEntryCountMismatch_throwsCorruptSection_compressionMap(@TempDir Path dir)
            throws IOException {
        Path out = writeSmallV5(dir, "mc-mismatch.sst");
        V5TestSupport.patchFooter(out, f -> f.withBlockCount(Math.max(2, f.blockCount + 100)));
        CorruptSectionException ex = assertThrows(CorruptSectionException.class,
                () -> TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        assertTrue(
                ex.sectionName().equals(CorruptSectionException.SECTION_COMPRESSION_MAP)
                        || ex.sectionName().equals(CorruptSectionException.SECTION_FOOTER),
                "R18: blockCount/map-entries mismatch must throw footer or compression-map; got "
                        + ex.sectionName());
    }

    // =========================================================================
    // R37 — Tight-pack validation
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R37
    @Test
    void readerSectionWithGap_throwsCorruptSection_footer(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "gap.sst");
        V5TestSupport.patchFooter(out, f -> f.withIdxOffset(f.idxOffset + 100L));
        IOException ex = assertThrows(IOException.class, () -> TrieSSTableReader.open(out,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate()));
        assertInstanceOf(CorruptSectionException.class, ex);
        CorruptSectionException cse = (CorruptSectionException) ex;
        assertEquals(CorruptSectionException.SECTION_FOOTER, cse.sectionName(),
                "R37: section-gap must throw section=footer");
    }

    // =========================================================================
    // R34, R35 — Version compatibility
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R34
    @Test
    void readerSupportsV5Magic(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "v5-opens.sst");
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            assertNotNull(r);
        }
    }

    // @spec sstable.end-to-end-integrity.R35
    @Test
    void readerV1FileDoesNotApplyV5Validations(@TempDir Path dir) throws IOException {
        Path out = writeV1SSTable(dir, "pre-v5.sst");
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer())) {
            assertTrue(r.get(seg("a")).isPresent(), "v1 file should be readable by v5 reader");
        }
    }

    // =========================================================================
    // Integration
    // =========================================================================

    // @spec sstable.end-to-end-integrity.R25
    // @spec sstable.end-to-end-integrity.R27
    // @spec sstable.end-to-end-integrity.R36
    @Test
    void writeV5ManyBlocks_readerVerifiesAll(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("many-blocks.sst");
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(out)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate()).blockSize(1024).build()) {
            for (int i = 0; i < 200; i++) {
                String key = String.format("k-%06d", i);
                String value = "v-" + i + "-" + "x".repeat(100);
                w.append(put(key, value, i + 1));
            }
            w.finish();
        }
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            int count = 0;
            Iterator<Entry> it = r.scan();
            while (it.hasNext()) {
                it.next();
                count++;
            }
            assertEquals(200, count);
        }
    }

    // ===== Hardening (adversarial, Cycle 1) =====

    // Finding: H-RL-4
    // Bug: second close() on a normal (non-failed) reader throws ClosedChannelException,
    // confusing callers that use try-with-resources alongside explicit close().
    // Correct behavior: close() is idempotent for a normal reader. Second call is a no-op.
    // Fix location: TrieSSTableReader.close()
    // Regression watch: first close() still releases the underlying channel and buffers.
    @Test
    void readerCloseIsIdempotent_normalReader(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "h-rl-4.sst");
        TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(), null,
                CompressionCodec.deflate());
        r.close();
        // Second close must not throw.
        r.close();
    }

    // Finding: H-RL-5
    // Bug: get(key) after close() produces a ClosedChannelException deep in the read path
    // (unhelpful for callers) or returns stale data from an already-released buffer.
    // Correct behavior: get() after close() throws IllegalStateException with a clear message
    // identifying the reader as closed.
    // Fix location: TrieSSTableReader.get(MemorySegment)
    // Regression watch: get() before close() still returns entries correctly.
    @Test
    void readerGetAfterClose_throwsIllegalState(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "h-rl-5.sst");
        TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(), null,
                CompressionCodec.deflate());
        r.close();
        assertThrows(IllegalStateException.class, () -> r.get(seg("k-001")));
    }

    // Finding: H-RL-6
    // Bug: scan() after close() returns an iterator whose hasNext()/next() call yields a
    // low-level ClosedChannelException, or worse, silently returns no elements as if
    // the SSTable were empty.
    // Correct behavior: scan() after close() throws IllegalStateException up front (not
    // lazily when the iterator is consumed). Callers must not receive a usable iterator
    // on a closed reader.
    // Fix location: TrieSSTableReader.scan()
    // Regression watch: scan() before close() still returns a valid iterator.
    @Test
    void readerScanAfterClose_throwsIllegalState(@TempDir Path dir) throws IOException {
        Path out = writeSmallV5(dir, "h-rl-6.sst");
        TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(), null,
                CompressionCodec.deflate());
        r.close();
        assertThrows(IllegalStateException.class, () -> r.scan());
    }
}
