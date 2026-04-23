package jlsm.sstable;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
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
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the SSTable v3 format: end-to-end write/read with per-block CRC32C
 * checksums and configurable block sizes.
 *
 * <p>
 * These tests use the {@code TrieSSTableWriter.Builder} which does not exist yet, so they will fail
 * with compilation errors until the builder is implemented. Tests that use existing constructors
 * will fail at runtime because v3 format detection and CRC32C verification are not yet wired in.
 * </p>
 */
class SSTableV3IntegrationTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private static Entry.Delete del(String key, long seq) {
        return new Entry.Delete(seg(key), new SequenceNumber(seq));
    }

    private List<Entry> generateEntries(int count) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String key = "key-%05d".formatted(i);
            String value = "value-data-for-entry-" + i;
            entries.add(put(key, value, i + 1));
        }
        return entries;
    }

    /**
     * Reads the last 8 bytes of a file and interprets them as a big-endian long (the magic number
     * in the SSTable footer).
     */
    private long readMagic(Path path) throws IOException {
        long fileSize = Files.size(path);
        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ)) {
            ch.position(fileSize - 8);
            ByteBuffer buf = ByteBuffer.allocate(8);
            while (buf.hasRemaining()) {
                if (ch.read(buf) == -1) {
                    throw new IOException("unexpected end of file reading magic");
                }
            }
            buf.flip();
            return buf.getLong();
        }
    }

    // ---- Test 1: Write and read v3 with compression ----

    // @spec sstable.v3-format-upgrade.R4,R5,R6,R7,R14,R17 — end-to-end v3 write/read with CRC32C
    // verification
    @Test
    void writeAndReadV3WithCompression(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("v3-compressed.sst");
        CompressionCodec deflate = CompressionCodec.deflate(6);

        // Builder API — does not exist yet; will fail with compilation error
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(deflate).formatVersion(3)
                .build()) {
            List<Entry> entries = generateEntries(10);
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            assertEquals(10L, r.metadata().entryCount());

            // Verify point lookup
            Optional<Entry> result = r.get(seg("key-00000"));
            assertTrue(result.isPresent());
            assertInstanceOf(Entry.Put.class, result.get());
            assertEquals(-1L,
                    seg("value-data-for-entry-0").mismatch(((Entry.Put) result.get()).value()));

            // Verify full scan
            Iterator<Entry> iter = r.scan();
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            assertEquals(10, count);
        }
    }

    // ---- Test 2: Write and read v3 with custom block size ----

    // @spec sstable.v3-format-upgrade.R10,R12,R13,R14 — writer honors blockSize via Builder,
    // roundtrip preserves data
    @Test
    void writeAndReadV3WithCustomBlockSize(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("v3-large-blocks.sst");
        CompressionCodec deflate = CompressionCodec.deflate(6);

        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(deflate)
                .blockSize(SSTableFormat.REMOTE_BLOCK_SIZE).build()) {
            List<Entry> entries = generateEntries(50);
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            assertEquals(50L, r.metadata().entryCount());

            Iterator<Entry> iter = r.scan();
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            assertEquals(50, count);
        }
    }

    // @spec sstable.v3-format-upgrade.R15 — reader exposes the blockSize stored in the v3 footer
    @Test
    void readerExposesStoredBlockSizeFromV3Footer(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("v3-blocksize-roundtrip.sst");
        int chosen = 16384; // non-default, pow2, within [1024, 32 MiB]

        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate(6)).blockSize(chosen).build()) {
            w.append(put("k", "v", 1));
            w.finish();
        }

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            assertEquals((long) chosen, r.blockSize(),
                    "reader.blockSize() must return the value stored in the v3 footer");
        }
    }

    // @spec sstable.v3-format-upgrade.R15,R18 — v1/v2 files return the default block size (4096)
    @Test
    void readerReportsDefaultBlockSizeForV1Files(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("v1-blocksize-default.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path)) {
            w.append(put("k", "v", 1));
            w.finish();
        }

        try (TrieSSTableReader r = TrieSSTableReader.open(path,
                BlockedBloomFilter.deserializer())) {
            assertEquals((long) SSTableFormat.DEFAULT_BLOCK_SIZE, r.blockSize(),
                    "v1 reader must report DEFAULT_BLOCK_SIZE since v1 has no footer blockSize");
        }
    }

    // ---- Test 3: Writer with codec produces v3 magic ----

    // @spec sstable.v3-format-upgrade.R16 — codec-configured writer writes MAGIC_V3 (never
    // MAGIC_V2)
    @Test
    void writerWithCodecProducesV3Magic(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("v3-magic.sst");
        CompressionCodec deflate = CompressionCodec.deflate(6);

        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(deflate).formatVersion(3)
                .build()) {
            w.append(put("a", "alpha", 1));
            w.finish();
        }

        assertEquals(SSTableFormat.MAGIC_V3, readMagic(path),
                "v3 writer should write MAGIC_V3 in the footer");
    }

    // ---- Test 4: Writer without codec produces v1 magic ----

    // @spec sstable.v3-format-upgrade.R16 — uncompressed writes (no codec) continue to produce v1
    // format
    @Test
    void writerWithoutCodecProducesV1(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("v1-magic.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path)) {
            w.append(put("a", "alpha", 1));
            w.finish();
        }

        assertEquals(SSTableFormat.MAGIC, readMagic(path),
                "v1 writer should write original MAGIC in the footer");
    }

    // ---- Test 5: Builder rejects non-default block size without codec ----

    // @spec sstable.v3-format-upgrade.R16 — non-default blockSize without codec rejected at
    // construction
    @Test
    void builderRejectsNonDefaultBlockSizeWithoutCodec() {
        assertThrows(IllegalArgumentException.class,
                () -> TrieSSTableWriter.builder().id(1L).level(Level.L0)
                        .path(Path.of("/tmp/dummy.sst")).blockSize(8192)
                        // no codec
                        .build(),
                "non-default block size without a codec should be rejected");
    }

    // ---- Test 6: v3 reader reads file written via legacy constructor (now v3 per R16) ----

    // @spec sstable.v3-format-upgrade.R16 — codec-configured legacy constructor produces v3 (not
    // v2)
    @Test
    void v3ReaderReadsFileFromLegacyConstructor(@TempDir Path dir) throws IOException {
        // Before F16 this constructor produced v2; after R16 it produces v3.
        Path path = dir.resolve("legacy-ctor.sst");
        CompressionCodec deflate = CompressionCodec.deflate();
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), deflate)) {
            w.append(put("alpha", "one", 1));
            w.append(put("beta", "two", 2));
            w.append(put("gamma", "three", 3));
            w.finish();
        }

        // The legacy constructor must now produce v3 output.
        assertEquals(SSTableFormat.MAGIC_V3, readMagic(path),
                "5-arg constructor with codec must produce MAGIC_V3 (never v2)");

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            assertEquals(3L, r.metadata().entryCount());

            Optional<Entry> result = r.get(seg("beta"));
            assertTrue(result.isPresent());
            assertInstanceOf(Entry.Put.class, result.get());
            assertEquals(-1L, seg("two").mismatch(((Entry.Put) result.get()).value()));
        }
    }

    // ---- Test 7: v3 reader reads v1 file correctly ----

    // @spec sstable.v3-format-upgrade.R8,R17,R19 — v3-capable reader falls back to v1
    // (hasChecksums=false branch);
    // v2 (also hasChecksums=false) is the same code path — version dispatch sets the flag
    @Test
    void v3ReaderReadsV1FileCorrectly(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("v1-compat.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path)) {
            w.append(put("foo", "bar", 1));
            w.append(put("hello", "world", 2));
            w.finish();
        }

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none())) {
            assertEquals(2L, r.metadata().entryCount());

            Optional<Entry> result = r.get(seg("foo"));
            assertTrue(result.isPresent());
            assertInstanceOf(Entry.Put.class, result.get());
            assertEquals(-1L, seg("bar").mismatch(((Entry.Put) result.get()).value()));
        }
    }

    // ---- Test 8: Corrupted block throws CorruptBlockException ----

    // @spec sstable.v3-format-upgrade.R6,R9 — corruption surfaces as CorruptBlockException with
    // diagnostic fields
    @Test
    void corruptedBlockThrowsCorruptBlockException(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("v3-corrupt.sst");
        CompressionCodec deflate = CompressionCodec.deflate(6);

        // Write a valid v3 file
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(deflate).formatVersion(3)
                .build()) {
            // Write enough entries to ensure at least one data block
            for (int i = 0; i < 100; i++) {
                w.append(put("key-%05d".formatted(i), "value-" + i, i + 1));
            }
            w.finish();
        }

        // Corrupt a byte in the data block region (near the start of the file)
        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ch.position(16); // skip into data block region
            ByteBuffer corrupt = ByteBuffer.allocate(1);
            ch.read(corrupt);
            corrupt.flip();
            byte original = corrupt.get(0);
            corrupt.put(0, (byte) (original ^ 0xFF)); // flip all bits
            corrupt.rewind();
            ch.position(16);
            ch.write(corrupt);
        }

        // Reading should detect the CRC mismatch and throw CorruptBlockException
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            CorruptBlockException ex = assertThrows(CorruptBlockException.class, () -> {
                Iterator<Entry> iter = r.scan();
                while (iter.hasNext()) {
                    iter.next();
                }
            });
            assertTrue(ex.blockIndex() >= 0, "block index should be non-negative");
            assertNotEquals(ex.expectedChecksum(), ex.actualChecksum(),
                    "expected and actual checksums should differ for corrupt block");
        }
    }

    // ---- Test 9: v3 footer with invalid block size throws IOException ----

    // @spec sstable.v3-format-upgrade.R20 — invalid blockSize from on-disk v3 footer produces
    // IOException
    @Test
    void v3FooterWithInvalidBlockSizeThrowsIOException(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("v3-bad-blocksize.sst");
        CompressionCodec deflate = CompressionCodec.deflate(6);

        // Write a valid v3 file
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(deflate).formatVersion(3)
                .build()) {
            w.append(put("a", "alpha", 1));
            w.finish();
        }

        // Overwrite the blockSize field in the v3 footer with an invalid value.
        // v3 footer is 72 bytes. blockSize is the new field added after entryCount
        // and before magic. Layout (from end): magic(8) + blockSize(8) + entryCount(8) + ...
        // So blockSize is at fileSize - 16 (8 bytes magic + 8 bytes blockSize from end).
        long fileSize = Files.size(path);
        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ch.position(fileSize - 16); // blockSize field position
            ByteBuffer buf = ByteBuffer.allocate(8);
            buf.putLong(999L); // not a power of 2, not in valid range
            buf.flip();
            ch.write(buf);
        }

        assertThrows(IOException.class, () -> {
            try (TrieSSTableReader ignored = TrieSSTableReader.open(path,
                    BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                    CompressionCodec.deflate())) {
                // should throw during open
            }
        });
    }

    // ---- Test 10: CRC32C computed after compression fallback ----

    // @spec sstable.v3-format-upgrade.R4 — CRC32C computed over post-fallback bytes (raw when NONE
    // fallback triggers)
    @Test
    void crc32cComputedAfterCompressionFallback(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("v3-incompressible.sst");
        CompressionCodec deflate = CompressionCodec.deflate(6);
        Random rng = new Random(42);

        // Write entries with random (incompressible) values
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(deflate).formatVersion(3)
                .build()) {
            for (int i = 0; i < 200; i++) {
                String key = "key-%05d".formatted(i);
                byte[] randomValue = new byte[256];
                rng.nextBytes(randomValue);
                var keyBytes = seg(key);
                var valBytes = MemorySegment.ofArray(randomValue);
                w.append(new Entry.Put(keyBytes, valBytes, new SequenceNumber(i + 1)));
            }
            w.finish();
        }

        // The file should still be readable — the reader computes CRC32C on
        // the stored bytes (whether compressed or raw after fallback) and the
        // checksums should match.
        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.none(), CompressionCodec.deflate())) {
            assertEquals(200L, r.metadata().entryCount());

            // Full scan should succeed without CorruptBlockException
            Iterator<Entry> iter = r.scan();
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            assertEquals(200, count);
        }
    }

    // @spec sstable.v3-format-upgrade.R21 — v3 section ordering validation rejects overlapping
    // sections with IOException
    @Test
    void v3FooterRejectsFlSectionOverlappingFooter(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("v3-overlap.sst");
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(path)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate(6)).formatVersion(3).build()) {
            w.append(put("k", "v", 1));
            w.finish();
        }

        // Corrupt the fltLength field so that flt section extends past footerStart.
        // v3 footer layout: mapOffset(0), mapLength(8), idxOffset(16), idxLength(24),
        // fltOffset(32), fltLength(40), entryCount(48), blockSize(56), MAGIC_V3(64).
        // fltLength is at fileSize - 72 + 40 = fileSize - 32.
        long fileSize = Files.size(path);
        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.WRITE)) {
            ch.position(fileSize - 32);
            ByteBuffer buf = ByteBuffer.allocate(8);
            buf.putLong(fileSize); // fltLength = fileSize ensures overlap with footer
            buf.flip();
            ch.write(buf);
        }

        IOException ex = assertThrows(IOException.class,
                () -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.none(), CompressionCodec.deflate()));
        assertTrue(
                ex.getMessage().contains("overlap") || ex.getMessage().contains("exceeds")
                        || ex.getMessage().contains("flt"),
                "R21: section-ordering violation must surface as IOException, got: "
                        + ex.getMessage());
    }
}
