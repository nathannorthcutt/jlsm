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
 * Tests for handler exception accounting (R14 + R45(m)) and async handler completion semantics
 * (R34d Pass 3 amendment).
 *
 * @spec transport.multiplexed-framing.R14
 * @spec transport.multiplexed-framing.R34d
 * @spec transport.multiplexed-framing.R45
 */
class MultiplexedTransportHandlerExceptionsTest {

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
                .start(new NodeAddress(nodeId, "127.0.0.1", port), 500L); // short timeout so test
                                                                          // failures don't hang
        transports.add(t);
        return t;
    }

    @Test
    void syncHandlerThrowIncrementsHandlerExceptionsCounter() throws Exception {
        MultiplexedTransport client = startTransport("sync-thr-client");
        MultiplexedTransport server = startTransport("sync-thr-server");

        server.registerHandler(MessageType.QUERY_REQUEST, (from, msg) -> {
            throw new RuntimeException("sync explode");
        });

        Message req = new Message(MessageType.QUERY_REQUEST, client.self(), 1L, "x".getBytes());
        CompletableFuture<Message> pending = client.request(server.self(), req);

        // The request should time out (R14 says no response is sent)
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> pending.get(2, TimeUnit.SECONDS));
        assertNotNull(ee.getCause());

        // Verify the server's handler-exceptions counter went up (R45(m))
        // The counter is incremented asynchronously; allow brief settle time
        Thread.sleep(100);
        assertTrue(server.metrics().handlerExceptions.get() >= 1,
                "R45(m) handlerExceptions counter should be >= 1; got "
                        + server.metrics().handlerExceptions.get());
    }

    @Test
    void asyncHandlerExceptionalCompletionIncrementsHandlerExceptionsCounter() throws Exception {
        // R34d: handler that returns a CF completing exceptionally must be counted as an
        // exception, just like a sync throw.
        MultiplexedTransport client = startTransport("async-thr-client");
        MultiplexedTransport server = startTransport("async-thr-server");

        server.registerHandler(MessageType.QUERY_REQUEST, (from, msg) -> {
            CompletableFuture<Message> f = new CompletableFuture<>();
            // Complete exceptionally on a separate thread so handler.handle() returns first
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                f.completeExceptionally(new RuntimeException("async explode"));
            });
            return f;
        });

        Message req = new Message(MessageType.QUERY_REQUEST, client.self(), 1L, "x".getBytes());
        CompletableFuture<Message> pending = client.request(server.self(), req);

        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> pending.get(2, TimeUnit.SECONDS));
        assertNotNull(ee.getCause());

        Thread.sleep(150);
        assertTrue(server.metrics().handlerExceptions.get() >= 1,
                "R45(m) handlerExceptions counter must increment on async failure");
    }

    @Test
    void noHandlerRegisteredIncrementsNoHandlerCounter() throws Exception {
        MultiplexedTransport client = startTransport("no-handler-client");
        MultiplexedTransport server = startTransport("no-handler-server");

        // No handler registered for QUERY_REQUEST
        Message req = new Message(MessageType.QUERY_REQUEST, client.self(), 1L, "x".getBytes());
        CompletableFuture<Message> pending = client.request(server.self(), req);

        // Should time out — no handler means no response per R12
        assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));

        Thread.sleep(100);
        assertTrue(server.metrics().noHandlerDiscards.get() >= 1,
                "R45(b) noHandlerDiscards must increment");
    }
}
