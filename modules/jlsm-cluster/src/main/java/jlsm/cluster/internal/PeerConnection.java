package jlsm.cluster.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
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

    private final NodeAddress remote;
    private final SocketChannel channel;
    private final MultiplexedTransport transport;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final PendingMap pending = new PendingMap();
    private final int maxFrameSize;
    private volatile Thread readerThread;
    private volatile boolean dead = false;

    PeerConnection(NodeAddress remote, SocketChannel channel, MultiplexedTransport transport) {
        this.remote = remote;
        this.channel = channel;
        this.transport = transport;
        this.maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
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
     * Encodes and writes a frame; loops on partial writes (R17). Must be called from any thread —
     * write serialization is enforced by the lock.
     *
     * @spec transport.multiplexed-framing.R15
     * @spec transport.multiplexed-framing.R15a
     * @spec transport.multiplexed-framing.R17
     */
    public void write(Frame frame) throws IOException {
        if (dead) {
            throw new ClosedChannelException();
        }
        byte[] wire = FrameCodec.encode(frame);
        ByteBuffer buf = ByteBuffer.wrap(wire);
        writeLock.lock();
        try {
            while (buf.hasRemaining()) { // R17
                int n = channel.write(buf);
                if (n < 0) {
                    throw new IOException("end-of-stream during write");
                }
            }
        } finally {
            writeLock.unlock();
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
                transport.dispatch(this, frame); // R11-R14
            }
        } catch (IOException e) {
            if (!dead) {
                LOG.log(Level.FINE, "reader loop terminated", e);
            }
        } finally {
            markDead(new IOException("connection closed"));
        }
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
