package jlsm.cluster.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

import jlsm.cluster.NodeAddress;

/**
 * @spec transport.multiplexed-framing.R40
 * @spec transport.multiplexed-framing.R40-bidi
 * @spec transport.multiplexed-framing.R40a
 */
class HandshakeTest {

    @Test
    void roundTripPreservesAddress() throws IOException {
        NodeAddress addr = new NodeAddress("node-A", "127.0.0.1", 9000);
        byte[] wire = Handshake.encode(addr);
        NodeAddress decoded = Handshake.decode(ByteBuffer.wrap(wire), "node-B");
        assertEquals(addr, decoded);
    }

    @Test
    void rejectsVersionMismatch() {
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0x99); // unknown version
        buf.putInt(20);
        buf.flip();
        IOException ex = assertThrows(IOException.class, () -> Handshake.decode(buf, "self"));
        assertTrue(ex.getMessage().contains("unsupported handshake version"));
    }

    @Test
    void rejectsOversizedTotalLength() {
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        buf.put(Handshake.VERSION);
        buf.putInt(Handshake.MAX_TOTAL_LENGTH + 1);
        buf.flip();
        IOException ex = assertThrows(IOException.class, () -> Handshake.decode(buf, "self"));
        assertTrue(ex.getMessage().contains("total-length"));
    }

    @Test
    void rejectsZeroTotalLength() {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buf.put(Handshake.VERSION);
        buf.putInt(0);
        buf.flip();
        assertThrows(IOException.class, () -> Handshake.decode(buf, "self"));
    }

    @Test
    void rejectsEmptyNodeId() {
        // Build manually: version=1, totalLen=12, nodeIdLen=0, hostLen=4, host="abcd", port=80
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        buf.put(Handshake.VERSION);
        buf.putInt(4 + 0 + 4 + 4 + 4);
        buf.putInt(0); // nodeIdLen=0 — empty
        buf.putInt(4);
        buf.put("abcd".getBytes());
        buf.putInt(80);
        buf.flip();
        IOException ex = assertThrows(IOException.class, () -> Handshake.decode(buf, "self"));
        assertTrue(ex.getMessage().contains("nodeId length"));
    }

    @Test
    void rejectsOversizedNodeId() {
        byte[] big = new byte[Handshake.MAX_NODE_ID_BYTES + 1];
        java.util.Arrays.fill(big, (byte) 'A');
        ByteBuffer buf = ByteBuffer.allocate(big.length + 32).order(ByteOrder.BIG_ENDIAN);
        buf.put(Handshake.VERSION);
        buf.putInt(4 + big.length + 4 + 4 + 4);
        buf.putInt(big.length);
        buf.put(big);
        buf.putInt(4);
        buf.put("abcd".getBytes());
        buf.putInt(80);
        buf.flip();
        IOException ex = assertThrows(IOException.class, () -> Handshake.decode(buf, "self"));
        assertTrue(ex.getMessage().contains("nodeId length"));
    }

    @Test
    void rejectsLocalNodeIdCollision() {
        NodeAddress addr = new NodeAddress("self-id", "127.0.0.1", 9000);
        byte[] wire = Handshake.encode(addr);
        IOException ex = assertThrows(IOException.class,
                () -> Handshake.decode(ByteBuffer.wrap(wire), "self-id"));
        assertTrue(ex.getMessage().contains("peer claims local nodeId"));
    }

    @Test
    void rejectsMalformedUtf8InNodeId() {
        // 0xC0 0x80 is overlong encoding for NUL — invalid per RFC 3629
        byte[] overlong = new byte[]{ (byte) 0xC0, (byte) 0x80 };
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        buf.put(Handshake.VERSION);
        buf.putInt(4 + overlong.length + 4 + 4 + 4);
        buf.putInt(overlong.length);
        buf.put(overlong);
        buf.putInt(4);
        buf.put("abcd".getBytes());
        buf.putInt(80);
        buf.flip();
        IOException ex = assertThrows(IOException.class, () -> Handshake.decode(buf, "self"));
        assertTrue(ex.getMessage().contains("UTF-8"));
    }

    @Test
    void rejectsInvalidPort() {
        // Build with port=0
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        buf.put(Handshake.VERSION);
        buf.putInt(4 + 4 + 4 + 9 + 4);
        buf.putInt(4);
        buf.put("nodeA".getBytes(), 0, 4);
        buf.putInt(9);
        buf.put("127.0.0.1".getBytes());
        buf.putInt(0); // invalid port
        buf.flip();
        IOException ex = assertThrows(IOException.class, () -> Handshake.decode(buf, "self"));
        assertTrue(ex.getMessage().contains("port"));
    }
}
