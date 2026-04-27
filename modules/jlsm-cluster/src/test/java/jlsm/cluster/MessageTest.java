package jlsm.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Message} record — validation, defensive copies, sequence number.
 *
 * @spec engine.clustering.R8 — (type, sender, sequenceNumber, payload); defensive-copy on
 *       construction
 * @spec engine.clustering.R10 — monotonic sequence number exposed for de-duplication by receivers
 * @spec engine.clustering.R11 — empty payload accepted; null payload rejected with NPE
 */
class MessageTest {

    private static final NodeAddress SENDER = new NodeAddress("n1", "localhost", 8080);

    @Test
    void validConstruction() {
        var msg = new Message(MessageType.PING, SENDER, 0, new byte[]{ 1, 2, 3 });
        assertEquals(MessageType.PING, msg.type());
        assertEquals(SENDER, msg.sender());
        assertEquals(0, msg.sequenceNumber());
        assertArrayEquals(new byte[]{ 1, 2, 3 }, msg.payload());
    }

    @Test
    void emptyPayloadAllowed() {
        var msg = new Message(MessageType.ACK, SENDER, 0, new byte[0]);
        assertEquals(0, msg.payload().length);
    }

    @Test
    void nullTypeThrows() {
        assertThrows(NullPointerException.class, () -> new Message(null, SENDER, 0, new byte[0]));
    }

    @Test
    void nullSenderThrows() {
        assertThrows(NullPointerException.class,
                () -> new Message(MessageType.PING, null, 0, new byte[0]));
    }

    @Test
    void nullPayloadThrows() {
        assertThrows(NullPointerException.class,
                () -> new Message(MessageType.PING, SENDER, 0, null));
    }

    @Test
    void negativeSequenceNumberThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new Message(MessageType.PING, SENDER, -1, new byte[0]));
    }

    @Test
    void defensiveCopyOnConstruction() {
        byte[] original = { 1, 2, 3 };
        var msg = new Message(MessageType.PING, SENDER, 0, original);
        original[0] = 99;
        // Should not affect internal state
        assertEquals(1, msg.payload()[0]);
    }

    @Test
    void defensiveCopyOnAccess() {
        var msg = new Message(MessageType.PING, SENDER, 0, new byte[]{ 1, 2, 3 });
        byte[] firstAccess = msg.payload();
        firstAccess[0] = 99;
        // Second access should still see original value
        assertEquals(1, msg.payload()[0]);
    }
}
