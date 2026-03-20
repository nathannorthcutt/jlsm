package jlsm.engine.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MessageType} enum completeness.
 */
class MessageTypeTest {

    @Test
    void allValuesPresent() {
        var values = MessageType.values();
        assertEquals(7, values.length);
        assertNotNull(MessageType.PING);
        assertNotNull(MessageType.ACK);
        assertNotNull(MessageType.VIEW_CHANGE);
        assertNotNull(MessageType.QUERY_REQUEST);
        assertNotNull(MessageType.QUERY_RESPONSE);
        assertNotNull(MessageType.STATE_DIGEST);
        assertNotNull(MessageType.STATE_DELTA);
    }

    @Test
    void valueOfRoundTrip() {
        for (MessageType mt : MessageType.values()) {
            assertEquals(mt, MessageType.valueOf(mt.name()));
        }
    }
}
