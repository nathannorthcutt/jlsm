package jlsm.cluster.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import jlsm.cluster.Message;
import jlsm.cluster.MessageType;
import jlsm.cluster.NodeAddress;

/**
 * Tests the R26b/R27 pending-map invariant under rapid request cycling. Exercises the R26b
 * scheduler-failure path indirectly: every request goes through {@code orTimeout} arming +
 * whenComplete cleanup; after a high volume of round-trips, no entries should leak.
 *
 * @spec transport.multiplexed-framing.R26b
 * @spec transport.multiplexed-framing.R27
 */
class MultiplexedTransportPendingInvariantTest {

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
    void pendingMapReturnsToZeroAfterRapidRequestCycling() throws Exception {
        MultiplexedTransport client = startTransport("inv-client");
        MultiplexedTransport server = startTransport("inv-server");

        server.registerHandler(MessageType.QUERY_REQUEST,
                (from, msg) -> CompletableFuture
                        .completedFuture(new Message(MessageType.QUERY_RESPONSE, server.self(),
                                msg.sequenceNumber(), msg.payload())));

        int cycles = 200;
        List<CompletableFuture<Message>> futures = new ArrayList<>(cycles);
        for (int i = 0; i < cycles; i++) {
            futures.add(client.request(server.self(), new Message(MessageType.QUERY_REQUEST,
                    client.self(), i, ("c-" + i).getBytes())));
        }
        for (CompletableFuture<Message> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        // Pending map should be empty — no leaks per R27. Also exercise the R26b cleanup path:
        // every future's whenComplete callback fires on response delivery and removes the entry.
        int pending = clientPendingSize(client, server.self());
        assertEquals(0, pending, "pending map must drain to 0; got " + pending);
    }

    @Test
    void pendingMapDrainsOnRequestPathExceptions() throws Exception {
        MultiplexedTransport client = startTransport("inv-fail-client");
        MultiplexedTransport server = startTransport("inv-fail-server");

        // Server immediately fails handlers — client requests time out / fail
        server.registerHandler(MessageType.QUERY_REQUEST, (from, msg) -> {
            CompletableFuture<Message> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("nope"));
            return f;
        });

        int cycles = 50;
        List<CompletableFuture<Message>> futures = new ArrayList<>(cycles);
        for (int i = 0; i < cycles; i++) {
            // Use a short request timeout via subsequent transport calls; default 30s would slow
            // this test. Instead, we inspect that the failed handler path triggers eventual
            // cleanup of the map even though the futures themselves remain unresolved by handler.
            futures.add(client.request(server.self(),
                    new Message(MessageType.QUERY_REQUEST, client.self(), i, "x".getBytes())));
        }

        // We don't await all 50 timeouts (would take 30s × 50). Instead close the server which
        // forces R20 path to fail-all-pending on the connection.
        server.close();

        // Wait briefly for the read-failure to propagate to the client side and clear pending
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && clientPendingSize(client, server.self()) > 0) {
            Thread.sleep(50);
        }
        // After R20 fail-all, pending must be empty
        int pending = clientPendingSize(client, server.self());
        assertEquals(0, pending, "after R20 connection-failure cleanup, pending must be 0");
    }

    /** Read the live PendingMap size for the connection to {@code peer}. */
    @SuppressWarnings("unchecked")
    private static int clientPendingSize(MultiplexedTransport client, NodeAddress peer)
            throws Exception {
        Field peersField = MultiplexedTransport.class.getDeclaredField("peers");
        peersField.setAccessible(true);
        Map<String, PeerConnection> peers = (Map<String, PeerConnection>) peersField.get(client);
        PeerConnection conn = peers.get(peer.nodeId());
        if (conn == null) {
            return 0;
        }
        return conn.pending().size();
    }
}
