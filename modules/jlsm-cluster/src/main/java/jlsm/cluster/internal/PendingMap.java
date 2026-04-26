package jlsm.cluster.internal;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jlsm.cluster.Message;

/**
 * Per-connection pending request map and stream-id allocator.
 *
 * <p>
 * Implements {@code transport.multiplexed-framing} R6 (stream-id allocation), R6a (capacity cap),
 * R8 (register-before-write), R26b (value-conditional removal — Pass 3 amendment), R27 (no-leak).
 *
 * @spec transport.multiplexed-framing.R6
 * @spec transport.multiplexed-framing.R6a
 * @spec transport.multiplexed-framing.R8
 * @spec transport.multiplexed-framing.R26b
 * @spec transport.multiplexed-framing.R27
 */
public final class PendingMap {

    /** Default capacity per R6a. */
    public static final int DEFAULT_CAPACITY = 65536;

    private final int capacity;
    private final ConcurrentHashMap<Integer, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextStreamId = new AtomicInteger(1);

    public PendingMap() {
        this(DEFAULT_CAPACITY);
    }

    public PendingMap(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * Allocate a stream-id and register the future. Returns the assigned stream-id, or fails the
     * future and returns -1 if the map is at capacity (R6a).
     *
     * @spec transport.multiplexed-framing.R6
     * @spec transport.multiplexed-framing.R6a
     * @spec transport.multiplexed-framing.R8
     */
    public int register(CompletableFuture<Message> future) {
        if (future == null) {
            throw new IllegalArgumentException("future must not be null");
        }
        if (pending.size() >= capacity) {
            future.completeExceptionally(
                    new IOException("stream-id pending map at capacity (" + capacity + ")"));
            return -1;
        }
        // R6: monotonic counter, skip 0 and negatives on wrap.
        for (int attempts = 0; attempts < capacity + 2; attempts++) {
            int id = nextStreamId.getAndIncrement();
            if (id <= 0) {
                // Wrapped past Integer.MAX_VALUE; reset to 1.
                nextStreamId.compareAndSet(id + 1, 1);
                continue;
            }
            if (pending.putIfAbsent(id, future) == null) {
                return id;
            }
            // Collision (only possible after wrap with R6a entries near cap); try next.
        }
        future.completeExceptionally(
                new IOException("could not allocate unique stream-id within bounded scan"));
        return -1;
    }

    /**
     * Value-conditional removal per R26b: removes only if the entry's value identity matches.
     *
     * @spec transport.multiplexed-framing.R26b
     */
    public boolean remove(int streamId, CompletableFuture<Message> future) {
        return pending.remove(streamId, future);
    }

    /** Looks up the future for a stream-id, returning null if absent. */
    public CompletableFuture<Message> lookup(int streamId) {
        return pending.get(streamId);
    }

    /**
     * Removes and returns the future for a stream-id, or null if absent.
     *
     * @spec transport.multiplexed-framing.R11
     */
    public CompletableFuture<Message> takeForResponse(int streamId) {
        return pending.remove(streamId);
    }

    /**
     * Fails all pending entries with the given cause, used during connection failure or close
     * cleanup (R20, R25, R28).
     *
     * @spec transport.multiplexed-framing.R20
     * @spec transport.multiplexed-framing.R25
     * @spec transport.multiplexed-framing.R28
     */
    public void failAll(Throwable cause) {
        for (CompletableFuture<Message> f : pending.values()) {
            f.completeExceptionally(cause);
        }
        pending.clear();
    }

    public int size() {
        return pending.size();
    }

    public int capacity() {
        return capacity;
    }
}
