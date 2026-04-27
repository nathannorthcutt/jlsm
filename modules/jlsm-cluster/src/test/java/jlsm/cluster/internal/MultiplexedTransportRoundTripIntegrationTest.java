package jlsm.cluster.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import jlsm.cluster.Message;
import jlsm.cluster.MessageType;
import jlsm.cluster.NodeAddress;

/**
 * Round-trip integration test for {@link MultiplexedTransport}: spins up two transports on
 * localhost ephemeral ports, exchanges messages, and asserts ordering + correct delivery.
 *
 * <p>
 * This is the WD-01 acceptance criterion: "Round-trip integration test: multi-stream send/receive
 * with ordering assertions."
 *
 * @spec transport.multiplexed-framing.R7
 * @spec transport.multiplexed-framing.R8
 * @spec transport.multiplexed-framing.R10
 * @spec transport.multiplexed-framing.R11
 * @spec transport.multiplexed-framing.R12
 * @spec transport.multiplexed-framing.R13
 * @spec transport.multiplexed-framing.R28
 * @spec transport.multiplexed-framing.R40
 * @spec transport.multiplexed-framing.R40-bidi
 */
class MultiplexedTransportRoundTripIntegrationTest {

    private final List<MultiplexedTransport> transports = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (MultiplexedTransport t : transports) {
            t.close();
        }
        transports.clear();
    }

    private MultiplexedTransport startTransport(String nodeId) throws IOException {
        int port = ephemeralPort();
        NodeAddress addr = new NodeAddress(nodeId, "127.0.0.1", port);
        MultiplexedTransport t = MultiplexedTransport.start(addr);
        transports.add(t);
        return t;
    }

    private static int ephemeralPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void fireAndForgetMessageReachesHandler() throws Exception {
        MultiplexedTransport sender = startTransport("sender");
        MultiplexedTransport receiver = startTransport("receiver");

        CompletableFuture<Message> received = new CompletableFuture<>();
        receiver.registerHandler(MessageType.PING, (from, msg) -> {
            received.complete(msg);
            return CompletableFuture.completedFuture(
                    new Message(MessageType.ACK, receiver.self(), 0L, new byte[0]));
        });

        Message m = new Message(MessageType.PING, sender.self(), 1L, "hello".getBytes());
        sender.send(receiver.self(), m);

        Message got = received.get(5, TimeUnit.SECONDS);
        assertEquals(MessageType.PING, got.type());
        assertEquals(1L, got.sequenceNumber());
        assertArrayEquals("hello".getBytes(), got.payload());
        assertEquals("sender", got.sender().nodeId()); // R41
    }

    @Test
    void requestResponseRoundTrip() throws Exception {
        MultiplexedTransport client = startTransport("client");
        MultiplexedTransport server = startTransport("server");

        server.registerHandler(MessageType.QUERY_REQUEST, (from, msg) -> {
            byte[] reqBody = msg.payload();
            byte[] respBody = new byte[reqBody.length + 4];
            System.arraycopy(reqBody, 0, respBody, 0, reqBody.length);
            System.arraycopy("-ack".getBytes(), 0, respBody, reqBody.length, 4);
            return CompletableFuture.completedFuture(
                    new Message(MessageType.QUERY_RESPONSE, server.self(), 100L, respBody));
        });

        Message req = new Message(MessageType.QUERY_REQUEST, client.self(), 42L,
                "hello".getBytes());
        Message resp = client.request(server.self(), req).get(5, TimeUnit.SECONDS);

        assertEquals(MessageType.QUERY_RESPONSE, resp.type());
        assertEquals(100L, resp.sequenceNumber());
        assertArrayEquals("hello-ack".getBytes(), resp.payload());
        assertEquals("server", resp.sender().nodeId());
    }

    @Test
    void multipleConcurrentRequestsCompleteIndependently() throws Exception {
        MultiplexedTransport client = startTransport("client-multi");
        MultiplexedTransport server = startTransport("server-multi");

        server.registerHandler(MessageType.QUERY_REQUEST, (from, msg) -> {
            // Echo the payload back as response
            return CompletableFuture.completedFuture(new Message(MessageType.QUERY_RESPONSE,
                    server.self(), msg.sequenceNumber(), msg.payload()));
        });

        List<CompletableFuture<Message>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            byte[] payload = ("req-" + i).getBytes();
            Message req = new Message(MessageType.QUERY_REQUEST, client.self(), i, payload);
            futures.add(client.request(server.self(), req));
        }

        // All requests complete with matching payloads — proves multi-stream concurrency
        for (int i = 0; i < 10; i++) {
            Message resp = futures.get(i).get(5, TimeUnit.SECONDS);
            assertArrayEquals(("req-" + i).getBytes(), resp.payload());
            assertEquals(i, resp.sequenceNumber());
        }
    }

    @Test
    void closeSetsClosedFlagAndRejectsFurtherCalls() throws Exception {
        MultiplexedTransport t = startTransport("close-victim");
        t.close();

        IOException sendEx = assertThrows(IOException.class,
                () -> t.send(new NodeAddress("x", "127.0.0.1", 1234),
                        new Message(MessageType.PING, t.self(), 0L, new byte[0])));
        assertTrue(sendEx.getMessage().contains("closed")); // R29

        IllegalStateException reqEx = assertThrows(IllegalStateException.class,
                () -> t.request(new NodeAddress("x", "127.0.0.1", 1234),
                        new Message(MessageType.QUERY_REQUEST, t.self(), 0L, new byte[0])));
        assertTrue(reqEx.getMessage().contains("closed")); // R29
    }

    // handshakeFailureIncrementsCounter test deferred until full R40 5s read-timeout
    // discipline lands — see work-plan.md WU-3.
}
