package jlsm.sstable;

import jlsm.core.bloom.BloomFilter;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ResourceLifecycleAdversarialTest {

    @TempDir
    Path dir;

    Path sstPath;

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    @BeforeEach
    void writeSSTable() throws IOException {
        sstPath = dir.resolve("test.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, sstPath)) {
            w.append(put("a", "va", 1));
            w.append(put("b", "vb", 2));
            w.append(put("c", "vc", 3));
            w.finish();
        }
    }

    private static long openFdCount() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            return unix.getOpenFileDescriptorCount();
        }
        return -1;
    }

    // Finding: F-R1.resource_lifecycle.1.1
    // Bug: readBloomFilter closes Arena immediately after deserialize(); a custom
    // Deserializer that retains the MemorySegment reference will get
    // IllegalStateException on subsequent access (use-after-close).
    // Correct behavior: The MemorySegment passed to deserialize() must remain valid
    // after deserialization, or the API must not close the Arena.
    // Fix location: TrieSSTableReader.readBloomFilter (lines 638-648)
    // Regression watch: Ensure BlockedBloomFilter still works after the fix.
    @Test
    void test_readBloomFilter_retainingDeserializer_useAfterClose() throws IOException {
        // A custom deserializer that retains a reference to the MemorySegment
        // and uses it later in mightContain(). This is a legitimate implementation
        // strategy — the Deserializer contract does not forbid it.
        BloomFilter.Deserializer retainingDeserializer = bytes -> {
            // Read header values while segment is still alive
            int numBlocks = bytes.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 0);
            int numHashFunctions = bytes.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN),
                    4);

            // Return a BloomFilter that retains the segment for later use
            return new BloomFilter() {
                private final MemorySegment retained = bytes;
                private final int blocks = numBlocks;

                @Override
                public void add(MemorySegment key) {
                    throw new UnsupportedOperationException("read-only");
                }

                @Override
                public boolean mightContain(MemorySegment key) {
                    // Access the retained segment — this will throw
                    // IllegalStateException if the backing Arena is closed
                    long firstBlock = retained
                            .get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), 16);
                    return firstBlock != 0 || blocks > 0;
                }

                @Override
                public double falsePositiveRate() {
                    return 0.01;
                }

                @Override
                public MemorySegment serialize() {
                    return retained;
                }
            };
        };

        // Open the SSTable with the retaining deserializer.
        // The readBloomFilter method will close the Arena after deserialization,
        // but the returned BloomFilter still holds a reference to the segment.
        try (TrieSSTableReader reader = TrieSSTableReader.open(sstPath, retainingDeserializer)) {
            // reader.get() internally calls bloomFilter.mightContain(), which
            // accesses the retained MemorySegment. Before the fix, this throws
            // IllegalStateException("Already closed") from the closed Arena.
            Optional<Entry> result = reader.get(seg("a"));
            assertTrue(result.isPresent(), "key 'a' should be found in the SSTable");
        }
    }

    // Finding: F-R1.resource_lifecycle.1.3
    // Bug: open/openLazy catch IOException but not Error — channel leaks on OutOfMemoryError
    // Correct behavior: Channel must be closed when any Throwable (including Error) escapes
    // Fix location: TrieSSTableReader.open (v1/v2) and openLazy (v1/v2) catch blocks
    // Regression watch: Ensure IOException paths still work correctly after broadening catch
    @Test
    void test_open_errorDuringRead_channelLeaksFileDescriptor() throws Exception {
        long baselineFds = openFdCount();
        if (baselineFds < 0) {
            // Not on a Unix system — skip gracefully
            return;
        }

        // A deserializer that throws OutOfMemoryError, simulating memory pressure
        // during bloom filter deserialization inside the try block.
        BloomFilter.Deserializer oomDeserializer = bytes -> {
            throw new OutOfMemoryError("simulated OOM during bloom filter deserialization");
        };

        int iterations = 50;
        for (int i = 0; i < iterations; i++) {
            try {
                TrieSSTableReader.open(sstPath, oomDeserializer);
                fail("Expected OutOfMemoryError to propagate");
            } catch (OutOfMemoryError expected) {
                // Before fix: channel not closed (catch only handles IOException)
                // After fix: channel closed in catch (Error) or finally block
            }
        }

        long afterFds = openFdCount();
        // Allow a small margin for JVM internal FD churn, but 50 leaked FDs is unmistakable
        assertTrue(afterFds - baselineFds < 5,
                "File descriptors leaked: before=" + baselineFds + " after=" + afterFds + " (delta="
                        + (afterFds - baselineFds) + "). "
                        + "Channel not closed when Error escapes open().");
    }

    // Finding: F-R1.resource_lifecycle.1.3 (cluster resource_lifecycle-1, TrieSSTableWriter)
    // Bug: A WriterCommitHook.Lease impl that violates its contract by returning null
    // from freshScope() triggers an NPE inside finishUnderCommitHook (at
    // `freshScope.isPresent()`). The catch block in finish() only handles IOException
    // and ClosedByInterruptException, so the RuntimeException escapes without
    // transitioning state to FAILED. The writer is left in an indeterminate OPEN
    // state with a partial v5 layout already on disk and no v6 scope section, and
    // no R10b FAILED transition was performed.
    // Correct behavior: a non-conformant hook return (null) must surface as an IOException
    // from finish() with state == FAILED, so that close() cleans up the partial.
    // Fix location: TrieSSTableWriter.finish (lines 442-450) — broaden catch to also
    // handle RuntimeException and transition to FAILED, OR add a defensive
    // Objects.requireNonNull on the freshScope return inside finishUnderCommitHook.
    // Regression watch: ensure the existing IOException → FAILED path (R10b) still
    // surfaces the original IOException unchanged; ClosedByInterruptException
    // preserves the interrupt flag.
    @Test
    void test_finishUnderCommitHook_nullFreshScope_writerTransitionsToFailed() throws IOException {
        // A hook impl that violates the documented contract by returning null from
        // freshScope(); finishUnderCommitHook must defensively reject this rather than
        // letting an NPE escape with state == OPEN.
        final Path out = dir.resolve("sst-null-fresh-scope.sst");
        final TableScope cscope = new TableScope(new TenantId("tenant-A"), new DomainId("domain-X"),
                new TableId("table-Z"));
        final WriterCommitHook hook = tableName -> new WriterCommitHook.Lease() {
            @Override
            public Optional<TableScope> freshScope() {
                // Contract violation: returns null instead of Optional.empty().
                return null;
            }

            @Override
            public void close() {
            }
        };

        final TrieSSTableWriter w = TrieSSTableWriter.builder().id(42L).level(Level.L0).path(out)
                .scope(cscope).dekVersions(new int[]{ 1 }).commitHook(hook).tableNameForLock("racy")
                .build();
        try {
            w.append(new Entry.Put(seg("k1"), seg("v1"), new SequenceNumber(1L)));

            // Correct behavior: finish() must NOT throw a raw NPE. It must surface as an
            // IOException (after transitioning to FAILED), preserving the R10b invariant
            // that mid-finish failures put the writer in FAILED state so that close()
            // cleans up the partial.
            assertThrows(IOException.class, w::finish,
                    "freshScope() returning null must surface as IOException, "
                            + "not propagate as NPE through the IOException-only catch block");

            // After the IOException is caught, subsequent mutating operations must be
            // rejected (R10b — once FAILED, refuse all subsequent operations).
            assertThrows(IllegalStateException.class, w::finish,
                    "writer must be in FAILED state after the failed finish — second "
                            + "finish() call must reject with IllegalStateException");
        } finally {
            // close() must remove the *.partial.* tmp file regardless of what state the
            // writer ended up in.
            w.close();
        }
        assertFalse(Files.exists(out), "no committed v6 file emitted (R10c step 5)");
        try (var s = Files.list(dir)) {
            assertFalse(
                    s.anyMatch(p -> p.getFileName().toString().contains(".partial.")
                            && p.getFileName().toString().contains("sst-null-fresh-scope")),
                    "R10b/R10e — partial tmp file deleted on close after FAILED");
        }
    }

    // Finding: F-R1.resource_lifecycle.1.3 (sibling — F-R1.resource_lifecycle.1.4)
    // Same root cause as the test above: a RuntimeException from any path inside
    // finishUnderCommitHook (acquire(), freshScope(), or close()) escapes the
    // IOException-only catch in finish() with state still == OPEN.
    @Test
    void test_finishUnderCommitHook_acquireThrowsRuntime_writerTransitionsToFailed()
            throws IOException {
        final Path out = dir.resolve("sst-acquire-runtime.sst");
        final TableScope cscope = new TableScope(new TenantId("tenant-A"), new DomainId("domain-X"),
                new TableId("table-Z"));
        // A hook impl that throws an unchecked exception from acquire(). The SPI declares
        // `throws IOException` but provides no compile-time guarantee that
        // implementations don't escape RuntimeException. Defensive code in the writer
        // must still transition to FAILED.
        final WriterCommitHook hook = tableName -> {
            throw new IllegalStateException("simulated unchecked failure during acquire");
        };

        final TrieSSTableWriter w = TrieSSTableWriter.builder().id(43L).level(Level.L0).path(out)
                .scope(cscope).dekVersions(new int[]{ 1 }).commitHook(hook).tableNameForLock("racy")
                .build();
        try {
            w.append(new Entry.Put(seg("k1"), seg("v1"), new SequenceNumber(1L)));

            // Either an IOException (preferred, mirrors R10b semantics) or the original
            // unchecked exception is acceptable from finish() — but in either case the
            // writer's state must end up in FAILED, NOT OPEN.
            assertThrows(Throwable.class, w::finish);

            // The defining assertion: subsequent ops must be rejected because the writer
            // ended in FAILED. If the writer stayed in OPEN (the bug), this second
            // finish() would re-enter the try block and re-flush the layout — producing
            // a malformed file. If the writer is correctly in FAILED, this throws ISE.
            assertThrows(IllegalStateException.class, w::finish,
                    "writer must be in FAILED state after the failed finish — second "
                            + "finish() call must reject with IllegalStateException");
        } finally {
            w.close();
        }
        assertFalse(Files.exists(out));
        try (var s = Files.list(dir)) {
            assertFalse(
                    s.anyMatch(p -> p.getFileName().toString().contains(".partial.")
                            && p.getFileName().toString().contains("sst-acquire-runtime")),
                    "R10b/R10e — partial tmp file deleted on close after failed finish");
        }
    }

    /**
     * SeekableByteChannel that delegates to a backing FileChannel but is NOT a FileChannel. Used to
     * simulate non-FileChannel storage providers (S3, GCS, custom NIO providers) where fsync is
     * skipped per R23.
     */
    private static final class NonFileSeekableByteChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;

        NonFileSeekableByteChannel(SeekableByteChannel delegate) {
            this.delegate = delegate;
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
        public SeekableByteChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
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

    // Finding: F-R1.resource_lifecycle.1.5
    // Bug: closeChannelQuietly() runs BEFORE commitFromPartial(); on non-FileChannel outputs,
    // the post-magic forceOrSkip is a no-op (R23 fsync skip) and channel.close() may not flush
    // kernel buffers. The Files.move(ATOMIC_MOVE) success path then renames a partial file
    // whose trailing bytes may be unflushed. The FsyncSkipListener is documented (R23) as the
    // escape valve, but it is NOT invoked at the close-then-move boundary on the success path
    // — only at the post-magic site (before close) and on AtomicMoveNotSupportedException.
    // Suggested defense: invoke FsyncSkipListener with a "close-before-move" / "non-file-channel-
    // commit" reason on the success path so the engine can plug in a backend-specific durability
    // call (e.g. S3 PUT with If-None-Match) between close and rename.
    // Correct behavior: on a non-FileChannel writer, the listener must be invoked at least once
    // with a reason that distinguishes the close-then-move commit boundary from the in-flush
    // fsync sites. Without this, the engine has no signal to perform a backend-specific durability
    // step between channel close and atomic move.
    // Fix location: TrieSSTableWriter.finish() between line 435 (closeChannelQuietly) and line 436
    // (commitFromPartial), or inside commitFromPartial before the Files.move call.
    // Regression watch: do NOT invoke the listener on FileChannel outputs (the existing fc.force
    // path makes the close-move boundary durable). Listener invocation must remain non-throwing.
    @Test
    void test_finish_nonFileChannel_listenerInvokedAtCloseMoveBoundary() throws Exception {
        final Path out = dir.resolve("sst-non-file-channel.sst");
        // Recording listener — capture every (path, channelClass, reason) tuple.
        final List<String> reasons = new ArrayList<>();
        final FsyncSkipListener listener = (p, cls, reason) -> reasons.add(reason);

        // Construct a writer normally (opens a FileChannel internally). Then swap the `channel`
        // field for a delegating SeekableByteChannel that is NOT a FileChannel — this simulates
        // a non-FileChannel NIO provider (S3, GCS) without requiring an in-memory FileSystem.
        final TrieSSTableWriter w = TrieSSTableWriter.builder().id(99L).level(Level.L0).path(out)
                .fsyncSkipListener(listener).build();

        // Reflectively swap the writer's `channel` field with a non-FileChannel delegate so
        // forceOrSkip falls through to the listener-invocation branch (R23) and the writer's
        // close-then-move ordering exercises the non-FileChannel code path.
        final Field channelField = TrieSSTableWriter.class.getDeclaredField("channel");
        channelField.setAccessible(true);
        final SeekableByteChannel realChannel = (SeekableByteChannel) channelField.get(w);
        channelField.set(w, new NonFileSeekableByteChannel(realChannel));

        try {
            w.append(put("a", "va", 1));
            w.append(put("b", "vb", 2));
            w.append(put("c", "vc", 3));
            w.finish();
        } finally {
            w.close();
        }

        // Confirm the v5 force calls fell through to the listener (sanity check that the swap
        // worked and forceOrSkip did invoke the listener at the existing R23 sites).
        assertTrue(reasons.contains("non-file-channel"),
                "R23: at least one in-flush fsync site must invoke the listener on a "
                        + "non-FileChannel writer (post-data, post-metadata, post-footer)");

        // The bug: the listener is NOT invoked at the close-then-move boundary on the success
        // path. The engine has no signal to perform backend-specific durability between
        // closeChannelQuietly() and Files.move(). The defense the finding describes as missing
        // is precisely a listener invocation at this site.
        //
        // Pass condition (defense present): listener invoked with a reason that distinguishes
        // the close-then-move site from the in-flush fsync sites. Acceptable reasons include
        // "close-before-move", "non-file-channel-commit", "post-close", "pre-rename", etc. —
        // any reason that is not one of the in-flush sites.
        //
        // Fail condition (bug present): no listener invocation at the close-then-move boundary;
        // only the in-flush sites fire.
        final List<String> closeMoveReasons = reasons.stream()
                .filter(r -> !r.equals("non-file-channel"))
                .filter(r -> !r.equals("atomic-rename-unsupported")).toList();
        assertFalse(closeMoveReasons.isEmpty(),
                "F-R1.resource_lifecycle.1.5: on a non-FileChannel output, the FsyncSkipListener "
                        + "must be invoked at the close-then-move boundary so the engine can "
                        + "perform a backend-specific durability call between channel close and "
                        + "atomic rename. Recorded reasons: " + reasons);
    }
}
