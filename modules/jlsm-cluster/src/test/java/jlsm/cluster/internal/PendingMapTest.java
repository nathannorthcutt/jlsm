package jlsm.cluster.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import jlsm.cluster.Message;

/**
 * @spec transport.multiplexed-framing.R6
 * @spec transport.multiplexed-framing.R6a
 * @spec transport.multiplexed-framing.R8
 * @spec transport.multiplexed-framing.R11
 * @spec transport.multiplexed-framing.R26b
 * @spec transport.multiplexed-framing.R27
 */
class PendingMapTest {

    @Test
    void registerAssignsMonotonicStreamIds() {
        PendingMap map = new PendingMap();
        int a = map.register(new CompletableFuture<>());
        int b = map.register(new CompletableFuture<>());
        int c = map.register(new CompletableFuture<>());
        assertTrue(a < b && b < c, "stream-ids monotonic: a=" + a + " b=" + b + " c=" + c);
        assertTrue(a > 0, "stream-ids never 0 or negative");
    }

    @Test
    void registerAtCapacityFailsFuture() throws Exception {
        PendingMap map = new PendingMap(1);
        CompletableFuture<Message> first = new CompletableFuture<>();
        assertEquals(1, map.register(first));

        CompletableFuture<Message> second = new CompletableFuture<>();
        int id = map.register(second);
        assertEquals(-1, id);
        assertTrue(second.isCompletedExceptionally());
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> second.get(50, TimeUnit.MILLISECONDS));
        assertInstanceOf(IOException.class, ee.getCause());
        assertTrue(ee.getCause().getMessage().contains("capacity"));
    }

    @Test
    void valueConditionalRemoveSucceedsOnIdentityMatch() {
        PendingMap map = new PendingMap();
        CompletableFuture<Message> f = new CompletableFuture<>();
        int id = map.register(f);
        assertTrue(map.remove(id, f));
        assertNull(map.lookup(id));
    }

    @Test
    void valueConditionalRemoveFailsOnDifferentValue() {
        PendingMap map = new PendingMap();
        CompletableFuture<Message> registered = new CompletableFuture<>();
        CompletableFuture<Message> stale = new CompletableFuture<>();
        int id = map.register(registered);
        // R26b: removing with a stale reference must NOT remove the registered entry
        assertFalse(map.remove(id, stale));
        assertSame(registered, map.lookup(id));
    }

    @Test
    void takeForResponseRemovesEntry() {
        PendingMap map = new PendingMap();
        CompletableFuture<Message> f = new CompletableFuture<>();
        int id = map.register(f);
        assertSame(f, map.takeForResponse(id));
        assertNull(map.lookup(id));
    }

    @Test
    void failAllCompletesFuturesExceptionallyAndClears() throws Exception {
        PendingMap map = new PendingMap();
        CompletableFuture<Message> a = new CompletableFuture<>();
        CompletableFuture<Message> b = new CompletableFuture<>();
        map.register(a);
        map.register(b);
        IOException cause = new IOException("connection died");
        map.failAll(cause);
        assertEquals(0, map.size());
        assertTrue(a.isCompletedExceptionally());
        assertTrue(b.isCompletedExceptionally());
        ExecutionException ea = assertThrows(ExecutionException.class,
                () -> a.get(50, TimeUnit.MILLISECONDS));
        assertSame(cause, ea.getCause());
    }

    @Test
    void rejectsZeroOrNegativeCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new PendingMap(0));
        assertThrows(IllegalArgumentException.class, () -> new PendingMap(-1));
    }

    @Test
    void registerRejectsNullFuture() {
        PendingMap map = new PendingMap();
        assertThrows(IllegalArgumentException.class, () -> map.register(null));
    }

    @Test
    void multipleRegisterAndCompleteCycleDoesNotLeak() throws TimeoutException {
        PendingMap map = new PendingMap();
        for (int i = 0; i < 1000; i++) {
            CompletableFuture<Message> f = new CompletableFuture<>();
            int id = map.register(f);
            assertSame(f, map.takeForResponse(id));
        }
        assertEquals(0, map.size(), "R27 — no entries leak across complete cycle");
    }
}
