package jlsm.remote;

import jlsm.core.model.Entry;
import jlsm.core.model.SequenceNumber;
import jlsm.wal.remote.RemoteWriteAheadLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link RemoteWriteAheadLog} running against an in-process S3Proxy.
 * Mirrors the functional tests in the unit test suite but uses S3-backed paths.
 */
@ExtendWith(S3Fixture.class)
class RemoteWalS3Test {

    private Path dir;

    @BeforeEach
    void setup(S3Fixture fixture) {
        dir = fixture.newTestDirectory();
    }

    private static MemorySegment seg(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment out = Arena.ofAuto().allocate(bytes.length);
        MemorySegment.copy(bytes, 0, out, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return out;
    }

    private static String str(MemorySegment s) {
        return new String(s.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private RemoteWriteAheadLog wal() throws IOException {
        return RemoteWriteAheadLog.builder().directory(dir).build();
    }

    // ---- append ----

    @Test
    void appendPutReturnsIncreasingSequenceNumbers() throws IOException {
        try (var w = wal()) {
            SequenceNumber s1 = w.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            SequenceNumber s2 = w.append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
            assertTrue(s2.value() > s1.value());
        }
    }

    @Test
    void appendDeleteReturnsSequenceNumber() throws IOException {
        try (var w = wal()) {
            SequenceNumber s = w.append(new Entry.Delete(seg("key"), SequenceNumber.ZERO));
            assertTrue(s.value() > 0);
        }
    }

    @Test
    void appendNullThrowsNPE() throws IOException {
        try (var w = wal()) {
            assertThrows(NullPointerException.class, () -> w.append(null));
        }
    }

    // ---- replay ----

    @Test
    void replayZeroReturnsAllEntries() throws IOException {
        try (var w = wal()) {
            w.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            w.append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
            w.append(new Entry.Delete(seg("k1"), SequenceNumber.ZERO));

            Iterator<Entry> it = w.replay(SequenceNumber.ZERO);
            List<Entry> entries = new ArrayList<>();
            it.forEachRemaining(entries::add);
            assertEquals(3, entries.size());
        }
    }

    @Test
    void replayPreservesKeyAndValue() throws IOException {
        try (var w = wal()) {
            w.append(new Entry.Put(seg("myKey"), seg("myValue"), SequenceNumber.ZERO));

            Iterator<Entry> it = w.replay(SequenceNumber.ZERO);
            assertTrue(it.hasNext());
            Entry entry = it.next();
            assertInstanceOf(Entry.Put.class, entry);
            assertEquals("myKey", str(entry.key()));
            assertEquals("myValue", str(((Entry.Put) entry).value()));
        }
    }

    @Test
    void replayFromSkipsEarlierEntries() throws IOException {
        try (var w = wal()) {
            w.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            SequenceNumber second = w.append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
            w.append(new Entry.Put(seg("k3"), seg("v3"), SequenceNumber.ZERO));

            Iterator<Entry> it = w.replay(second);
            List<Entry> entries = new ArrayList<>();
            it.forEachRemaining(entries::add);
            assertEquals(2, entries.size());
            assertTrue(entries.stream().allMatch(e -> e.sequenceNumber().compareTo(second) >= 0));
        }
    }

    @Test
    void replayBeyondLastReturnsEmpty() throws IOException {
        try (var w = wal()) {
            w.append(new Entry.Put(seg("k"), seg("v"), SequenceNumber.ZERO));
            Iterator<Entry> it = w.replay(new SequenceNumber(Long.MAX_VALUE));
            assertFalse(it.hasNext());
        }
    }

    @Test
    void replayNullThrowsNPE() throws IOException {
        try (var w = wal()) {
            assertThrows(NullPointerException.class, () -> w.replay(null));
        }
    }

    // ---- lastSequenceNumber ----

    @Test
    void lastSequenceNumberIsZeroOnEmptyLog() throws IOException {
        try (var w = wal()) {
            assertEquals(SequenceNumber.ZERO, w.lastSequenceNumber());
        }
    }

    @Test
    void lastSequenceNumberReflectsLastAppend() throws IOException {
        try (var w = wal()) {
            w.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            SequenceNumber last = w.append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
            assertEquals(last, w.lastSequenceNumber());
        }
    }

    // ---- reopen / recovery ----

    @Test
    void reopenResumesSequenceNumbers() throws IOException {
        SequenceNumber lastBeforeClose;
        try (var w = wal()) {
            w.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            lastBeforeClose = w.append(new Entry.Put(seg("k2"), seg("v2"), SequenceNumber.ZERO));
        }

        try (var w = wal()) {
            SequenceNumber next = w.append(new Entry.Put(seg("k3"), seg("v3"), SequenceNumber.ZERO));
            assertTrue(next.value() > lastBeforeClose.value());
        }
    }

    @Test
    void reopenReadsAllPreviousEntries() throws IOException {
        try (var w = wal()) {
            w.append(new Entry.Put(seg("k1"), seg("v1"), SequenceNumber.ZERO));
            w.append(new Entry.Delete(seg("k2"), SequenceNumber.ZERO));
        }

        try (var w = wal()) {
            List<Entry> entries = new ArrayList<>();
            w.replay(SequenceNumber.ZERO).forEachRemaining(entries::add);
            assertEquals(2, entries.size());
        }
    }

    // ---- truncateBefore ----

    @Test
    void truncateBeforeDeletesFilesBeforeThreshold() throws IOException {
        List<SequenceNumber> seqs = new ArrayList<>();
        try (var w = wal()) {
            for (int i = 0; i < 5; i++) {
                seqs.add(w.append(new Entry.Put(seg("k" + i), seg("v"), SequenceNumber.ZERO)));
            }
            SequenceNumber upTo = seqs.get(3);
            w.truncateBefore(upTo);

            // After truncation, replay from upTo should return exactly 2 entries
            List<Entry> remaining = new ArrayList<>();
            w.replay(upTo).forEachRemaining(remaining::add);
            assertEquals(2, remaining.size());
            assertTrue(remaining.stream().allMatch(e -> e.sequenceNumber().compareTo(upTo) >= 0));
        }
    }

    @Test
    void truncateBeforeNullThrowsNPE() throws IOException {
        try (var w = wal()) {
            assertThrows(NullPointerException.class, () -> w.truncateBefore(null));
        }
    }

    // ---- latency baseline (informational only — always passes) ----

    @Test
    void latencyBaseline_tenAppends() throws IOException {
        int count = 10;
        try (var w = wal()) {
            long start = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                w.append(new Entry.Put(seg("k" + i), seg("value-" + i), SequenceNumber.ZERO));
            }
            long elapsed = System.currentTimeMillis() - start;
            System.out.printf("[S3 WAL latency] %d appends in %d ms (%.1f ms/op)%n",
                    count, elapsed, (double) elapsed / count);
        }
        // Assert nothing — this is informational output only
    }
}
