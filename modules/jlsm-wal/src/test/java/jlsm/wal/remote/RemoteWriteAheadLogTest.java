package jlsm.wal.remote;

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

class RemoteWriteAheadLogTest {

    @TempDir Path dir;

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
                () -> RemoteWriteAheadLog.builder().directory(null).build());
    }

    // -------------------------------------------------------------------------
    // append — basic
    // -------------------------------------------------------------------------

    @Test
    void appendPutReturnsIncreasingSequenceNumbers() throws IOException {
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            SequenceNumber s1 = wal.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            SequenceNumber s2 = wal.append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
            assertTrue(s2.value() > s1.value());
        }
    }

    @Test
    void appendCreatesExactlyOneFilePerCall() throws IOException {
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            int count = 5;
            for (int i = 0; i < count; i++) {
                wal.append(new Entry.Put(seg("k" + i), seg("v" + i), SequenceNumber.ZERO));
            }
            long walFiles = Files.list(dir).filter(p -> p.getFileName().toString().startsWith("wal-")).count();
            assertEquals(count, walFiles, "each append must create exactly one file");
        }
    }

    @Test
    void appendDeleteReturnsSequenceNumber() throws IOException {
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            SequenceNumber s = wal.append(new Entry.Delete(seg("key"), SequenceNumber.ZERO));
            assertTrue(s.value() > 0);
        }
    }

    @Test
    void appendNullThrowsNPE() throws IOException {
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            assertThrows(NullPointerException.class, () -> wal.append(null));
        }
    }

    // -------------------------------------------------------------------------
    // replay
    // -------------------------------------------------------------------------

    @Test
    void replayZeroReturnsAllEntries() throws IOException {
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
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
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
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
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            SequenceNumber second = wal.append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
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
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("k"), seg("v"), SequenceNumber.ZERO));
            SequenceNumber beyond = new SequenceNumber(Long.MAX_VALUE);
            Iterator<Entry> it = wal.replay(beyond);
            assertFalse(it.hasNext());
        }
    }

    @Test
    void replayNullThrowsNPE() throws IOException {
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            assertThrows(NullPointerException.class, () -> wal.replay(null));
        }
    }

    // -------------------------------------------------------------------------
    // lastSequenceNumber
    // -------------------------------------------------------------------------

    @Test
    void lastSequenceNumberIsZeroOnEmptyLog() throws IOException {
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            assertEquals(SequenceNumber.ZERO, wal.lastSequenceNumber());
        }
    }

    @Test
    void lastSequenceNumberReflectsLastAppend() throws IOException {
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            SequenceNumber last = wal.append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
            assertEquals(last, wal.lastSequenceNumber());
        }
    }

    // -------------------------------------------------------------------------
    // Crash recovery / reopen
    // -------------------------------------------------------------------------

    @Test
    void reopenResumesSequenceNumbers() throws IOException {
        SequenceNumber lastBeforeClose;
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            lastBeforeClose = wal.append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
        }

        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            SequenceNumber next = wal.append(new Entry.Put(seg("k3"), seg("v3"), SequenceNumber.ZERO));
            assertTrue(next.value() > lastBeforeClose.value());
        }
    }

    @Test
    void reopenReadsAllPreviousEntries() throws IOException {
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            wal.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            wal.append(new Entry.Delete(seg("k2"), SequenceNumber.ZERO));
        }

        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            List<Entry> entries = new ArrayList<>();
            wal.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(2, entries.size());
        }
    }

    // -------------------------------------------------------------------------
    // truncateBefore
    // -------------------------------------------------------------------------

    @Test
    void truncateBeforeDeletesFilesBeforeThreshold() throws IOException {
        List<SequenceNumber> seqs = new ArrayList<>();
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            for (int i = 0; i < 5; i++) {
                seqs.add(wal.append(new Entry.Put(seg("k" + i), seg("v"), SequenceNumber.ZERO)));
            }

            SequenceNumber upTo = seqs.get(3); // delete files with seqnum < seqs[3]
            wal.truncateBefore(upTo);

            long remaining = Files.list(dir).filter(p -> p.getFileName().toString().startsWith("wal-")).count();
            // Files with seqnum >= upTo.value() should remain
            assertEquals(seqs.size() - 3, remaining,
                    "files with seqnum < upTo should be deleted");
        }
    }

    @Test
    void truncateBeforeNullThrowsNPE() throws IOException {
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            assertThrows(NullPointerException.class, () -> wal.truncateBefore(null));
        }
    }

    @Test
    void afterTruncateRemainingEntriesStillReadable() throws IOException {
        List<SequenceNumber> seqs = new ArrayList<>();
        try (var wal = RemoteWriteAheadLog.builder().directory(dir).build()) {
            for (int i = 0; i < 5; i++) {
                seqs.add(wal.append(new Entry.Put(seg("k" + i), seg("v" + i), SequenceNumber.ZERO)));
            }
            SequenceNumber upTo = seqs.get(2);
            wal.truncateBefore(upTo);

            List<Entry> remaining = new ArrayList<>();
            wal.replay(upTo).forEachRemaining(remaining::add);
            assertFalse(remaining.isEmpty());
            assertTrue(remaining.stream().allMatch(e -> e.sequenceNumber().compareTo(upTo) >= 0));
        }
    }

    // -------------------------------------------------------------------------
    // Concurrent appends
    // -------------------------------------------------------------------------

    @Test
    void concurrentAppendsProduceUniqueSequenceNumbers() throws Exception {
        int threads = 4;
        int appendsPerThread = 50;
        CopyOnWriteArrayList<SequenceNumber> allSeqs = new CopyOnWriteArrayList<>();

        try (var wal = RemoteWriteAheadLog.builder()
                .directory(dir)
                .poolSize(threads)
                .bufferSize(4096)
                .build()) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                int tid = t;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < appendsPerThread; i++) {
                            SequenceNumber seq = wal.append(
                                    new Entry.Put(seg("t" + tid + "k" + i), seg("v"), SequenceNumber.ZERO));
                            allSeqs.add(seq);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> f : futures) f.get();
            pool.shutdown();
        }

        int expected = threads * appendsPerThread;
        assertEquals(expected, allSeqs.size());
        long distinct = allSeqs.stream().map(SequenceNumber::value).distinct().count();
        assertEquals(expected, distinct, "duplicate sequence numbers detected");

        // Each append creates exactly one file
        long fileCount = Files.list(dir).filter(p -> p.getFileName().toString().startsWith("wal-")).count();
        assertEquals(expected, fileCount, "each append should create exactly one file");
    }
}
