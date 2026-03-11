package jlsm.wal.local;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWriteAheadLogTest {

    @TempDir
    Path dir;

    private static MemorySegment seg(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment out = Arena.ofAuto().allocate(bytes.length);
        MemorySegment.copy(bytes, 0, out, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return out;
    }

    private static String str(MemorySegment s) {
        return new String(s.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Builder validation
    // -------------------------------------------------------------------------

    @Test
    void rejectsNullDirectory() {
        assertThrows(NullPointerException.class,
                () -> LocalWriteAheadLog.builder().directory(null).build());
    }

    @Test
    void rejectsNegativeSegmentSize() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalWriteAheadLog.builder().directory(dir).segmentSize(-1).build());
    }

    @Test
    void rejectsZeroSegmentSize() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalWriteAheadLog.builder().directory(dir).segmentSize(0).build());
    }

    // -------------------------------------------------------------------------
    // append — basic
    // -------------------------------------------------------------------------

    @Test
    void appendPutReturnsIncreasingSequenceNumbers() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            SequenceNumber s1 = wal
                    .append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            SequenceNumber s2 = wal
                    .append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
            assertTrue(s2.value() > s1.value());
        }
    }

    @Test
    void appendDeleteReturnsSequenceNumber() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            SequenceNumber s = wal.append(new Entry.Delete(seg("key"), SequenceNumber.ZERO));
            assertTrue(s.value() > 0);
        }
    }

    @Test
    void appendNullThrowsNPE() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            assertThrows(NullPointerException.class, () -> wal.append(null));
        }
    }

    // -------------------------------------------------------------------------
    // replay
    // -------------------------------------------------------------------------

    @Test
    void replayZeroReturnsAllEntries() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            wal.append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
            wal.append(new Entry.Delete(seg("k1"), SequenceNumber.ZERO));

            Iterator<Entry> it = wal.replay(SequenceNumber.ZERO);
            List<Entry> entries = new ArrayList<>();
            it.forEachRemaining(entries::add);
            assertEquals(3, entries.size());
        }
    }

    @Test
    void replayPreservesKeyAndValue() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("myKey"), seg("myValue"), SequenceNumber.ZERO));

            Iterator<Entry> it = wal.replay(SequenceNumber.ZERO);
            assertTrue(it.hasNext());
            Entry entry = it.next();
            assertInstanceOf(Entry.Put.class, entry);
            assertEquals("myKey", str(entry.key()));
            assertEquals("myValue", str(((Entry.Put) entry).value()));
        }
    }

    @Test
    void replayFromSkipsEarlierEntries() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            SequenceNumber second = wal
                    .append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
            wal.append(new Entry.Put(seg("k3"), seg("v3"), SequenceNumber.ZERO));

            Iterator<Entry> it = wal.replay(second);
            List<Entry> entries = new ArrayList<>();
            it.forEachRemaining(entries::add);
            assertEquals(2, entries.size());
            assertTrue(entries.stream().allMatch(e -> e.sequenceNumber().compareTo(second) >= 0));
        }
    }

    @Test
    void replayBeyondLastReturnsEmpty() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("k"), seg("v"), SequenceNumber.ZERO));
            SequenceNumber beyond = new SequenceNumber(Long.MAX_VALUE);
            Iterator<Entry> it = wal.replay(beyond);
            assertFalse(it.hasNext());
        }
    }

    @Test
    void replayNullThrowsNPE() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            assertThrows(NullPointerException.class, () -> wal.replay(null));
        }
    }

    // -------------------------------------------------------------------------
    // lastSequenceNumber
    // -------------------------------------------------------------------------

    @Test
    void lastSequenceNumberIsZeroOnEmptyLog() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            assertEquals(SequenceNumber.ZERO, wal.lastSequenceNumber());
        }
    }

    @Test
    void lastSequenceNumberReflectsLastAppend() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            SequenceNumber last = wal
                    .append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
            assertEquals(last, wal.lastSequenceNumber());
        }
    }

    // -------------------------------------------------------------------------
    // Crash recovery / reopen
    // -------------------------------------------------------------------------

    @Test
    void reopenResumesSequenceNumbers() throws IOException {
        SequenceNumber lastBeforeClose;
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            lastBeforeClose = wal.append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
        }

        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            SequenceNumber next = wal
                    .append(new Entry.Put(seg("k3"), seg("v3"), SequenceNumber.ZERO));
            assertTrue(next.value() > lastBeforeClose.value(),
                    "sequence number after reopen must exceed last before close");
        }
    }

    @Test
    void reopenReadsAllPreviousEntries() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            wal.append(new Entry.Delete(seg("k2"), SequenceNumber.ZERO));
        }

        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(2, entries.size());
        }
    }

    @Test
    void partialLastRecordIsSkippedOnReopen(@TempDir Path tempDir) throws IOException {
        long segSize = 4096; // small segment for this test
        Path segFile;
        try (var wal = LocalWriteAheadLog.builder().directory(tempDir).segmentSize(segSize)
                .build()) {
            wal.append(new Entry.Put(seg("good"), seg("record"), SequenceNumber.ZERO));
            // Find the segment file to corrupt
            segFile = Files.list(tempDir).filter(p -> p.getFileName().toString().startsWith("wal-"))
                    .findFirst().orElseThrow();
        }

        // Corrupt the end of the file (simulate partial write)
        byte[] content = Files.readAllBytes(segFile);
        // Append some garbage bytes to simulate a partial record
        byte[] corrupted = new byte[content.length + 20];
        System.arraycopy(content, 0, corrupted, 0, content.length);
        corrupted[content.length] = (byte) 0x00;
        corrupted[content.length + 1] = (byte) 0x00;
        corrupted[content.length + 2] = (byte) 0x01;
        corrupted[content.length + 3] = (byte) 0x00; // frameContentLen = 256, but only 16 bytes
                                                     // follow
        Files.write(segFile, corrupted);

        // Reopen should skip the partial record
        try (var wal = LocalWriteAheadLog.builder().directory(tempDir).segmentSize(segSize)
                .build()) {
            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            // Should only see the one good record
            assertEquals(1, entries.size());
        }
    }

    // -------------------------------------------------------------------------
    // Segment rollover
    // -------------------------------------------------------------------------

    @Test
    void appendPastSegmentCapacityCreatesNewSegmentFile() throws IOException {
        // Very small segment so rollover happens quickly
        long segSize = 256;
        try (var wal = LocalWriteAheadLog.builder().directory(dir).segmentSize(segSize)
                .bufferSize(1024).build()) {
            // Write enough entries to force a rollover
            for (int i = 0; i < 20; i++) {
                wal.append(new Entry.Put(seg("key-" + i), seg("value-" + i), SequenceNumber.ZERO));
            }
            long walFileCount = Files.list(dir)
                    .filter(p -> p.getFileName().toString().startsWith("wal-")).count();
            assertTrue(walFileCount > 1, "expected multiple segment files after rollover");
        }
    }

    @Test
    void replayAcrossMultipleSegments() throws IOException {
        long segSize = 256;
        int count = 20;
        try (var wal = LocalWriteAheadLog.builder().directory(dir).segmentSize(segSize)
                .bufferSize(1024).build()) {
            for (int i = 0; i < count; i++) {
                wal.append(new Entry.Put(seg("k" + i), seg("v" + i), SequenceNumber.ZERO));
            }

            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(count, entries.size());
        }
    }

    // -------------------------------------------------------------------------
    // truncateBefore
    // -------------------------------------------------------------------------

    @Test
    void truncateBeforeDeletesObsoleteSegments() throws IOException {
        long segSize = 256;
        List<SequenceNumber> seqs = new ArrayList<>();
        try (var wal = LocalWriteAheadLog.builder().directory(dir).segmentSize(segSize)
                .bufferSize(1024).build()) {
            for (int i = 0; i < 20; i++) {
                seqs.add(wal.append(new Entry.Put(seg("k" + i), seg("v"), SequenceNumber.ZERO)));
            }
            long beforeTruncate = Files.list(dir)
                    .filter(p -> p.getFileName().toString().startsWith("wal-")).count();
            assertTrue(beforeTruncate > 1);

            // Truncate before the last sequence number
            SequenceNumber upTo = seqs.get(seqs.size() - 1);
            wal.truncateBefore(upTo);

            long afterTruncate = Files.list(dir)
                    .filter(p -> p.getFileName().toString().startsWith("wal-")).count();
            assertTrue(afterTruncate < beforeTruncate, "some segments should have been deleted");

            // Remaining entries still readable
            List<Entry> remaining = new ArrayList<>();
            wal.replay(upTo).forEachRemaining(remaining::add);
            assertFalse(remaining.isEmpty());
        }
    }

    @Test
    void truncateBeforeNullThrowsNPE() throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(dir).build()) {
            assertThrows(NullPointerException.class, () -> wal.truncateBefore(null));
        }
    }

    // -------------------------------------------------------------------------
    // Concurrent appends
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // close() resource cleanup (Violation 3)
    // These tests verify that close() leaves WAL segment files intact and
    // readable after normal shutdown, serving as regression guards for the
    // deferred-exception pattern in close().
    // -------------------------------------------------------------------------

    /**
     * Verifies that after normal close(), the WAL segment file(s) remain present on disk. If
     * close() silently swallowed an exception and failed to flush or sync, the file might be
     * truncated or absent. The deferred-exception fix ensures both channel and memory-mapped
     * segment are released even if one throws.
     */
    @Test
    void closeAfterAppendsLeavesSegmentFilesOnDisk(@TempDir Path walDir) throws IOException {
        try (var wal = LocalWriteAheadLog.builder().directory(walDir).build()) {
            wal.append(new Entry.Put(seg("key1"), seg("value1"), SequenceNumber.ZERO));
            wal.append(new Entry.Put(seg("key2"), seg("value2"), SequenceNumber.ZERO));
            wal.append(new Entry.Delete(seg("key3"), SequenceNumber.ZERO));
        } // close() called here

        long segFileCount = Files.list(walDir)
                .filter(p -> p.getFileName().toString().startsWith("wal-")).count();
        assertTrue(segFileCount >= 1,
                "at least one WAL segment file must remain on disk after close()");
    }

    /**
     * Verifies that a WAL reopened after normal close() can replay all entries that were appended
     * before close(). This confirms that close() did not silently discard buffered or uncommitted
     * data, which would be a symptom of a botched multi-resource close.
     */
    @Test
    void closeFlushesAllEntriesReadableOnReopen(@TempDir Path walDir) throws IOException {
        final int numEntries = 10;
        try (var wal = LocalWriteAheadLog.builder().directory(walDir).build()) {
            for (int i = 0; i < numEntries; i++) {
                wal.append(new Entry.Put(seg("k" + i), seg("v" + i), SequenceNumber.ZERO));
            }
        } // close() called here

        // Reopen and verify all entries are still present
        try (var wal = LocalWriteAheadLog.builder().directory(walDir).build()) {
            List<Entry> replayed = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(replayed::add);
            assertEquals(numEntries, replayed.size(), "all " + numEntries
                    + " entries written before close() must be readable on reopen; "
                    + "a botched close() that suppressed an fsync exception would lose data");
        }
    }

    /**
     * Verifies that close() does not throw an exception under normal conditions (no forced
     * failures). This is the baseline case for the deferred-exception pattern: when neither force()
     * nor channel.close() fails, close() must complete silently.
     */
    @Test
    void closeDoesNotThrowUnderNormalConditions(@TempDir Path walDir) throws IOException {
        var wal = LocalWriteAheadLog.builder().directory(walDir).build();
        wal.append(new Entry.Put(seg("key"), seg("val"), SequenceNumber.ZERO));
        // Must not throw
        assertDoesNotThrow(wal::close,
                "close() must not throw when both force() and channel.close() succeed");
    }

    @Test
    void concurrentAppendsProduceUniqueSequenceNumbers() throws Exception {
        int threads = 4;
        int appendsPerThread = 50;
        CopyOnWriteArrayList<SequenceNumber> allSeqs = new CopyOnWriteArrayList<>();

        try (var wal = LocalWriteAheadLog.builder().directory(dir).bufferSize(4096).build()) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                int tid = t;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < appendsPerThread; i++) {
                            SequenceNumber seq = wal.append(new Entry.Put(seg("t" + tid + "k" + i),
                                    seg("v"), SequenceNumber.ZERO));
                            allSeqs.add(seq);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> f : futures)
                f.get();
            pool.shutdown();
        }

        int expected = threads * appendsPerThread;
        assertEquals(expected, allSeqs.size());
        // All sequence numbers must be unique
        long distinct = allSeqs.stream().map(SequenceNumber::value).distinct().count();
        assertEquals(expected, distinct, "duplicate sequence numbers detected");
    }
}
