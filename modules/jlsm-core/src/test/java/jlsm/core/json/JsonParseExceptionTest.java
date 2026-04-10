package jlsm.core.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JsonParseException.
 */
class JsonParseExceptionTest {

    @Test
    void constructorWithMessageAndOffset() {
        JsonParseException ex = new JsonParseException("bad token", 42);
        assertEquals(42, ex.offset());
        assertTrue(ex.getMessage().contains("bad token"));
        assertTrue(ex.getMessage().contains("42"));
    }

    @Test
    void constructorWithCause() {
        Throwable cause = new RuntimeException("root cause");
        JsonParseException ex = new JsonParseException("parse error", 10, cause);
        assertEquals(10, ex.offset());
        assertSame(cause, ex.getCause());
        assertTrue(ex.getMessage().contains("parse error"));
    }

    @Test
    void negativeOffsetDoesNotAppearInMessage() {
        JsonParseException ex = new JsonParseException("unknown location", -1);
        assertEquals(-1, ex.offset());
        assertFalse(ex.getMessage().contains("byte offset"));
    }

    @Test
    void zeroOffsetIsValid() {
        JsonParseException ex = new JsonParseException("at start", 0);
        assertEquals(0, ex.offset());
        assertTrue(ex.getMessage().contains("0"));
    }

    @Test
    void isRuntimeException() {
        assertInstanceOf(RuntimeException.class, new JsonParseException("test", 0));
    }
}
