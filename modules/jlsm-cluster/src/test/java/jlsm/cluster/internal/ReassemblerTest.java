package jlsm.cluster.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import jlsm.cluster.MessageType;

/**
 * Tests for {@link Reassembler} — per-connection, per-stream-id reassembly state machine.
 *
 * @spec transport.multiplexed-framing.R35
 * @spec transport.multiplexed-framing.R35a
 * @spec transport.multiplexed-framing.R36
 * @spec transport.multiplexed-framing.R37
 * @spec transport.multiplexed-framing.R37a
 * @spec transport.multiplexed-framing.R38
 * @spec transport.multiplexed-framing.R43a
 */
class ReassemblerTest {

    private static Frame chunk(MessageType type, int streamId, byte flags, long seq, byte[] body) {
        return new Frame(type, streamId, flags, seq, body);
    }

    @Test
    void singleFrameDispatchesImmediately() {
        Reassembler r = new Reassembler(64 * 1024, 64 * 1024);
        Frame f = chunk(MessageType.PING, 1, (byte) 0, 7L, "hello".getBytes());
        Reassembler.Result result = r.accept(f);
        assertEquals(Reassembler.Outcome.DISPATCH, result.outcome());
        assertSame(f.type(), result.dispatched().type());
        assertEquals(7L, result.dispatched().sequenceNumber());
        assertArrayEquals("hello".getBytes(), result.dispatched().body());
    }

    @Test
    void multiFrameReassembledOnFinalChunk() {
        Reassembler r = new Reassembler(64 * 1024, 64 * 1024);
        Frame c1 = chunk(MessageType.QUERY_REQUEST, 5, Frame.FLAG_MORE_FRAMES, 100L,
                "abc".getBytes());
        Frame c2 = chunk(MessageType.QUERY_REQUEST, 5, Frame.FLAG_MORE_FRAMES, 100L,
                "def".getBytes());
        Frame c3 = chunk(MessageType.QUERY_REQUEST, 5, (byte) 0, 100L, "ghi".getBytes());

        assertEquals(Reassembler.Outcome.BUFFER, r.accept(c1).outcome());
        assertEquals(Reassembler.Outcome.BUFFER, r.accept(c2).outcome());
        Reassembler.Result done = r.accept(c3);
        assertEquals(Reassembler.Outcome.DISPATCH, done.outcome());
        assertArrayEquals("abcdefghi".getBytes(), done.dispatched().body());
        // R43a: type and seq from first chunk preserved
        assertEquals(MessageType.QUERY_REQUEST, done.dispatched().type());
        assertEquals(100L, done.dispatched().sequenceNumber());
    }

    @Test
    void perStreamIsolation() {
        Reassembler r = new Reassembler(64 * 1024, 64 * 1024);
        Frame stream5First = chunk(MessageType.QUERY_REQUEST, 5, Frame.FLAG_MORE_FRAMES, 1L,
                "AAA".getBytes());
        Frame stream7First = chunk(MessageType.QUERY_REQUEST, 7, Frame.FLAG_MORE_FRAMES, 2L,
                "XXX".getBytes());
        Frame stream5Last = chunk(MessageType.QUERY_REQUEST, 5, (byte) 0, 1L, "BBB".getBytes());
        Frame stream7Last = chunk(MessageType.QUERY_REQUEST, 7, (byte) 0, 2L, "YYY".getBytes());

        r.accept(stream5First);
        r.accept(stream7First);
        Reassembler.Result done5 = r.accept(stream5Last);
        Reassembler.Result done7 = r.accept(stream7Last);

        assertArrayEquals("AAABBB".getBytes(), done5.dispatched().body());
        assertArrayEquals("XXXYYY".getBytes(), done7.dispatched().body());
    }

    @Test
    void perStreamSizeLimitTriggersDrain() {
        // 10-byte limit; first chunk 6, second chunk 6 → exceeds on second arrival
        Reassembler r = new Reassembler(10, 64 * 1024);
        Frame c1 = chunk(MessageType.QUERY_REQUEST, 1, Frame.FLAG_MORE_FRAMES, 0L,
                "ABCDEF".getBytes());
        Frame c2 = chunk(MessageType.QUERY_REQUEST, 1, Frame.FLAG_MORE_FRAMES, 0L,
                "GHIJKL".getBytes());
        Frame c3 = chunk(MessageType.QUERY_REQUEST, 1, (byte) 0, 0L, "MNO".getBytes());

        assertEquals(Reassembler.Outcome.BUFFER, r.accept(c1).outcome());
        // c2 pushes total over limit → drain begins; this chunk is discarded; subsequent buffered
        Reassembler.Result r2 = r.accept(c2);
        assertEquals(Reassembler.Outcome.DRAINED, r2.outcome());
        // Still draining — drop intermediate frames
        Reassembler.Result r3 = r.accept(c3);
        // Final chunk in drain state ends the stream; final chunk discarded; outcome SIZE_LIMIT_END
        assertEquals(Reassembler.Outcome.SIZE_LIMIT_END, r3.outcome());
    }

    @Test
    void globalBudgetExhaustionDrainsBeforeFirstChunkBuffered() {
        // budget = 5 bytes, per-stream max = 64KiB
        Reassembler r = new Reassembler(64 * 1024, 5);
        // Stream 1 takes most of the budget
        Frame s1 = chunk(MessageType.QUERY_REQUEST, 1, Frame.FLAG_MORE_FRAMES, 0L,
                "AAAA".getBytes());
        assertEquals(Reassembler.Outcome.BUFFER, r.accept(s1).outcome());
        // Stream 2 first chunk would push beyond budget — must drain BEFORE buffering
        Frame s2 = chunk(MessageType.QUERY_REQUEST, 2, Frame.FLAG_MORE_FRAMES, 0L,
                "BBBB".getBytes());
        Reassembler.Result r2 = r.accept(s2);
        assertEquals(Reassembler.Outcome.DRAINED, r2.outcome());
    }

    @Test
    void chunkTypeTagMismatchTreatedAsCorrupt() {
        // R43a: type tag must match across chunks
        Reassembler r = new Reassembler(64 * 1024, 64 * 1024);
        Frame c1 = chunk(MessageType.QUERY_REQUEST, 1, Frame.FLAG_MORE_FRAMES, 0L, "AB".getBytes());
        Frame c2 = chunk(MessageType.QUERY_RESPONSE, 1, (byte) 0, 0L, "CD".getBytes());
        r.accept(c1);
        Reassembler.Result r2 = r.accept(c2);
        assertEquals(Reassembler.Outcome.CORRUPT, r2.outcome());
    }

    @Test
    void chunkSeqMismatchTreatedAsCorrupt() {
        // R43a: seq number must match across chunks
        Reassembler r = new Reassembler(64 * 1024, 64 * 1024);
        Frame c1 = chunk(MessageType.QUERY_REQUEST, 1, Frame.FLAG_MORE_FRAMES, 100L,
                "AB".getBytes());
        Frame c2 = chunk(MessageType.QUERY_REQUEST, 1, (byte) 0, 200L, "CD".getBytes());
        r.accept(c1);
        Reassembler.Result r2 = r.accept(c2);
        assertEquals(Reassembler.Outcome.CORRUPT, r2.outcome());
    }

    @Test
    void singleFrameDoesNotConsumeReassemblyBudget() {
        // R37a v3: single-frame messages bypass reassembly budget (only multi-frame consumes)
        Reassembler r = new Reassembler(64 * 1024, 5);
        // Even though budget is 5, single-frame message of 100 bytes is fine
        byte[] big = new byte[100];
        Frame f = chunk(MessageType.PING, 1, (byte) 0, 0L, big);
        Reassembler.Result result = r.accept(f);
        assertEquals(Reassembler.Outcome.DISPATCH, result.outcome());
    }

    @Test
    void resultRecordCarriesDispatchedFrameAndStreamId() {
        Reassembler r = new Reassembler(64 * 1024, 64 * 1024);
        Frame f = chunk(MessageType.PING, 1, (byte) 0, 0L, new byte[0]);
        Reassembler.Result result = r.accept(f);
        assertNotNull(result.outcome());
        assertEquals(Reassembler.Outcome.DISPATCH, result.outcome());
        assertNotNull(result.dispatched());
        assertEquals(1, result.streamId());
    }

    @Test
    void rejectsZeroOrNegativeLimits() {
        assertThrows(IllegalArgumentException.class, () -> new Reassembler(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Reassembler(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Reassembler(-1, -1));
    }

    @Test
    void completedReassemblyFreesBudget() {
        // Verify that once a stream reassembles, its bytes return to the budget
        Reassembler r = new Reassembler(64 * 1024, 10);
        Frame c1 = chunk(MessageType.QUERY_REQUEST, 1, Frame.FLAG_MORE_FRAMES, 0L,
                "AAAA".getBytes());
        Frame c2 = chunk(MessageType.QUERY_REQUEST, 1, (byte) 0, 0L, "BBBB".getBytes());
        r.accept(c1);
        r.accept(c2);
        // Budget should be freed; new stream starts fresh
        Frame c3 = chunk(MessageType.QUERY_REQUEST, 2, Frame.FLAG_MORE_FRAMES, 1L,
                "CCCCC".getBytes());
        assertEquals(Reassembler.Outcome.BUFFER, r.accept(c3).outcome());
    }
}
