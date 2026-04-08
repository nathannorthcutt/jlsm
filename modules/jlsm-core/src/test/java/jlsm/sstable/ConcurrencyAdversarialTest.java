package jlsm.sstable;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

}
