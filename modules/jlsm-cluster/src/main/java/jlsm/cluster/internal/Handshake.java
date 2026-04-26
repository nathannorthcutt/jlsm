package jlsm.cluster.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import jlsm.cluster.NodeAddress;

/**
 * Bidirectional handshake codec per {@code transport.multiplexed-framing} R40 / R40-bidi / R40a,
 * including all v2/v3 amendments (version validation, nodeId well-formedness, byte-equality
 * comparison rule).
 *
 * <p>
 * Wire format:
 *
 * <pre>
 * [version:1][total-length:int32][nodeIdLen:int32][nodeId-utf8][hostLen:int32][host-utf8][port:int32]
 * </pre>
 *
 * <p>
 * Constants:
 * <ul>
 * <li>protocol version = 1</li>
 * <li>maximum total-length = 4 KiB (R40)</li>
 * <li>nodeId length range = [1, 256] bytes (R40 v3 amendment, length-prefix-on-wire check)</li>
 * <li>UTF-8 well-formedness MUST be validated per RFC 3629 (R40 v3 amendment, MUST not should)</li>
 * </ul>
 *
 * @spec transport.multiplexed-framing.R40
 * @spec transport.multiplexed-framing.R40-bidi
 * @spec transport.multiplexed-framing.R40a
 */
public final class Handshake {

    /** Protocol version this implementation speaks (R40). */
    public static final byte VERSION = 1;

    /** Maximum allowable total-length value (R40). */
    public static final int MAX_TOTAL_LENGTH = 4 * 1024;

    /** Maximum allowable nodeId length in raw UTF-8 bytes (R40 v3). */
    public static final int MAX_NODE_ID_BYTES = 256;

    /** Maximum allowable host length in raw UTF-8 bytes. */
    public static final int MAX_HOST_BYTES = 256;

    private Handshake() {
    }

    /**
     * Encodes a handshake from this node's address. Used for both R40 (forward, connecting node
     * sends first) and R40-bidi (reverse, accepting node echoes its own address).
     */
    public static byte[] encode(NodeAddress self) {
        if (self == null) {
            throw new IllegalArgumentException("self must not be null");
        }
        final byte[] nodeId = self.nodeId().getBytes(StandardCharsets.UTF_8);
        final byte[] host = self.host().getBytes(StandardCharsets.UTF_8);
        // total-length covers everything after the (1B version + 4B totalLen) preamble
        final int totalLength = 4 + nodeId.length + 4 + host.length + 4;
        final ByteBuffer buf = ByteBuffer.allocate(1 + 4 + totalLength).order(ByteOrder.BIG_ENDIAN);
        buf.put(VERSION);
        buf.putInt(totalLength);
        buf.putInt(nodeId.length);
        buf.put(nodeId);
        buf.putInt(host.length);
        buf.put(host);
        buf.putInt(self.port());
        return buf.array();
    }

    /**
     * Decodes a handshake from a wire-form byte buffer. Validates version, total-length, nodeId
     * length-prefix-on-wire, host length-prefix, UTF-8 well-formedness, and the local-node
     * collision rule (nodeId must not equal {@code localNodeId}). Returns the peer's NodeAddress on
     * success.
     *
     * @param buf buffer positioned at the version byte
     * @param localNodeId this node's own nodeId; the peer must not claim the same identity (R40 v3)
     * @throws IOException on any validation failure (version mismatch, oversize, malformed UTF-8,
     *             identity collision, etc.)
     */
    public static NodeAddress decode(ByteBuffer buf, String localNodeId) throws IOException {
        if (buf == null) {
            throw new IllegalArgumentException("buf must not be null");
        }
        buf.order(ByteOrder.BIG_ENDIAN);

        // R40 v3: version byte first; reject before reading any payload.
        final byte version = buf.get();
        if (version != VERSION) {
            throw new IOException(
                    "unsupported handshake version: 0x" + Integer.toHexString(version & 0xFF)
                            + " (expected 0x" + Integer.toHexString(VERSION & 0xFF) + ")");
        }

        // R40: total-length next, ≤ 4 KiB.
        final int totalLength = buf.getInt();
        if (totalLength <= 0 || totalLength > MAX_TOTAL_LENGTH) {
            throw new IOException("handshake total-length out of range: " + totalLength);
        }
        if (buf.remaining() < totalLength) {
            throw new IOException("buffer underflow: declared " + totalLength + " bytes, only "
                    + buf.remaining() + " remaining");
        }

        // R40 v3: nodeId length-prefix validated on wire bytes BEFORE allocating buffer.
        final int nodeIdLen = buf.getInt();
        if (nodeIdLen < 1 || nodeIdLen > MAX_NODE_ID_BYTES) {
            throw new IOException(
                    "nodeId length out of [1, " + MAX_NODE_ID_BYTES + "]: " + nodeIdLen);
        }
        if (buf.remaining() < nodeIdLen) {
            throw new IOException("buffer underflow reading nodeId");
        }
        final byte[] nodeIdBytes = new byte[nodeIdLen];
        buf.get(nodeIdBytes);
        // R40 v3: UTF-8 well-formedness MUST be validated.
        final String nodeId = decodeStrictUtf8(nodeIdBytes, "nodeId");

        // R40 v3: peer must not claim our own identity.
        if (localNodeId != null && nodeId.equals(localNodeId)) {
            throw new IOException("peer claims local nodeId: " + nodeId);
        }

        final int hostLen = buf.getInt();
        if (hostLen < 1 || hostLen > MAX_HOST_BYTES) {
            throw new IOException("host length out of [1, " + MAX_HOST_BYTES + "]: " + hostLen);
        }
        if (buf.remaining() < hostLen) {
            throw new IOException("buffer underflow reading host");
        }
        final byte[] hostBytes = new byte[hostLen];
        buf.get(hostBytes);
        final String host = decodeStrictUtf8(hostBytes, "host");

        if (buf.remaining() < 4) {
            throw new IOException("buffer underflow reading port");
        }
        final int port = buf.getInt();
        if (port <= 0 || port > 65535) {
            throw new IOException("port out of valid range: " + port);
        }

        return new NodeAddress(nodeId, host, port);
    }

    /**
     * Strict UTF-8 decode rejecting overlong encodings and unpaired surrogates per RFC 3629.
     */
    private static String decodeStrictUtf8(byte[] bytes, String fieldName) throws IOException {
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("invalid UTF-8 in field " + fieldName, e);
        }
    }
}
