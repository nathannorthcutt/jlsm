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
import java.nio.file.Path;
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
            public byte[] compress(byte[] input, int offset, int length) {
                // Should never be used — this codec is only passed to the reader
                throw new UnsupportedOperationException("malicious compress");
            }

            @Override
            public byte[] decompress(byte[] input, int offset, int length, int uncompressedLength) {
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

}
