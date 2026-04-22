package jlsm.core.io;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Adversarial tests for concurrency / lifecycle interactions on constructs in {@code jlsm.core.io}.
 * Each test documents the finding it exercises, the bug shape, and the expected correct behavior so
 * that the test serves as regression coverage after a fix lands.
 */
class ConcurrencyAdversarialTest {

    // Finding: F-R1.concurrency.1.2
    // Bug: ArenaBufferPool.close() invalidates MemorySegments held by concurrent acquirers.
    // A caller may invoke close() while another thread still holds a segment previously
    // returned by acquire(). arena.close() on Arena.ofShared() revokes every live
    // segment; the in-use segment then throws IllegalStateException deep inside an
    // unrelated hot path (e.g. SSTable block write) with no diagnostic indication that
    // the root cause was a premature close(). The pool knows how many segments are
    // currently out — queue.size() compared against the configured pool size — but
    // close() does not consult this invariant before tearing down the arena.
    // Correct behavior: close() must detect outstanding acquires (queue.size() < poolSize)
    // and fail fast with a clear IllegalStateException. The arena must remain alive so
    // holders of in-use segments can finish their work. An orderly caller releases all
    // acquired segments before close(); an errant caller receives an immediate,
    // actionable error at the close() call site rather than a cryptic FFM exception
    // propagating out of a writer internal.
    // Fix location: ArenaBufferPool.close() at lines 123-137. Before claiming the close
    // transition (the `closing.compareAndSet(false, true)` call), compare queue.size()
    // against the configured pool size (a new final field captured at construction) and
    // throw IllegalStateException if they differ.
    // Regression watch: existing tests that acquire-then-release-then-close must continue
    // to pass (queue.size() == poolSize at close time is the legal path). The idempotent
    // repeat-close contract must also hold — a second close() on an already-closed pool
    // must not throw.
    @Test
    @Timeout(10)
    void test_ArenaBufferPool_close_refusesWhileSegmentsOutstanding() throws Exception {
        ArenaBufferPool pool = ArenaBufferPool.builder().poolSize(2).bufferSize(64)
                .acquireTimeoutMillis(1000).build();
        MemorySegment outstanding = pool.acquire();
        try {
            // A caller that closes the pool while a segment is still checked out is engaging
            // in a lifecycle misuse that would otherwise silently revoke `outstanding` and
            // surface as an IllegalStateException ("Already closed") deep in whatever code
            // path uses the segment next. The pool must catch this at close() time with a
            // descriptive diagnostic and refuse the transition.
            IllegalStateException ex = assertThrows(IllegalStateException.class, pool::close,
                    "close() must refuse while segments are still outstanding (acquire"
                            + " without matching release) — the FFM arena teardown would"
                            + " otherwise invalidate the in-use segment mid-use.");
            // Bonus: the error message must identify the outstanding-acquire misuse so the
            // caller can correlate the error with the missing release, not just a generic ISE.
            String msg = ex.getMessage();
            assertTrue(msg != null && msg.toLowerCase().contains("outstanding"),
                    "close() IllegalStateException message must reference outstanding acquires;"
                            + " observed: " + msg);
            // After the refused close(), the arena must still be alive so the in-use segment
            // remains valid and the caller can release it and retry close().
            outstanding.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 1);
        } finally {
            pool.release(outstanding);
            pool.close();
        }
    }
}
