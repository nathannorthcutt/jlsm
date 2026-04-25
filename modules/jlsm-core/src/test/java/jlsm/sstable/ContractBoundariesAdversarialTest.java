package jlsm.sstable;

import jlsm.core.compression.CompressionCodec;
import jlsm.core.io.MemorySerializer;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.encryption.DomainId;
import jlsm.encryption.ReadContext;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.sstable.internal.CompressionMap;
import jlsm.sstable.internal.SSTableFormat;
import jlsm.sstable.internal.V5Footer;
import jlsm.sstable.internal.V6Footer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for contract boundary violations in SSTable reader/writer.
 */
class ContractBoundariesAdversarialTest {

    @TempDir
    Path tempDir;

    // Finding: F-R1.contract_boundaries.1.3
    // Bug: Writer does not validate that custom CompressionCodec has a non-conflicting codecId
    // Correct behavior: Writer constructor should reject codecs with codecId 0x00 (reserved for
    // NoneCodec)
    // Fix location: TrieSSTableWriter constructor (~line 134)
    // Regression watch: Ensure null codec (v1 format) is still accepted; only non-null codecs with
    // 0x00 are rejected
    @Test
    void test_TrieSSTableWriter_contract_boundaries_custom_codec_with_none_id_accepted()
            throws IOException {
        // Create a custom codec that claims codecId 0x00 (same as NoneCodec)
        CompressionCodec maliciousCodec = new CompressionCodec() {
            @Override
            public byte codecId() {
                return 0x00;
            }

            @Override
            public java.lang.foreign.MemorySegment compress(java.lang.foreign.MemorySegment src,
                    java.lang.foreign.MemorySegment dst) {
                java.lang.foreign.MemorySegment.copy(src, 0, dst, 0, src.byteSize());
                return dst.asSlice(0, src.byteSize());
            }

            @Override
            public java.lang.foreign.MemorySegment decompress(java.lang.foreign.MemorySegment src,
                    java.lang.foreign.MemorySegment dst, int uncompressedLength) {
                java.lang.foreign.MemorySegment.copy(src, 0, dst, 0, src.byteSize());
                return dst.asSlice(0, src.byteSize());
            }
        };

        Path file = tempDir.resolve("conflict.sst");

        // The writer should reject a codec with codecId 0x00 because the writer's
        // incompressible fallback always stores raw blocks with codecId 0x00 (NoneCodec).
        // Accepting a custom codec with the same ID creates ambiguous compression map entries.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new TrieSSTableWriter(1L, new Level(0), file,
                        n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01), maliciousCodec));
        assertTrue(ex.getMessage().contains("0x00") || ex.getMessage().contains("NoneCodec"),
                "exception should mention the conflicting codec ID or NoneCodec, got: "
                        + ex.getMessage());
    }

    // Finding: F-R1.contract_boundaries.1.4
    // Bug: buildCodecMap silently overwrites duplicate codec IDs — last one wins
    // Correct behavior: buildCodecMap should throw IllegalArgumentException when two codecs share
    // the same codecId
    // Fix location: TrieSSTableReader.buildCodecMap (~line 434)
    // Regression watch: Ensure NoneCodec auto-include still works; only duplicate non-NoneCodec IDs
    // are rejected
    @Test
    void test_buildCodecMap_contract_boundaries_duplicate_codec_ids_silently_overwrite()
            throws IOException {
        // Create two distinct custom codecs that both claim codecId 0x10
        CompressionCodec codecA = new CompressionCodec() {
            @Override
            public byte codecId() {
                return 0x10;
            }

            @Override
            public java.lang.foreign.MemorySegment compress(java.lang.foreign.MemorySegment src,
                    java.lang.foreign.MemorySegment dst) {
                java.lang.foreign.MemorySegment.copy(src, 0, dst, 0, src.byteSize());
                return dst.asSlice(0, src.byteSize());
            }

            @Override
            public java.lang.foreign.MemorySegment decompress(java.lang.foreign.MemorySegment src,
                    java.lang.foreign.MemorySegment dst, int uncompressedLength) {
                long len = Math.min(src.byteSize(), uncompressedLength);
                java.lang.foreign.MemorySegment.copy(src, 0, dst, 0, len);
                return dst.asSlice(0, uncompressedLength);
            }

            @Override
            public String toString() {
                return "CodecA(0x10)";
            }
        };

        CompressionCodec codecB = new CompressionCodec() {
            @Override
            public byte codecId() {
                return 0x10; // Same ID as codecA — this is the conflict
            }

            @Override
            public java.lang.foreign.MemorySegment compress(java.lang.foreign.MemorySegment src,
                    java.lang.foreign.MemorySegment dst) {
                // Different behavior from codecA — prepends 0xFF byte
                dst.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0xFF);
                java.lang.foreign.MemorySegment.copy(src, 0, dst, 1, src.byteSize());
                return dst.asSlice(0, src.byteSize() + 1);
            }

            @Override
            public java.lang.foreign.MemorySegment decompress(java.lang.foreign.MemorySegment src,
                    java.lang.foreign.MemorySegment dst, int uncompressedLength) {
                long len = Math.min(src.byteSize() - 1, uncompressedLength);
                java.lang.foreign.MemorySegment.copy(src, 1, dst, 0, len);
                return dst.asSlice(0, uncompressedLength);
            }

            @Override
            public String toString() {
                return "CodecB(0x10)";
            }
        };

        // Write a minimal v2 SSTable file using DeflateCodec so we have a valid v2 file to open
        Path file = tempDir.resolve("duplicate-codec.sst");
        try (var writer = new TrieSSTableWriter(1L, new Level(0), file,
                n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01),
                CompressionCodec.deflate())) {
            MemorySegment key = Arena.ofAuto().allocate(1);
            key.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0x01);
            MemorySegment value = Arena.ofAuto().allocate(1);
            value.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0x01);
            writer.append(new Entry.Put(key, value, new SequenceNumber(1L)));
            writer.finish();
        }

        // Attempt to open with two codecs that have the same codecId (0x10).
        // buildCodecMap should reject duplicate codec IDs rather than silently overwriting.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TrieSSTableReader.open(file,
                        jlsm.bloom.blocked.BlockedBloomFilter.deserializer(), null, codecA,
                        codecB));
        assertTrue(ex.getMessage().contains("0x10") || ex.getMessage().contains("duplicate"),
                "exception should mention the conflicting codec ID or 'duplicate', got: "
                        + ex.getMessage());
    }

    // Finding: F-R1.cb.2.1 — mapLength int truncation. In v5, mapLength is bounded inline at
    // line ~1626 of TrieSSTableReader (Integer.MAX_VALUE guard) and the footer self-checksum
    // protects against tampering. The historical Footer.validate() path the test targeted is
    // dead post v1-v4 collapse.
    @org.junit.jupiter.api.Disabled("v3 footer bug; v5 has inline Integer.MAX_VALUE guards plus footer CRC")
    @Test
    void test_TrieSSTableReader_contract_boundaries_mapLength_exceeds_int_max() throws IOException {
        // Step 1: Write a valid v2 SSTable
        Path file = tempDir.resolve("huge-maplen.sst");
        try (var writer = new TrieSSTableWriter(1L, new Level(0), file,
                n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01),
                CompressionCodec.deflate())) {
            MemorySegment key = Arena.ofAuto().allocate(1);
            key.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x01);
            MemorySegment value = Arena.ofAuto().allocate(1);
            value.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x02);
            writer.append(new Entry.Put(key, value, new SequenceNumber(1L)));
            writer.finish();
        }

        // Step 2: Corrupt the footer's mapLength field to exceed Integer.MAX_VALUE.
        // v3 footer layout (72 bytes from end): mapOffset(0), mapLength(8), idxOffset(16),
        // idxLength(24), fltOffset(32), fltLength(40), entryCount(48), blockSize(56), MAGIC_V3(64).
        // mapLength starts at fileSize - 72 + 8 = fileSize - 64
        long fileSize = Files.size(file);
        long mapLengthOffset = fileSize - 64;
        long corruptMapLength = 0x0000_0001_0000_0000L; // 4 GiB — exceeds Integer.MAX_VALUE

        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            ch.position(mapLengthOffset);
            ByteBuffer buf = ByteBuffer.allocate(8);
            buf.putLong(corruptMapLength);
            buf.flip();
            ch.write(buf);
        }

        // Step 3: Attempt to open — should throw IOException mentioning mapLength, not silently
        // truncate
        IOException ex = assertThrows(IOException.class,
                () -> TrieSSTableReader.open(file,
                        jlsm.bloom.blocked.BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        assertTrue(ex.getMessage().contains("mapLength"),
                "exception should mention mapLength, got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.2.2
    // Bug: IllegalArgumentException from CompressionMap.deserialize leaks channel in open/openLazy
    // Correct behavior: Channel must be closed even when a RuntimeException (IAE) is thrown
    // Fix location: TrieSSTableReader.java open() and openLazy() catch blocks (~lines 207-213,
    // 259-265)
    // Regression watch: Ensure IOException and Error catch blocks still work; valid v2 files still
    // open
    @org.junit.jupiter.api.Disabled("v3 path; v5 verifies map CRC at open before deserialize, so IAE leak path is unreachable")
    @Test
    void test_TrieSSTableReader_contract_boundaries_IAE_from_deserialize_leaks_channel()
            throws IOException {
        // Step 1: Write a valid v2 SSTable
        Path file = tempDir.resolve("corrupt-compmap.sst");
        try (var writer = new TrieSSTableWriter(1L, new Level(0), file,
                n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01),
                CompressionCodec.deflate())) {
            MemorySegment key = Arena.ofAuto().allocate(1);
            key.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x01);
            MemorySegment value = Arena.ofAuto().allocate(1);
            value.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x02);
            writer.append(new Entry.Put(key, value, new SequenceNumber(1L)));
            writer.finish();
        }

        // Step 2: Corrupt the compression map data to trigger IAE from CompressionMap.deserialize.
        // We corrupt the block count field in the map data to a large positive value that causes
        // "data too short" — the footer's mapLength stays consistent, so footer validation passes,
        // but CompressionMap.deserialize sees that the data is too short for the claimed block
        // count.
        long fileSize = Files.size(file);
        long mapOffsetFieldPos = fileSize - 72; // mapOffset is first field in v3 footer
        long mapOffset;
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            ch.position(mapOffsetFieldPos);
            ByteBuffer buf = ByteBuffer.allocate(8);
            while (buf.hasRemaining()) {
                ch.read(buf);
            }
            buf.flip();
            mapOffset = buf.getLong();
        }

        // Overwrite the block count at mapOffset with a huge value (e.g. 999999).
        // The actual map has only 1 block, so data.length will be far too short for 999999 blocks.
        // CompressionMap.deserialize will throw IAE: "compression map data too short".
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            ch.position(mapOffset);
            ByteBuffer buf = ByteBuffer.allocate(4);
            buf.putInt(999999); // claims 999999 blocks but actual data holds only 1
            buf.flip();
            ch.write(buf);
        }

        // Step 3: open() must not leak the channel. Before the fix, the IAE from
        // CompressionMap.deserialize bypasses the IOException-only catch block, leaking the
        // channel.
        // After the fix, the RuntimeException catch block closes the channel before re-throwing.
        //
        // We measure open file descriptors before and after a batch of failed opens.
        // Each leaked channel holds one FD; if the fix is applied, FDs are reclaimed.
        var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        // Cast to com.sun.management to access getOpenFileDescriptorCount
        long fdBefore = ((com.sun.management.UnixOperatingSystemMXBean) osBean)
                .getOpenFileDescriptorCount();

        int iterations = 500;
        for (int i = 0; i < iterations; i++) {
            assertThrows(IllegalArgumentException.class,
                    () -> TrieSSTableReader.open(file,
                            jlsm.bloom.blocked.BlockedBloomFilter.deserializer(), null,
                            CompressionCodec.deflate()));
        }
        for (int i = 0; i < iterations; i++) {
            assertThrows(IllegalArgumentException.class,
                    () -> TrieSSTableReader.openLazy(file,
                            jlsm.bloom.blocked.BlockedBloomFilter.deserializer(), null,
                            CompressionCodec.deflate()));
        }

        long fdAfter = ((com.sun.management.UnixOperatingSystemMXBean) osBean)
                .getOpenFileDescriptorCount();
        long leaked = fdAfter - fdBefore;
        // If channels are leaked, we expect ~1000 new FDs (500 open + 500 openLazy).
        // If channels are closed, the FD count should be roughly unchanged (within a small margin).
        assertTrue(leaked < 10,
                "Expected channels to be closed (FD leak count should be near 0), "
                        + "but %d file descriptors were leaked (before=%d, after=%d)"
                                .formatted(leaked, fdBefore, fdAfter));
    }

    // Finding: F-R1.contract_boundaries.1.7
    // Bug: v1 open() on a v2 file throws opaque "bad magic" error instead of indicating v2 format
    // Correct behavior: readFooterV1 should detect v2 magic and throw a clear error mentioning v2
    // format
    // Fix location: TrieSSTableReader.readFooterV1 (~line 557)
    // Regression watch: v1 open on v1 files must still work; v1 open on truly corrupt files must
    // still say "bad magic"
    @org.junit.jupiter.api.Disabled("v1 reader was removed by the SSTable v1-v4 collapse; the simple open() now delegates to the v5 path")
    @Test
    void test_TrieSSTableReader_contract_boundaries_v1_open_on_v2_file_gives_opaque_error()
            throws IOException {
        // Write a v2 file (with compression codec)
        Path file = tempDir.resolve("v2-format.sst");
        try (var writer = new TrieSSTableWriter(1L, new Level(0), file,
                n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01),
                CompressionCodec.deflate())) {
            MemorySegment key = Arena.ofAuto().allocate(1);
            key.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0x42);
            MemorySegment value = Arena.ofAuto().allocate(1);
            value.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0x43);
            writer.append(new Entry.Put(key, value, new SequenceNumber(1L)));
            writer.finish();
        }

        // Attempt to open using the v1 API — should get a clear error about v3 format (the
        // writer now always produces v3 when a compression codec is configured, per F16 R16).
        IOException ex = assertThrows(IOException.class,
                () -> TrieSSTableReader.open(file,
                        jlsm.bloom.blocked.BlockedBloomFilter.deserializer(),
                        (jlsm.core.cache.BlockCache) null));
        // The error message should mention the compressed version (v3) to help the caller
        // understand they need the open method with codecs, not just an opaque "bad magic" error.
        assertTrue(ex.getMessage().toLowerCase().contains("v3"),
                "error should mention v3 format to guide caller, got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.2.4
    // Bug: CompressionMap.deserialize silently ignores trailing bytes
    // Correct behavior: deserialize should reject data with trailing bytes (data.length !=
    // expectedLength)
    // Fix location: CompressionMap.java:176 (change < to !=)
    // Regression watch: Ensure exact-length data still deserializes correctly; too-short data still
    // rejected
    @Test
    void test_CompressionMap_deserialize_contract_boundaries_trailing_bytes_silently_ignored() {
        // Create a valid CompressionMap with one entry, serialize it, then append trailing garbage
        var map = new CompressionMap(List.of(new CompressionMap.Entry(0L, 100, 200, (byte) 0x00)));
        byte[] validData = map.serialize();

        // Sanity: exact-length data deserializes fine
        CompressionMap roundTripped = CompressionMap.deserialize(validData);
        assertEquals(1, roundTripped.blockCount());

        // Append 10 trailing garbage bytes — this should be rejected, not silently accepted
        byte[] dataWithTrailing = Arrays.copyOf(validData, validData.length + 10);
        for (int i = validData.length; i < dataWithTrailing.length; i++) {
            dataWithTrailing[i] = (byte) 0xDE; // garbage
        }

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CompressionMap.deserialize(dataWithTrailing),
                "deserialize should reject data with trailing bytes, but it silently accepted them");
        assertTrue(ex.getMessage().contains("trailing") || ex.getMessage().contains("length"),
                "exception should mention trailing bytes or length mismatch, got: "
                        + ex.getMessage());
    }

    // Finding: F-R1.cb.3.2
    // Bug: idxLength not guarded against Integer.MAX_VALUE truncation in validate()
    // Correct behavior: Footer.validate() should reject idxLength > Integer.MAX_VALUE with a clear
    // IOException
    // Fix location: TrieSSTableReader.Footer.validate() (~line 514-554)
    // Regression watch: Ensure valid files with small idxLength still open correctly
    @org.junit.jupiter.api.Disabled("v3 footer bug; v5 has inline Integer.MAX_VALUE guards plus footer CRC")
    @Test
    void test_Footer_validate_contract_boundaries_idxLength_exceeds_int_max() throws IOException {
        // Step 1: Write a valid v2 SSTable
        Path file = tempDir.resolve("huge-idxlen.sst");
        try (var writer = new TrieSSTableWriter(1L, new Level(0), file,
                n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01),
                CompressionCodec.deflate())) {
            MemorySegment key = Arena.ofAuto().allocate(1);
            key.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x01);
            MemorySegment value = Arena.ofAuto().allocate(1);
            value.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x02);
            writer.append(new Entry.Put(key, value, new SequenceNumber(1L)));
            writer.finish();
        }

        // Step 2: Corrupt the footer's idxLength field to 0x8000_0000L (2^31)
        // v3 footer layout (72 bytes from end): mapOffset(0), mapLength(8), idxOffset(16),
        // idxLength(24), fltOffset(32), fltLength(40), entryCount(48), blockSize(56), MAGIC_V3(64).
        // idxLength is at fileSize - 72 + 24 = fileSize - 48
        long fileSize = Files.size(file);
        long idxLengthOffset = fileSize - 48;
        long corruptIdxLength = 0x8000_0000L; // 2^31 — exceeds Integer.MAX_VALUE

        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            ch.position(idxLengthOffset);
            ByteBuffer buf = ByteBuffer.allocate(8);
            buf.putLong(corruptIdxLength);
            buf.flip();
            ch.write(buf);
        }

        // Step 3: Attempt to open — should throw IOException mentioning idxLength, not truncate
        IOException ex = assertThrows(IOException.class,
                () -> TrieSSTableReader.open(file,
                        jlsm.bloom.blocked.BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        assertTrue(ex.getMessage().contains("idxLength"),
                "exception should mention idxLength, got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.3.3
    // Bug: fltLength not guarded against Integer.MAX_VALUE truncation in validate()
    // Correct behavior: Footer.validate() should reject fltLength > Integer.MAX_VALUE with a clear
    // IOException
    // Fix location: TrieSSTableReader.Footer.validate() (~line 528-532)
    // Regression watch: Ensure valid files with small fltLength still open correctly
    @org.junit.jupiter.api.Disabled("v3 footer bug; v5 has inline Integer.MAX_VALUE guards plus footer CRC")
    @Test
    void test_Footer_validate_contract_boundaries_fltLength_exceeds_int_max() throws IOException {
        // Step 1: Write a valid v2 SSTable
        Path file = tempDir.resolve("huge-fltlen.sst");
        try (var writer = new TrieSSTableWriter(1L, new Level(0), file,
                n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01),
                CompressionCodec.deflate())) {
            MemorySegment key = Arena.ofAuto().allocate(1);
            key.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x01);
            MemorySegment value = Arena.ofAuto().allocate(1);
            value.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x02);
            writer.append(new Entry.Put(key, value, new SequenceNumber(1L)));
            writer.finish();
        }

        // Step 2: Corrupt the footer's fltLength field to 0x1_0000_0000L (2^32)
        // v3 footer layout (72 bytes from end): mapOffset(0), mapLength(8), idxOffset(16),
        // idxLength(24), fltOffset(32), fltLength(40), entryCount(48), blockSize(56), MAGIC_V3(64).
        // fltLength is at fileSize - 72 + 40 = fileSize - 32
        long fileSize = Files.size(file);
        long fltLengthOffset = fileSize - 32;
        long corruptFltLength = 0x1_0000_0000L; // 2^32 — exceeds Integer.MAX_VALUE, truncates to 0

        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            ch.position(fltLengthOffset);
            ByteBuffer buf = ByteBuffer.allocate(8);
            buf.putLong(corruptFltLength);
            buf.flip();
            ch.write(buf);
        }

        // Step 3: Attempt to open — should throw IOException mentioning fltLength, not truncate to
        // 0
        IOException ex = assertThrows(IOException.class,
                () -> TrieSSTableReader.open(file,
                        jlsm.bloom.blocked.BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        // v3 footer validation may trip on either (a) fltLength > Integer.MAX_VALUE or
        // (b) the flt section spilling past footerStart. Either is an acceptable failure mode:
        // both are IOException at validation time, as F16 R20/R21 require.
        assertTrue(ex.getMessage().contains("fltLength") || ex.getMessage().contains("flt section"),
                "exception should mention fltLength or flt section bounds, got: "
                        + ex.getMessage());
    }

    // Finding: F-R1.cb.3.4
    // Bug: validate() accepts fileSize but never checks offset+length bounds against it
    // Correct behavior: validate() should reject offset+length that exceeds fileSize with
    // IOException
    // Fix location: TrieSSTableReader.Footer.validate() (~line 514-564)
    // Regression watch: Ensure valid files still open correctly; only over-sized offset+length
    // rejected
    @org.junit.jupiter.api.Disabled("v3 footer bug; v5's section-extents inline check plus footer CRC handle this")
    @Test
    void test_Footer_validate_contract_boundaries_offset_plus_length_exceeds_fileSize()
            throws IOException {
        // Step 1: Write a valid v2 SSTable
        Path file = tempDir.resolve("overshoot-idx.sst");
        try (var writer = new TrieSSTableWriter(1L, new Level(0), file,
                n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01),
                CompressionCodec.deflate())) {
            MemorySegment key = Arena.ofAuto().allocate(1);
            key.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x01);
            MemorySegment value = Arena.ofAuto().allocate(1);
            value.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x02);
            writer.append(new Entry.Put(key, value, new SequenceNumber(1L)));
            writer.finish();
        }

        // Step 2: Corrupt idxLength to be twice the file size.
        // idxOffset stays valid (small positive), but idxOffset + idxLength >> fileSize.
        // v3 footer layout (72 bytes from end): mapOffset(0), mapLength(8), idxOffset(16),
        // idxLength(24), fltOffset(32), fltLength(40), entryCount(48), blockSize(56), MAGIC_V3(64).
        // idxLength is at fileSize - 72 + 24 = fileSize - 48
        long fileSize = Files.size(file);
        long idxLengthOffset = fileSize - 48;
        long corruptIdxLength = fileSize * 2; // positive, fits in int, but exceeds file bounds

        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            ch.position(idxLengthOffset);
            ByteBuffer buf = ByteBuffer.allocate(8);
            buf.putLong(corruptIdxLength);
            buf.flip();
            ch.write(buf);
        }

        // Step 3: Attempt to open — should throw IOException at validation time,
        // not later during I/O with an opaque short-read or channel error.
        // The fileSize parameter is passed to validate() but never used in the body,
        // so this corrupt footer passes validation and the error surfaces later.
        IOException ex = assertThrows(IOException.class,
                () -> TrieSSTableReader.open(file,
                        jlsm.bloom.blocked.BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.deflate()));
        assertTrue(ex.getMessage().contains("fileSize") || ex.getMessage().contains("file size")
                || ex.getMessage().contains("exceeds") || ex.getMessage().contains("bounds"),
                "exception should mention file size bounds violation at validation time, got: "
                        + ex.getMessage());
    }

    // Finding: F-R1.cb.2.5
    // Bug: IOException during finish() leaves state OPEN; re-calling finish() corrupts SSTable
    // Correct behavior: After finish() throws IOException, the writer should transition to a
    // failed state and reject subsequent finish() calls with IllegalStateException
    // Fix location: TrieSSTableWriter.finish() (~line 258-319) — state must change before
    // re-throwing
    // Regression watch: Successful finish() must still work; close() after failed finish() must
    // still
    // clean up the partial file
    @Test
    void test_TrieSSTableWriter_finish_IOException_leaves_state_open_allows_corrupt_retry()
            throws Exception {
        Path file = tempDir.resolve("finish-retry.sst");
        var writer = new TrieSSTableWriter(1L, new Level(0), file,
                n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01),
                CompressionCodec.deflate());

        // Append one entry so we have data to flush
        MemorySegment key = Arena.ofAuto().allocate(1);
        key.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x01);
        MemorySegment value = Arena.ofAuto().allocate(1);
        value.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x02);
        writer.append(new Entry.Put(key, value, new SequenceNumber(1L)));

        // Use reflection to close the underlying channel, which will cause
        // finish() to throw IOException when it tries to write
        Field channelField = TrieSSTableWriter.class.getDeclaredField("channel");
        channelField.setAccessible(true);
        SeekableByteChannel channel = (SeekableByteChannel) channelField.get(writer);
        channel.close();

        // First call to finish() should throw IOException because the channel is closed
        assertThrows(IOException.class, writer::finish,
                "finish() should throw IOException when channel is closed");

        // Second call to finish() should throw IllegalStateException, NOT be allowed to proceed.
        // Before the fix, state remains OPEN after IOException, so the guard passes and
        // the method attempts to write again — corrupting the file layout.
        IllegalStateException ex = assertThrows(IllegalStateException.class, writer::finish,
                "finish() should be rejected after a prior IOException — state should not be OPEN");
        assertTrue(
                ex.getMessage().contains("finish") || ex.getMessage().contains("failed")
                        || ex.getMessage().contains("closed"),
                "exception should indicate finish is no longer possible, got: " + ex.getMessage());
    }

    // Finding: F-R1.contract_boundaries.01.01
    // Bug: writeFooterV5 has no producer-side invariant guard on blockCount / mapLength /
    // entryCount / blockSize — a writer in a bad state (e.g. blockCount=0 or mapLength=0) will
    // ship a footer that violates R17/R18. Detection is delegated to the reader, so a malformed
    // SSTable can be produced, uploaded, or transferred before the consumer-side integrity check
    // runs.
    // Correct behavior: writeFooterV5 must validate producer state before writing any bytes to
    // the channel. Invalid blockCount (<1), mapLength (<1), entryCount (<1), or blockSize (not
    // power-of-two in [MIN..MAX]) must throw IllegalStateException WITHOUT writing bytes.
    // Fix location: TrieSSTableWriter.writeFooterV5 (~line 665-681) — add runtime checks on
    // self.blockCount, self.entryCount, self.blockSize and the mapLength parameter before
    // constructing the V5Footer / writing to the channel.
    // Regression watch: legitimate v5 writes (blockCount>=1, mapLength>=1, entryCount>=1) must
    // still produce a valid footer; the existing V5 writer test suite must still pass.
    // @spec sstable.end-to-end-integrity.R55
    @Test
    void test_writeFooterV5_contract_boundaries_rejects_zero_blockCount_before_write()
            throws Exception {
        // Construct a v5 writer and append one entry so entryCount/blockSize are legitimate.
        Path file = tempDir.resolve("bad-blockcount.sst");
        TrieSSTableWriter writer = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(file)
                .bloomFactory(n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate()).build();

        MemorySegment key = Arena.ofAuto().allocate(1);
        key.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x01);
        MemorySegment value = Arena.ofAuto().allocate(1);
        value.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x02);
        writer.append(new Entry.Put(key, value, new SequenceNumber(1L)));

        // Bloom filter is created inside finish() before writeFooterV5, but writeFooterV5 itself
        // only reads writer state fields. Stamp blockCount to an invariant-violating 0 before
        // invoking writeFooterV5 directly.
        Field blockCountField = TrieSSTableWriter.class.getDeclaredField("blockCount");
        blockCountField.setAccessible(true);
        blockCountField.setInt(writer, 0);

        // Record channel write position BEFORE the invocation so we can detect any stray bytes
        // that escaped to disk.
        Field channelField = TrieSSTableWriter.class.getDeclaredField("channel");
        channelField.setAccessible(true);
        SeekableByteChannel channel = (SeekableByteChannel) channelField.get(writer);
        long positionBefore = channel.position();

        // Invoke writeFooterV5 with otherwise-legal parameters; the only invariant violation is
        // blockCount=0 (violates R17). A correct producer-side guard must reject this state and
        // NOT emit the footer bytes.
        java.lang.reflect.Method writeFooterV5 = TrieSSTableWriter.class.getDeclaredMethod(
                "writeFooterV5", long.class, long.class, long.class, long.class, int.class,
                long.class, long.class, int.class, long.class, long.class, int.class, int.class);
        writeFooterV5.setAccessible(true);

        Throwable cause = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> writeFooterV5.invoke(writer, 0L, 16L, 0L, 0L, 0, 16L, 16L, 0, 32L, 16L, 0, 0),
                "writeFooterV5 must reject state with blockCount=0 (R17 violation) before writing")
                .getCause();
        assertTrue(cause instanceof IllegalStateException,
                "expected IllegalStateException from producer-side invariant guard, got: "
                        + (cause == null ? "null"
                                : cause.getClass().getName() + ": " + cause.getMessage()));
        assertTrue(
                cause.getMessage() != null && (cause.getMessage().contains("blockCount")
                        || cause.getMessage().contains("R17")),
                "exception should identify the violated invariant (blockCount / R17), got: "
                        + cause.getMessage());

        // Critical: no bytes should have been written to the channel. The producer contract is
        // that a malformed footer must not reach the on-disk surface where a consumer or a
        // remote backend could observe it.
        long positionAfter = channel.position();
        assertEquals(positionBefore, positionAfter,
                "writeFooterV5 must not write any bytes when producer-state invariants fail; "
                        + "bytes leaked: " + (positionAfter - positionBefore));

        // Cleanup — close the channel so @TempDir can clean up (we bypassed the writer's close
        // lifecycle via reflection).
        channel.close();
    }

    // Finding: F-R1.contract_boundaries.01.02
    // Bug: validateTightPacking does not verify the data-region lower bound — a footer with
    // mapOffset=0 claims the compression map starts at file offset 0, overlapping the data
    // region (which per R37 section ordering must occupy [0, mapOffset)). The current validator
    // only walks adjacent sorted sections, so a single-byte attack that sets mapOffset=0
    // escapes detection and surfaces later as an opaque downstream decoder error.
    // Correct behavior: validateTightPacking must reject a footer whose first sorted section
    // starts at offset 0 (i.e. mapOffset == 0 when map is the first section) by returning the
    // offending section name — per R37 the data region [0, mapOffset) must be non-empty and
    // sections must be contiguous with it.
    // Fix location: V5Footer.validateTightPacking (~line 158-185) — after sorting sections,
    // check that sections.get(0).offset > 0 and return the offending name if not.
    // Regression watch: typicalFooter (mapOffset=4096) and all other existing valid packings
    // must still return null; gap/overlap diagnostics must still surface their original names.
    @Test
    void test_validateTightPacking_contract_boundaries_rejects_mapOffset_zero() {
        // Craft a footer with mapOffset = 0. The compression map claims to start at the very
        // first byte of the file, overlapping the data region. All other fields are internally
        // consistent: the map ends at offset 1024, idx at 1536, flt at 1792 — adjacent-section
        // walk alone would report no violation.
        V5Footer footer = new V5Footer(/* mapOffset */ 0L, /* mapLength */ 1024L,
                /* dictOffset */ 0L, /* dictLength */ 0L, /* idxOffset */ 1024L,
                /* idxLength */ 512L, /* fltOffset */ 1536L, /* fltLength */ 256L,
                /* entryCount */ 42L, /* blockSize */ 1024L, /* blockCount */ 3,
                /* mapChecksum */ 0xCAFEBABE, /* dictChecksum */ 0, /* idxChecksum */ 0xDEADBEEF,
                /* fltChecksum */ 0x11223344, /* footerChecksum */ 0,
                /* magic */ SSTableFormat.MAGIC_V5);

        String violating = V5Footer.validateTightPacking(footer);
        assertNotNull(violating,
                "validateTightPacking must reject mapOffset=0 — the first section cannot start "
                        + "at file offset 0 because the data region must precede it per R37");
        assertEquals(CorruptSectionException.SECTION_COMPRESSION_MAP, violating,
                "violating section should be identified as the compression map (first section "
                        + "claiming offset 0), got: " + violating);
    }

    // Finding: F-R1.contract_boundaries.03.01
    // Bug: RecoveryScanIterator.readBlockAtCursor has an else branch that silently parses the
    // raw payload as an EntryCodec stream WITHOUT per-block CRC32C verification when
    // compressionMap is null or blocksWalked >= compressionMap.blockCount(). This is
    // unreachable under current v5-only invariants, but the path is not self-enforcing —
    // if a future regression (removed v5-only guard, divergent blockCount, null
    // compressionMap) reaches it, corrupt blocks would be accepted without the
    // CorruptBlockException that R10 mandates.
    // Correct behavior: readBlockAtCursor must refuse to parse a block without CRC verification.
    // A defensive runtime guard (IllegalStateException or equivalent) at the else branch
    // must reject the state and propagate so recovery-scan callers see the violation
    // instead of silently consuming un-verified payload.
    // Fix location: TrieSSTableReader.RecoveryScanIterator.readBlockAtCursor (else branch near
    // line 816-818) — replace the silent parseDecompressedBlockEntries(payload) with a
    // runtime check that throws IllegalStateException (wrapped via the iterator's IOException
    // path) identifying the broken invariant.
    // Regression watch: normal v5 recovery-scan (where the invariant holds) must still succeed;
    // the guard must only fire when the invariant is actually violated.
    // @spec sstable.end-to-end-integrity.R56
    @Test
    void test_readBlockAtCursor_contract_boundaries_rejects_missing_compressionMap_without_crc()
            throws Exception {
        // Step 1: write a valid v5 SSTable with enough data for at least one block.
        Path file = tempDir.resolve("recovery-missing-compmap.sst");
        try (var writer = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(file)
                .bloomFactory(n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate()).build()) {
            MemorySegment key = Arena.ofAuto().allocate(4);
            key.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x01);
            key.set(ValueLayout.JAVA_BYTE, 1, (byte) 0x02);
            key.set(ValueLayout.JAVA_BYTE, 2, (byte) 0x03);
            key.set(ValueLayout.JAVA_BYTE, 3, (byte) 0x04);
            MemorySegment value = Arena.ofAuto().allocate(4);
            value.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x10);
            value.set(ValueLayout.JAVA_BYTE, 1, (byte) 0x20);
            value.set(ValueLayout.JAVA_BYTE, 2, (byte) 0x30);
            value.set(ValueLayout.JAVA_BYTE, 3, (byte) 0x40);
            writer.append(new Entry.Put(key, value, new SequenceNumber(1L)));
            writer.finish();
        }

        // Step 2: open it as a v5 reader.
        TrieSSTableReader reader = TrieSSTableReader.open(file,
                jlsm.bloom.blocked.BlockedBloomFilter.deserializer(), null,
                CompressionCodec.deflate());
        try {
            // Step 3: simulate the forbidden invariant violation by clearing the compressionMap
            // field via reflection. In a legitimate v5 reader this must never happen — but the
            // readBlockAtCursor else branch *today* would silently parse the raw on-disk payload
            // without a CRC check if it did. The defensive guard must reject this state.
            Field compressionMapField = TrieSSTableReader.class.getDeclaredField("compressionMap");
            compressionMapField.setAccessible(true);
            compressionMapField.set(reader, null);

            // Step 4: run recovery-scan. The iterator's ctor (advance() -> readBlockAtCursor)
            // is where the defensive guard fires. Without the fix, readBlockAtCursor's else
            // branch silently parses the raw payload and returns a parsed Entry. With the fix,
            // the guard surfaces an exception — either directly out of recoveryScan() (when the
            // ctor's first advance() trips the guard) or out of hasNext() on a later iteration.
            Throwable thrown = assertThrows(Throwable.class, () -> {
                @SuppressWarnings("resource")
                java.util.Iterator<Entry> it = reader.recoveryScan();
                // Drain until the guard fires (or exhaustion — which would be the un-fixed bug).
                while (it.hasNext()) {
                    it.next();
                }
            }, "recovery-scan must refuse to parse blocks when the CRC-verification path "
                    + "cannot run — silent fallback to un-verified parsing violates R10");
            // Accept either IllegalStateException or an IOException that wraps one — the fix
            // may route through the iterator's sneakyThrow / advance() IOException path.
            boolean acceptable = (thrown instanceof IllegalStateException)
                    || (thrown instanceof IOException);
            assertTrue(acceptable,
                    "expected IllegalStateException or IOException identifying the broken "
                            + "invariant, got: " + thrown.getClass().getName() + ": "
                            + thrown.getMessage());
            // The message should mention the violated invariant so operators can diagnose.
            String msg = thrown.getMessage() == null ? "" : thrown.getMessage().toLowerCase();
            assertTrue(msg.contains("compressionmap") || msg.contains("compression map")
                    || msg.contains("crc") || msg.contains("invariant") || msg.contains("recovery"),
                    "exception message should identify the broken CRC/compressionMap invariant, "
                            + "got: " + thrown.getMessage());
        } finally {
            reader.close();
        }
    }

    // Finding: F-R1.contract_boundaries.1.001
    // Bug: V6Footer.encodeScopeSection reads sortedDekVersions twice — once at line 86 for
    // validation, again at lines 114-116 for encoding — without snapshotting. A caller
    // that mutates the array between the two reads (single-threaded via a callback in
    // the validation path or, as exercised here, directly observable across the
    // validation→encoding transition) can produce a footer whose CRC32C is computed
    // over corrupt (non-ascending or non-positive) DEK versions even though validation
    // passed.
    // Correct behavior: encodeScopeSection must operate on a snapshot of sortedDekVersions
    // captured at method entry; the encoded bytes must always satisfy R3a/R3b regardless
    // of caller-side mutation timing. Equivalently: a caller cannot influence the
    // encoded output by mutating the array reference after encodeScopeSection has
    // accepted it for processing.
    // Fix location: V6Footer.encodeScopeSection (modules/jlsm-core/.../V6Footer.java:78-122)
    // — clone the array on entry, validate and encode from the clone.
    // Regression watch: ensure normal (non-concurrent) encode/decode round-trip still works,
    // and that legitimate IAE for invalid input arrays still fires before the snapshot
    // defence affects callers.
    // @spec sstable.footer-encryption-scope.R3a
    // @spec sstable.footer-encryption-scope.R3b
    @Test
    @Timeout(60)
    void test_V6Footer_encodeScopeSection_contract_boundaries_caller_mutation_window_yields_corrupt_footer()
            throws Exception {
        final TableScope scope = new TableScope(new TenantId("t"), new DomainId("d"),
                new TableId("tbl"));
        // Strictly ascending, positive — passes validation as-is. Use a longer array so the
        // encoder loop spans more wall-clock time, broadening the race window.
        final int n = 64;
        final int[] versions = new int[n];
        for (int i = 0; i < n; i++) {
            versions[i] = i + 1;
        }
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(
                false);
        // Mutator: cycle between (a) hold a poison value (-1) at versions[n-1] for a brief
        // window so the encoder loop's tail iteration has a real chance to read it, and
        // (b) restore the legitimate value so the next call's validation pass succeeds.
        // Two-phase hold prevents the mutator from being a no-op race that the encoder
        // never observes.
        final Thread mutator = new Thread(() -> {
            while (!stop.get()) {
                versions[n - 1] = -1; // poison
                // Spin briefly — long enough to span an encode call's tail iteration but
                // short enough to allow many test iterations.
                for (int k = 0; k < 50 && !stop.get(); k++) {
                    Thread.onSpinWait();
                }
                versions[n - 1] = n; // restore
                for (int k = 0; k < 50 && !stop.get(); k++) {
                    Thread.onSpinWait();
                }
            }
        }, "v6footer-mutator");
        mutator.setDaemon(true);
        mutator.start();
        try {
            // Run many encode-then-parse iterations. With the snapshot fix every parse
            // succeeds because the encoded bytes always reflect the entry-time copy.
            // Without the fix, the encoded CRC will sometimes cover a -1 in the tail,
            // and parse() throws IOException citing R3b ("non-positive") — failing
            // this test.
            final int iterations = 1_000_000;
            for (int i = 0; i < iterations; i++) {
                // Skip iterations whose validation pass would itself trip the
                // -1 — the bug is only observable when validation passes (caller
                // sees no error) yet encoding emits the poisoned value.
                byte[] encoded;
                try {
                    encoded = V6Footer.encodeScopeSection(scope, versions);
                } catch (IllegalArgumentException expected) {
                    // Mutator caught the validation read — caller-visible failure,
                    // not a TOCTOU. Continue.
                    continue;
                }
                // parseInternal verifies CRC32C, then walks DEK versions enforcing
                // R3a/R3b. If a mutation slipped between validation (line 86) and
                // encoding (lines 114-116), parse() throws IOException identifying
                // the violated invariant.
                V6Footer.parse(encoded, encoded.length);
            }
        } finally {
            stop.set(true);
            mutator.join();
        }
    }

    // Finding: F-R1.contract_boundaries.1.002
    // Bug: V6Footer.Parsed canonical constructor accepts any Set without defensive copy or
    // unmodifiable wrap. A caller (parseInternal, materialiseDekVersionSet, future tests/mocks
    // or refactors) can construct Parsed with a mutable HashSet they later mutate. The
    // accessor dekVersionSet() returns the live mutable reference, so R3e's "materialised at
    // footer-parse time" guarantee — and the dispatch-side membership pre-check that depends
    // on a stable snapshot — is broken.
    // Correct behavior: Parsed's compact constructor must take an unmodifiable snapshot of
    // dekVersionSet (e.g. Set.copyOf(...)) so that mutations of the source set after
    // construction do not affect the contents observed via Parsed.dekVersionSet().
    // Fix location: V6Footer.Parsed (modules/jlsm-core/.../V6Footer.java:151) — add a compact
    // constructor that wraps dekVersionSet via Set.copyOf and null-checks all components.
    // Regression watch: parseInternal and materialiseDekVersionSet must still produce a Parsed
    // whose dekVersionSet contains exactly the parsed versions; encode/decode round trips
    // must still succeed; existing v6 footer tests must still pass.
    // @spec sstable.footer-encryption-scope.R3e
    @Test
    void test_V6Footer_Parsed_contract_boundaries_mutable_set_mutation_visible_to_consumers() {
        // Construct a Parsed with a HashSet the caller still holds a reference to.
        TableScope scope = new TableScope(new TenantId("t"), new DomainId("d"), new TableId("tbl"));
        Set<Integer> source = new HashSet<>();
        source.add(1);
        source.add(2);
        source.add(3);
        V6Footer.Parsed parsed = new V6Footer.Parsed(scope, source, 100L);

        // Snapshot the size and a known member at construction.
        int sizeAtConstruction = parsed.dekVersionSet().size();
        boolean containsTwoAtConstruction = parsed.dekVersionSet().contains(2);
        assertEquals(3, sizeAtConstruction);
        assertTrue(containsTwoAtConstruction);

        // Caller mutates the source set after Parsed has been constructed. With the defensive
        // copy in place, parsed.dekVersionSet() must continue to reflect the snapshot taken
        // at construction time (R3e materialised-at-parse-time invariant).
        source.remove(2);
        source.add(999);

        // The parsed view must NOT see the post-construction mutations.
        assertEquals(sizeAtConstruction, parsed.dekVersionSet().size(),
                "Parsed.dekVersionSet() must be a snapshot — size changed after source "
                        + "mutation, indicating R3e dispatch invariant is broken");
        assertTrue(parsed.dekVersionSet().contains(2),
                "Parsed.dekVersionSet() must still contain version 2 after source removed it; "
                        + "live mutable reference defeats R3e dispatch-side pre-check");
        assertTrue(!parsed.dekVersionSet().contains(999),
                "Parsed.dekVersionSet() must not contain version 999 added to source after "
                        + "construction; live mutable reference defeats R3e snapshot guarantee");

        // The returned Set must itself be unmodifiable so a downstream consumer cannot mutate
        // the parse-time snapshot either.
        assertThrows(UnsupportedOperationException.class, () -> parsed.dekVersionSet().add(42),
                "Parsed.dekVersionSet() must return an unmodifiable view — direct mutation by "
                        + "a downstream consumer must be rejected to preserve R3e's snapshot");
    }

    // Finding: F-R1.contract_boundaries.3.5
    // Bug: TrieSSTableReader holds a populated ReadContext (constructed at openWithExpectedScope
    // line 365 from the v6 footer's DEK-version set) but no production caller in this cluster
    // wires it into the deserialize path. The producer side (TrieSSTableReader) materialises the
    // Set<Integer> for hot-path R3e dispatch checks but the consumer side
    // (MemorySerializer<T>.deserialize) is never invoked with an overload that consumes that
    // context — the boundary is structurally severed.
    // Correct behavior: TrieSSTableReader must expose a typed get/scan API that threads its
    // own readContext() into the MemorySerializer's (segment, ReadContext) overload, so the
    // R3e gate is reachable from the SSTable read path. A test asserting the reader invokes
    // serializer.deserialize(seg, ctx) — not serializer.deserialize(seg) — when reading via
    // the typed API proves the bridge is wired.
    // Fix location: TrieSSTableReader — add a public typed get(MemorySegment key,
    // MemorySerializer<V> serializer) that deserialises via the (segment, ReadContext)
    // overload, passing this.readContext.
    // Regression watch: existing single-arg get(MemorySegment) must still return the raw
    // Optional<Entry> unchanged; the new typed overload must NOT alter the on-disk format or
    // the legacy read path; non-encrypted MemorySerializer implementations (no override of
    // the default (seg, ctx) overload) must still produce the same value as the single-arg
    // path (the default delegates back to deserialize(segment)).
    // @spec sstable.footer-encryption-scope.R3e
    // @spec sstable.footer-encryption-scope.R3f
    @Test
    void test_TrieSSTableReader_contract_boundaries_typedGet_threadsReadContextToDeserialize()
            throws IOException {
        // Step 1: write a v6 SSTable with a populated dekVersionSet so the reader's ReadContext
        // is non-empty and observable in the serializer dispatch.
        TableScope scope = new TableScope(new TenantId("tenantA"), new DomainId("domainX"),
                new TableId("table1"));
        int[] dekVersions = new int[]{ 42 };
        Path file = tempDir.resolve("v6-typed-get.sst");
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(7L).level(Level.L0).path(file)
                .bloomFactory(n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.none()).scope(scope).dekVersions(dekVersions).build()) {
            MemorySegment key = MemorySegment.ofArray(new byte[]{ 'k' });
            MemorySegment value = MemorySegment.ofArray(new byte[]{ 'v' });
            w.append(new Entry.Put(key, value, new SequenceNumber(1L)));
            w.finish();
        }

        // Step 2: open the v6 reader with the matching expected scope; readContext() must
        // expose dekVersionSet={42}.
        try (TrieSSTableReader reader = TrieSSTableReader.open(file,
                jlsm.bloom.blocked.BlockedBloomFilter.deserializer(),
                java.util.Optional.of(scope))) {
            assertEquals(Set.of(42), reader.readContext().allowedDekVersions(),
                    "precondition: reader's ReadContext must reflect the v6 footer's "
                            + "DEK-version set");

            // Step 3: build a recording MemorySerializer that distinguishes which deserialize
            // overload was called. The (segment, ReadContext) override captures the context
            // it was invoked with; the single-arg overload records that it was hit instead.
            final java.util.concurrent.atomic.AtomicReference<ReadContext> observedCtx = new java.util.concurrent.atomic.AtomicReference<>(
                    null);
            final java.util.concurrent.atomic.AtomicBoolean singleArgHit = new java.util.concurrent.atomic.AtomicBoolean(
                    false);
            MemorySerializer<String> recording = new MemorySerializer<String>() {
                @Override
                public MemorySegment serialize(String value) {
                    return MemorySegment
                            .ofArray(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

                @Override
                public String deserialize(MemorySegment segment) {
                    singleArgHit.set(true);
                    byte[] copy = new byte[(int) segment.byteSize()];
                    MemorySegment.copy(segment, 0, MemorySegment.ofArray(copy), 0,
                            segment.byteSize());
                    return new String(copy, java.nio.charset.StandardCharsets.UTF_8);
                }

                @Override
                public String deserialize(MemorySegment segment, ReadContext readContext) {
                    observedCtx.set(readContext);
                    byte[] copy = new byte[(int) segment.byteSize()];
                    MemorySegment.copy(segment, 0, MemorySegment.ofArray(copy), 0,
                            segment.byteSize());
                    return new String(copy, java.nio.charset.StandardCharsets.UTF_8);
                }
            };

            // Step 4: invoke the typed get. Before the fix, no such overload exists on
            // TrieSSTableReader (compile failure). With the fix, it must dispatch to
            // deserialize(segment, ReadContext) and pass this.readContext.
            java.util.Optional<String> result = reader.get(MemorySegment.ofArray(new byte[]{ 'k' }),
                    recording);

            // Step 5: assert the bridge is wired.
            assertTrue(result.isPresent(), "typed get must surface the value");
            assertEquals("v", result.get(), "typed get must return the deserialised value");
            assertFalse(singleArgHit.get(),
                    "typed get must NOT dispatch to deserialize(MemorySegment) — that path "
                            + "loses the ReadContext and the R3e gate becomes unreachable");
            ReadContext seen = observedCtx.get();
            assertNotNull(seen, "typed get must invoke deserialize(MemorySegment, ReadContext)");
            assertSame(reader.readContext(), seen,
                    "typed get must thread the reader's own ReadContext (same instance) into "
                            + "the deserializer to preserve the materialised-at-parse-time "
                            + "snapshot guarantee (R3e)");
            assertEquals(Set.of(42), seen.allowedDekVersions(),
                    "the threaded ReadContext must carry the footer's DEK-version set so the "
                            + "consumer-side R3e gate has the data it needs");
        }
    }

}
