package jlsm.encryption;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Objects;

/**
 * Operator-supplied proof-of-key-control for a rekey operation. Carries two wrapped sentinels — one
 * wrapped under the old tenant KEK, one wrapped under the new tenant KEK — and a freshness
 * timestamp. The {@link jlsm.encryption.internal.RekeySentinelVerifier} dual-unwraps both and
 * verifies byte-equality of the underlying plaintext nonces (R78a).
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R78a).
 *
 * @spec encryption.primitives-lifecycle R78a
 *
 * @param oldSentinelWrapped sentinel wrapped under the old tenant KEK
 * @param newSentinelWrapped sentinel wrapped under the new tenant KEK
 * @param timestamp instant the sentinel pair was generated
 */
public record RekeySentinel(ByteBuffer oldSentinelWrapped, ByteBuffer newSentinelWrapped,
        Instant timestamp) {

    /**
     * Validates inputs and takes a defensive copy of buffer contents — caller-side mutation to the
     * source array, position, or limit after construction MUST NOT mutate the recorded view.
     */
    public RekeySentinel {
        Objects.requireNonNull(oldSentinelWrapped, "oldSentinelWrapped");
        Objects.requireNonNull(newSentinelWrapped, "newSentinelWrapped");
        Objects.requireNonNull(timestamp, "timestamp");
        if (oldSentinelWrapped.remaining() == 0) {
            throw new IllegalArgumentException("oldSentinelWrapped must be non-empty");
        }
        if (newSentinelWrapped.remaining() == 0) {
            throw new IllegalArgumentException("newSentinelWrapped must be non-empty");
        }
        // Defensive copy: snapshot the current `remaining()` bytes into a private read-only
        // backing buffer so the record behaves as a stable value (matches WrapResult discipline).
        oldSentinelWrapped = snapshot(oldSentinelWrapped);
        newSentinelWrapped = snapshot(newSentinelWrapped);
    }

    private static ByteBuffer snapshot(ByteBuffer source) {
        final ByteBuffer dup = source.duplicate();
        final byte[] copy = new byte[dup.remaining()];
        dup.get(copy);
        return ByteBuffer.wrap(copy).asReadOnlyBuffer();
    }

    /**
     * Returns a fresh view of the wrapped old-sentinel bytes with an independent position/limit so
     * multiple consumers can each drain the payload without affecting one another. Underlying
     * storage is shared and read-only.
     */
    @Override
    public ByteBuffer oldSentinelWrapped() {
        return oldSentinelWrapped.duplicate();
    }

    /** Independent view of the new-sentinel bytes — see {@link #oldSentinelWrapped()}. */
    @Override
    public ByteBuffer newSentinelWrapped() {
        return newSentinelWrapped.duplicate();
    }
}
