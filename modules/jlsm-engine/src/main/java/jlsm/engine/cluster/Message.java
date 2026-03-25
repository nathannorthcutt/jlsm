package jlsm.engine.cluster;

import java.util.Objects;

/**
 * A typed message exchanged between cluster nodes over the {@link ClusterTransport}.
 *
 * <p>
 * Contract: Immutable value type encapsulating a message with its type, sender identity,
 * sequence number for deduplication/ordering, and an opaque payload. The payload format
 * is determined by the {@link MessageType}.
 *
 * <p>
 * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
 *
 * @param type           the message type; must not be null
 * @param sender         the address of the sending node; must not be null
 * @param sequenceNumber monotonically increasing sequence for ordering; must be non-negative
 * @param payload        the message payload bytes; must not be null (may be empty)
 */
public record Message(MessageType type, NodeAddress sender, long sequenceNumber, byte[] payload) {

    public Message {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException(
                    "sequenceNumber must be non-negative, got: " + sequenceNumber);
        }
        payload = payload.clone();
    }

    /**
     * Returns a defensive copy of the payload.
     *
     * @return a copy of the payload bytes; never null
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
