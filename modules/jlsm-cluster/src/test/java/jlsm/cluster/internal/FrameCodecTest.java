package jlsm.cluster.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

import jlsm.cluster.MessageType;

/**
 * Tests for {@link FrameCodec} wire-format encode/decode and validation.
 *
 * @spec transport.multiplexed-framing.R1
 * @spec transport.multiplexed-framing.R2
 * @spec transport.multiplexed-framing.R2a
 * @spec transport.multiplexed-framing.R2b
 * @spec transport.multiplexed-framing.R3
 * @spec transport.multiplexed-framing.R4
 * @spec transport.multiplexed-framing.R9
 * @spec transport.multiplexed-framing.R10
 */
class FrameCodecTest {

    @Test
    void roundTripPing() throws IOException {
        Frame original = new Frame(MessageType.PING, 1, (byte) 0, 100L,
                new byte[]{ 0x01, 0x02, 0x03 });
        byte[] wire = FrameCodec.encode(original);

        // Skip 4-byte length prefix; decode body
        ByteBuffer buf = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        int length = buf.getInt();
        Frame decoded = FrameCodec.decode(length, buf);

        assertEquals(MessageType.PING, decoded.type());
        assertEquals(1, decoded.streamId());
        assertEquals((byte) 0, decoded.flags());
        assertEquals(100L, decoded.sequenceNumber());
        assertArrayEquals(new byte[]{ 0x01, 0x02, 0x03 }, decoded.body());
    }

    @Test
    void lengthPrefixIsBigEndian() {
        Frame f = new Frame(MessageType.PING, 1, (byte) 0, 0L, new byte[]{ 0x10 });
        byte[] wire = FrameCodec.encode(f);
        // 14 header + 1 body = 15
        assertEquals(0x00, wire[0]);
        assertEquals(0x00, wire[1]);
        assertEquals(0x00, wire[2]);
        assertEquals(0x0F, wire[3]);
    }

    @Test
    void allMessageTypeTagsMapBackCorrectly() throws IOException {
        for (MessageType type : MessageType.values()) {
            Frame f = new Frame(type, 7, (byte) 0, 42L, new byte[0]);
            byte[] wire = FrameCodec.encode(f);
            ByteBuffer buf = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
            int len = buf.getInt();
            Frame decoded = FrameCodec.decode(len, buf);
            assertEquals(type, decoded.type(), "round-trip for " + type);
        }
    }

    @Test
    void decodeRejectsUnknownTypeTag() {
        // Manually build a wire frame with type tag 0xFF (unknown)
        ByteBuffer buf = ByteBuffer.allocate(Frame.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0xFF); // unknown type tag
        buf.putInt(1); // streamId
        buf.put((byte) 0); // flags
        buf.putLong(0L); // seq
        buf.flip();
        IOException ex = assertThrows(IOException.class,
                () -> FrameCodec.decode(Frame.HEADER_SIZE, buf));
        assertTrue(ex.getMessage().contains("unknown MessageType tag"));
    }

    @Test
    void decodeRejectsNegativeSequenceNumber() {
        ByteBuffer buf = ByteBuffer.allocate(Frame.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put(MessageType.PING.tag());
        buf.putInt(1);
        buf.put((byte) 0);
        buf.putLong(-1L); // bit 63 set
        buf.flip();
        IOException ex = assertThrows(IOException.class,
                () -> FrameCodec.decode(Frame.HEADER_SIZE, buf));
        assertTrue(ex.getMessage().contains("sequence number is negative"));
    }

    @Test
    void decodeRejectsLengthBelowHeaderSize() {
        ByteBuffer buf = ByteBuffer.allocate(0);
        IOException ex = assertThrows(IOException.class, () -> FrameCodec.decode(13, buf));
        assertTrue(ex.getMessage().contains("below minimum"));
    }

    @Test
    void decodeRejectsStreamId0WithMoreFramesFlag() {
        ByteBuffer buf = ByteBuffer.allocate(Frame.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put(MessageType.QUERY_REQUEST.tag());
        buf.putInt(0); // stream-id 0 (fire-and-forget)
        buf.put(Frame.FLAG_MORE_FRAMES); // but MORE_FRAMES set — corrupt per R9
        buf.putLong(0L);
        buf.flip();
        IOException ex = assertThrows(IOException.class,
                () -> FrameCodec.decode(Frame.HEADER_SIZE, buf));
        assertTrue(ex.getMessage().contains("MORE_FRAMES"));
    }

    @Test
    void encodeRejectsNullFrame() {
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.encode(null));
    }

    @Test
    void responseFlagRoundTrips() throws IOException {
        Frame f = new Frame(MessageType.QUERY_RESPONSE, 5, Frame.FLAG_RESPONSE, 0L, new byte[0]);
        byte[] wire = FrameCodec.encode(f);
        ByteBuffer buf = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        int len = buf.getInt();
        Frame decoded = FrameCodec.decode(len, buf);
        assertTrue(decoded.isResponse());
        assertFalse(decoded.hasMoreFrames());
    }

    @Test
    void moreFramesAndResponseFlagsAreIndependent() throws IOException {
        // Multi-chunk response: both flags set on intermediate frames, only RESPONSE on last
        Frame intermediate = new Frame(MessageType.QUERY_RESPONSE, 7,
                (byte) (Frame.FLAG_MORE_FRAMES | Frame.FLAG_RESPONSE), 1L, new byte[]{ 0x42 });
        byte[] wire = FrameCodec.encode(intermediate);
        ByteBuffer buf = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        int len = buf.getInt();
        Frame decoded = FrameCodec.decode(len, buf);
        assertTrue(decoded.hasMoreFrames());
        assertTrue(decoded.isResponse());
    }
}
