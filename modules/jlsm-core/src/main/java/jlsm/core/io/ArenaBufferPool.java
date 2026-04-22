package jlsm.core.io;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Off-heap buffer pool backed by a shared {@link Arena}. Segments are pre-allocated at construction
 * and returned to the pool via {@link #release}. Shared across WAL and compaction.
 */
public final class ArenaBufferPool implements AutoCloseable {

    private final Arena arena;
    private final LinkedBlockingQueue<MemorySegment> queue;
    private final long acquireTimeoutNanos;
    private final long bufferSize;
    private final int poolSize;
    // Two-phase close state: `closing` is the atomic claim on the right to call arena.close()
    // (ensures Arena.close() is invoked at most once, even under concurrent close()).
    // `closed` is the observable "close has taken effect" flag — set to true only AFTER
    // arena.close() has returned, so observers of isClosed()==true have a happens-before
    // guarantee that the arena scope is no longer alive.
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private volatile boolean closed = false;

    private ArenaBufferPool(int poolSize, long bufferSize, long acquireTimeoutMillis) {
        assert poolSize >= 1 : "poolSize must be >= 1";
        assert bufferSize >= 1 : "bufferSize must be >= 1";
        assert acquireTimeoutMillis > 0 : "acquireTimeoutMillis must be > 0";

        this.arena = Arena.ofShared();
        this.acquireTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(acquireTimeoutMillis);
        this.bufferSize = bufferSize;
        this.poolSize = poolSize;
        this.queue = new LinkedBlockingQueue<>(poolSize);
        try {
            for (int i = 0; i < poolSize; i++) {
                boolean offered = queue.offer(arena.allocate(bufferSize, 8));
                assert offered : "queue must accept all pre-allocated segments";
            }
        } catch (Throwable t) {
            // The partially-constructed pool will never be published (this never escapes
            // since the ctor is throwing). Release every byte of off-heap memory the Arena
            // committed before the failure — GC does not release Arena-backed native
            // memory; only an explicit close() does. Suppress any exception from close()
            // onto the original failure so the caller sees the root cause.
            try {
                arena.close();
            } catch (Throwable closeFailure) {
                t.addSuppressed(closeFailure);
            }
            throw t;
        }
    }

    /**
     * Acquires a segment from the pool, blocking up to the configured timeout.
     *
     * @throws IOException if the pool is exhausted or the thread is interrupted
     */
    public MemorySegment acquire() throws IOException {
        try {
            MemorySegment seg = queue.poll(acquireTimeoutNanos, TimeUnit.NANOSECONDS);
            if (seg == null) {
                throw new IOException("buffer pool exhausted");
            }
            return seg;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for buffer", e);
        }
    }

    /**
     * Returns a segment to the pool.
     *
     * @param segment must not be null; must have been obtained from this pool
     */
    public void release(MemorySegment segment) {
        Objects.requireNonNull(segment, "segment must not be null");
        boolean offered = queue.offer(segment);
        assert offered : "release should always succeed — pool capacity invariant violated";
    }

    /**
     * Returns {@code true} if and only if {@link #close()} has been invoked at least once on this
     * pool.
     *
     * @spec sstable.pool-aware-block-size.R0
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Returns the buffer size configured on this pool via {@link Builder#bufferSize(long)}.
     *
     * <p>
     * The returned value is immutable for the lifetime of the pool and is valid regardless of
     * whether {@link #close()} has been invoked. This method must not throw.
     *
     * @spec sstable.pool-aware-block-size.R1
     * @spec sstable.pool-aware-block-size.R2
     */
    public long bufferSize() {
        return bufferSize;
    }

    /**
     * Closes the underlying {@link Arena}. Idempotent.
     *
     * <p>
     * Under concurrent invocation, exactly one thread wins the claim on the close transition (via
     * {@code closing}), invokes {@link Arena#close()}, and then publishes {@code closed=true}.
     * Losing threads spin-wait until {@code closed} is observed true so that every caller of
     * {@code close()} returns with {@link #isClosed()} observable as {@code true}. Readers of
     * {@link #isClosed()} therefore have a happens-before guarantee that the arena has already been
     * closed when they observe {@code true}.
     *
     * <p>
     * Callers must ensure every segment obtained via {@link #acquire()} has been returned via
     * {@link #release(MemorySegment)} before invoking {@code close()}. Closing the pool while
     * segments are still checked out would invalidate those segments mid-use (the underlying shared
     * {@link Arena} revokes every live segment on close), surfacing as a cryptic
     * {@link IllegalStateException} from deep inside the caller that still holds the segment. To
     * catch this lifecycle misuse at the close() call site with a descriptive diagnostic,
     * {@code close()} detects outstanding acquires (via the pool's internal queue size) and throws
     * {@link IllegalStateException} without touching the arena — leaving every in-flight segment
     * valid so the misbehaving caller can release it and retry close().
     *
     * @throws IllegalStateException if one or more segments are still outstanding at the time of
     *             the first close() invocation (i.e. fewer segments are in the pool than were
     *             allocated at construction). Subsequent close() calls after a successful close
     *             remain idempotent and do not re-check this invariant.
     */
    @Override
    public void close() {
        // Fast path: if close() has already taken effect, return immediately. This preserves
        // the idempotent contract — a second close() on an already-closed pool must not throw
        // even though the queue state is no longer meaningful.
        if (closed) {
            return;
        }
        // Liveness check: the pool knows how many segments are currently out-for-use via
        // queue.size() vs the configured poolSize. If any segment is still checked out, the
        // arena must NOT be closed — closing would revoke the in-use segment mid-use and
        // surface as an opaque FFM exception in the caller's hot path. Fail fast with a
        // diagnostic that names the misuse. This is a caller-contract violation, not a
        // transient condition, so IllegalStateException is the right shape.
        int outstanding = poolSize - queue.size();
        if (outstanding > 0) {
            throw new IllegalStateException("close() refused: " + outstanding
                    + " outstanding acquired segment(s) not yet released — every acquire() must"
                    + " be matched by release() before closing the pool");
        }
        if (closing.compareAndSet(false, true)) {
            try {
                arena.close();
            } finally {
                closed = true;
            }
        } else {
            // Another thread is performing the close; wait for it to finish so that
            // isClosed() observes true on return from this call.
            while (!closed) {
                Thread.onSpinWait();
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int poolSize = -1;
        private long bufferSize = -1;
        private long acquireTimeoutMillis = -1;

        public Builder poolSize(int poolSize) {
            if (poolSize < 1) {
                throw new IllegalArgumentException("poolSize must be >= 1, got: " + poolSize);
            }
            this.poolSize = poolSize;
            return this;
        }

        public Builder bufferSize(long bufferSize) {
            if (bufferSize < 1) {
                throw new IllegalArgumentException("bufferSize must be >= 1, got: " + bufferSize);
            }
            this.bufferSize = bufferSize;
            return this;
        }

        public Builder acquireTimeoutMillis(long acquireTimeoutMillis) {
            if (acquireTimeoutMillis <= 0) {
                throw new IllegalArgumentException(
                        "acquireTimeoutMillis must be > 0, got: " + acquireTimeoutMillis);
            }
            this.acquireTimeoutMillis = acquireTimeoutMillis;
            return this;
        }

        public ArenaBufferPool build() {
            if (poolSize < 1)
                throw new IllegalStateException("poolSize must be configured");
            if (bufferSize < 1)
                throw new IllegalStateException("bufferSize must be configured");
            if (acquireTimeoutMillis <= 0)
                throw new IllegalStateException("acquireTimeoutMillis must be configured");
            return new ArenaBufferPool(poolSize, bufferSize, acquireTimeoutMillis);
        }
    }
}
