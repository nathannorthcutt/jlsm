package jlsm.cluster;

/**
 * Types of messages exchanged between cluster nodes.
 *
 * <p>
 * Contract: Enumeration of all message categories used by the clustering subsystem. Each
 * {@link Message} carries exactly one type, which determines how the payload is interpreted and
 * which handler processes it.
 *
 * <p>
 * Wire format ({@code transport.multiplexed-framing} R2a): each enum value maps to a stable
 * {@code byte} tag. The values must not be reordered. Unknown tags decode as a corrupt frame.
 *
 * <p>
 * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
 *
 * @spec engine.clustering.R9 — message types distinguish at minimum: ping, ack, view change, query
 *       request, query response, state digest, state delta
 * @spec transport.multiplexed-framing.R2a — type tag byte mapping (PING=0x00 .. STATE_DELTA=0x06)
 */
public enum MessageType {

    /** Liveness probe sent by a monitoring node. */
    PING((byte) 0x00),

    /** Acknowledgement of a {@link #PING}. */
    ACK((byte) 0x01),

    /** Membership view change proposal or notification. */
    VIEW_CHANGE((byte) 0x02),

    /** Query operation request to a remote partition owner. */
    QUERY_REQUEST((byte) 0x03),

    /** Response to a {@link #QUERY_REQUEST}. */
    QUERY_RESPONSE((byte) 0x04),

    /** Anti-entropy state digest for consistency checking. */
    STATE_DIGEST((byte) 0x05),

    /** Delta update in response to a {@link #STATE_DIGEST} mismatch. */
    STATE_DELTA((byte) 0x06);

    private static final MessageType[] BY_TAG = buildTagTable();

    private final byte tag;

    MessageType(byte tag) {
        this.tag = tag;
    }

    /** Returns this type's stable wire tag (R2a). */
    public byte tag() {
        return tag;
    }

    /** Maps a wire tag byte back to a {@link MessageType}, or {@code null} if unknown (R2a). */
    public static MessageType fromTag(byte tag) {
        final int idx = tag & 0xFF;
        if (idx < 0 || idx >= BY_TAG.length) {
            return null;
        }
        return BY_TAG[idx];
    }

    private static MessageType[] buildTagTable() {
        final MessageType[] table = new MessageType[256];
        for (final MessageType t : values()) {
            table[t.tag & 0xFF] = t;
        }
        return table;
    }
}
