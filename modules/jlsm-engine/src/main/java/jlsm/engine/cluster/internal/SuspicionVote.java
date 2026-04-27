package jlsm.engine.cluster.internal;

import jlsm.cluster.internal.NodeAddressCodec;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Objects;

import jlsm.cluster.NodeAddress;

/**
 * Immutable payload for the {@code SUSPICION_VOTE} VIEW_CHANGE sub-type (byte {@code 0x06}).
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Carries a per-round agree/disagree vote from an observer back to the proposer.</li>
 * <li>Big-endian wire framing:
 * {@code [subType:1=0x06][roundId:8][voter-addr][subject-addr][agree:1][voterInc:8]} (fields
 * serialized in record declaration order).</li>
 * <li>{@code NodeAddress} encoding reuses
 * {@code [nodeIdLen:4][nodeId:n][hostLen:4][host:n][port:4]} shared with
 * {@code RapidMembership.encodeJoinPayload}.</li>
 * <li>Null {@code voter} or {@code subject} → {@link NullPointerException}.</li>
 * <li>Negative {@code roundId} or {@code voterIncarnation} → {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * <p>
 * Governed by: F04.R34, R36 and .decisions/cluster-membership-protocol/adr.md.
 */
public record SuspicionVote(long roundId, NodeAddress voter, NodeAddress subject, boolean agree,
        long voterIncarnation) {

    /** VIEW_CHANGE sub-type byte for this payload. */
    public static final byte SUB_TYPE = 0x06;

    public SuspicionVote {
        Objects.requireNonNull(voter, "voter must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        if (roundId < 0L) {
            throw new IllegalArgumentException("roundId must be non-negative, got: " + roundId);
        }
        if (voterIncarnation < 0L) {
            throw new IllegalArgumentException(
                    "voterIncarnation must be non-negative, got: " + voterIncarnation);
        }
    }

    public byte[] serialize() {
        final byte[] voterBytes = NodeAddressCodec.encode(voter);
        final byte[] subjectBytes = NodeAddressCodec.encode(subject);
        final ByteBuffer buf = ByteBuffer
                .allocate(1 + 8 + voterBytes.length + subjectBytes.length + 1 + 8);
        buf.put(SUB_TYPE);
        buf.putLong(roundId);
        buf.put(voterBytes);
        buf.put(subjectBytes);
        buf.put(agree ? (byte) 1 : (byte) 0);
        buf.putLong(voterIncarnation);
        return buf.array();
    }

    public static SuspicionVote deserialize(byte[] payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (payload.length == 0) {
            throw new IllegalArgumentException("insufficient bytes for field subType");
        }
        if (payload[0] != SUB_TYPE) {
            throw new IllegalArgumentException(
                    "expected sub-type 0x06, got: 0x" + String.format("%02x", payload[0] & 0xFF));
        }
        final ByteBuffer buf = ByteBuffer.wrap(payload, 1, payload.length - 1);
        try {
            final long roundId = buf.getLong();
            final NodeAddress voter = NodeAddressCodec.decode(buf, "voter");
            final NodeAddress subject = NodeAddressCodec.decode(buf, "subject");
            if (!buf.hasRemaining()) {
                throw new IllegalArgumentException("insufficient bytes for field agree");
            }
            final byte agreeByte = buf.get();
            if (agreeByte != 0 && agreeByte != 1) {
                throw new IllegalArgumentException("invalid agree byte: " + agreeByte);
            }
            final long voterIncarnation = buf.getLong();
            if (buf.hasRemaining()) {
                throw new IllegalArgumentException(
                        "trailing bytes after voterIncarnation: " + buf.remaining());
            }
            return new SuspicionVote(roundId, voter, subject, agreeByte == 1, voterIncarnation);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("insufficient bytes for SuspicionVote payload", e);
        }
    }
}
