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
 * This implementation lands the foundational behaviour required by R1-R29 and R45 (basic counter
 * surface). Multi-frame reassembly (R35-R37c), abuse-threshold handling (R37c),
 * cleanupBarrier-based peer-departure (R30 v3), bidirectional handshake tie-break (R23a/R23b),
 * accept-loop safe-publication option (a)/(b)/(c) (R39), and observability counters (j-m) are
 * tracked under WD-01 follow-up work — see {@code work-plan.md}.
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
 * @spec transport.multiplexed-framing.R33
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
    private final Map<MessageType, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final TransportMetrics metrics = new TransportMetrics();
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
        Frame frame = new Frame(msg.type(), Frame.NO_REPLY_STREAM_ID, (byte) 0,
                msg.sequenceNumber(), msg.payload()); // R7
        try {
            conn.write(frame); // R15-R17
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
            Frame frame = new Frame(msg.type(), streamId, (byte) 0, msg.sequenceNumber(),
                    msg.payload());
            try {
                conn.write(frame);
                future.orTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS); // R26
                future.whenComplete((r, t) -> conn.pending().remove(streamId, future)); // R26b/R27
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
    }

    private PeerConnection getOrConnect(NodeAddress target) throws IOException {
        PeerConnection existing = peers.get(target.nodeId());
        if (existing != null && existing.isOpen()) {
            return existing;
        }
        synchronized (peers) { // R23
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
                PeerConnection existing = peers.get(nodeId);
                if (existing != null && existing.isOpen()) {
                    // R23a tie-break (simplified): lower nodeId wins outbound.
                    if (self.nodeId().compareTo(nodeId) < 0) {
                        ch.close();
                        continue;
                    } else {
                        existing.close();
                    }
                }
                PeerConnection conn = new PeerConnection(remote, ch, this);
                peers.put(nodeId, conn);
                conn.startReader();
            } catch (IOException e) {
                metrics.handshakeFailures.incrementAndGet();
                closeQuietly(ch);
            }
        }
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
        Thread.ofVirtual() // R34
                .name("jlsm-cluster-handler-" + frame.type() + "-" + frame.streamId())
                .start(() -> dispatchHandler(conn, frame, handler));
    }

    private void dispatchHandler(PeerConnection conn, Frame frame, MessageHandler handler) {
        Message msg = buildMessage(conn.remote(), frame);
        if (frame.streamId() == Frame.NO_REPLY_STREAM_ID) { // R12
            try {
                handler.handle(conn.remote(), msg);
            } catch (Exception e) {
                metrics.handlerExceptions.incrementAndGet(); // R45(m)
            }
            return;
        }
        // R13: incoming request — invoke handler, send response on success.
        try {
            CompletableFuture<Message> response = handler.handle(conn.remote(), msg);
            response.whenComplete((respMsg, err) -> {
                if (err != null) { // R14
                    metrics.handlerExceptions.incrementAndGet();
                    return;
                }
                if (closed) {
                    metrics.postCloseDiscards.incrementAndGet(); // R45(k)/R28
                    return;
                }
                Frame respFrame = new Frame(respMsg.type(), frame.streamId(), Frame.FLAG_RESPONSE,
                        respMsg.sequenceNumber(), respMsg.payload());
                try {
                    conn.write(respFrame);
                } catch (IOException ioe) {
                    metrics.writeFailures.incrementAndGet();
                    LOG.log(Level.FINE, "response write failed", ioe);
                }
            });
        } catch (Exception e) { // R14 sync throw
            metrics.handlerExceptions.incrementAndGet();
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
