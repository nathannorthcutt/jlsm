package jlsm.engine.cluster.internal;

import jlsm.cluster.internal.NodeAddressCodec;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Objects;

import jlsm.cluster.NodeAddress;

/**
 * Immutable payload for the {@code SUSPICION_PROPOSAL} VIEW_CHANGE sub-type (byte {@code 0x05}).
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Big-endian wire framing:
 * {@code [subType:1=0x05][subjectInc:8][roundId:8][epoch:8][subject-addr][proposer-addr]} (fields
 * serialized in record declaration order).</li>
 * <li>{@code NodeAddress} encoding reuses the
 * {@code [nodeIdLen:4][nodeId-utf8:n][hostLen:4][host-utf8:n][port:4]} convention shared with
 * {@code RapidMembership.encodeJoinPayload} / {@code decodeNodeAddress}.</li>
 * <li>Null {@code subject} or {@code proposer} → {@link NullPointerException}.</li>
 * <li>Negative {@code subjectIncarnation}, {@code roundId} or {@code epoch} →
 * {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * <p>
 * Governed by: F04.R34, R36, R37, R38 and .decisions/cluster-membership-protocol/adr.md.
 */
public record SuspicionProposal(NodeAddress subject, long subjectIncarnation, long roundId,
        long epoch, NodeAddress proposer) {

    /** VIEW_CHANGE sub-type byte for this payload. */
    public static final byte SUB_TYPE = 0x05;

    public SuspicionProposal {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(proposer, "proposer must not be null");
        if (subjectIncarnation < 0L) {
            throw new IllegalArgumentException(
                    "subjectIncarnation must be non-negative, got: " + subjectIncarnation);
        }
        if (roundId < 0L) {
            throw new IllegalArgumentException("roundId must be non-negative, got: " + roundId);
        }
        if (epoch < 0L) {
            throw new IllegalArgumentException("epoch must be non-negative, got: " + epoch);
        }
    }

    public byte[] serialize() {
        final byte[] subjectBytes = NodeAddressCodec.encode(subject);
        final byte[] proposerBytes = NodeAddressCodec.encode(proposer);
        final ByteBuffer buf = ByteBuffer
                .allocate(1 + 8 + 8 + 8 + subjectBytes.length + proposerBytes.length);
        buf.put(SUB_TYPE);
        buf.putLong(subjectIncarnation);
        buf.putLong(roundId);
        buf.putLong(epoch);
        buf.put(subjectBytes);
        buf.put(proposerBytes);
        return buf.array();
    }

    public static SuspicionProposal deserialize(byte[] payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (payload.length == 0) {
            throw new IllegalArgumentException("insufficient bytes for field subType");
        }
        if (payload[0] != SUB_TYPE) {
            throw new IllegalArgumentException(
                    "expected sub-type 0x05, got: 0x" + String.format("%02x", payload[0] & 0xFF));
        }
        final ByteBuffer buf = ByteBuffer.wrap(payload, 1, payload.length - 1);
        try {
            final long subjectIncarnation = buf.getLong();
            final long roundId = buf.getLong();
            final long epoch = buf.getLong();
            final NodeAddress subject = NodeAddressCodec.decode(buf, "subject");
            final NodeAddress proposer = NodeAddressCodec.decode(buf, "proposer");
            if (buf.hasRemaining()) {
                throw new IllegalArgumentException(
                        "trailing bytes after proposer: " + buf.remaining());
            }
            return new SuspicionProposal(subject, subjectIncarnation, roundId, epoch, proposer);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("insufficient bytes for SuspicionProposal payload",
                    e);
        }
    }
}
