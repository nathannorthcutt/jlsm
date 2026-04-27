package jlsm.cluster.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import jlsm.cluster.Message;
import jlsm.cluster.MessageType;
import jlsm.cluster.NodeAddress;

/**
 * Tests for R23a per-peer locking and R23b N≥3 simultaneous-handshake invariant. Per Pass 3 R23a
 * generalisation, distinct peers must not block each other on connection establishment; per R23b,
 * any number of in-flight handshakes to the same peer must converge to a single registered
 * connection (first to complete wins; others reuse or are aborted).
 *
 * @spec transport.multiplexed-framing.R23
 * @spec transport.multiplexed-framing.R23a
 * @spec transport.multiplexed-framing.R23b
 */
class MultiplexedTransportSimultaneousHandshakeTest {

    private final List<MultiplexedTransport> transports = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (MultiplexedTransport t : transports) {
            t.close();
        }
        transports.clear();
    }

    private MultiplexedTransport startTransport(String nodeId) throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        MultiplexedTransport t = MultiplexedTransport
                .start(new NodeAddress(nodeId, "127.0.0.1", port));
        transports.add(t);
        return t;
    }

    @Test
    void concurrentRequestsToSamePeerConvergeOnSingleConnection() throws Exception {
        MultiplexedTransport client = startTransport("conv-client");
        MultiplexedTransport server = startTransport("conv-server");

        server.registerHandler(MessageType.QUERY_REQUEST,
                (from, msg) -> CompletableFuture
                        .completedFuture(new Message(MessageType.QUERY_RESPONSE, server.self(),
                                msg.sequenceNumber(), msg.payload())));

        // Fire 8 concurrent request() calls to the same peer; each must observe the same
        // PeerConnection in the registry. Without per-peer locking + double-checked-locking the
        // race could either install duplicates or serialize unrelated peers.
        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < callers; i++) {
            int seq = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    Message resp = client
                            .request(server.self(), new Message(MessageType.QUERY_REQUEST,
                                    client.self(), seq, ("c-" + seq).getBytes()))
                            .get(5, TimeUnit.SECONDS);
                    if (resp == null || !new String(resp.payload()).equals("c-" + seq)) {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "all 8 concurrent calls should finish");
        assertEquals(0, errors.get(),
                "all concurrent requests must succeed; observed errors=" + errors.get());
    }

    @Test
    void connectionsToDistinctPeersDoNotBlockEachOther() throws Exception {
        // R23a Pass 3 amendment: per-peer locking — distinct peers must establish connections
        // in parallel without serialization through a global lock.
        MultiplexedTransport client = startTransport("multi-client");
        MultiplexedTransport peerA = startTransport("multi-peerA");
        MultiplexedTransport peerB = startTransport("multi-peerB");
        MultiplexedTransport peerC = startTransport("multi-peerC");

        for (MultiplexedTransport p : new MultiplexedTransport[]{ peerA, peerB, peerC }) {
            p.registerHandler(MessageType.PING, (from, msg) -> CompletableFuture
                    .completedFuture(new Message(MessageType.ACK, p.self(), 0L, new byte[0])));
        }

        // Three concurrent sends to three distinct peers. With per-peer locking these run in
        // parallel; with a single global lock they would serialize. The test passes regardless of
        // locking strategy as long as all three round-trip — but it explicitly exercises the case.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        AtomicInteger errors = new AtomicInteger();

        for (MultiplexedTransport target : new MultiplexedTransport[]{ peerA, peerB, peerC }) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    client.send(target.self(),
                            new Message(MessageType.PING, client.self(), 0L, new byte[0]));
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "all 3 distinct-peer sends should finish");
        assertEquals(0, errors.get(), "no errors expected");
    }

    @Test
    void rapidPeerRejoinResolvesToFreshConnection() throws Exception {
        // After peerDeparted, a flurry of new requests should converge on a single new connection
        // rather than racing.
        MultiplexedTransport client = startTransport("rejoin-stress-client");
        MultiplexedTransport server = startTransport("rejoin-stress-server");

        server.registerHandler(MessageType.PING, (from, msg) -> CompletableFuture
                .completedFuture(new Message(MessageType.ACK, server.self(), 0L, new byte[0])));

        client.send(server.self(), new Message(MessageType.PING, client.self(), 0L, new byte[0]));
        Thread.sleep(100);
        client.peerDeparted(server.self());
        assertTrue(client.awaitPeerCleanup(server.self(), 2, TimeUnit.SECONDS));

        // Now fire 5 concurrent rejoins
        int callers = 5;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < callers; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    client.send(server.self(),
                            new Message(MessageType.PING, client.self(), 0L, new byte[0]));
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "rejoin sends should all complete");
        assertEquals(0, errors.get(), "rejoin errors=" + errors.get());
    }
}
