package jlsm.cluster.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import jlsm.cluster.Message;
import jlsm.cluster.MessageType;
import jlsm.cluster.NodeAddress;

/**
 * Tests for R30 cleanupBarrier and R30a re-join await semantics (Pass 3 amendment, F018).
 *
 * @spec transport.multiplexed-framing.R30
 * @spec transport.multiplexed-framing.R30a
 */
class MultiplexedTransportPeerDepartureTest {

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
    void peerDepartedFailsPendingFutures() throws Exception {
        MultiplexedTransport client = startTransport("dep-client");
        MultiplexedTransport server = startTransport("dep-server");

        // Server registers a handler that NEVER responds — request will be pending
        server.registerHandler(MessageType.QUERY_REQUEST, (from, msg) -> new CompletableFuture<>());

        Message req = new Message(MessageType.QUERY_REQUEST, client.self(), 1L, "x".getBytes());
        CompletableFuture<Message> pending = client.request(server.self(), req);

        // Give time for the request to land in the server's pending registry
        Thread.sleep(100);

        // Now mark the server as departed from the client's perspective
        client.peerDeparted(server.self());

        // The pending future must complete exceptionally (R30 step 2)
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> pending.get(2, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, ee.getCause());
    }

    @Test
    void rejoiningPeerAwaitsCleanupBarrier() throws Exception {
        MultiplexedTransport client = startTransport("rejoin-client");
        MultiplexedTransport server = startTransport("rejoin-server");

        // Establish the connection first
        server.registerHandler(MessageType.PING, (from, msg) -> CompletableFuture
                .completedFuture(new Message(MessageType.ACK, server.self(), 0L, new byte[0])));
        client.send(server.self(), new Message(MessageType.PING, client.self(), 0L, new byte[0]));
        Thread.sleep(100);

        // Mark server as departed
        client.peerDeparted(server.self());

        // Wait for cleanup to complete (barrier should resolve quickly since pending was empty)
        boolean cleanupDone = client.awaitPeerCleanup(server.self(), 2, TimeUnit.SECONDS);
        assertTrue(cleanupDone, "cleanup barrier should resolve within timeout");

        // Now re-join: a new request should succeed (cleanup is done, new connection established)
        client.send(server.self(), new Message(MessageType.PING, client.self(), 1L, new byte[0]));
    }

    @Test
    void peerDepartedDoesNotBlockNotificationThread() throws Exception {
        MultiplexedTransport client = startTransport("nb-client");
        MultiplexedTransport server = startTransport("nb-server");

        server.registerHandler(MessageType.PING, (from, msg) -> CompletableFuture
                .completedFuture(new Message(MessageType.ACK, server.self(), 0L, new byte[0])));
        client.send(server.self(), new Message(MessageType.PING, client.self(), 0L, new byte[0]));
        Thread.sleep(100);

        // peerDeparted should return quickly (R30 "must not block the notification thread")
        long start = System.nanoTime();
        client.peerDeparted(server.self());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 100,
                "peerDeparted blocked for " + elapsedMs + "ms; should be < 100ms");
    }

    @Test
    void peerDepartedIsIdempotent() throws Exception {
        MultiplexedTransport client = startTransport("idem-client");
        MultiplexedTransport server = startTransport("idem-server");

        server.registerHandler(MessageType.PING, (from, msg) -> CompletableFuture
                .completedFuture(new Message(MessageType.ACK, server.self(), 0L, new byte[0])));
        client.send(server.self(), new Message(MessageType.PING, client.self(), 0L, new byte[0]));
        Thread.sleep(100);

        // Two consecutive departures must not throw
        client.peerDeparted(server.self());
        client.peerDeparted(server.self());
    }

    @Test
    void awaitPeerCleanupForUnknownPeerReturnsTrue() throws Exception {
        MultiplexedTransport t = startTransport("await-unknown");
        // R30a: if no cleanup is in progress (cleanupBarrier == null), await should return true
        assertTrue(t.awaitPeerCleanup(new NodeAddress("ghost", "127.0.0.1", 9999), 100,
                TimeUnit.MILLISECONDS));
    }
}
