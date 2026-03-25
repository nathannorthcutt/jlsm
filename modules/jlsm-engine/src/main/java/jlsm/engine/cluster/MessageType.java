package jlsm.engine.cluster;

/**
 * Types of messages exchanged between cluster nodes.
 *
 * <p>
 * Contract: Enumeration of all message categories used by the clustering subsystem.
 * Each {@link Message} carries exactly one type, which determines how the payload is
 * interpreted and which handler processes it.
 *
 * <p>
 * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
 */
public enum MessageType {

    /** Liveness probe sent by a monitoring node. */
    PING,

    /** Acknowledgement of a {@link #PING}. */
    ACK,

    /** Membership view change proposal or notification. */
    VIEW_CHANGE,

    /** Query operation request to a remote partition owner. */
    QUERY_REQUEST,

    /** Response to a {@link #QUERY_REQUEST}. */
    QUERY_RESPONSE,

    /** Anti-entropy state digest for consistency checking. */
    STATE_DIGEST,

    /** Delta update in response to a {@link #STATE_DIGEST} mismatch. */
    STATE_DELTA
}
