package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RekeySentinel} — the operator-supplied rekey proof-of-control record. Validates
 * input rejection, defensive copy semantics, and timestamp preservation.
 *
 * @spec encryption.primitives-lifecycle R78a
 */
class RekeySentinelTest {

    private static final Instant NOW = Instant.parse("2026-04-27T12:00:00Z");

    private static ByteBuffer wrappedBytes(int seed) {
        final byte[] data = new byte[16];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (seed + i);
        }
        return ByteBuffer.wrap(data);
    }

    @Test
    void construct_validArgs_succeeds() {
        final RekeySentinel sentinel = new RekeySentinel(wrappedBytes(1), wrappedBytes(2), NOW);
        assertNotNull(sentinel.oldSentinelWrapped());
        assertNotNull(sentinel.newSentinelWrapped());
        assertEquals(NOW, sentinel.timestamp());
    }

    @Test
    void construct_nullOldSentinel_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> new RekeySentinel(null, wrappedBytes(1), NOW));
    }

    @Test
    void construct_nullNewSentinel_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> new RekeySentinel(wrappedBytes(1), null, NOW));
    }

    @Test
    void construct_nullTimestamp_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> new RekeySentinel(wrappedBytes(1), wrappedBytes(2), null));
    }

    @Test
    void construct_emptyOldSentinel_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new RekeySentinel(ByteBuffer.allocate(0), wrappedBytes(2), NOW));
    }

    @Test
    void construct_emptyNewSentinel_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new RekeySentinel(wrappedBytes(1), ByteBuffer.allocate(0), NOW));
    }

    @Test
    void defensiveCopy_callerMutatesAfterConstruct_recordIsStable() {
        // Per R78a, the sentinel record must hold a private copy of the buffer contents so
        // a caller-side mutation to position/limit/contents cannot mutate the recorded view.
        final byte[] originalOld = new byte[]{ (byte) 0x10, (byte) 0x20, (byte) 0x30, (byte) 0x40 };
        final byte[] originalNew = new byte[]{ (byte) 0x50, (byte) 0x60, (byte) 0x70, (byte) 0x80 };
        final ByteBuffer oldBuf = ByteBuffer.wrap(originalOld);
        final ByteBuffer newBuf = ByteBuffer.wrap(originalNew);
        final RekeySentinel sentinel = new RekeySentinel(oldBuf, newBuf, NOW);

        // Mutate caller-owned arrays AFTER construction
        originalOld[0] = (byte) 0xFF;
        originalNew[0] = (byte) 0xFF;
        // Mutate buffer position
        oldBuf.position(oldBuf.limit());
        newBuf.position(newBuf.limit());

        // Recorded sentinel must NOT see those mutations
        final byte[] recordedOld = new byte[sentinel.oldSentinelWrapped().remaining()];
        sentinel.oldSentinelWrapped().duplicate().get(recordedOld);
        final byte[] recordedNew = new byte[sentinel.newSentinelWrapped().remaining()];
        sentinel.newSentinelWrapped().duplicate().get(recordedNew);

        assertArrayEquals(new byte[]{ (byte) 0x10, (byte) 0x20, (byte) 0x30, (byte) 0x40 },
                recordedOld, "old sentinel must be insulated from caller mutation");
        assertArrayEquals(new byte[]{ (byte) 0x50, (byte) 0x60, (byte) 0x70, (byte) 0x80 },
                recordedNew, "new sentinel must be insulated from caller mutation");
    }

    @Test
    void accessors_returnIndependentBuffers_perCall() {
        // Two calls to oldSentinelWrapped() should return independent ByteBuffer cursors so
        // multiple consumers can drain in parallel — same discipline as WrapResult.
        final RekeySentinel sentinel = new RekeySentinel(wrappedBytes(1), wrappedBytes(2), NOW);
        final ByteBuffer view1 = sentinel.oldSentinelWrapped();
        final ByteBuffer view2 = sentinel.oldSentinelWrapped();
        // Drain view1
        while (view1.hasRemaining()) {
            view1.get();
        }
        // view2 must still have the same remaining bytes as a freshly-derived view
        assertEquals(16, view2.remaining(), "second view must be cursor-independent");
        assertNotSame(view1, view2);
    }
}
