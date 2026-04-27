package jlsm.cluster.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import jlsm.cluster.NodeAddress;

/**
 * Per-peer connection state: SocketChannel + write lock + pending map + reader thread.
 *
 * @spec transport.multiplexed-framing.R15 — write serialization via ReentrantLock
 * @spec transport.multiplexed-framing.R16 — ReentrantLock (not synchronized)
 * @spec transport.multiplexed-framing.R17 — partial-write loop while holding lock
 * @spec transport.multiplexed-framing.R18 — per-connection reader virtual thread
 * @spec transport.multiplexed-framing.R19 — partial-read accumulation
 * @spec transport.multiplexed-framing.R20 — read-failure cleanup
 */
public final class PeerConnection {

    private static final Logger LOG = Logger.getLogger(PeerConnection.class.getName());

    /** Default max frame size for R5 enforcement. */
    private static final int DEFAULT_MAX_FRAME_SIZE = 2 * 1024 * 1024;

    /** Default per-stream reassembled-message limit (R37). */
    private static final long DEFAULT_REASSEMBLY_PER_STREAM_BYTES = 64L * 1024 * 1024;

    /** Default global reassembly buffer budget (R37a). */
    private static final long DEFAULT_REASSEMBLY_BUDGET_BYTES = 64L * 1024 * 1024;

    private final NodeAddress remote;
    private final SocketChannel channel;
    private final MultiplexedTransport transport;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final PendingMap pending = new PendingMap();
    private final Reassembler reassembler;
    private final AbuseTracker abuseTracker = new AbuseTracker();
    private final int maxFrameSize;
    private volatile Thread readerThread;
    private volatile boolean dead = false;

    PeerConnection(NodeAddress remote, SocketChannel channel, MultiplexedTransport transport) {
        this.remote = remote;
        this.channel = channel;
        this.transport = transport;
        this.maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
        this.reassembler = new Reassembler(DEFAULT_REASSEMBLY_PER_STREAM_BYTES,
                DEFAULT_REASSEMBLY_BUDGET_BYTES);
    }

    public NodeAddress remote() {
        return remote;
    }

    public PendingMap pending() {
        return pending;
    }

    public boolean isOpen() {
        return !dead && channel.isOpen();
    }

    /**
     * Encodes and writes a single frame; loops on partial writes (R17). Must be called from any
     * thread — write serialization is enforced by the lock.
     *
     * @spec transport.multiplexed-framing.R15
     * @spec transport.multiplexed-framing.R17
     */
    public void write(Frame frame) throws IOException {
        if (dead) {
            throw new ClosedChannelException();
        }
        writeLock.lock();
        try {
            writeFrameLocked(frame);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Encodes and writes a logical message, chunking if body exceeds the configurable
     * max-body-per-frame (R43). Holds the write lock for the entire chunk sequence so chunks are
     * not interleaved with other writers' frames (R15a).
     *
     * @spec transport.multiplexed-framing.R15
     * @spec transport.multiplexed-framing.R15a
     * @spec transport.multiplexed-framing.R17
     * @spec transport.multiplexed-framing.R43
     * @spec transport.multiplexed-framing.R43a
     * @spec transport.multiplexed-framing.R44
     */
    public void writeMessage(jlsm.cluster.MessageType type, int streamId, byte baseFlags,
            long sequenceNumber, byte[] body) throws IOException {
        if (dead) {
            throw new ClosedChannelException();
        }
        int maxBodyPerFrame = maxFrameSize - Frame.HEADER_SIZE;
        java.util.List<Frame> chunks = Chunker.split(type, streamId, baseFlags, sequenceNumber,
                body, maxBodyPerFrame);
        writeLock.lock();
        try {
            for (Frame f : chunks) {
                writeFrameLocked(f); // R15a: write lock held across all chunks
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void writeFrameLocked(Frame frame) throws IOException {
        byte[] wire = FrameCodec.encode(frame);
        ByteBuffer buf = ByteBuffer.wrap(wire);
        while (buf.hasRemaining()) { // R17
            int n = channel.write(buf);
            if (n < 0) {
                throw new IOException("end-of-stream during write");
            }
        }
    }

    /** Starts the reader virtual thread. */
    void startReader() {
        readerThread = Thread.ofVirtual().name("jlsm-cluster-reader-" + remote.nodeId())
                .start(this::readerLoop);
    }

    /**
     * Reader loop. Accumulates partial reads, decodes frames, dispatches to the transport.
     *
     * @spec transport.multiplexed-framing.R18
     * @spec transport.multiplexed-framing.R19
     * @spec transport.multiplexed-framing.R20
     */
    private void readerLoop() {
        try {
            ByteBuffer lengthBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            while (!dead && channel.isOpen()) {
                lengthBuf.clear();
                MultiplexedTransport.readFully(channel, lengthBuf); // R19
                lengthBuf.flip();
                int length = lengthBuf.getInt();
                if (length < Frame.HEADER_SIZE) { // R4
                    throw new IOException("corrupt frame: length " + length);
                }
                if (length > maxFrameSize) { // R5
                    throw new IOException("frame exceeds max size: " + length);
                }
                ByteBuffer frameBuf = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
                MultiplexedTransport.readFully(channel, frameBuf);
                frameBuf.flip();
                Frame frame = FrameCodec.decode(length, frameBuf);
                processFrame(frame); // R35-R38 reassembly + R37c abuse threshold + R11-R14 dispatch
            }
        } catch (IOException e) {
            if (!dead) {
                LOG.log(Level.FINE, "reader loop terminated", e);
            }
        } finally {
            markDead(new IOException("connection closed"));
        }
    }

    /**
     * Routes a single decoded frame through the reassembler, then dispatches if complete. Tracks
     * R37c per-connection abuse threshold and closes the connection if exceeded.
     *
     * @spec transport.multiplexed-framing.R35
     * @spec transport.multiplexed-framing.R35a
     * @spec transport.multiplexed-framing.R37
     * @spec transport.multiplexed-framing.R37c
     */
    private void processFrame(Frame frame) throws IOException {
        Reassembler.Result result = reassembler.accept(frame);
        switch (result.outcome()) {
            case DISPATCH:
                transport.dispatch(this, result.dispatched()); // R11-R14
                break;
            case BUFFER:
                // continue reading; no dispatch this frame
                break;
            case DRAINED:
                transport.metrics().reassemblyLimitExceeded.incrementAndGet(); // R45(d)
                if (abuseTracker.recordViolation()) { // R37c
                    transport.metrics().corruptFrameDisconnections.incrementAndGet();
                    LOG.log(Level.WARNING,
                            "peer {0} exceeded R37c abuse threshold; closing connection",
                            remote.nodeId());
                    throw new IOException(
                            "R37c abuse threshold exceeded for peer " + remote.nodeId());
                }
                break;
            case SIZE_LIMIT_END:
                transport.metrics().reassemblyLimitExceeded.incrementAndGet();
                // For inbound (no pending future on our side): R37 says no response, count + drop.
                // For outbound (pending future for our request): MultiplexedTransport.dispatch is
                // not called; the pending future will time out via R26.
                CompletableFuture<jlsm.cluster.Message> pendingFuture = pending
                        .lookup(result.streamId());
                if (pendingFuture != null) {
                    // Outbound: complete pending exceptionally per R37
                    pendingFuture.completeExceptionally(
                            new IOException("response exceeded reassembly limit"));
                    pending.remove(result.streamId(), pendingFuture);
                } else {
                    // Inbound oversized request — silent drop with counter (R45(l))
                    transport.metrics().requestTooLargeDiscarded.incrementAndGet();
                }
                if (abuseTracker.recordViolation()) { // R37c
                    transport.metrics().corruptFrameDisconnections.incrementAndGet();
                    throw new IOException(
                            "R37c abuse threshold exceeded after size-limit-end for peer "
                                    + remote.nodeId());
                }
                break;
            case CORRUPT:
                transport.metrics().corruptFrameDisconnections.incrementAndGet();
                throw new IOException("corrupt frame: chunk header inconsistent with first chunk");
            default:
                throw new AssertionError("unhandled reassembly outcome: " + result.outcome());
        }
    }

    /** Liveness check for {@link DispatchPool} — used by R20 v3 lazy-drain. */
    public boolean isAlive() {
        return !dead;
    }

    private synchronized void markDead(Throwable cause) { // R20
        if (dead) {
            return;
        }
        dead = true;
        pending.failAll(cause);
        closeQuietly(channel);
        transport.onConnectionDead(this);
    }

    /** Closes this connection, completing pending futures exceptionally. */
    public void close() {
        markDead(new IOException("connection closed by transport")); // R28
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    private static void closeQuietly(Channel c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
