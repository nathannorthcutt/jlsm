package jlsm.cluster.internal;

import jlsm.cluster.MessageType;

/**
 * Decoded frame header + body from the multiplexed-framing wire protocol.
 *
 * <p>
 * Wire format ({@code transport.multiplexed-framing} R1-R3):
 *
 * <pre>
 * ┌──────────────┬───────┬───────────┬──────┬──────────┬──────────┐
 * │ length:int32 │ type  │ streamId  │ flags│ seq:int64│ body     │
 * │  (4 bytes)   │ (1B)  │  (4 bytes)│ (1B) │ (8 bytes)│ (N bytes)│
 * └──────────────┴───────┴───────────┴──────┴──────────┴──────────┘
 *                └────────────── 14-byte header ────────┘
 * </pre>
 *
 * Length prefix encodes header (14) + body length, NOT including the length field itself.
 *
 * @spec transport.multiplexed-framing.R1
 * @spec transport.multiplexed-framing.R2
 * @spec transport.multiplexed-framing.R3
 */
public record Frame(MessageType type, int streamId, byte flags, long sequenceNumber, byte[] body) {

    /** R2 — header is exactly 14 bytes. */
    public static final int HEADER_SIZE = 14;

    /** R7 — fire-and-forget messages use stream ID 0. */
    public static final int NO_REPLY_STREAM_ID = 0;

    /** R9 — bit 0 (0x01) of flags = MORE_FRAMES. */
    public static final byte FLAG_MORE_FRAMES = 0x01;

    /** R10 — bit 1 (0x02) of flags = RESPONSE. */
    public static final byte FLAG_RESPONSE = 0x02;

    public Frame {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
    }

    public boolean hasMoreFrames() {
        return (flags & FLAG_MORE_FRAMES) != 0;
    }

    public boolean isResponse() {
        return (flags & FLAG_RESPONSE) != 0;
    }
}
