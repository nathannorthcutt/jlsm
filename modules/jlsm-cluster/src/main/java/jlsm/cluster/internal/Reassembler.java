package jlsm.cluster.internal;

import java.util.HashMap;
import java.util.Map;

import jlsm.cluster.MessageType;

/**
 * Per-connection multi-frame reassembly state machine.
 *
 * <p>
 * Maintains per-stream-id reassembly buffers; on each incoming frame, returns one of:
 * {@link Outcome#DISPATCH} (single-frame or final chunk completed), {@link Outcome#BUFFER}
 * (intermediate chunk buffered), {@link Outcome#DRAINED} (this chunk discarded — drain state
 * entered or in progress), {@link Outcome#SIZE_LIMIT_END} (final chunk of an over-limit stream —
 * drain ends), {@link Outcome#CORRUPT} (chunk header inconsistent with first chunk; caller must
 * close).
 *
 * <p>
 * Enforces:
 * <ul>
 * <li><b>R37</b> per-stream maximum reassembled message size (default 64 MiB)</li>
 * <li><b>R37a</b> global reassembly memory budget; first-chunk allocation gated by remaining
 * budget; single-frame messages bypass</li>
 * <li><b>R38</b> per-stream-id isolation</li>
 * <li><b>R43a</b> chunks share type tag and sequence number</li>
 * </ul>
 *
 * <p>
 * Not thread-safe; the calling reader thread is the sole accessor per-connection.
 *
 * @spec transport.multiplexed-framing.R35
 * @spec transport.multiplexed-framing.R35a
 * @spec transport.multiplexed-framing.R36
 * @spec transport.multiplexed-framing.R37
 * @spec transport.multiplexed-framing.R37a
 * @spec transport.multiplexed-framing.R38
 * @spec transport.multiplexed-framing.R43a
 */
public final class Reassembler {

    public enum Outcome {
        /** Frame is a complete logical message (single-frame or final reassembled chunk). */
        DISPATCH,
        /** Intermediate chunk buffered; no dispatch this call. */
        BUFFER,
        /** Chunk discarded due to drain state (size limit hit or budget exhausted). */
        DRAINED,
        /** Final chunk of a draining stream — drain ends, chunk discarded. R37 outbound case. */
        SIZE_LIMIT_END,
        /** Chunk header inconsistent with stream's first chunk (R43a violation). */
        CORRUPT
    }

    /** Result of an {@link #accept(Frame)} call. */
    public record Result(Outcome outcome, Frame dispatched, int streamId) {
    }

    private final long perStreamMaxBytes;
    private final long globalBudgetBytes;
    private final Map<Integer, StreamState> streams = new HashMap<>();
    private long bytesInFlight = 0;

    public Reassembler(long perStreamMaxBytes, long globalBudgetBytes) {
        if (perStreamMaxBytes <= 0) {
            throw new IllegalArgumentException(
                    "perStreamMaxBytes must be positive: " + perStreamMaxBytes);
        }
        if (globalBudgetBytes <= 0) {
            throw new IllegalArgumentException(
                    "globalBudgetBytes must be positive: " + globalBudgetBytes);
        }
        this.perStreamMaxBytes = perStreamMaxBytes;
        this.globalBudgetBytes = globalBudgetBytes;
    }

    /**
     * Process one incoming frame, advancing per-stream reassembly state.
     *
     * @return a {@link Result} indicating what action the caller should take
     */
    public Result accept(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        final int streamId = frame.streamId();
        final boolean more = frame.hasMoreFrames();
        StreamState state = streams.get(streamId);

        // Case 1: single-frame, no existing state → dispatch directly (bypasses budget)
        if (state == null && !more) {
            return new Result(Outcome.DISPATCH, frame, streamId);
        }

        // Case 2: first chunk of a multi-frame message
        if (state == null) {
            // R37a: budget check BEFORE buffering the first chunk
            if (bytesInFlight + frame.body().length > globalBudgetBytes) {
                streams.put(streamId,
                        new StreamState(frame.type(), frame.sequenceNumber(), null, true));
                return new Result(Outcome.DRAINED, null, streamId);
            }
            // R37: per-stream limit check
            if (frame.body().length > perStreamMaxBytes) {
                streams.put(streamId,
                        new StreamState(frame.type(), frame.sequenceNumber(), null, true));
                return new Result(Outcome.DRAINED, null, streamId);
            }
            BufferAccumulator acc = new BufferAccumulator();
            acc.append(frame.body());
            bytesInFlight += frame.body().length;
            streams.put(streamId,
                    new StreamState(frame.type(), frame.sequenceNumber(), acc, false));
            return new Result(Outcome.BUFFER, null, streamId);
        }

        // Existing state — must validate type/seq match (R43a)
        if (state.type() != frame.type() || state.sequenceNumber() != frame.sequenceNumber()) {
            // Free buffered bytes, drop state
            if (state.acc() != null) {
                bytesInFlight -= state.acc().size();
            }
            streams.remove(streamId);
            return new Result(Outcome.CORRUPT, null, streamId);
        }

        // Case 3: chunk arrives while stream is in drain state
        if (state.draining()) {
            if (more) {
                // intermediate chunk during drain — discard
                return new Result(Outcome.DRAINED, null, streamId);
            }
            // final chunk during drain — drain ends, discard final
            streams.remove(streamId);
            return new Result(Outcome.SIZE_LIMIT_END, null, streamId);
        }

        // Case 4: chunk arrives with active reassembly buffer
        BufferAccumulator acc = state.acc();
        long projected = (long) acc.size() + frame.body().length;
        if (projected > perStreamMaxBytes) {
            // R37: per-stream limit hit — discard buffer, enter drain
            bytesInFlight -= acc.size();
            streams.put(streamId,
                    new StreamState(state.type(), state.sequenceNumber(), null, true));
            if (more) {
                return new Result(Outcome.DRAINED, null, streamId);
            }
            // limit-hit on final chunk — drain ends immediately
            streams.remove(streamId);
            return new Result(Outcome.SIZE_LIMIT_END, null, streamId);
        }
        // Budget shouldn't trip here since the per-stream cap < global budget by construction
        // when configured sensibly; we still guard
        if (bytesInFlight + frame.body().length > globalBudgetBytes) {
            bytesInFlight -= acc.size();
            streams.put(streamId,
                    new StreamState(state.type(), state.sequenceNumber(), null, true));
            return new Result(Outcome.DRAINED, null, streamId);
        }
        acc.append(frame.body());
        bytesInFlight += frame.body().length;

        if (more) {
            return new Result(Outcome.BUFFER, null, streamId);
        }

        // Final chunk: dispatch reassembled message
        byte[] body = acc.collect();
        bytesInFlight -= body.length;
        streams.remove(streamId);
        // Synthetic dispatched frame: type+seq from first chunk (R43a), flags clear, full body
        Frame dispatched = new Frame(state.type(), streamId, frame.flags(), state.sequenceNumber(),
                body);
        return new Result(Outcome.DISPATCH, dispatched, streamId);
    }

    /** Total bytes currently buffered across all in-flight reassemblies. */
    public long bytesInFlight() {
        return bytesInFlight;
    }

    /** Number of in-flight (buffered or draining) streams. */
    public int activeStreamCount() {
        return streams.size();
    }

    private record StreamState(MessageType type, long sequenceNumber, BufferAccumulator acc,
            boolean draining) {
    }

    /** Mutable byte accumulator — single-thread only. */
    private static final class BufferAccumulator {
        private byte[] buf = new byte[0];

        void append(byte[] chunk) {
            byte[] grown = new byte[buf.length + chunk.length];
            System.arraycopy(buf, 0, grown, 0, buf.length);
            System.arraycopy(chunk, 0, grown, buf.length, chunk.length);
            buf = grown;
        }

        int size() {
            return buf.length;
        }

        byte[] collect() {
            return buf;
        }
    }
}
