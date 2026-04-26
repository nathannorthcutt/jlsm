package jlsm.cluster.internal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Queryable transport counters per {@code transport.multiplexed-framing} R45.
 *
 * <p>
 * Implements counters (a) orphaned responses, (b) no-handler discards, (c) corrupt frame
 * disconnections, (e) handshake failures, (f) write failures, (g) reconnection attempts, (h)
 * handler dispatch overflow, (i) stream-id exhaustion, (k) post-close response discards (Pass 3
 * amendment), (m) handler exceptions on incoming requests (Pass 2 amendment).
 *
 * <p>
 * Counters (d), (j), (l) are tracked in their respective subsystems (reassembly buffer, handshake
 * blocking gate, request-too-large path) — added when those subsystems land per the work plan.
 *
 * @spec transport.multiplexed-framing.R45
 */
public final class TransportMetrics {

    public final AtomicLong orphanedResponses = new AtomicLong(); // (a)
    public final AtomicLong noHandlerDiscards = new AtomicLong(); // (b)
    public final AtomicLong corruptFrameDisconnections = new AtomicLong(); // (c)
    public final AtomicLong reassemblyLimitExceeded = new AtomicLong(); // (d)
    public final AtomicLong handshakeFailures = new AtomicLong(); // (e)
    public final AtomicLong writeFailures = new AtomicLong(); // (f)
    public final AtomicLong reconnectionAttempts = new AtomicLong(); // (g)
    public final AtomicLong dispatchOverflow = new AtomicLong(); // (h)
    public final AtomicLong streamIdExhaustion = new AtomicLong(); // (i)
    public final AtomicLong handshakeBlocked = new AtomicLong(); // (j)
    public final AtomicLong postCloseDiscards = new AtomicLong(); // (k)
    public final AtomicLong requestTooLargeDiscarded = new AtomicLong(); // (l)
    public final AtomicLong handlerExceptions = new AtomicLong(); // (m)

    /** Used internally by accept loop on accept-throw recovery (R39a). */
    public final AtomicLong acceptErrors = new AtomicLong();
}
