package jlsm.cluster.internal;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import jlsm.cluster.NodeAddress;

/**
 * Shared big-endian {@link NodeAddress} binary codec.
 *
 * <p>
 * Mirrors the {@code [nodeIdLen:4][nodeId-utf8:n][hostLen:4][host-utf8:n][port:4]} framing used by
 * {@code RapidMembership.encodeJoinPayload} / {@code RapidMembership.decodeNodeAddress} so the new
 * {@code SUSPICION_PROPOSAL} / {@code SUSPICION_VOTE} / {@code ALIVE_REFUTATION} sub-types (0x05,
 * 0x06, 0x07) encode addresses identically to the existing {@code MSG_JOIN_REQUEST} /
 * {@code MSG_LEAVE} / {@code MSG_VIEW_CHANGE_PROPOSAL} sub-types (0x01, 0x03, 0x04).
 *
 * <p>
 * All methods throw {@link IllegalArgumentException} on truncated payloads or invalid length
 * prefixes; callers should wrap upstream byte input so these exceptions surface as decoder errors.
 */
public final class NodeAddressCodec {

    private NodeAddressCodec() {
    }

    public static byte[] encode(NodeAddress addr) {
        final byte[] nodeId = addr.nodeId().getBytes(StandardCharsets.UTF_8);
        final byte[] host = addr.host().getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(4 + nodeId.length + 4 + host.length + 4);
        buf.putInt(nodeId.length);
        buf.put(nodeId);
        buf.putInt(host.length);
        buf.put(host);
        buf.putInt(addr.port());
        return buf.array();
    }

    public static NodeAddress decode(ByteBuffer buf, String fieldLabel) {
        final int nodeIdLen;
        try {
            nodeIdLen = buf.getInt();
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException(
                    "insufficient bytes for field " + fieldLabel + ".nodeIdLen", e);
        }
        if (nodeIdLen <= 0) {
            throw new IllegalArgumentException(
                    "invalid " + fieldLabel + ".nodeIdLen: " + nodeIdLen);
        }
        if (buf.remaining() < nodeIdLen) {
            throw new IllegalArgumentException(
                    "insufficient bytes for field " + fieldLabel + ".nodeId");
        }
        final byte[] nodeIdBytes = new byte[nodeIdLen];
        buf.get(nodeIdBytes);

        final int hostLen;
        try {
            hostLen = buf.getInt();
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException(
                    "insufficient bytes for field " + fieldLabel + ".hostLen", e);
        }
        if (hostLen <= 0) {
            throw new IllegalArgumentException("invalid " + fieldLabel + ".hostLen: " + hostLen);
        }
        if (buf.remaining() < hostLen) {
            throw new IllegalArgumentException(
                    "insufficient bytes for field " + fieldLabel + ".host");
        }
        final byte[] hostBytes = new byte[hostLen];
        buf.get(hostBytes);

        final int port;
        try {
            port = buf.getInt();
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException(
                    "insufficient bytes for field " + fieldLabel + ".port", e);
        }
        return new NodeAddress(new String(nodeIdBytes, StandardCharsets.UTF_8),
                new String(hostBytes, StandardCharsets.UTF_8), port);
    }
}
