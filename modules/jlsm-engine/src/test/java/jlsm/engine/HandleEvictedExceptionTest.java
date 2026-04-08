package jlsm.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HandleEvictedExceptionTest {

    // --- Happy path ---

    @Test
    void testHandleEvictedExceptionFields() {
        var site = new StackTraceElement[]{
                new StackTraceElement("com.example.App", "run", "App.java", 42) };
        var ex = new HandleEvictedException("users", "thread-1", 5, site,
                HandleEvictedException.Reason.EVICTION);

        assertEquals("users", ex.tableName());
        assertEquals("thread-1", ex.sourceId());
        assertEquals(5, ex.handleCountAtEviction());
        assertArrayEquals(site, ex.allocationSite());
        assertEquals(HandleEvictedException.Reason.EVICTION, ex.reason());

        // Message should contain diagnostic info
        assertTrue(ex.getMessage().contains("users"));
        assertTrue(ex.getMessage().contains("thread-1"));
        assertTrue(ex.getMessage().contains("EVICTION"));
    }

    @Test
    void testHandleEvictedExceptionNullAllocationSite() {
        var ex = new HandleEvictedException("orders", "conn-2", 3, null,
                HandleEvictedException.Reason.ENGINE_SHUTDOWN);

        assertEquals("orders", ex.tableName());
        assertNull(ex.allocationSite());
        assertEquals(HandleEvictedException.Reason.ENGINE_SHUTDOWN, ex.reason());
    }

    @Test
    void testHandleEvictedExceptionAllReasons() {
        for (var reason : HandleEvictedException.Reason.values()) {
            var ex = new HandleEvictedException("t", "s", 0, null, reason);
            assertEquals(reason, ex.reason());
            assertTrue(ex.getMessage().contains(reason.name()));
        }
    }

    // --- Error cases ---

    @Test
    void testHandleEvictedExceptionNullTableNameThrows() {
        assertThrows(NullPointerException.class, () -> new HandleEvictedException(null, "src", 0,
                null, HandleEvictedException.Reason.EVICTION));
    }

    @Test
    void testHandleEvictedExceptionNullSourceIdThrows() {
        assertThrows(NullPointerException.class, () -> new HandleEvictedException("t", null, 0,
                null, HandleEvictedException.Reason.EVICTION));
    }

    @Test
    void testHandleEvictedExceptionNullReasonThrows() {
        assertThrows(NullPointerException.class,
                () -> new HandleEvictedException("t", "src", 0, null, null));
    }

    // --- Boundary ---

    @Test
    void testHandleEvictedExceptionZeroHandleCount() {
        var ex = new HandleEvictedException("t", "s", 0, null,
                HandleEvictedException.Reason.TABLE_DROPPED);

        assertEquals(0, ex.handleCountAtEviction());
    }

    @Test
    void testHandleEvictedExceptionIsIllegalStateException() {
        var ex = new HandleEvictedException("t", "s", 0, null,
                HandleEvictedException.Reason.EVICTION);

        assertInstanceOf(IllegalStateException.class, ex);
    }
}
