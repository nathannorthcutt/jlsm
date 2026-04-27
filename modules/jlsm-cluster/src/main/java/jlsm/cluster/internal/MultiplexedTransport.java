package jlsm.cluster.internal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import jlsm.cluster.ClusterTransport;
import jlsm.cluster.Message;
import jlsm.cluster.MessageHandler;
import jlsm.cluster.MessageType;
import jlsm.cluster.NodeAddress;

/**
 * NIO-based {@link ClusterTransport} implementation per {@code transport.multiplexed-framing} v3.
 *
 * <p>
 * One TCP connection per peer with Kafka-style binary framing (4-byte length prefix + 14-byte
 * header). Reader virtual threads dispatch incoming frames; writes serialize through a
 * per-connection {@link ReentrantLock}. Pending request futures are correlated by stream-id.
 *
 * <p>
 * Coverage: R1-R29 (wire format, multiplexing, dispatch, lifecycle), R23a/R23b (per-peer locking
 * with stable lock identity; concurrent same-peer attempts converge on a single connection), R26b
 * (scheduler-failure-resilient timeout arming; whenComplete cleanup attached before orTimeout),
 * R30/R30a (peer-departure cleanupBarrier), R33 (thread safety), R34/R34b/R34c/R34d (handler
 * dispatch, bounded pool, response bypass, async completion), R35-R38, R37a, R37c (multi-frame
 * reassembly + abuse threshold via {@link Reassembler} + {@link AbuseTracker}), R39 (option-b
 * safe-publication via static factory + R39(a)(i) backlog), R40/R40-bidi/R40a (bidirectional
 * handshake), R43, R43a, R44 (outbound chunking via {@link Chunker}), R45 (counters a-m via
 * {@link TransportMetrics}).
 *
 * @spec transport.multiplexed-framing.R1
 * @spec transport.multiplexed-framing.R6
 * @spec transport.multiplexed-framing.R7
 * @spec transport.multiplexed-framing.R8
 * @spec transport.multiplexed-framing.R10
 * @spec transport.multiplexed-framing.R11
 * @spec transport.multiplexed-framing.R12
 * @spec transport.multiplexed-framing.R13
 * @spec transport.multiplexed-framing.R14
 * @spec transport.multiplexed-framing.R15
 * @spec transport.multiplexed-framing.R16
 * @spec transport.multiplexed-framing.R17
 * @spec transport.multiplexed-framing.R18
 * @spec transport.multiplexed-framing.R19
 * @spec transport.multiplexed-framing.R20
 * @spec transport.multiplexed-framing.R21
 * @spec transport.multiplexed-framing.R26
 * @spec transport.multiplexed-framing.R28
 * @spec transport.multiplexed-framing.R29
 * @spec transport.multiplexed-framing.R30
 * @spec transport.multiplexed-framing.R30a
 * @spec transport.multiplexed-framing.R33
 * @spec transport.multiplexed-framing.R34
 * @spec transport.multiplexed-framing.R34b
 * @spec transport.multiplexed-framing.R34c
 * @spec transport.multiplexed-framing.R34d
 * @spec transport.multiplexed-framing.R39
 * @spec transport.multiplexed-framing.R40
 * @spec transport.multiplexed-framing.R45
 */
public final class MultiplexedTransport implements ClusterTransport {

    private static final Logger LOG = Logger.getLogger(MultiplexedTransport.class.getName());

    /** Default request-response timeout (R26). */
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 30_000L;

    private final NodeAddress self;
    private final ServerSocketChannel serverChannel;
    private final Thread acceptThread;
    private final Map<String, PeerConnection> peers = new ConcurrentHashMap<>();
    /**
     * R23a v3: per-peer lock objects — stable identity for the JVM lifetime (no eviction). Each
     * peer's connection establishment, tie-break, and registry mutation serialize on the same
     * object, so distinct peers proceed in parallel without contention.
     */
    private final Map<String, Object> perPeerLocks = new ConcurrentHashMap<>();
    /** R30 v3: per-peer cleanup barriers — non-null while a peer-departure cleanup is in-flight. */
    private final Map<String, CompletableFuture<Void>> pendingCleanups = new ConcurrentHashMap<>();
    private final Map<MessageType, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final TransportMetrics metrics = new TransportMetrics();
    private final DispatchPool dispatchPool = new DispatchPool();
    private final long requestTimeoutMs;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private volatile boolean closed = false;

    /**
     * Static factory per R39 option (b): bind and start atomically. Avoids unsafe-this-escape
     * during construction.
     *
     * @spec transport.multiplexed-framing.R39
     */
    public static MultiplexedTransport start(NodeAddress self) throws IOException {
        return start(self, DEFAULT_REQUEST_TIMEOUT_MS);
    }

    /** As {@link #start(NodeAddress)} with a configurable request timeout (R26). */
    public static MultiplexedTransport start(NodeAddress self, long requestTimeoutMs)
            throws IOException {
        MultiplexedTransport t = new MultiplexedTransport(self, requestTimeoutMs);
        t.acceptThread.start();
        return t;
    }

    private MultiplexedTransport(NodeAddress self, long requestTimeoutMs) throws IOException {
        if (self == null) {
            throw new IllegalArgumentException("self must not be null");
        }
        if (requestTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "requestTimeoutMs must be positive: " + requestTimeoutMs);
        }
        this.self = self;
        this.requestTimeoutMs = requestTimeoutMs;
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.bind(new InetSocketAddress(self.host(), self.port()), 1024); // R39(a)(i)
        this.acceptThread = Thread.ofVirtual().name("jlsm-cluster-accept-" + self.nodeId())
                .unstarted(this::acceptLoop);
    }

    public NodeAddress self() {
        return self;
    }

    public TransportMetrics metrics() {
        return metrics;
    }

    @Override
    public void send(NodeAddress target, Message msg) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (msg == null) {
            throw new IllegalArgumentException("msg must not be null");
        }
        if (closed) {
            throw new IOException("transport is closed"); // R29
        }
        PeerConnection conn = getOrConnect(target);
        try {
            conn.writeMessage(msg.type(), Frame.NO_REPLY_STREAM_ID, (byte) 0, msg.sequenceNumber(),
                    msg.payload()); // R7 + R43/R44 (R44 will throw if F&F too big)
        } catch (IOException e) {
            metrics.writeFailures.incrementAndGet();
            throw e;
        }
    }

    @Override
    public CompletableFuture<Message> request(NodeAddress target, Message msg) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (msg == null) {
            throw new IllegalArgumentException("msg must not be null");
        }
        if (closed) {
            throw new IllegalStateException("transport is closed"); // R29
        }
        CompletableFuture<Message> future = new CompletableFuture<>();
        try {
            PeerConnection conn = getOrConnect(target);
            int streamId = conn.pending().register(future); // R8
            if (streamId < 0) {
                metrics.streamIdExhaustion.incrementAndGet();
                return future;
            }
            try {
                conn.writeMessage(msg.type(), streamId, (byte) 0, msg.sequenceNumber(),
                        msg.payload()); // R43 chunks if oversize
                // R26b cleanup is registered BEFORE arming the timeout so that even if the
                // timeout-arming step throws (e.g. RejectedExecutionException from a shutting-down
                // scheduler — exotic, but covered by the spec), the pending-map entry has a
                // single-shot value-conditional remove path attached to the future.
                future.whenComplete((r, t) -> conn.pending().remove(streamId, future)); // R26b/R27
                try {
                    future.orTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS); // R26
                } catch (RuntimeException timerEx) {
                    // R26b Pass 3: scheduler-failure path. Complete future immediately; the
                    // R26b whenComplete callback above runs on completion and removes the entry.
                    metrics.writeFailures.incrementAndGet();
                    future.completeExceptionally(new IOException(
                            "transport unavailable: timeout scheduler failed", timerEx));
                }
            } catch (IOException e) {
                metrics.writeFailures.incrementAndGet();
                conn.pending().remove(streamId, future); // R26b
                future.completeExceptionally(e);
            }
        } catch (IOException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public void registerHandler(MessageType type, MessageHandler handler) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        if (closed) {
            throw new IllegalStateException("transport is closed"); // R29
        }
        handlers.put(type, handler);
    }

    @Override
    public void deregisterHandler(MessageType type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (closed) {
            throw new IllegalStateException("transport is closed"); // R29
        }
        handlers.remove(type); // R34a
    }

    @Override
    public void close() {
        closed = true; // R28(1)
        for (PeerConnection conn : peers.values()) { // R28(2,3)
            conn.close();
        }
        peers.clear();
        closeQuietly(serverChannel); // R28(4)
        acceptThread.interrupt(); // R28(5)
        try {
            acceptThread.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dispatchPool.close();
    }

    private PeerConnection getOrConnect(NodeAddress target) throws IOException {
        PeerConnection existing = peers.get(target.nodeId());
        if (existing != null && existing.isOpen()) {
            return existing;
        }
        // R30a: await any in-flight cleanup barrier before establishing a new connection
        try {
            if (!awaitPeerCleanup(target, requestTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("peer-departure cleanup did not complete within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted awaiting peer-departure cleanup", e);
        }
        // R23a Pass 3: per-peer lock — distinct peers proceed in parallel; same-peer concurrent
        // attempts queue on the monitor (R23b queue is the natural FIFO of waiters here).
        synchronized (perPeerLock(target.nodeId())) {
            existing = peers.get(target.nodeId());
            if (existing != null && existing.isOpen()) {
                return existing;
            }
            SocketChannel ch = SocketChannel.open(); // R21/R22
            ch.connect(new InetSocketAddress(target.host(), target.port()));
            ch.socket().setSoTimeout(5_000); // R40
            // R40 forward handshake (initial connecting node sends first)
            byte[] hs = Handshake.encode(self);
            ch.write(ByteBuffer.wrap(hs));
            // R40-bidi reverse handshake — pass OUR nodeId for collision check
            NodeAddress echoed = readHandshake(ch, self.nodeId());
            if (!echoed.equals(target)) {
                ch.close();
                metrics.handshakeFailures.incrementAndGet();
                throw new IOException(
                        "reverse handshake mismatch: expected " + target + ", got " + echoed);
            }
            ch.socket().setSoTimeout(0); // back to no-timeout
            PeerConnection conn = new PeerConnection(target, ch, this);
            peers.put(target.nodeId(), conn);
            conn.startReader();
            return conn;
        }
    }

    private void acceptLoop() {
        while (!closed) {
            SocketChannel ch;
            try {
                ch = serverChannel.accept();
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                metrics.acceptErrors.incrementAndGet(); // R39a
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            try {
                ch.socket().setSoTimeout(5_000); // R40
                NodeAddress remote = readHandshake(ch, self.nodeId()); // R40
                byte[] hs = Handshake.encode(self);
                ch.write(ByteBuffer.wrap(hs)); // R40-bidi
                // After handshake, return to blocking I/O without timeout for reader loop
                ch.socket().setSoTimeout(0);
                String nodeId = remote.nodeId();
                // R23a Pass 3 + R23b: serialize tie-break and registry insert per-peer
                boolean lostTieBreak = false;
                synchronized (perPeerLock(nodeId)) {
                    PeerConnection existing = peers.get(nodeId);
                    if (existing != null && existing.isOpen()) {
                        // R23a tie-break: lower nodeId wins outbound.
                        if (self.nodeId().compareTo(nodeId) < 0) {
                            lostTieBreak = true;
                        } else {
                            existing.close();
                        }
                    }
                    if (!lostTieBreak) {
                        PeerConnection conn = new PeerConnection(remote, ch, this);
                        peers.put(nodeId, conn);
                        conn.startReader();
                    }
                }
                if (lostTieBreak) {
                    ch.close();
                }
            } catch (IOException e) {
                metrics.handshakeFailures.incrementAndGet();
                closeQuietly(ch);
            }
        }
    }

    private Object perPeerLock(String nodeId) {
        return perPeerLocks.computeIfAbsent(nodeId, k -> new Object());
    }

    private NodeAddress readHandshake(SocketChannel ch, String localNodeId) throws IOException {
        // Read up to maximum handshake size; Handshake.decode validates strictly.
        ByteBuffer header = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
        readFully(ch, header);
        header.flip();
        byte version = header.get();
        if (version != Handshake.VERSION) {
            throw new IOException(
                    "unsupported handshake version: 0x" + Integer.toHexString(version & 0xFF));
        }
        int totalLength = header.getInt();
        if (totalLength <= 0 || totalLength > Handshake.MAX_TOTAL_LENGTH) {
            throw new IOException("handshake total-length out of range: " + totalLength);
        }
        ByteBuffer body = ByteBuffer.allocate(1 + 4 + totalLength).order(ByteOrder.BIG_ENDIAN);
        body.put(version);
        body.putInt(totalLength);
        ByteBuffer remainder = body.slice(5, totalLength);
        readFully(ch, remainder);
        body.position(0);
        return Handshake.decode(body, localNodeId);
    }

    static void readFully(SocketChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int n = ch.read(buf);
            if (n < 0) {
                throw new IOException("end-of-stream during read");
            }
            if (n == 0) {
                Thread.onSpinWait();
            }
        }
    }

    /**
     * Dispatch a complete (non-multi-frame) frame received by a {@link PeerConnection}'s reader.
     */
    void dispatch(PeerConnection conn, Frame frame) {
        // R34c: responses bypass any handler queue and complete on the reader thread directly.
        if (frame.isResponse()) { // R11
            CompletableFuture<Message> pending = conn.pending().takeForResponse(frame.streamId());
            if (pending == null) {
                metrics.orphanedResponses.incrementAndGet(); // R45(a)
                return;
            }
            if (closed) {
                pending.completeExceptionally(new IOException("transport closed"));
                metrics.postCloseDiscards.incrementAndGet(); // R45(k)
                return;
            }
            pending.complete(buildMessage(conn.remote(), frame));
            return;
        }
        MessageHandler handler = handlers.get(frame.type());
        if (handler == null) {
            metrics.noHandlerDiscards.incrementAndGet(); // R45(b)
            return;
        }
        // R34b: handler dispatch goes through bounded pool (semaphore + queue).
        // R20: liveness check carries connection-alive epoch for lazy-drain on dead connection.
        boolean accepted = dispatchPool.submit(() -> dispatchHandler(conn, frame, handler),
                conn::isAlive);
        if (!accepted) {
            metrics.dispatchOverflow.incrementAndGet(); // R45(h)
        }
    }

    private void dispatchHandler(PeerConnection conn, Frame frame, MessageHandler handler) {
        Message msg = buildMessage(conn.remote(), frame);
        if (frame.streamId() == Frame.NO_REPLY_STREAM_ID) { // R12
            try {
                CompletableFuture<Message> response = handler.handle(conn.remote(), msg);
                if (response != null) {
                    // R34d: block this vthread until the async handler CF terminates so the
                    // R34b permit is held until "handler completion" per the v3 definition.
                    try {
                        response.get();
                    } catch (Exception ex) {
                        metrics.handlerExceptions.incrementAndGet(); // R45(m)
                    }
                }
            } catch (Exception e) { // R14 sync throw
                metrics.handlerExceptions.incrementAndGet();
            }
            return;
        }
        // R13: incoming request — invoke handler, await response CF terminal state, send.
        Message respMsg;
        try {
            CompletableFuture<Message> response = handler.handle(conn.remote(), msg);
            // R34d: hold the permit until the response CF terminates (sync or async).
            respMsg = response.get();
        } catch (Exception e) { // R14 — sync throw or async exceptional completion
            metrics.handlerExceptions.incrementAndGet();
            return;
        }
        // R28 + R34c-bis: if transport closed mid-handler, discard response silently.
        if (closed) {
            metrics.postCloseDiscards.incrementAndGet(); // R45(k)
            return;
        }
        try {
            conn.writeMessage(respMsg.type(), frame.streamId(), Frame.FLAG_RESPONSE,
                    respMsg.sequenceNumber(), respMsg.payload()); // R43 chunks if oversize
        } catch (IOException ioe) {
            metrics.writeFailures.incrementAndGet();
            LOG.log(Level.FINE, "response write failed", ioe);
        }
    }

    private Message buildMessage(NodeAddress sender, Frame frame) { // R41
        return new Message(frame.type(), sender, frame.sequenceNumber(), frame.body());
    }

    long nextSequenceNumber() {
        return sequenceCounter.getAndIncrement();
    }

    void onConnectionDead(PeerConnection conn) { // R20
        peers.remove(conn.remote().nodeId(), conn);
    }

    /**
     * Mark a peer as departed and tear down its connection. Implements R30 v3 split: step (1)
     * atomic — install cleanupBarrier in {@link #pendingCleanups}, remove from {@link #peers}; step
     * (2) dispatched on a virtual thread — close channel, fail pending futures, complete the
     * barrier.
     *
     * <p>
     * Idempotent: a second call for the same peer while cleanup is still in-flight is a no-op (the
     * existing barrier is left in place). A call for an unknown peer is also a no-op.
     *
     * <p>
     * Does not block the calling (notification) thread.
     *
     * @spec transport.multiplexed-framing.R30
     */
    public void peerDeparted(NodeAddress peer) {
        if (peer == null) {
            throw new IllegalArgumentException("peer must not be null");
        }
        final String nodeId = peer.nodeId();
        final PeerConnection conn;
        final CompletableFuture<Void> barrier;
        // Step (1) — atomic: install barrier and remove registry entry
        synchronized (peers) {
            if (pendingCleanups.containsKey(nodeId)) {
                // Cleanup already in-flight; idempotent no-op
                return;
            }
            conn = peers.remove(nodeId);
            if (conn == null) {
                // Peer not connected; nothing to clean up
                return;
            }
            barrier = new CompletableFuture<>();
            pendingCleanups.put(nodeId, barrier);
        }
        // Step (2) — dispatched: close channel, fail pending, complete barrier
        Thread.ofVirtual().name("jlsm-cluster-departure-" + nodeId).start(() -> {
            try {
                conn.close();
            } finally {
                pendingCleanups.remove(nodeId);
                barrier.complete(null);
            }
        });
    }

    /**
     * Wait for any in-flight peer-departure cleanup for the given peer to complete. Returns
     * immediately {@code true} if no cleanup is in progress.
     *
     * @spec transport.multiplexed-framing.R30a
     */
    public boolean awaitPeerCleanup(NodeAddress peer, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (peer == null) {
            throw new IllegalArgumentException("peer must not be null");
        }
        CompletableFuture<Void> barrier = pendingCleanups.get(peer.nodeId());
        if (barrier == null) {
            return true;
        }
        try {
            barrier.get(timeout, unit);
            return true;
        } catch (java.util.concurrent.ExecutionException e) {
            return barrier.isDone();
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        }
    }

    private static void closeQuietly(Channel c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
