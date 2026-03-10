package jlsm.core.io;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Off-heap buffer pool backed by a shared {@link Arena}. Segments are pre-allocated at
 * construction and returned to the pool via {@link #release}. Shared across WAL and compaction.
 */
public final class ArenaBufferPool implements AutoCloseable {

    private final Arena arena;
    private final LinkedBlockingQueue<MemorySegment> queue;
    private final long acquireTimeoutNanos;
    private volatile boolean closed = false;

    private ArenaBufferPool(int poolSize, long bufferSize, long acquireTimeoutMillis) {
        assert poolSize >= 1 : "poolSize must be >= 1";
        assert bufferSize >= 1 : "bufferSize must be >= 1";
        assert acquireTimeoutMillis > 0 : "acquireTimeoutMillis must be > 0";

        this.arena = Arena.ofShared();
        this.acquireTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(acquireTimeoutMillis);
        this.queue = new LinkedBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            boolean offered = queue.offer(arena.allocate(bufferSize, 8));
            assert offered : "queue must accept all pre-allocated segments";
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
     * Closes the underlying {@link Arena}. Idempotent.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            arena.close();
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
            if (poolSize < 1) throw new IllegalStateException("poolSize must be configured");
            if (bufferSize < 1) throw new IllegalStateException("bufferSize must be configured");
            if (acquireTimeoutMillis <= 0) throw new IllegalStateException("acquireTimeoutMillis must be configured");
            return new ArenaBufferPool(poolSize, bufferSize, acquireTimeoutMillis);
        }
    }
}
