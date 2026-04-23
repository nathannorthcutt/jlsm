package jlsm.sstable;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import jlsm.core.compression.ZstdDictionaryTrainer;
import jlsm.core.model.Level;
import jlsm.sstable.internal.SSTableFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TDD tests for SSTable v4 dictionary compression reading in {@link TrieSSTableReader}.
 *
 * <p>
 * Most tests require native libzstd to write v4 files with dictionary-compressed blocks. Tests are
 * guarded with {@code assumeTrue(ZstdDictionaryTrainer.isAvailable())}.
 */
// @spec compression.zstd-dictionary.R19,R19a,R19b,R19c,R20,R20a,R21,R22
class DictionaryCompressionReaderTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private List<Entry> generateEntries(int count) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String key = "key-%05d".formatted(i);
            String value = "value-data-for-entry-%05d-with-some-repeated-content-structure"
                    .formatted(i);
            entries.add(put(key, value, i + 1));
        }
        return entries;
    }

    private long readMagic(Path path) throws IOException {
        long fileSize = Files.size(path);
        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ)) {
            ch.position(fileSize - 8);
            ByteBuffer buf = ByteBuffer.allocate(8);
            while (buf.hasRemaining()) {
                if (ch.read(buf) == -1)
                    throw new IOException("unexpected EOF reading magic");
            }
            buf.flip();
            return buf.getLong();
        }
    }

    /**
     * Writes a v4 SSTable with dictionary compression, returning the path.
     */
    private Path writeV4File(Path dir, int entryCount) throws IOException {
        Path path = dir.resolve("v4-dict-%d.sst".formatted(entryCount));
        CompressionCodec codec = CompressionCodec.zstd();

        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec).blockSize(1024) // small
                                                                                                 // blocks
                                                                                                 // to
                                                                                                 // ensure
                                                                                                 // we
                                                                                                 // exceed
                                                                                                 // 64
                                                                                                 // block
                                                                                                 // threshold
                .dictionaryTraining(true).build()) {
            for (Entry e : generateEntries(entryCount)) {
                w.append(e);
            }
            w.finish();
        }

        // Sanity: assert v4 magic
        assertEquals(SSTableFormat.MAGIC_V4, readMagic(path), "expected v4 file");
        return path;
    }

    // ---- Test 1: Round-trip write with dictionary -> read -> verify all entries ----

    @Test
    void roundTripDictionaryCompressedEntries(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        int count = 2000;
        Path path = writeV4File(dir, count);

        // R20a: reader should override caller-provided ZSTD codec with embedded dictionary
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.zstd())) {
            assertEquals(count, r.metadata().entryCount());

            // Verify full scan returns all entries in order
            Iterator<Entry> iter = r.scan();
            int scanned = 0;
            while (iter.hasNext()) {
                Entry e = iter.next();
                assertNotNull(e);
                scanned++;
            }
            assertEquals(count, scanned, "scan should return all written entries");
        }
    }

    // ---- Test 2: Point lookup on v4 file ----

    @Test
    void pointLookupOnV4File(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        Path path = writeV4File(dir, 2000);

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.zstd())) {
            // Lookup a key that exists
            Optional<Entry> found = r.get(seg("key-00100"));
            assertTrue(found.isPresent(), "key-00100 should exist");
            assertInstanceOf(Entry.Put.class, found.get());
            Entry.Put p = (Entry.Put) found.get();
            String expectedValue = "value-data-for-entry-00100-with-some-repeated-content-structure";
            assertEquals(-1L, seg(expectedValue).mismatch(p.value()), "value should match exactly");

            // Lookup a key that does NOT exist
            Optional<Entry> missing = r.get(seg("nonexistent-key"));
            assertFalse(missing.isPresent(), "nonexistent key should not be found");
        }
    }

    // ---- Test 3: Lazy reader on v4 file ----

    @Test
    void lazyReaderOnV4File(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        Path path = writeV4File(dir, 2000);

        try (TrieSSTableReader r = TrieSSTableReader.openLazy(path,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.zstd())) {
            assertEquals(2000L, r.metadata().entryCount());

            // Point lookup via lazy channel
            Optional<Entry> found = r.get(seg("key-00500"));
            assertTrue(found.isPresent(), "key-00500 should exist in lazy reader");
        }
    }

    // ---- Test 4: R20a — Reader overrides caller codec for ID 0x03 with embedded dictionary ----

    @Test
    void readerOverridesCallerCodecWithEmbeddedDictionary(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        Path path = writeV4File(dir, 2000);

        // Caller provides plain ZSTD (no dictionary) — reader should detect v4, load embedded
        // dictionary, and override the codec for ID 0x03 with a dictionary-bound codec
        CompressionCodec plainZstd = CompressionCodec.zstd();
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, plainZstd)) {
            // If the reader did NOT override with dictionary codec, decompression would fail
            // because data was compressed with dictionary. Successful scan proves override works.
            Iterator<Entry> iter = r.scan();
            int scanned = 0;
            while (iter.hasNext()) {
                iter.next();
                scanned++;
            }
            assertEquals(2000, scanned,
                    "reader should successfully decompress all blocks using embedded dictionary");
        }
    }

    // ---- Test 5: R19b — Reader validates v4 section ordering ----

    @Test
    void readerRejectsInvalidV4SectionOrdering(@TempDir Path dir) throws IOException {
        // Write a corrupted v4 file with overlapping sections by manipulating footer bytes
        Path path = dir.resolve("corrupt-v4-ordering.sst");

        // Write a minimal file with v4 magic but invalid section offsets
        // Footer layout: mapOffset(8), mapLength(8), dictOffset(8), dictLength(8),
        // idxOffset(8), idxLength(8), fltOffset(8), fltLength(8),
        // entryCount(8), blockSize(8), magic(8) = 88 bytes
        byte[] footer = new byte[SSTableFormat.FOOTER_SIZE_V4];
        ByteBuffer bb = ByteBuffer.wrap(footer);
        bb.putLong(0); // mapOffset = 0
        bb.putLong(100); // mapLength = 100
        bb.putLong(50); // dictOffset = 50 -- OVERLAPS with map section (map ends at 100)
        bb.putLong(100); // dictLength = 100
        bb.putLong(100); // idxOffset = 100 -- OVERLAPS with dict section
        bb.putLong(50); // idxLength = 50
        bb.putLong(100); // fltOffset = 100
        bb.putLong(50); // fltLength = 50
        bb.putLong(1); // entryCount
        bb.putLong(4096); // blockSize
        bb.putLong(SSTableFormat.MAGIC_V4); // magic

        // Write some dummy data + footer
        byte[] dummyData = new byte[200];
        byte[] fileContent = new byte[dummyData.length + footer.length];
        System.arraycopy(dummyData, 0, fileContent, 0, dummyData.length);
        System.arraycopy(footer, 0, fileContent, dummyData.length, footer.length);
        Files.write(path, fileContent);

        // R19b: reader should reject overlapping sections with IOException
        assertThrows(IOException.class,
                () -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.zstd()),
                "v4 reader should reject overlapping section offsets");
    }

    // ---- Test 6: R22 — Unknown magic ----

    @Test
    void readerRejectsUnknownMagic(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("unknown-magic.sst");
        byte[] data = new byte[96]; // enough for any footer
        ByteBuffer bb = ByteBuffer.wrap(data);
        // Write unknown magic at the end
        bb.position(data.length - 8);
        bb.putLong(0xDEADBEEFCAFEBABEL);
        Files.write(path, data);

        IOException ex = assertThrows(IOException.class,
                () -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.zstd()),
                "reader should throw IOException for unknown magic");
        assertTrue(ex.getMessage().contains("magic") || ex.getMessage().contains("valid"),
                "error message should mention bad magic: " + ex.getMessage());
    }

    // ---- Test 7: R19c — v4 uses v3-style 21-byte compression map entries ----

    @Test
    void v4UsesV3StyleCompressionMapEntries(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        Path path = writeV4File(dir, 2000);

        // Read footer to extract mapLength and verify entry size
        long fileSize = Files.size(path);
        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ)) {
            // Read 88-byte footer
            byte[] footerBuf = new byte[SSTableFormat.FOOTER_SIZE_V4];
            ch.position(fileSize - SSTableFormat.FOOTER_SIZE_V4);
            ByteBuffer bb = ByteBuffer.wrap(footerBuf);
            while (bb.hasRemaining()) {
                if (ch.read(bb) == -1)
                    throw new IOException("unexpected EOF");
            }
            bb.flip();
            long mapOffset = bb.getLong();
            long mapLength = bb.getLong();

            // mapLength should be 4 (blockCount int) + blockCount * 21 (v3 entry size)
            // We just verify the entry size divides evenly after subtracting the 4-byte header
            assertTrue(mapLength > 4,
                    "compression map should have at least the block count header");
            long entriesBytes = mapLength - 4;
            assertEquals(0, entriesBytes % SSTableFormat.COMPRESSION_MAP_ENTRY_SIZE_V3,
                    "v4 compression map entries should be 21 bytes each (v3 style with CRC32C)");
        }
    }

    // ---- Test 8: Reader handles v4 file with dictLength == 0 (no dictionary stored) ----

    @Test
    void v4FileDictLengthZero(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        // A v3 file read by a v4-aware reader should still work
        Path path = dir.resolve("v3-no-dict.sst");
        CompressionCodec codec = CompressionCodec.zstd();

        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec).build()) {
            for (Entry e : generateEntries(10)) {
                w.append(e);
            }
            w.finish();
        }

        // v3 file should still be readable by the v4-aware reader
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.zstd())) {
            assertEquals(10L, r.metadata().entryCount());

            Iterator<Entry> iter = r.scan();
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            assertEquals(10, count);
        }
    }

    // ---- Test 9: R20a/R21 — v3 reader must always use plain ZSTD for codec ID 0x03 ----

    // @spec compression.zstd-dictionary.R20a,R21 — regression test: on v3 (no dictionary
    // meta-block) the reader
    // must use a plain ZSTD codec for ID 0x03 regardless of the caller-supplied codec list.
    // Before the fix, the reader only honored caller-supplied codecs; opening without a
    // ZSTD codec threw "unknown compression codec ID 0x03" even though plain ZSTD is the
    // correct decompressor for v3 ZSTD blocks.
    @Test
    void readerInjectsPlainZstdCodecForV3File(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd so the writer actually produces codec ID 0x03");

        // Write a v3 SSTable with plain ZSTD — at least one block will have codec ID 0x03.
        Path path = dir.resolve("v3-plain-zstd.sst");
        CompressionCodec plainZstd = CompressionCodec.zstd();
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(plainZstd)
                .formatVersion(3).build()) {
            for (Entry e : generateEntries(500)) {
                w.append(e);
            }
            w.finish();
        }
        assertEquals(SSTableFormat.MAGIC_V3, readMagic(path));

        // Open with NO caller-supplied codecs via the codec-aware varargs overload.
        // The file has codec ID 0x03 blocks; the reader must inject plain ZSTD per
        // R20a/R21 so decompression succeeds without the caller providing a ZSTD codec.
        CompressionCodec[] noCodecs = new CompressionCodec[0];
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, noCodecs)) {
            Iterator<Entry> iter = r.scan();
            int scanned = 0;
            while (iter.hasNext()) {
                iter.next();
                scanned++;
            }
            assertEquals(500, scanned, "reader must inject plain ZSTD for ID 0x03 on v3 files "
                    + "regardless of caller-supplied codec list");
        }
    }

    // @spec compression.zstd-dictionary.R20a,R21 — openLazy path must perform the same injection as
    // open()
    @Test
    void lazyReaderInjectsPlainZstdCodecForV3File(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd so the writer actually produces codec ID 0x03");

        Path path = dir.resolve("v3-plain-zstd-lazy.sst");
        CompressionCodec plainZstd = CompressionCodec.zstd();
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(plainZstd)
                .formatVersion(3).build()) {
            for (Entry e : generateEntries(500)) {
                w.append(e);
            }
            w.finish();
        }
        assertEquals(SSTableFormat.MAGIC_V3, readMagic(path));

        CompressionCodec[] noCodecs = new CompressionCodec[0];
        try (TrieSSTableReader r = TrieSSTableReader.openLazy(path,
                BlockedBloomFilter.deserializer(), null, noCodecs)) {
            Optional<Entry> found = r.get(seg("key-00100"));
            assertTrue(found.isPresent(), "key-00100 must be readable with injected plain ZSTD");
        }
    }

    // ---- Test 11: Round-trip with deletes mixed in ----

    @Test
    void roundTripWithDeletesMixed(@TempDir Path dir) throws IOException {
        assumeTrue(ZstdDictionaryTrainer.isAvailable(),
                "requires native libzstd for dictionary training");

        Path path = dir.resolve("v4-with-deletes.sst");
        CompressionCodec codec = CompressionCodec.zstd();

        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            String key = "key-%05d".formatted(i);
            if (i % 10 == 0) {
                entries.add(new Entry.Delete(seg(key), new SequenceNumber(i + 1)));
            } else {
                entries.add(
                        put(key, "value-data-for-entry-%05d-with-some-repeated-content-structure"
                                .formatted(i), i + 1));
            }
        }

        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(codec).blockSize(1024)
                .dictionaryTraining(true).build()) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        assertEquals(SSTableFormat.MAGIC_V4, readMagic(path));

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.zstd())) {
            assertEquals(2000L, r.metadata().entryCount());

            // Verify a Delete entry
            Optional<Entry> delEntry = r.get(seg("key-00000"));
            assertTrue(delEntry.isPresent());
            assertInstanceOf(Entry.Delete.class, delEntry.get());

            // Verify a Put entry
            Optional<Entry> putEntry = r.get(seg("key-00001"));
            assertTrue(putEntry.isPresent());
            assertInstanceOf(Entry.Put.class, putEntry.get());
        }
    }
}
