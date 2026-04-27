package jlsm.cluster.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import jlsm.cluster.MessageType;

/**
 * Encodes and decodes frames per {@code transport.multiplexed-framing} R1-R5, R9, R10.
 *
 * <p>
 * All multi-byte integer fields use big-endian byte order (R3). Decode validates: negative or
 * undersized length prefix (R4), oversized frame against configured maximum (R5), unknown type tag
 * (R2a), negative sequence number (R2b), invalid flag combinations (R9 — stream-id 0 with
 * MORE_FRAMES set is corrupt).
 *
 * @spec transport.multiplexed-framing.R1
 * @spec transport.multiplexed-framing.R2
 * @spec transport.multiplexed-framing.R2a
 * @spec transport.multiplexed-framing.R2b
 * @spec transport.multiplexed-framing.R3
 * @spec transport.multiplexed-framing.R4
 * @spec transport.multiplexed-framing.R5
 * @spec transport.multiplexed-framing.R9
 * @spec transport.multiplexed-framing.R10
 */
public final class FrameCodec {

    private FrameCodec() {
    }

    /**
     * Encodes a frame to a heap-allocated byte array suitable for channel write. Includes the
     * 4-byte length prefix.
     */
    public static byte[] encode(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        final int frameSize = Frame.HEADER_SIZE + frame.body().length;
        final ByteBuffer buf = ByteBuffer.allocate(4 + frameSize).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(frameSize); // R1 length prefix
        buf.put(frame.type().tag()); // R2 type tag
        buf.putInt(frame.streamId()); // R2 stream ID
        buf.put(frame.flags()); // R2 flags
        buf.putLong(frame.sequenceNumber()); // R2 sequence number
        buf.put(frame.body());
        return buf.array();
    }

    /**
     * Decodes a single frame given a body buffer of exactly {@code length} bytes (the value of the
     * length prefix). The length prefix itself must be consumed by the caller before invoking this
     * method.
     *
     * @param length length-prefix value previously read; validated for R4/R5 by the caller
     * @param body buffer of {@code length} bytes containing header + body
     * @throws IOException if the frame violates R2a (unknown type), R2b (negative seq), or R9
     *             (stream-id 0 + MORE_FRAMES set)
     */
    public static Frame decode(int length, ByteBuffer body) throws IOException {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        if (length < Frame.HEADER_SIZE) {
            throw new IOException(
                    "length " + length + " is below minimum header size " + Frame.HEADER_SIZE);
        }
        body.order(ByteOrder.BIG_ENDIAN);
        final byte typeTag = body.get();
        final MessageType type = MessageType.fromTag(typeTag);
        if (type == null) { // R2a
            throw new IOException(
                    "unknown MessageType tag: 0x" + Integer.toHexString(typeTag & 0xFF));
        }
        final int streamId = body.getInt();
        final byte flags = body.get();
        final long seq = body.getLong();
        if (seq < 0L) { // R2b
            throw new IOException("sequence number is negative: " + seq);
        }
        final boolean moreFrames = (flags & Frame.FLAG_MORE_FRAMES) != 0;
        if (moreFrames && streamId == Frame.NO_REPLY_STREAM_ID) { // R9
            throw new IOException("stream-id 0 must not set MORE_FRAMES flag");
        }
        final int bodyLen = length - Frame.HEADER_SIZE;
        final byte[] payload = new byte[bodyLen];
        body.get(payload);
        return new Frame(type, streamId, flags, seq, payload);
    }
}
