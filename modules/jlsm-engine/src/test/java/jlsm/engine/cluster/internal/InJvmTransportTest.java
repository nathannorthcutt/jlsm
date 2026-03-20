package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.Message;
import jlsm.engine.cluster.MessageType;
import jlsm.engine.cluster.NodeAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InJvmTransport} — send, request, handler dispatch, close, registry.
 */
class InJvmTransportTest {

    private static final NodeAddress ADDR_A = new NodeAddress("a", "localhost", 8001);
    private static final NodeAddress ADDR_B = new NodeAddress("b", "localhost", 8002);

    @AfterEach
    void cleanup() {
        InJvmTransport.clearRegistry();
    }

    @Test
    void constructionRegistersInRegistry() {
        var transport = new InJvmTransport(ADDR_A);
        // Should not throw — a is registered
        assertNotNull(transport);
    }

    @Test
    void duplicateAddressThrows() {
        new InJvmTransport(ADDR_A);
        assertThrows(IllegalArgumentException.class, () -> new InJvmTransport(ADDR_A));
    }

    @Test
    void nullAddressThrows() {
        assertThrows(NullPointerException.class, () -> new InJvmTransport(null));
    }

    @Test
    void registerHandlerAndSend() throws IOException {
        var transportA = new InJvmTransport(ADDR_A);
        var transportB = new InJvmTransport(ADDR_B);

        var received = new AtomicReference<Message>();
        transportB.registerHandler(MessageType.PING, (sender, msg) -> {
            received.set(msg);
            return CompletableFuture.completedFuture(
                    new Message(MessageType.ACK, ADDR_B, msg.sequenceNumber(), new byte[0]));
        });

        var ping = new Message(MessageType.PING, ADDR_A, 1, new byte[]{42});
        transportA.send(ADDR_B, ping);

        // Handler should have been invoked
        assertNotNull(received.get());
        assertEquals(MessageType.PING, received.get().type());
    }

    @Test
    void requestReturnsResponse() throws ExecutionException, InterruptedException, TimeoutException {
        var transportA = new InJvmTransport(ADDR_A);
        var transportB = new InJvmTransport(ADDR_B);

        transportB.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) ->
                CompletableFuture.completedFuture(
                        new Message(MessageType.QUERY_RESPONSE, ADDR_B, msg.sequenceNumber(),
                                new byte[]{99})));

        var request = new Message(MessageType.QUERY_REQUEST, ADDR_A, 5, new byte[]{1});
        var responseFuture = transportA.request(ADDR_B, request);
        var response = responseFuture.get(5, TimeUnit.SECONDS);

        assertEquals(MessageType.QUERY_RESPONSE, response.type());
        assertArrayEquals(new byte[]{99}, response.payload());
    }

    @Test
    void sendToUnregisteredTargetThrows() {
        var transportA = new InJvmTransport(ADDR_A);
        var msg = new Message(MessageType.PING, ADDR_A, 0, new byte[0]);

        assertThrows(IOException.class, () -> transportA.send(ADDR_B, msg));
    }

    @Test
    void sendWithNoHandlerForTypeDoesNotThrow() throws IOException {
        var transportA = new InJvmTransport(ADDR_A);
        var transportB = new InJvmTransport(ADDR_B);
        // No handler registered on B for PING

        var msg = new Message(MessageType.PING, ADDR_A, 0, new byte[0]);
        // Should not throw — fire-and-forget with no handler is a no-op
        transportA.send(ADDR_B, msg);
    }

    @Test
    void closeUnregistersFromRegistry() throws Exception {
        var transportA = new InJvmTransport(ADDR_A);
        transportA.close();

        // Should be able to create a new transport with the same address
        var transportA2 = new InJvmTransport(ADDR_A);
        assertNotNull(transportA2);
    }

    @Test
    void sendAfterCloseThrows() throws Exception {
        var transportA = new InJvmTransport(ADDR_A);
        var transportB = new InJvmTransport(ADDR_B);
        transportA.close();

        var msg = new Message(MessageType.PING, ADDR_A, 0, new byte[0]);
        assertThrows(IOException.class, () -> transportA.send(ADDR_B, msg));
    }

    @Test
    void registerHandlerNullTypeThrows() {
        var transport = new InJvmTransport(ADDR_A);
        assertThrows(NullPointerException.class, () ->
                transport.registerHandler(null, (s, m) -> CompletableFuture.completedFuture(m)));
    }

    @Test
    void registerHandlerNullHandlerThrows() {
        var transport = new InJvmTransport(ADDR_A);
        assertThrows(NullPointerException.class, () ->
                transport.registerHandler(MessageType.PING, null));
    }

    @Test
    void closeIsIdempotent() throws Exception {
        var transport = new InJvmTransport(ADDR_A);
        transport.close();
        transport.close(); // Should not throw
    }
}
