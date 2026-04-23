package jlsm.sstable;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for dispatch routing in SSTable reader/writer.
 */
class DispatchRoutingAdversarialTest {

    @TempDir
    Path tempDir;

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    /**
     * Writes a v2 SSTable with deflate compression and returns the path.
     */
    private Path writeV2SSTable(List<Entry> entries, CompressionCodec codec) throws IOException {
        Path file = tempDir.resolve("test.sst");
        try (var writer = new TrieSSTableWriter(1L, new Level(0), file,
                n -> new jlsm.bloom.blocked.BlockedBloomFilter(n, 0.01), codec)) {
            for (Entry entry : entries) {
                writer.append(entry);
            }
            writer.finish();
        }
        return file;
    }

    // Finding: F-R1.dispatch_routing.1.1
    // Bug: Custom codec with codecId 0x00 silently replaces NoneCodec in dispatch table
    // Correct behavior: buildCodecMap should reject user-supplied codecs with codecId 0x00
    // (the NoneCodec ID) because the writer's incompressible fallback always stores raw
    // blocks with codecId 0x00, and overwriting NoneCodec would corrupt those blocks.
    // Fix location: TrieSSTableReader.buildCodecMap (line ~415)
    // Regression watch: Ensure NoneCodec is always preserved in the codec map regardless
    // of user-supplied codecs.
    @Test
    void test_buildCodecMap_dispatch_routing_custom_codec_overwrites_none() throws IOException {
        // Write a v2 SSTable with deflate — some blocks may fall back to NoneCodec (codecId 0x00)
        // when the compressed output is not smaller than the input.
        List<Entry> entries = List.of(put("apple", "red", 1), put("banana", "yellow", 2),
                put("cherry", "dark-red", 3));
        Path file = writeV2SSTable(entries, CompressionCodec.deflate());

        // Create a malicious codec that claims codecId 0x00
        CompressionCodec maliciousCodec = new CompressionCodec() {
            @Override
            public byte codecId() {
                return 0x00;
            }

            @Override
            public java.lang.foreign.MemorySegment compress(java.lang.foreign.MemorySegment src,
                    java.lang.foreign.MemorySegment dst) {
                // Should never be used — this codec is only passed to the reader
                throw new UnsupportedOperationException("malicious compress");
            }

            @Override
            public java.lang.foreign.MemorySegment decompress(java.lang.foreign.MemorySegment src,
                    java.lang.foreign.MemorySegment dst, int uncompressedLength) {
                // If this codec replaces NoneCodec, it will corrupt raw uncompressed blocks
                throw new UnsupportedOperationException("malicious decompress");
            }
        };

        // Attempting to open with a codec whose ID collides with NoneCodec (0x00)
        // should be rejected — not silently allowed.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TrieSSTableReader.open(file, BlockedBloomFilter.deserializer(), null,
                        maliciousCodec));
        assertTrue(ex.getMessage().contains("0x00") || ex.getMessage().contains("NoneCodec"),
                "exception should mention the conflicting codec ID or NoneCodec, got: "
                        + ex.getMessage());
    }

    // Finding: F-R1.dispatch_routing.1.4
    // Bug: scan(fromKey, toKey) returns an IndexRangeIterator whose constructor pre-fetches
    // the first entry via advance(). If the reader is closed after scan() but before
    // the first next() call, hasNext() still returns true — the closed state is not
    // checked in hasNext(), only in advance(). A caller checking hasNext() on a closed
    // reader gets a stale "true" for the pre-fetched entry.
    // Correct behavior: hasNext() should return false when the reader is closed.
    // Fix location: TrieSSTableReader.IndexRangeIterator.hasNext() (~line 892)
    // Regression watch: Ensure the closed check does not interfere with normal iteration
    // when the reader is open.
    @Test
    void test_IndexRangeIterator_dispatch_routing_closed_reader_hasNext_returns_true()
            throws IOException {
        // Write a v2 SSTable with enough entries to ensure scan(from, to) returns results
        List<Entry> entries = List.of(put("alpha", "v1", 1), put("bravo", "v2", 2),
                put("charlie", "v3", 3), put("delta", "v4", 4));
        Path file = writeV2SSTable(entries, CompressionCodec.deflate());

        // Open the reader, get a range scan iterator, then close the reader before iterating
        try (var reader = TrieSSTableReader.open(file, BlockedBloomFilter.deserializer(), null,
                CompressionCodec.deflate())) {
            Iterator<Entry> iter = reader.scan(seg("alpha"), seg("delta"));
            // Close the reader while the iterator holds a pre-fetched entry
            reader.close();

            // The iterator pre-fetched the first entry in its constructor.
            // hasNext() should return false because the reader is closed —
            // iterating a closed reader is invalid regardless of pre-fetched state.
            assertFalse(iter.hasNext(), "hasNext() should return false when the reader is closed, "
                    + "even when a pre-fetched entry exists");
        }
    }

    /**
     * Writes a small v5 SSTable (deflate codec, builder API) and returns the path.
     */
    private Path writeV5SSTable(String name) throws IOException {
        Path file = tempDir.resolve(name);
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(file)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate()).build()) {
            for (int i = 0; i < 10; i++) {
                w.append(put(String.format("k-%03d", i), "v-" + i, i + 1));
            }
            w.finish();
        }
        return file;
    }

    /**
     * Writes a legacy v1 SSTable (no codec) and returns the path. v1 files have a 48-byte footer
     * ending in MAGIC_V1 (0x4A4C534D53535401L, trailing byte 0x01).
     */
    private Path writeV1SSTable(String name) throws IOException {
        Path file = tempDir.resolve(name);
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, file,
                n -> new BlockedBloomFilter(n, 0.01))) {
            for (int i = 0; i < 10; i++) {
                w.append(put(String.format("k-%03d", i), "v-" + i, i + 1));
            }
            w.finish();
        }
        return file;
    }

    // Finding: F-R1.dispatch_routing.1.3
    // Bug: A legitimate legacy v1 SSTable whose trailing magic byte suffers a single-bit flip
    // from 0x01 (MAGIC_V1 LSB) to 0x03 (MAGIC_V3 LSB) bypasses format-specific guarantees
    // by routing into the v3 branch of readFooter. The v3 branch reads the last 72 bytes
    // and reinterprets them as a v3 footer — but those bytes are actually the v1 file's
    // bloom-filter tail + 48-byte v1 footer, not a real v3 footer. v3 has no footer
    // self-checksum (R26 applies only to v5), so the only accidental barriers are the
    // power-of-2 blockSize gate and Footer.validate(fileSize) overlap checks. If the
    // random bytes happen to satisfy those gates the file is silently opened with
    // version=3 and arbitrary section offsets, or if they fail they surface as generic
    // IOException rather than CorruptSectionException(footer) / IncompleteSSTable. R34's
    // implicit assumption ("each magic uniquely identifies a format") is violated when
    // single-bit corruption can cross between legacy magics (V1↔V3 distance 1, V2↔V3
    // distance 1). The suspect report notes this as a medium-severity finding.
    // Correct behavior: open must reject the corrupted file with either
    // CorruptSectionException(SECTION_FOOTER) or IncompleteSSTableException — consistent
    // with the F-R1.dispatch_routing.1.1 V5→V4 defence already in place. A successful
    // open or a generic IOException means the dispatch-corruption hazard has not been
    // addressed at this legacy boundary.
    // Fix location: TrieSSTableReader.readFooter (magic dispatch region, legacy branch entry)
    // Regression watch: a fix must still admit genuine v3 files (MAGIC_V3 + 72-byte footer
    // with the correct v3 field layout) without false-positive rejection, and must not
    // break the existing V5→V4 defence for finding 1.1.
    @Test
    void test_readFooter_dispatch_routing_v1_magic_flip_to_v3_bypasses_format_contract()
            throws IOException {
        // Write a genuine v1 SSTable (no codec → v1 format per writer constructor).
        // Its trailing byte is 0x01 (LSB of MAGIC_V1).
        Path file = writeV1SSTable("v1-magic-flip.sst");
        long fileSize = Files.size(file);

        // Pre-condition: confirm the trailing byte is 0x01 (LSB of MAGIC_V1).
        byte trailingBefore;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            ch.read(one, fileSize - 1);
            trailingBefore = one.get(0);
        }
        assertEquals((byte) 0x01, trailingBefore,
                "pre-condition: trailing byte of a v1 SSTable must be 0x01 (LSB of MAGIC_V1)");

        // Attack: flip 0x01 -> 0x03 in the trailing magic byte. This moves MAGIC_V1 to
        // MAGIC_V3 with a single-bit change (Hamming distance = 1, bit 1 flipped on).
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            one.put(0, (byte) 0x03);
            ch.write(one, fileSize - 1);
        }

        // Correct behavior: open must throw CorruptSectionException(footer) or
        // IncompleteSSTableException. Any other outcome (successful open, generic IOException
        // from a pseudo-random v3 reinterpretation of v1 bytes) means the legacy dispatch
        // boundary silently admitted a cross-version reinterpretation.
        IOException ex = assertThrows(IOException.class, () -> TrieSSTableReader.open(file,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate()));
        boolean detected = (ex instanceof CorruptSectionException cse
                && cse.sectionName().equals(CorruptSectionException.SECTION_FOOTER))
                || ex instanceof IncompleteSSTableException;
        assertTrue(detected,
                "R34/R26 defence-in-depth: a single-bit flip of the magic byte (0x01 -> 0x03) "
                        + "on a genuine v1 file must be detected as CorruptSection(footer) or "
                        + "IncompleteSSTable; dispatch into the v3 branch silently admitted a "
                        + "cross-version reinterpretation of legacy bytes. Got: "
                        + ex.getClass().getName() + ": " + ex.getMessage());
    }

    // Finding: F-R1.dispatch_routing.1.1
    // Bug: A legitimate v5 SSTable whose trailing magic LSB is flipped from 0x05 to 0x04
    // bypasses the v5 footer self-checksum (R26). The reader dispatches on the flipped
    // magic into the v4 branch, which has no footer self-checksum, and reinterprets the
    // last 88 bytes of the 112-byte v5 footer as a v4 footer. Accidental barriers
    // (blockSize power-of-2 gate, section overlap checks) may or may not trip, and when
    // they do they surface as generic IOException rather than CorruptSectionException —
    // so R26's promise ("v5 footer self-checksum verified before any other validation")
    // is silently bypassed whenever the magic discriminant suffers a single-bit flip.
    // Correct behavior: open must reject the corrupted file with either
    // CorruptSectionException(SECTION_FOOTER) or IncompleteSSTableException — the same
    // outcome the existing readerFlipBitInMagic test asserts for flips to non-LSB bytes.
    // A successful open (or any other exception type) means R26 was bypassed.
    // Fix location: TrieSSTableReader.readFooter (magic dispatch region, v5 branch entry)
    // Regression watch: a fix must still admit genuine v4 files (MAGIC_V4 + 88-byte footer
    // with the correct v4 field layout) without false-positive rejection.
    @Test
    void test_readFooter_dispatch_routing_v5_magic_lsb_flip_to_v4_bypasses_self_checksum()
            throws IOException {
        // Write a genuine v5 SSTable. Its trailing byte is 0x05 (LSB of MAGIC_V5).
        Path file = writeV5SSTable("v5-magic-lsb-flip.sst");
        long fileSize = Files.size(file);

        // Pre-condition: confirm the trailing byte is 0x05 (otherwise the attack description
        // is wrong and the test would be meaningless).
        byte trailingBefore;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            ch.read(one, fileSize - 1);
            trailingBefore = one.get(0);
        }
        assertEquals((byte) 0x05, trailingBefore,
                "pre-condition: trailing byte of a v5 SSTable must be 0x05 (LSB of MAGIC_V5)");

        // Attack: flip 0x05 -> 0x04 in the trailing magic byte. This moves MAGIC_V5 to
        // MAGIC_V4 with a single-bit change (Hamming distance = 1).
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            one.put(0, (byte) 0x04);
            ch.write(one, fileSize - 1);
        }

        // Correct behavior: open must throw CorruptSectionException(footer) or
        // IncompleteSSTableException. Any other outcome (successful open, generic IOException)
        // means the v5 self-checksum (R26) has been bypassed by dispatch routing.
        IOException ex = assertThrows(IOException.class, () -> TrieSSTableReader.open(file,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate()));
        boolean detected = (ex instanceof CorruptSectionException cse
                && cse.sectionName().equals(CorruptSectionException.SECTION_FOOTER))
                || ex instanceof IncompleteSSTableException;
        assertTrue(detected,
                "R26: a single-bit flip of the magic LSB (0x05 -> 0x04) on a genuine v5 file "
                        + "must be detected as CorruptSection(footer) or IncompleteSSTable; "
                        + "dispatch into the v4 branch silently bypassed the v5 self-checksum. "
                        + "Got: " + ex.getClass().getName() + ": " + ex.getMessage());
    }

    // Finding: F-R1.dispatch_routing.1.6
    // Bug: TrieSSTableReader.open/openLazy derive the format version purely from the file's own
    // trailing magic bytes. There is no cross-check against any external authority (manifest,
    // catalog, level metadata) that would bind the file to a known-expected version. When a
    // caller KNOWS the file should be v5 (for example, because the manifest recorded the
    // format version at write time) there is no API surface that lets the caller express
    // that expectation so the reader can reject a mismatch. The defence-in-depth
    // against dispatch-routing attacks from 1.1/1.2 — "require expected-version match" — is
    // structurally unavailable at this API (factories accept no expected-version parameter).
    // This leaves a residual risk: a genuine v4 (or v1..v3) file delivered in place of a v5
    // file still opens successfully, even though 1.1's speculative v5 self-checksum only
    // rejects CORRUPTED v5 files — it cannot reject genuine legacy files masquerading as
    // the intended v5 target because their self-checksum will not happen to match the
    // hypothetical intact MAGIC_V5 substitution (probability ~2^-32).
    // Correct behavior: the factory must expose a way for a caller to specify the required
    // format version, and must throw CorruptSectionException(SECTION_FOOTER) when the file
    // on disk reports a different version. Callers that do NOT specify an expected version
    // must retain the current auto-detect behavior (no regression).
    // Fix location: TrieSSTableReader.open (4-arg) / openLazy (4-arg) — add an overload that
    // accepts an expected version and enforces the cross-check after readFooter returns.
    // Regression watch: auto-detect callers must continue to work across v1..v5 without change;
    // a caller specifying expectedVersion=5 that opens a real v5 file must succeed.
    @Test
    void test_open_dispatch_routing_expected_version_mismatch_rejects_genuine_legacy_file()
            throws IOException {
        // Write a genuine v1 SSTable. No corruption applied — this is a legitimate legacy file
        // whose on-disk format is v1 (48-byte footer ending in MAGIC_V1).
        Path file = writeV1SSTable("v1-expected-v5.sst");

        // Caller knows (from external authority e.g. manifest) that this file SHOULD be v5.
        // Without an expected-version parameter the current API silently opens it as v1.
        // With the fix, the reader must reject the version mismatch.
        IOException ex = assertThrows(IOException.class,
                () -> TrieSSTableReader.open(file, BlockedBloomFilter.deserializer(), null,
                        /* expectedVersion */ 5, CompressionCodec.deflate()));
        assertTrue(
                ex instanceof CorruptSectionException cse
                        && cse.sectionName().equals(CorruptSectionException.SECTION_FOOTER),
                "expected CorruptSectionException(SECTION_FOOTER) for version mismatch; got: "
                        + ex.getClass().getName() + ": " + ex.getMessage());
        assertTrue(ex.getMessage().contains("version") || ex.getMessage().contains("expected"),
                "exception message should mention the expected vs actual version; got: "
                        + ex.getMessage());
    }

}
