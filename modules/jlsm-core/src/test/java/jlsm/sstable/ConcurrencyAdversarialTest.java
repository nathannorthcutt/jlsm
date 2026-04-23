package jlsm.sstable;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency adversarial tests for SSTable components.
 *
 * <p>
 * Note: {@link TrieSSTableWriter} is a single-threaded component by design. It has no
 * synchronization and is not intended to be used from multiple threads. Concurrency findings
 * against it are classified as IMPOSSIBLE.
 */
class ConcurrencyAdversarialTest {

    @TempDir
    Path tempDir;

    static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    // Finding: F-R1.concurrency.1.3
    // Bug: writeBytes() loops forever if channel.write() returns 0 without throwing
    // Correct behavior: throw IOException after bounded zero-progress attempts
    // Fix location: TrieSSTableWriter.writeBytes (lines 229-235)
    // Regression watch: ensure normal writes still complete — only zero-progress triggers the guard
    @Test
    void test_writeBytes_zeroProgressLoop_throwsAfterBoundedAttempts() throws Exception {
        Path sstPath = tempDir.resolve("test.sst");
        try (var writer = new TrieSSTableWriter(1L, new Level(0), sstPath)) {
            // Append one entry so the writer has data to flush
            writer.append(put("aaa", "value1", 1));

            // Replace the channel with one that returns 0 from write()
            Field channelField = TrieSSTableWriter.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            SeekableByteChannel realChannel = (SeekableByteChannel) channelField.get(writer);
            AtomicInteger writeCallCount = new AtomicInteger(0);
            SeekableByteChannel zeroChannel = new ZeroProgressChannelWrapper(realChannel,
                    writeCallCount);
            channelField.set(writer, zeroChannel);

            // finish() flushes the remaining block via writeBytes()
            // Without the fix, this hangs forever. With the fix, it throws IOException.
            IOException ex = assertThrows(IOException.class, writer::finish);
            assertTrue(ex.getMessage().contains("no progress"),
                    "Expected 'no progress' in message but got: " + ex.getMessage());
            // Verify write was called multiple times (bounded retry happened)
            assertTrue(writeCallCount.get() > 1,
                    "Expected multiple write attempts but got: " + writeCallCount.get());
        }
    }

    // Finding: F-R1.conc.2.2
    // Bug: TOCTOU between checkNotClosed() and channel I/O in get() — caller receives
    // ClosedChannelException instead of IllegalStateException("reader is closed")
    // Correct behavior: get() should throw IllegalStateException when the reader is closed
    // between checkNotClosed() and the actual channel read, not ClosedChannelException
    // Fix location: TrieSSTableReader — readBytes or get/readDataAtV1 I/O paths
    // Regression watch: ensure normal get() still works; only concurrent-close path changes
    @Test
    void test_get_closedBetweenCheckAndIO_throwsIllegalStateException() throws Exception {
        // Write a v1 SSTable that we can open lazily
        Path sstPath = tempDir.resolve("toctou.sst");
        try (var writer = new TrieSSTableWriter(1L, Level.L0, sstPath)) {
            writer.append(put("aaa", "value1", 1));
            writer.append(put("bbb", "value2", 2));
            writer.finish();
        }

        // Open a lazy reader (lazy uses lazyChannel for I/O)
        TrieSSTableReader reader = TrieSSTableReader.openLazy(sstPath,
                BlockedBloomFilter.deserializer());

        // Replace lazyChannel with a wrapper that pauses on position() so we can
        // close the reader between checkNotClosed() and the actual read
        CountDownLatch positionReached = new CountDownLatch(1);
        CountDownLatch proceedWithRead = new CountDownLatch(1);

        Field channelField = TrieSSTableReader.class.getDeclaredField("lazyChannel");
        channelField.setAccessible(true);
        SeekableByteChannel realChannel = (SeekableByteChannel) channelField.get(reader);
        SeekableByteChannel pausingChannel = new PausingChannelWrapper(realChannel, positionReached,
                proceedWithRead);
        channelField.set(reader, pausingChannel);

        AtomicReference<Throwable> getException = new AtomicReference<>();

        // Thread A: calls get() — will pass checkNotClosed(), then block inside readBytes
        // at the position() call in the pausing wrapper
        Thread getThread = Thread.ofVirtual().start(() -> {
            try {
                reader.get(seg("aaa"));
            } catch (Throwable t) {
                getException.set(t);
            }
        });

        // Wait until Thread A is inside readBytes (past checkNotClosed)
        positionReached.await();

        // Thread B: close the reader — sets closed=true and closes the real channel
        reader.close();

        // Let Thread A proceed — it will now try to use the closed channel
        proceedWithRead.countDown();

        getThread.join(5000);
        assertFalse(getThread.isAlive(), "get thread should have completed");

        // The bug: without the fix, this is a ClosedChannelException (or IOException wrapping it).
        // Correct behavior: IllegalStateException with "reader is closed" message.
        Throwable thrown = getException.get();
        assertNotNull(thrown, "get() should have thrown an exception after concurrent close");
        assertInstanceOf(IllegalStateException.class, thrown,
                "Expected IllegalStateException but got " + thrown.getClass().getName() + ": "
                        + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("reader is closed"),
                "Expected 'reader is closed' in message but got: " + thrown.getMessage());
    }

    // Finding: F-R1.concurrency.1.1
    // Bug: recoveryScan's check-then-act ({activeReaderOps.get()==0} at line 645, then
    // recoveryInProgress=true at line 651) is not atomic with acquireReaderSlot's
    // check-then-act (read recoveryInProgress at line 1161, increment at line 1164).
    // Two threads can interleave so both believe they hold the R38 mutex:
    // recoveryScan sees activeReaderOps==0, a reader reads recoveryInProgress==false,
    // the reader increments activeReaderOps to 1, and then recoveryScan acquires the
    // lock and sets recoveryInProgress=true. Both proceed — R38 is breached.
    // Correct behavior: once recoveryScan begins its mutex-claim sequence (i.e., once
    // recoveryLock is held by recoveryScan), no new reader slot may be acquired;
    // acquireReaderSlot must serialize with recoveryScan's claim such that a
    // reader blocks (or throws ISE) when recoveryLock is held, regardless of the
    // (non-atomically-coupled) recoveryInProgress volatile flag.
    // Fix location: TrieSSTableReader.acquireReaderSlot (1160-1169) + recoveryScan (645-651) —
    // both sides must use recoveryLock as the synchronizing primitive for their
    // check-and-modify sequence (e.g., acquireReaderSlot takes the lock around the
    // check+increment; recoveryScan does its active-reader check inside the lock).
    // Regression watch: concurrent get() calls must still proceed in parallel (no global
    // serialization); only the recoveryScan-vs-get boundary must serialize.
    @Test
    @Timeout(15)
    void test_acquireReaderSlot_respectsRecoveryLockDuringRecoveryClaim() throws Exception {
        // Write a minimal v5 SSTable
        Path sstPath = tempDir.resolve("race.sst");
        try (var writer = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(sstPath)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate()).blockSize(1024).build()) {
            for (int i = 0; i < 10; i++) {
                writer.append(new Entry.Put(
                        MemorySegment.ofArray(("k-" + i).getBytes(StandardCharsets.UTF_8)),
                        MemorySegment.ofArray(("v-" + i).getBytes(StandardCharsets.UTF_8)),
                        new SequenceNumber(i + 1L)));
            }
            writer.finish();
        }

        try (TrieSSTableReader reader = TrieSSTableReader.open(sstPath,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate())) {

            // Reflectively grab the reader's recoveryLock so the test can simulate the
            // window during which recoveryScan has claimed the lock but has not yet set
            // recoveryInProgress=true (i.e., lines 648-651 of recoveryScan, split open).
            Field lockField = TrieSSTableReader.class.getDeclaredField("recoveryLock");
            lockField.setAccessible(true);
            ReentrantLock recoveryLock = (ReentrantLock) lockField.get(reader);

            // Claim the recovery lock — representing recoveryScan being mid-sequence
            // between tryLock() (line 648) and recoveryInProgress=true (line 651).
            // The check-then-act race permits a reader to slip through during this
            // window. The correct behavior is that acquireReaderSlot must see this
            // claim and serialize with it: the reader must either block on the lock
            // or reject with IllegalStateException — it must NOT return a value.
            recoveryLock.lock();
            try {
                CountDownLatch getDone = new CountDownLatch(1);
                AtomicReference<Throwable> getError = new AtomicReference<>();
                AtomicReference<Object> getResult = new AtomicReference<>();

                Thread getThread = Thread.ofVirtual().start(() -> {
                    try {
                        var result = reader
                                .get(MemorySegment.ofArray("k-0".getBytes(StandardCharsets.UTF_8)));
                        getResult.set(result);
                    } catch (Throwable t) {
                        getError.set(t);
                    } finally {
                        getDone.countDown();
                    }
                });

                // Wait up to 3s for the get thread to either (a) complete with a value,
                // (b) throw ISE, or (c) still be blocked on the recovery lock.
                boolean terminated = getDone.await(3, TimeUnit.SECONDS);

                if (terminated) {
                    // Acceptable outcomes under the fix: the reader must NOT have
                    // successfully returned a value while recoveryLock was held.
                    // Either it threw ISE (recovery in progress) — OK — or it got a
                    // value — BUG: R38 breached.
                    Object result = getResult.get();
                    Throwable err = getError.get();
                    if (err == null) {
                        fail("R38 mutex: get() returned a value while recoveryLock was held "
                                + "(check-then-act race). Expected IllegalStateException or "
                                + "blocking behavior; got result=" + result);
                    }
                    assertInstanceOf(IllegalStateException.class, err,
                            "Expected IllegalStateException (recovery-scan in progress) but got "
                                    + err.getClass().getName() + ": " + err.getMessage());
                } else {
                    // Reader is blocked on the lock — the correct serializing behavior.
                    // Release the lock so the reader can proceed and terminate cleanly.
                }

                recoveryLock.unlock();
                try {
                    assertTrue(getDone.await(5, TimeUnit.SECONDS),
                            "get() should complete after recoveryLock is released");
                } finally {
                    // Re-acquire so the outer finally can unconditionally unlock.
                    recoveryLock.lock();
                }
            } finally {
                if (recoveryLock.isHeldByCurrentThread()) {
                    recoveryLock.unlock();
                }
            }
        }
    }

    // Finding: F-R1.concurrency.1.3
    // Bug: RecoveryScanIterator has no close()/AutoCloseable — a caller that abandons
    // the iterator mid-stream (e.g. an early-break from a for-each loop) never
    // triggers releaseOnceExhausted(). The recoveryLock remains held forever, and
    // any subsequent recoveryScan() on the same reader throws ISE permanently.
    // Correct behavior: the returned iterator must expose a close() method (via
    // AutoCloseable). Closing the iterator must release recoveryLock and clear
    // recoveryInProgress so a subsequent recoveryScan() on the same reader
    // succeeds without error.
    // Fix location: TrieSSTableReader.RecoveryScanIterator (672-840) — add
    // AutoCloseable with a close() that invokes releaseOnceExhausted(); widen
    // recoveryScan()'s return type to expose close() to callers.
    // Regression watch: existing callers that iterate to completion must keep working
    // (releaseOnceExhausted is still invoked on natural exhaustion); a second
    // close() call after natural exhaustion must be a no-op (idempotent via
    // released flag).
    // @spec sstable.end-to-end-integrity.R44
    @Test
    @Timeout(15)
    void test_recoveryScan_abandonedIterator_releaseViaClose_unblocksSubsequentScan()
            throws Exception {
        // Write a v5 SSTable with several blocks so the iterator has multiple entries.
        Path sstPath = tempDir.resolve("abandon.sst");
        try (var writer = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(sstPath)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate()).blockSize(1024).build()) {
            for (int i = 0; i < 20; i++) {
                writer.append(new Entry.Put(
                        MemorySegment.ofArray(
                                String.format("k-%03d", i).getBytes(StandardCharsets.UTF_8)),
                        MemorySegment.ofArray(("value-" + i).getBytes(StandardCharsets.UTF_8)),
                        new SequenceNumber(i + 1L)));
            }
            writer.finish();
        }

        try (TrieSSTableReader reader = TrieSSTableReader.open(sstPath,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate())) {

            // Simulate the abandoned-iterator scenario: request a recovery scan,
            // consume one entry, then close the iterator WITHOUT draining the rest.
            // Prior to the fix, the iterator had no close() method and the caller
            // had no way to release the recoveryLock without iterating to exhaustion.
            Iterator<Entry> first = reader.recoveryScan();
            assertTrue(first instanceof AutoCloseable,
                    "recoveryScan() iterator must implement AutoCloseable so callers can "
                            + "release the recovery lock without draining");
            assertTrue(first.hasNext(), "scan should produce at least one entry");
            first.next(); // consume exactly one — leave lock held under the buggy code

            // Release the lock via close() — the fix.
            ((AutoCloseable) first).close();

            // After close(), a fresh recoveryScan() on the same reader must succeed.
            // Under the bug (no close support), the lock is still held and this call
            // throws IllegalStateException("recovery-scan in progress").
            Iterator<Entry> second = reader.recoveryScan();
            try {
                int count = 0;
                while (second.hasNext()) {
                    second.next();
                    count++;
                }
                assertEquals(20, count,
                        "Second recoveryScan should iterate all 20 entries after the "
                                + "first iterator was abandoned and closed");
            } finally {
                if (second instanceof AutoCloseable ac) {
                    ac.close();
                }
            }

            // close() must be idempotent on an already-closed iterator (released flag).
            ((AutoCloseable) first).close();
        }
    }

    // Finding: F-R1.concurrency.1.4
    // Bug: checkNotFailed reads failureCause and failureSection as two separate volatile
    // reads. When a concurrent writer publishes the FAILED state in two stores (set
    // cause first, then section, or vice-versa), a reader observing the torn window
    // sees cause != null && section == null and produces an IllegalStateException
    // whose message reads "reader failed: null" — observability regression vs. R43's
    // expectation of a stable, populated section identifier in the diagnostic.
    // Correct behavior: checkNotFailed must never emit the literal string "null" in the
    // ISE message when failureCause is non-null. Either (a) publish both fields as a
    // single atomic record reference, or (b) substitute a non-null sentinel when
    // failureSection has not yet been published — both satisfy R43's diagnostic
    // contract that the originating-exception path produce a well-formed ISE.
    // Fix location: TrieSSTableReader.checkNotFailed (1177-1184). The fix must preserve
    // the existing behavior when both fields are populated (message reads "reader
    // failed: <section>"); only the torn-state window may change.
    // Regression watch: naturally-transitioned FAILED states (cause + section both set)
    // must continue to produce "reader failed: <section>" messages with the cause
    // chained. Cause chain must always be preserved.
    @Test
    @Timeout(15)
    void test_checkNotFailed_tornFailedStateRead_noNullInMessage() throws Exception {
        // Write a minimal v5 SSTable so we can open a reader and invoke get().
        Path sstPath = tempDir.resolve("torn-failed.sst");
        try (var writer = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(sstPath)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate()).blockSize(1024).build()) {
            writer.append(
                    new Entry.Put(MemorySegment.ofArray("aaa".getBytes(StandardCharsets.UTF_8)),
                            MemorySegment.ofArray("value".getBytes(StandardCharsets.UTF_8)),
                            new SequenceNumber(1L)));
            writer.finish();
        }

        try (TrieSSTableReader reader = TrieSSTableReader.open(sstPath,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate())) {

            // Simulate a torn write from a concurrent R43 FAILED-state publisher: the
            // writer set failureCause but has not yet published failureSection. Under the
            // non-atomic two-field read in checkNotFailed, a reader observes the torn
            // state and would render "reader failed: null" in the ISE message.
            Field causeField = TrieSSTableReader.class.getDeclaredField("failureCause");
            causeField.setAccessible(true);
            causeField.set(reader, new CorruptSectionException(CorruptSectionException.SECTION_DATA,
                    "simulated torn write"));
            // Deliberately leave failureSection null to model the mid-transition window.

            IllegalStateException ise = assertThrows(IllegalStateException.class,
                    () -> reader.get(seg("aaa")));

            assertNotNull(ise.getCause(), "cause chain must be preserved on FAILED read");
            assertInstanceOf(CorruptSectionException.class, ise.getCause(),
                    "cause must be the originating CorruptSectionException (R43)");
            // The diagnostic must not expose the torn-read "null" section — R43
            // requires a stable, non-null identifier on the FAILED path.
            assertFalse(ise.getMessage().contains("null"),
                    "ISE message must not contain literal 'null' during torn FAILED-state "
                            + "read; got: " + ise.getMessage());
        }
    }

    // Finding: F-R1.concurrency.1.5
    // Bug: RecoveryScanIterator.readAbs reads via readBytes(lazyChannel, ...) directly,
    // bypassing readLazyChannel's ClosedChannelException -> IllegalStateException
    // translation. When close() races with an in-flight recoveryScan, the raw
    // ClosedChannelException propagates out via sneakyThrow, leaking an I/O
    // artefact to the caller and violating R41/R43's normalized close-contract.
    // Correct behavior: a recoveryScan that races with close() must surface as
    // IllegalStateException("reader is closed") (or an equivalent ISE), never as
    // a raw ClosedChannelException. The translation pattern used by get()/scan()
    // must apply to recoveryScan's I/O as well.
    // Fix location: TrieSSTableReader.RecoveryScanIterator.readAbs (827-838) — route
    // lazy reads through readLazyChannel (or an equivalent close-translating
    // wrapper) so the reader-closed window produces ISE rather than raw CCE.
    // Regression watch: a recoveryScan on an un-closed reader must still succeed with
    // identical semantics (normal iteration, natural releaseOnceExhausted on
    // completion or close()).
    @Test
    @Timeout(15)
    void test_recoveryScan_closedDuringRead_throwsIllegalStateException() throws Exception {
        // Write a v5 SSTable with several blocks so recovery has genuine lazy I/O work.
        Path sstPath = tempDir.resolve("recovery-close-race.sst");
        try (var writer = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(sstPath)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate()).blockSize(1024).build()) {
            for (int i = 0; i < 50; i++) {
                writer.append(new Entry.Put(
                        MemorySegment.ofArray(
                                String.format("k-%03d", i).getBytes(StandardCharsets.UTF_8)),
                        MemorySegment
                                .ofArray(("value-payload-" + i).getBytes(StandardCharsets.UTF_8)),
                        new SequenceNumber(i + 1L)));
            }
            writer.finish();
        }

        // Open a lazy reader — recoveryScan will pull blocks from lazyChannel.
        TrieSSTableReader reader = TrieSSTableReader.openLazy(sstPath,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate());

        // Wrap lazyChannel with a pausing wrapper. The wrapper blocks the very first
        // position() call so the test can close the reader before the read completes.
        CountDownLatch positionReached = new CountDownLatch(1);
        CountDownLatch proceedWithRead = new CountDownLatch(1);

        Field channelField = TrieSSTableReader.class.getDeclaredField("lazyChannel");
        channelField.setAccessible(true);
        SeekableByteChannel realChannel = (SeekableByteChannel) channelField.get(reader);
        SeekableByteChannel pausingChannel = new PausingChannelWrapper(realChannel, positionReached,
                proceedWithRead);
        channelField.set(reader, pausingChannel);

        AtomicReference<Throwable> scanException = new AtomicReference<>();

        // Thread A: invoke recoveryScan() — the constructor drives advance() which
        // calls readBlockAtCursor() -> readAbs() -> readBytes() -> pausingChannel
        // and blocks inside the first position() call.
        Thread scanThread = Thread.ofVirtual().start(() -> {
            try {
                Iterator<Entry> it = reader.recoveryScan();
                // If the caller somehow made it past the pause, drain the iterator
                // so the lock is released cleanly. The test should never reach here
                // because the pausing wrapper halts position() indefinitely until
                // we countDown proceedWithRead AFTER closing the reader.
                while (it.hasNext()) {
                    it.next();
                }
                if (it instanceof AutoCloseable ac) {
                    ac.close();
                }
            } catch (Throwable t) {
                scanException.set(t);
            }
        });

        // Wait for recoveryScan to reach the paused position() call.
        assertTrue(positionReached.await(5, TimeUnit.SECONDS),
                "recoveryScan should reach the paused position() call on lazyChannel");

        // Thread B: close the reader while Thread A is mid-read. close() closes the
        // real channel; when A resumes, the channel is closed and read/position
        // throw ClosedChannelException.
        reader.close();

        // Release the pause — Thread A resumes, sees the closed channel, and
        // propagates whatever exception the code translates the CCE into.
        proceedWithRead.countDown();

        scanThread.join(5000);
        assertFalse(scanThread.isAlive(), "recoveryScan thread should have completed");

        Throwable thrown = scanException.get();
        assertNotNull(thrown, "recoveryScan() should throw when the reader is closed mid-read");

        // The bug: without the fix, this is a raw ClosedChannelException (IOException
        // subtype) thrown via sneakyThrow from advance()'s IOException catch.
        // Correct behavior (matching get()/scan()): IllegalStateException("reader is closed").
        assertInstanceOf(IllegalStateException.class, thrown,
                "Expected IllegalStateException('reader is closed') but got "
                        + thrown.getClass().getName() + ": " + thrown.getMessage());
        assertTrue(thrown.getMessage() != null && thrown.getMessage().contains("reader is closed"),
                "Expected 'reader is closed' in message but got: " + thrown.getMessage());
    }

    // Finding: F-R1.concurrency.1.6
    // Bug: R43 FAILED-state transition is unimplemented — the failureCause and
    // failureSection fields exist and checkNotFailed reads them, but no code
    // path in the reader ever writes to those fields. A post-open lazy first-load
    // failure (CorruptSectionException from a lazy channel read) propagates out
    // as a bare IOException; the reader is NOT transitioned to FAILED state.
    // Subsequent calls to get()/scan() re-attempt the load and may observe
    // different failure modes depending on channel state — violating R43's
    // contract of a stable, ISE-wrapping-cause response on subsequent calls.
    // Correct behavior: a post-open lazy first-load failure must transition the
    // reader to FAILED state: failureCause and failureSection must be set so
    // checkNotFailed() throws IllegalStateException wrapping the originating
    // IOException on every subsequent get/scan/recoveryScan call.
    // Fix location: TrieSSTableReader.get (519-559) and any other lazy first-load
    // I/O path — on CorruptSectionException (or equivalent section-level
    // corruption), publish the cause and section to the failure fields before
    // rethrowing so subsequent calls hit checkNotFailed's ISE path.
    // Regression watch: non-lazy reads and non-corruption IOExceptions must not
    // permanently poison the reader (transient I/O errors should remain
    // retryable). Only section-level corruption detected on a lazy first-load
    // transitions to FAILED.
    @Test
    @Timeout(15)
    void test_get_lazyFirstLoadFailure_transitionsReaderToFailed() throws Exception {
        // Write a minimal v5 SSTable so we can open it lazily and trigger lazy I/O.
        Path sstPath = tempDir.resolve("r43-transition.sst");
        try (var writer = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(sstPath)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate()).blockSize(1024).build()) {
            for (int i = 0; i < 10; i++) {
                writer.append(new Entry.Put(
                        MemorySegment.ofArray(
                                String.format("k-%03d", i).getBytes(StandardCharsets.UTF_8)),
                        MemorySegment.ofArray(("value-" + i).getBytes(StandardCharsets.UTF_8)),
                        new SequenceNumber(i + 1L)));
            }
            writer.finish();
        }

        try (TrieSSTableReader reader = TrieSSTableReader.openLazy(sstPath,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate())) {

            // Replace lazyChannel with a wrapper that throws CorruptSectionException on
            // the first data-section read. This simulates a post-open lazy first-load
            // failure — the exact scenario R43 is specified to cover.
            Field channelField = TrieSSTableReader.class.getDeclaredField("lazyChannel");
            channelField.setAccessible(true);
            SeekableByteChannel realChannel = (SeekableByteChannel) channelField.get(reader);
            CorruptingChannelWrapper corruptingChannel = new CorruptingChannelWrapper(realChannel);
            channelField.set(reader, corruptingChannel);

            MemorySegment key = MemorySegment.ofArray("k-000".getBytes(StandardCharsets.UTF_8));

            // First call: lazy first-load fails with the injected CorruptSectionException.
            // R43(a) requires the reader transition to FAILED state here.
            IOException firstFailure = assertThrows(IOException.class, () -> reader.get(key),
                    "first get() should surface the lazy-load failure");

            // Second call: reader must now be in FAILED state. R43 mandates that
            // subsequent calls throw IllegalStateException wrapping the originating
            // cause — not re-invoke the failing I/O path (which may observe a
            // different error or even spuriously succeed).
            IllegalStateException ise = assertThrows(IllegalStateException.class,
                    () -> reader.get(key),
                    "second get() after lazy first-load failure must throw IllegalStateException "
                            + "(R43 FAILED state) — got something else, meaning reader was NOT "
                            + "transitioned to FAILED on the first failure");

            assertNotNull(ise.getCause(),
                    "R43 requires the originating cause to be chained on the FAILED-state ISE");
            // The cause must be a section-level corruption — the originating failure.
            assertInstanceOf(CorruptSectionException.class, ise.getCause(),
                    "R43: cause must be the originating CorruptSectionException; got "
                            + ise.getCause().getClass().getName());
            // The failure message must not render 'null' for the section (ties to
            // F-R1.concurrency.1.4's diagnostic contract).
            assertFalse(ise.getMessage().contains("null"),
                    "R43 diagnostic must not contain literal 'null'; got: " + ise.getMessage());

            // The first-failure IOException reference equality is not required by R43,
            // but the chained cause identity must match the originating corruption
            // so callers can distinguish corruption from transient I/O errors.
            // Sanity check: ensure firstFailure is a CorruptSectionException OR its
            // cause is one — keeps the test honest about what was thrown.
            assertTrue(
                    firstFailure instanceof CorruptSectionException
                            || firstFailure.getCause() instanceof CorruptSectionException,
                    "first failure should be or wrap a CorruptSectionException; got "
                            + firstFailure.getClass().getName() + ": " + firstFailure.getMessage());
        }
    }

    // Finding: F-R1.concurrency.1.7
    // Bug: recoveryScan()'s outer finally unconditionally calls recoveryLock.unlock() on
    // the "ctor failed" path. However, RecoveryScanIterator's advance() paths already
    // call releaseOnceExhausted() BEFORE sneakyThrow — releasing the lock. When the
    // ctor throws via advance() (e.g. readBlockAtCursor() throws IOException), the
    // outer finally then calls recoveryLock.unlock() again on a lock the current
    // thread no longer owns, producing IllegalMonitorStateException. The IMSE
    // masks the originating corruption exception — callers cannot distinguish a
    // CorruptSectionException from a lock-state bug.
    // Correct behavior: on ctor failure, recoveryScan() must propagate the original
    // exception (e.g. CorruptSectionException / IOException) to the caller without
    // masking it with IllegalMonitorStateException. The unwind must not double-unlock
    // the recovery lock. After the throw, the reader must remain usable — a
    // subsequent recoveryScan() call on a non-corrupt input path (or with the
    // corruption removed) must succeed.
    // Fix location: TrieSSTableReader.recoveryScan (644-682) — coordinate the outer
    // finally's unlock with releaseOnceExhausted()'s unlock so the lock is
    // released exactly once on the ctor-failed path.
    // Regression watch: the natural-exhaustion path (advance() drains successfully,
    // then iterator releases on last next()) must continue to release the lock
    // exactly once; the successful recoveryScan() path must still produce a
    // fully-iterable iterator.
    // @spec sstable.end-to-end-integrity.R45
    @Test
    @Timeout(15)
    void test_recoveryScan_ctorFailureViaIOException_doesNotMaskWithIMSE() throws Exception {
        // Write a v5 SSTable so we can open it lazily and drive recoveryScan.
        Path sstPath = tempDir.resolve("recovery-ctor-fail.sst");
        try (var writer = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(sstPath)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01))
                .codec(CompressionCodec.deflate()).blockSize(1024).build()) {
            for (int i = 0; i < 20; i++) {
                writer.append(new Entry.Put(
                        MemorySegment.ofArray(
                                String.format("k-%03d", i).getBytes(StandardCharsets.UTF_8)),
                        MemorySegment.ofArray(("value-" + i).getBytes(StandardCharsets.UTF_8)),
                        new SequenceNumber(i + 1L)));
            }
            writer.finish();
        }

        try (TrieSSTableReader reader = TrieSSTableReader.openLazy(sstPath,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate())) {

            // Reflectively swap the lazy channel AFTER openLazy has finished loading
            // the footer/bloom/index. The wrapper throws CorruptSectionException on
            // its first read — which will be the first data-region read during
            // recoveryScan's readBlockAtCursor(). That IOException is caught in
            // advance() (line 751), which calls releaseOnceExhausted() — unlocking
            // the recoveryLock — and then sneakyThrows the IOException. The outer
            // recoveryScan() finally currently tries to unlock again, producing IMSE
            // that masks the originating corruption exception.
            Field channelField = TrieSSTableReader.class.getDeclaredField("lazyChannel");
            channelField.setAccessible(true);
            SeekableByteChannel realChannel = (SeekableByteChannel) channelField.get(reader);
            channelField.set(reader, new CorruptingChannelWrapper(realChannel));

            // Under the bug: IMSE masks the IOException.
            // Under the fix: the originating IOException surfaces unmasked.
            Throwable thrown = null;
            try {
                reader.recoveryScan();
                fail("recoveryScan() should have thrown — channel injects corruption");
            } catch (Throwable t) {
                thrown = t;
            }

            assertNotNull(thrown, "recoveryScan() must throw on ctor failure");
            assertFalse(thrown instanceof IllegalMonitorStateException,
                    "recoveryScan() must not mask the originating exception with IMSE; got: "
                            + thrown.getClass().getName() + ": " + thrown.getMessage());
            // The originating exception is a CorruptSectionException (IOException subtype).
            // It may be propagated directly, or wrapped — but the chain must reach the
            // originating CorruptSectionException, not be replaced by IMSE.
            Throwable chain = thrown;
            boolean foundOriginating = false;
            while (chain != null) {
                if (chain instanceof CorruptSectionException) {
                    foundOriginating = true;
                    break;
                }
                chain = chain.getCause();
            }
            assertTrue(foundOriginating,
                    "originating CorruptSectionException must survive the unwind; got "
                            + thrown.getClass().getName() + ": " + thrown.getMessage());

            // Reader must remain usable — a subsequent recoveryScan() must NOT report
            // "recovery-scan in progress" (which would mean the lock is still held
            // from the prior failure). We restore the real channel so the next scan
            // can actually succeed.
            channelField.set(reader, realChannel);
            Iterator<Entry> it = reader.recoveryScan();
            try {
                int count = 0;
                while (it.hasNext()) {
                    it.next();
                    count++;
                }
                assertEquals(20, count,
                        "recoveryScan() after recovered corruption should iterate all entries");
            } finally {
                if (it instanceof AutoCloseable ac) {
                    ac.close();
                }
            }
        }
    }

    // Finding: F-R1.conc.2.7 — RESOLVED as FIX_IMPOSSIBLE (spec conflict)
    // v1 scan() returns a snapshot iterator that survives close(); v2 scan() returns
    // CompressedBlockIterator that requires live I/O and throws ISE after close().
    // This behavioral divergence is intentional: F08.R19 specifies ISE for v2,
    // F08.R11 preserves v1 snapshot behavior. Eager snapshot would reintroduce the
    // O(total data) memory pressure that F08 was designed to eliminate.
    // See F08.R29 for the explicit documentation of this design choice.

    /**
     * A SeekableByteChannel wrapper that always returns 0 from write(), simulating a non-standard
     * channel (e.g., remote backend under error).
     */
    private static final class ZeroProgressChannelWrapper implements SeekableByteChannel {
        private final SeekableByteChannel delegate;
        private final AtomicInteger writeCallCount;

        ZeroProgressChannelWrapper(SeekableByteChannel delegate, AtomicInteger writeCallCount) {
            this.delegate = delegate;
            this.writeCallCount = writeCallCount;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            writeCallCount.incrementAndGet();
            return 0; // zero progress — no bytes written
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            return delegate.position(newPosition);
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            return delegate.truncate(size);
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /**
     * A SeekableByteChannel wrapper that pauses on position(long) calls, allowing a test to
     * deterministically create a TOCTOU race between checkNotClosed() and the actual I/O.
     */
    private static final class PausingChannelWrapper implements SeekableByteChannel {
        private final SeekableByteChannel delegate;
        private final CountDownLatch positionReached;
        private final CountDownLatch proceedWithRead;

        PausingChannelWrapper(SeekableByteChannel delegate, CountDownLatch positionReached,
                CountDownLatch proceedWithRead) {
            this.delegate = delegate;
            this.positionReached = positionReached;
            this.proceedWithRead = proceedWithRead;
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            // Signal that we've reached the position call (inside readBytes, past checkNotClosed)
            positionReached.countDown();
            try {
                // Wait for the test to close the reader before we proceed
                proceedWithRead.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while pausing", e);
            }
            return delegate.position(newPosition);
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            return delegate.truncate(size);
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /**
     * A SeekableByteChannel wrapper that throws CorruptSectionException on the first read() call.
     * Used to simulate a post-open lazy first-load failure (R43). Subsequent reads delegate to the
     * underlying channel normally.
     */
    private static final class CorruptingChannelWrapper implements SeekableByteChannel {
        private final SeekableByteChannel delegate;
        private final AtomicInteger readCallCount = new AtomicInteger();

        CorruptingChannelWrapper(SeekableByteChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            // Throw CorruptSectionException on the first read — modeling a section-level
            // corruption detected during a post-open lazy first-load.
            if (readCallCount.incrementAndGet() == 1) {
                throw new CorruptSectionException(CorruptSectionException.SECTION_DATA,
                        "injected lazy first-load failure for R43 test");
            }
            return delegate.read(dst);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            return delegate.position(newPosition);
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            return delegate.truncate(size);
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

}
