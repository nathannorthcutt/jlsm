package jlsm.sstable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the SSTable v1–v4 collapse mandated by the pre-GA format-version deprecation policy.
 * After the collapse, all writers emit v5 magic and the reader rejects any file whose trailing
 * magic resolves to a legacy version (v1, v2, v3, v4) with an {@link IncompleteSSTableException}
 * carrying the same vocabulary as for any other unrecognised magic.
 *
 * <p>
 * The historical magic numbers are written out as raw {@code long} literals deliberately — the
 * named constants {@code SSTableFormat.MAGIC}, {@code MAGIC_V2}, {@code MAGIC_V3}, {@code MAGIC_V4}
 * are removed by this collapse and must not be referenced from this test.
 */
class SSTableV1V4CollapseTest {

    private static final long EXPECTED_MAGIC_V5 = 0x4A4C534D53535405L;
    private static final long LEGACY_MAGIC_V1 = 0x4A4C534D53535401L;
    private static final long LEGACY_MAGIC_V2 = 0x4A4C534D53535402L;
    private static final long LEGACY_MAGIC_V3 = 0x4A4C534D53535403L;
    private static final long LEGACY_MAGIC_V4 = 0x4A4C534D53535404L;

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private static long trailingMagic(Path path) throws IOException {
        byte[] all = Files.readAllBytes(path);
        assertTrue(all.length >= 8, "file too small to read trailing magic");
        return ByteBuffer.wrap(all, all.length - 8, 8).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private static Path writeFileWithTrailingMagic(Path dir, String name, long magic)
            throws IOException {
        Path path = dir.resolve(name);
        // Small file (16 bytes) — well below FOOTER_SIZE_V5 (112), so the speculative
        // v5-hypothesis check is skipped and dispatch lands directly on the magic-recognition
        // branch.
        ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(0L);
        bb.putLong(magic);
        Files.write(path, bb.array());
        return path;
    }

    @Test
    void defaultWriterEmitsV5Magic(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("default-writer.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out)) {
            w.append(put("apple", "red", 1));
            w.append(put("banana", "yellow", 2));
            w.finish();
        }
        assertEquals(EXPECTED_MAGIC_V5, trailingMagic(out),
                "post-collapse: 2-arg constructor must emit v5 magic in the trailing 8 bytes");
    }

    @Test
    void codecConstructorEmitsV5Magic(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("codec-writer.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, out,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            w.append(put("apple", "red", 1));
            w.append(put("banana", "yellow", 2));
            w.finish();
        }
        assertEquals(EXPECTED_MAGIC_V5, trailingMagic(out),
                "post-collapse: codec-bearing constructor must emit v5 magic in the trailing 8 bytes");
    }

    @Test
    void readerRejectsLegacyV1Magic(@TempDir Path dir) throws IOException {
        Path path = writeFileWithTrailingMagic(dir, "legacy-v1.sst", LEGACY_MAGIC_V1);
        IncompleteSSTableException ex = assertThrows(IncompleteSSTableException.class,
                () -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null));
        assertTrue(ex.getMessage().contains("recognised SSTable magic"),
                "post-collapse: v1 magic must be rejected as unrecognised; got: "
                        + ex.getMessage());
    }

    @Test
    void readerRejectsLegacyV2Magic(@TempDir Path dir) throws IOException {
        Path path = writeFileWithTrailingMagic(dir, "legacy-v2.sst", LEGACY_MAGIC_V2);
        IncompleteSSTableException ex = assertThrows(IncompleteSSTableException.class,
                () -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null));
        assertTrue(ex.getMessage().contains("recognised SSTable magic"),
                "post-collapse: v2 magic must be rejected as unrecognised; got: "
                        + ex.getMessage());
    }

    @Test
    void readerRejectsLegacyV3Magic(@TempDir Path dir) throws IOException {
        Path path = writeFileWithTrailingMagic(dir, "legacy-v3.sst", LEGACY_MAGIC_V3);
        IncompleteSSTableException ex = assertThrows(IncompleteSSTableException.class,
                () -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null));
        assertTrue(ex.getMessage().contains("recognised SSTable magic"),
                "post-collapse: v3 magic must be rejected as unrecognised; got: "
                        + ex.getMessage());
    }

    @Test
    void readerRejectsLegacyV4Magic(@TempDir Path dir) throws IOException {
        Path path = writeFileWithTrailingMagic(dir, "legacy-v4.sst", LEGACY_MAGIC_V4);
        IncompleteSSTableException ex = assertThrows(IncompleteSSTableException.class,
                () -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null));
        assertTrue(ex.getMessage().contains("recognised SSTable magic"),
                "post-collapse: v4 magic must be rejected as unrecognised; got: "
                        + ex.getMessage());
    }
}
