package jlsm.engine.cluster.internal;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Objects;

import jlsm.engine.cluster.NodeAddress;

/**
 * Immutable payload for the {@code ALIVE_REFUTATION} VIEW_CHANGE sub-type (byte {@code 0x07}).
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Broadcast by a node that discovers a {@code SUSPICION_PROPOSAL} targets itself; the node
 * bumps its incarnation and emits this refutation to invalidate any in-flight round.</li>
 * <li>Big-endian wire framing: {@code [subType:1=0x07][subject-addr][incarnation:8][epoch:8]}
 * (fields serialized in record declaration order).</li>
 * <li>{@code NodeAddress} encoding reuses
 * {@code [nodeIdLen:4][nodeId:n][hostLen:4][host:n][port:4]} shared with
 * {@code RapidMembership.encodeJoinPayload}.</li>
 * <li>Null {@code subject} → {@link NullPointerException}.</li>
 * <li>Negative {@code incarnation} or {@code epoch} → {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * <p>
 * Governed by: F04.R38 and .decisions/cluster-membership-protocol/adr.md.
 */
public record AliveRefutation(NodeAddress subject, long incarnation, long epoch) {

    /** VIEW_CHANGE sub-type byte for this payload. */
    public static final byte SUB_TYPE = 0x07;

    public AliveRefutation {
        Objects.requireNonNull(subject, "subject must not be null");
        if (incarnation < 0L) {
            throw new IllegalArgumentException(
                    "incarnation must be non-negative, got: " + incarnation);
        }
        if (epoch < 0L) {
            throw new IllegalArgumentException("epoch must be non-negative, got: " + epoch);
        }
    }

    public byte[] serialize() {
        final byte[] subjectBytes = NodeAddressCodec.encode(subject);
        final ByteBuffer buf = ByteBuffer.allocate(1 + subjectBytes.length + 8 + 8);
        buf.put(SUB_TYPE);
        buf.put(subjectBytes);
        buf.putLong(incarnation);
        buf.putLong(epoch);
        return buf.array();
    }

    public static AliveRefutation deserialize(byte[] payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (payload.length == 0) {
            throw new IllegalArgumentException("insufficient bytes for field subType");
        }
        if (payload[0] != SUB_TYPE) {
            throw new IllegalArgumentException(
                    "expected sub-type 0x07, got: 0x" + String.format("%02x", payload[0] & 0xFF));
        }
        final ByteBuffer buf = ByteBuffer.wrap(payload, 1, payload.length - 1);
        try {
            final NodeAddress subject = NodeAddressCodec.decode(buf, "subject");
            final long incarnation = buf.getLong();
            final long epoch = buf.getLong();
            if (buf.hasRemaining()) {
                throw new IllegalArgumentException(
                        "trailing bytes after epoch: " + buf.remaining());
            }
            return new AliveRefutation(subject, incarnation, epoch);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("insufficient bytes for AliveRefutation payload", e);
        }
    }
}
