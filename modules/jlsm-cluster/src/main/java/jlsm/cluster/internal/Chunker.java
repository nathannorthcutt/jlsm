package jlsm.cluster.internal;

import java.util.ArrayList;
import java.util.List;

import jlsm.cluster.MessageType;

/**
 * Splits an outbound message body into frames per {@code transport.multiplexed-framing} R43, R43a,
 * R44.
 *
 * <p>
 * R43: when body exceeds {@code maxBodyPerFrame}, split into chunks. All chunks except the last
 * have the MORE_FRAMES flag set; the last has it cleared. R43a: every chunk carries the same type
 * tag and sequence number as the first. R44: fire-and-forget messages (stream-id 0) cannot be
 * chunked — invocation with oversized body throws.
 *
 * @spec transport.multiplexed-framing.R43
 * @spec transport.multiplexed-framing.R43a
 * @spec transport.multiplexed-framing.R44
 */
public final class Chunker {

    private Chunker() {
    }

    /**
     * Split an outbound logical message into frames.
     *
     * @param type message type tag (R43a — preserved across chunks)
     * @param streamId stream id (preserved across chunks)
     * @param baseFlags caller-supplied flags (e.g. {@link Frame#FLAG_RESPONSE}); the chunker adds
     *            {@link Frame#FLAG_MORE_FRAMES} to non-final chunks
     * @param sequenceNumber sequence number (R43a — preserved across chunks)
     * @param body the full logical body to split
     * @param maxBodyPerFrame maximum body bytes per frame (typically {@code maxFrameSize - 14})
     * @return one or more frames; never empty
     * @throws IllegalArgumentException if body is fire-and-forget (stream-id 0) and exceeds
     *             {@code maxBodyPerFrame} (R44)
     */
    public static List<Frame> split(MessageType type, int streamId, byte baseFlags,
            long sequenceNumber, byte[] body, int maxBodyPerFrame) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        if (maxBodyPerFrame <= 0) {
            throw new IllegalArgumentException(
                    "maxBodyPerFrame must be positive: " + maxBodyPerFrame);
        }
        if (body.length <= maxBodyPerFrame) {
            return List.of(new Frame(type, streamId, baseFlags, sequenceNumber, body));
        }
        // Body exceeds max — chunking required
        if (streamId == Frame.NO_REPLY_STREAM_ID) {
            // R44: fire-and-forget cannot be chunked
            throw new IllegalArgumentException(
                    "fire-and-forget messages exceeding maxBodyPerFrame cannot be chunked: body="
                            + body.length + " bytes, max=" + maxBodyPerFrame);
        }
        int chunkCount = (body.length + maxBodyPerFrame - 1) / maxBodyPerFrame;
        List<Frame> chunks = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            int from = i * maxBodyPerFrame;
            int to = Math.min(from + maxBodyPerFrame, body.length);
            byte[] chunkBody = new byte[to - from];
            System.arraycopy(body, from, chunkBody, 0, chunkBody.length);
            byte flags = baseFlags;
            if (i < chunkCount - 1) {
                flags |= Frame.FLAG_MORE_FRAMES;
            }
            chunks.add(new Frame(type, streamId, flags, sequenceNumber, chunkBody));
        }
        return chunks;
    }
}
