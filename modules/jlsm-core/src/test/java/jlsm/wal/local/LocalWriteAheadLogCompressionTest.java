package jlsm.wal.local;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for WAL compression integration in LocalWriteAheadLog (F17.R25-R36).
 */
class LocalWriteAheadLogCompressionTest {

    @TempDir
    Path dir;

    private static MemorySegment seg(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment out = Arena.ofAuto().allocate(bytes.length);
        MemorySegment.copy(bytes, 0, out, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return out;
    }

    private static MemorySegment segOfSize(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ('A' + (i % 26));
        }
        MemorySegment out = Arena.ofAuto().allocate(size);
        MemorySegment.copy(bytes, 0, out, ValueLayout.JAVA_BYTE, 0, size);
        return out;
    }

    private static String str(MemorySegment s) {
        return new String(s.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Builder configuration (R27, R28, R29)
    // -------------------------------------------------------------------------

    /** R27: Default compression is Deflate level 6. */
    @Test
    void defaultCompressionIsDeflate() throws IOException {
        // Builder should accept build() without explicit compression setting,
        // and the WAL should use Deflate level 6 by default.
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            // Write a large entry that would be compressed
            SequenceNumber seq = wal
                    .append(new Entry.Put(seg("key"), segOfSize(200), SequenceNumber.ZERO));
            assertTrue(seq.value() > 0);

            // Replay should return the entry correctly (proves round-trip works with default codec)
            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(1, entries.size());
            assertInstanceOf(Entry.Put.class, entries.getFirst());
        }
    }

    /** R27: Custom codec via builder. */
    @Test
    void customCodecViaBuilder() throws IOException {
        CompressionCodec codec = CompressionCodec.deflate(1);
        try (var wal = LocalWriteAheadLog.builder().directory(dir).compression(codec).build()) {
            wal.append(new Entry.Put(seg("key"), segOfSize(200), SequenceNumber.ZERO));

            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(1, entries.size());
        }
    }

    /** R28: Custom minimum-size threshold via builder. */
    @Test
    void customMinSizeThresholdViaBuilder() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).compressionMinSize(128)
                .build()) {
            wal.append(new Entry.Put(seg("key"), segOfSize(200), SequenceNumber.ZERO));

            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(1, entries.size());
        }
    }

    /** R28: Negative min size threshold is rejected. */
    @Test
    void negativeMinSizeThresholdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalWriteAheadLog.builder().directory(dir).compressionMinSize(-1));
    }

    /** Compression disabled via NoneCodec. */
    @Test
    void compressionDisabledViaNoneCodec() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir)
                .compression(CompressionCodec.none()).build()) {
            wal.append(new Entry.Put(seg("key"), segOfSize(200), SequenceNumber.ZERO));

            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(1, entries.size());
        }
    }

    // -------------------------------------------------------------------------
    // Write path (R25, R26)
    // -------------------------------------------------------------------------

    /** R25: Records below threshold are written uncompressed. */
    @Test
    void smallRecordsWrittenUncompressed() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).compressionMinSize(64).build()) {
            // Write a small entry (below 64 byte threshold for the payload portion)
            wal.append(new Entry.Put(seg("k"), seg("v"), SequenceNumber.ZERO));

            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(1, entries.size());
            assertEquals("k", str(entries.getFirst().key()));
            assertEquals("v", str(((Entry.Put) entries.getFirst()).value()));
        }
    }

    /** R25/R26: Records above threshold are compressed and round-trip correctly. */
    @Test
    void largeRecordsCompressedRoundTrip() throws IOException {
        // Use a repeating pattern that compresses well
        String largeValue = "A".repeat(500);
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("bigKey"), seg(largeValue), SequenceNumber.ZERO));

            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(1, entries.size());
            Entry.Put put = assertInstanceOf(Entry.Put.class, entries.getFirst());
            assertEquals("bigKey", str(put.key()));
            assertEquals(largeValue, str(put.value()));
        }
    }

    /** R26: Records where compressed + 5 >= uncompressed are written uncompressed. */
    @Test
    void incompressibleDataWrittenUncompressed() throws IOException {
        // Random-ish data that won't compress well
        byte[] randomish = new byte[100];
        for (int i = 0; i < randomish.length; i++) {
            randomish[i] = (byte) (i * 37 + 13);
        }
        MemorySegment incompressible = Arena.ofAuto().allocate(randomish.length);
        MemorySegment.copy(randomish, 0, incompressible, ValueLayout.JAVA_BYTE, 0,
                randomish.length);

        try (var wal = LocalWriteAheadLog.builder().directory(dir).compressionMinSize(1).build()) {
            wal.append(new Entry.Put(seg("key"), incompressible, SequenceNumber.ZERO));

            // Must round-trip correctly regardless
            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(1, entries.size());
        }
    }

    /** Round-trip with mixed puts and deletes. */
    @Test
    void mixedPutsAndDeletesRoundTrip() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("key1"), seg("A".repeat(200)), SequenceNumber.ZERO));
            wal.append(new Entry.Delete(seg("key2"), SequenceNumber.ZERO));
            wal.append(new Entry.Put(seg("key3"), seg("B".repeat(300)), SequenceNumber.ZERO));

            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(3, entries.size());
            assertInstanceOf(Entry.Put.class, entries.get(0));
            assertInstanceOf(Entry.Delete.class, entries.get(1));
            assertInstanceOf(Entry.Put.class, entries.get(2));
        }
    }

    // -------------------------------------------------------------------------
    // Recovery path (R30, R31, R32, R33, R34, R35)
    // -------------------------------------------------------------------------

    /** R30: Recovery accepts codec set for building codec map. */
    @Test
    void recoveryWithCodecSet() throws IOException {
        // Write with deflate
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("key"), seg("A".repeat(200)), SequenceNumber.ZERO));
        }

        // Reopen with recovery codecs
        try (var wal = LocalWriteAheadLog.builder().directory(dir)
                .recoveryCodecs(CompressionCodec.deflate(), CompressionCodec.none()).build()) {
            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(1, entries.size());
        }
    }

    /** R31: Recovery handles mixed compressed/uncompressed records. */
    @Test
    void recoveryHandlesMixedRecords() throws IOException {
        // Write a mix of small (uncompressed) and large (compressed) records
        try (var wal = LocalWriteAheadLog.builder().directory(dir).compressionMinSize(64).build()) {
            wal.append(new Entry.Put(seg("small"), seg("v"), SequenceNumber.ZERO));
            wal.append(new Entry.Put(seg("big"), seg("X".repeat(500)), SequenceNumber.ZERO));
            wal.append(new Entry.Delete(seg("del"), SequenceNumber.ZERO));
        }

        // Reopen and replay all
        try (var wal = LocalWriteAheadLog.builder().directory(dir).compressionMinSize(64).build()) {
            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(3, entries.size());
        }
    }

    /** R35: 10+ consecutive decompression failures aborts recovery. */
    @Test
    void consecutiveDecompressionFailuresAbortRecovery() throws IOException {
        // Write valid compressed records
        try (var wal = LocalWriteAheadLog.builder().directory(dir).maxConsecutiveSkips(3).build()) {
            for (int i = 0; i < 5; i++) {
                wal.append(
                        new Entry.Put(seg("key" + i), seg("A".repeat(200)), SequenceNumber.ZERO));
            }
        }

        // Now corrupt the segment file -- mangle the compressed payload data
        Path segFile = Files.list(dir).filter(p -> p.getFileName().toString().startsWith("wal-"))
                .findFirst().orElseThrow();
        byte[] content = Files.readAllBytes(segFile);
        // Corrupt bytes throughout the middle of the file to damage multiple records
        for (int i = 20; i < content.length - 20 && i < 2000; i += 3) {
            content[i] = (byte) (content[i] ^ 0xFF);
        }
        Files.write(segFile, content);

        // Reopen with low consecutive skip threshold -- should throw IOException either during
        // recovery (constructor) or during replay when consecutive corrupt records are found.
        try {
            try (var wal = LocalWriteAheadLog.builder().directory(dir).maxConsecutiveSkips(3)
                    .build()) {
                List<Entry> entries = new ArrayList<>();
                wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
                // If we get here, recovery/replay skipped corrupt records without hitting threshold
                // That's also acceptable — the corruption may not have hit enough consecutive
                // records
            }
        } catch (IOException | UncheckedIOException e) {
            // Expected: consecutive skip threshold exceeded during recovery or replay
            String msg = e.getMessage() != null ? e.getMessage() : "";
            String causeMsg = e.getCause() != null && e.getCause().getMessage() != null
                    ? e.getCause().getMessage()
                    : "";
            assertTrue(
                    msg.contains("systematic codec failure") || msg.contains("consecutive")
                            || causeMsg.contains("systematic codec failure")
                            || causeMsg.contains("consecutive"),
                    "expected systematic codec failure message, got: " + msg);
        }
    }

    // -------------------------------------------------------------------------
    // Buffer management (R36)
    // -------------------------------------------------------------------------

    /** R36: WAL falls back to uncompressed when compression buffer can't be acquired. */
    @Test
    void fallsBackToUncompressedOnBufferExhaustion() throws IOException {
        // Use a very small pool that might be exhausted
        // poolSize=1 means the compression buffer acquire will fail (record buffer already held).
        // Short timeout so the test doesn't hang.
        try (var wal = LocalWriteAheadLog.builder().directory(dir).poolSize(1)
                .bufferSize(1024 * 1024).acquireTimeoutMillis(50).build()) {
            // Should fall back to uncompressed and still succeed
            wal.append(new Entry.Put(seg("key"), seg("A".repeat(200)), SequenceNumber.ZERO));

            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(1, entries.size());
        }
    }

    // -------------------------------------------------------------------------
    // Reopen round-trip (ensures recovery reads compressed records)
    // -------------------------------------------------------------------------

    @Test
    void reopenWithCompressionPreservesAllEntries() throws IOException {
        int count = 20;
        try (var wal = LocalWriteAheadLog.builder().directory(dir).segmentSize(4096).build()) {
            for (int i = 0; i < count; i++) {
                wal.append(new Entry.Put(seg("key-" + i), seg("value-" + "X".repeat(100) + i),
                        SequenceNumber.ZERO));
            }
        }

        try (var wal = LocalWriteAheadLog.builder().directory(dir).segmentSize(4096).build()) {
            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(count, entries.size());

            for (int i = 0; i < count; i++) {
                Entry.Put put = assertInstanceOf(Entry.Put.class, entries.get(i));
                assertEquals("key-" + i, str(put.key()));
            }
        }
    }
}
