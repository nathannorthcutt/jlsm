package jlsm.cluster.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import jlsm.cluster.MessageType;

/**
 * @spec transport.multiplexed-framing.R43
 * @spec transport.multiplexed-framing.R43a
 * @spec transport.multiplexed-framing.R44
 */
class ChunkerTest {

    @Test
    void smallBodyReturnsSingleFrame() {
        byte[] body = new byte[100];
        List<Frame> chunks = Chunker.split(MessageType.QUERY_REQUEST, 5, (byte) 0, 42L, body, 1024);
        assertEquals(1, chunks.size());
        assertSame(MessageType.QUERY_REQUEST, chunks.get(0).type());
        assertEquals(5, chunks.get(0).streamId());
        assertEquals(42L, chunks.get(0).sequenceNumber());
        assertFalse(chunks.get(0).hasMoreFrames());
        assertArrayEquals(body, chunks.get(0).body());
    }

    @Test
    void bodyExactlyAtBoundaryReturnsSingleFrame() {
        // Boundary: maxBodyPerFrame = 100; body = 100 → fits in one frame
        byte[] body = new byte[100];
        List<Frame> chunks = Chunker.split(MessageType.QUERY_REQUEST, 5, (byte) 0, 42L, body, 100);
        assertEquals(1, chunks.size());
        assertFalse(chunks.get(0).hasMoreFrames());
    }

    @Test
    void bodyOneOverBoundaryReturnsTwoFrames() {
        byte[] body = new byte[101];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        List<Frame> chunks = Chunker.split(MessageType.QUERY_REQUEST, 5, (byte) 0, 42L, body, 100);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).hasMoreFrames()); // R9 MORE_FRAMES
        assertFalse(chunks.get(1).hasMoreFrames()); // R9 last chunk has flag cleared
        assertEquals(100, chunks.get(0).body().length);
        assertEquals(1, chunks.get(1).body().length);
        // R43a: type tag and seq number from first chunk preserved on every chunk
        assertEquals(MessageType.QUERY_REQUEST, chunks.get(0).type());
        assertEquals(MessageType.QUERY_REQUEST, chunks.get(1).type());
        assertEquals(42L, chunks.get(0).sequenceNumber());
        assertEquals(42L, chunks.get(1).sequenceNumber());
    }

    @Test
    void responseFlagPreservedAcrossChunks() {
        byte[] body = new byte[300];
        List<Frame> chunks = Chunker.split(MessageType.QUERY_RESPONSE, 5, Frame.FLAG_RESPONSE, 42L,
                body, 100);
        assertEquals(3, chunks.size());
        // RESPONSE flag must be set on every chunk; MORE_FRAMES on all but last
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).isResponse(), "chunk " + i + " has RESPONSE");
            if (i < chunks.size() - 1) {
                assertTrue(chunks.get(i).hasMoreFrames(), "chunk " + i + " has MORE_FRAMES");
            } else {
                assertFalse(chunks.get(i).hasMoreFrames(), "last chunk has MORE_FRAMES cleared");
            }
        }
    }

    @Test
    void chunksConcatenateBackToOriginalBody() {
        byte[] body = new byte[1000];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i & 0xFF);
        }
        List<Frame> chunks = Chunker.split(MessageType.QUERY_REQUEST, 5, (byte) 0, 42L, body, 256);
        // Expect ceil(1000 / 256) = 4 chunks
        assertEquals(4, chunks.size());
        byte[] reassembled = new byte[body.length];
        int offset = 0;
        for (Frame f : chunks) {
            System.arraycopy(f.body(), 0, reassembled, offset, f.body().length);
            offset += f.body().length;
        }
        assertArrayEquals(body, reassembled);
    }

    @Test
    void fireAndForgetOversizeIsRejected() {
        byte[] body = new byte[200];
        // R44: fire-and-forget (stream-id 0) cannot be chunked
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Chunker
                .split(MessageType.PING, Frame.NO_REPLY_STREAM_ID, (byte) 0, 0L, body, 100));
        assertTrue(ex.getMessage().contains("fire-and-forget"));
    }

    @Test
    void rejectsZeroOrNegativeMaxBody() {
        assertThrows(IllegalArgumentException.class,
                () -> Chunker.split(MessageType.PING, 1, (byte) 0, 0L, new byte[10], 0));
        assertThrows(IllegalArgumentException.class,
                () -> Chunker.split(MessageType.PING, 1, (byte) 0, 0L, new byte[10], -1));
    }

    @Test
    void emptyBodyReturnsSingleEmptyFrame() {
        List<Frame> chunks = Chunker.split(MessageType.PING, 1, (byte) 0, 0L, new byte[0], 100);
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).body().length);
    }
}
