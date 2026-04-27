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
 * Integration tests exercising R43 outbound chunking and R35-R38 inbound reassembly over real
 * localhost TCP. Body sizes deliberately exceed the default 2 MiB max frame size to force the
 * chunking path.
 *
 * @spec transport.multiplexed-framing.R35
 * @spec transport.multiplexed-framing.R36
 * @spec transport.multiplexed-framing.R38
 * @spec transport.multiplexed-framing.R43
 * @spec transport.multiplexed-framing.R43a
 * @spec transport.multiplexed-framing.R44
 */
class MultiplexedTransportLargeMessageTest {

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
        NodeAddress addr = new NodeAddress(nodeId, "127.0.0.1", port);
        MultiplexedTransport t = MultiplexedTransport.start(addr);
        transports.add(t);
        return t;
    }

    @Test
    void requestResponseWithBodyExceedingFrameSizeRoundTrips() throws Exception {
        MultiplexedTransport client = startTransport("lm-client");
        MultiplexedTransport server = startTransport("lm-server");

        // 5 MiB body — well over the 2 MiB max frame size; will chunk
        int bodySize = 5 * 1024 * 1024;
        byte[] payload = new byte[bodySize];
        for (int i = 0; i < bodySize; i++) {
            payload[i] = (byte) (i & 0xFF);
        }

        server.registerHandler(MessageType.QUERY_REQUEST, (from, msg) -> {
            // Echo body back, exact same size — also will chunk
            return CompletableFuture.completedFuture(new Message(MessageType.QUERY_RESPONSE,
                    server.self(), msg.sequenceNumber(), msg.payload()));
        });

        Message req = new Message(MessageType.QUERY_REQUEST, client.self(), 1L, payload);
        Message resp = client.request(server.self(), req).get(15, TimeUnit.SECONDS);

        assertEquals(MessageType.QUERY_RESPONSE, resp.type());
        assertEquals(bodySize, resp.payload().length);
        // Verify byte-level fidelity
        byte[] gotPayload = resp.payload();
        for (int i = 0; i < bodySize; i++) {
            if (gotPayload[i] != (byte) (i & 0xFF)) {
                fail("byte mismatch at index " + i + ": expected " + ((byte) (i & 0xFF)) + " got "
                        + gotPayload[i]);
            }
        }
    }

    @Test
    void interleavedLargeAndSmallRequestsBothComplete() throws Exception {
        MultiplexedTransport client = startTransport("lm-mix-client");
        MultiplexedTransport server = startTransport("lm-mix-server");

        server.registerHandler(MessageType.QUERY_REQUEST,
                (from, msg) -> CompletableFuture
                        .completedFuture(new Message(MessageType.QUERY_RESPONSE, server.self(),
                                msg.sequenceNumber(), msg.payload())));

        // Send a 4 MiB request first, then a 100-byte request. Both should round-trip.
        // The big one will be chunked; the small one is single-frame.
        byte[] big = new byte[4 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 0x55);
        byte[] small = "hello".getBytes();

        CompletableFuture<Message> bigF = client.request(server.self(),
                new Message(MessageType.QUERY_REQUEST, client.self(), 100L, big));
        // Small request comes after — must not be blocked by reassembly of big response
        CompletableFuture<Message> smallF = client.request(server.self(),
                new Message(MessageType.QUERY_REQUEST, client.self(), 200L, small));

        Message bigResp = bigF.get(15, TimeUnit.SECONDS);
        Message smallResp = smallF.get(15, TimeUnit.SECONDS);

        assertEquals(big.length, bigResp.payload().length);
        assertArrayEquals(small, smallResp.payload());
        assertEquals(100L, bigResp.sequenceNumber());
        assertEquals(200L, smallResp.sequenceNumber());
    }

    @Test
    void fireAndForgetExceedingFrameSizeIsRejected() throws Exception {
        MultiplexedTransport client = startTransport("lm-faf-client");
        MultiplexedTransport server = startTransport("lm-faf-server");

        // 3 MiB body on a fire-and-forget — must throw per R44
        byte[] big = new byte[3 * 1024 * 1024];
        Message m = new Message(MessageType.PING, client.self(), 0L, big);
        // Trigger connection establishment first by registering a no-op handler so handshake is
        // fine
        server.registerHandler(MessageType.PING, (from, msg) -> CompletableFuture
                .completedFuture(new Message(MessageType.ACK, server.self(), 0L, new byte[0])));

        // R44: fire-and-forget cannot be chunked → IOException wraps IllegalArgumentException
        // since `send` throws IOException only and Chunker throws IAE. The transport surfaces this
        // as the IAE directly because writeMessage doesn't catch it.
        Exception ex = assertThrows(Exception.class, () -> client.send(server.self(), m));
        assertTrue(
                ex.getMessage().contains("fire-and-forget") || (ex.getCause() != null
                        && ex.getCause().getMessage().contains("fire-and-forget")),
                "expected R44 message about fire-and-forget; got: " + ex);
    }
}
