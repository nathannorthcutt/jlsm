package jlsm.engine.cluster.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import jlsm.cluster.NodeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * TDD test suite for {@link AliveRefutation}.
 *
 * <p>
 * Covers construction validation, 0x07 sub-type prefix, serialize/deserialize round-trip, and
 * rejection of truncated or mis-tagged payloads.
 */
@Timeout(5)
final class AliveRefutationTest {

    private static final NodeAddress SUBJECT = new NodeAddress("self", "10.0.0.4", 9004);

    @Test
    void constructor_rejectsNullSubject() {
        assertThrows(NullPointerException.class, () -> new AliveRefutation(null, 1L, 2L));
    }

    @Test
    void constructor_rejectsNegativeIncarnation() {
        assertThrows(IllegalArgumentException.class, () -> new AliveRefutation(SUBJECT, -1L, 2L));
    }

    @Test
    void constructor_rejectsNegativeEpoch() {
        assertThrows(IllegalArgumentException.class, () -> new AliveRefutation(SUBJECT, 1L, -2L));
    }

    @Test
    void constructor_acceptsZeroes() {
        AliveRefutation r = new AliveRefutation(SUBJECT, 0L, 0L);
        assertEquals(0L, r.incarnation());
        assertEquals(0L, r.epoch());
    }

    @Test
    void serialize_prefixesSubtypeByteZeroOhSeven() {
        AliveRefutation r = new AliveRefutation(SUBJECT, 9L, 11L);
        byte[] bytes = r.serialize();
        assertTrue(bytes.length > 0);
        assertEquals((byte) 0x07, bytes[0]);
    }

    @Test
    void serialize_roundTripsIdentical() {
        AliveRefutation original = new AliveRefutation(SUBJECT, 9L, 11L);
        AliveRefutation decoded = AliveRefutation.deserialize(original.serialize());
        assertEquals(original, decoded);
    }

    @Test
    void serialize_roundTripsMaxValues() {
        AliveRefutation original = new AliveRefutation(SUBJECT, Long.MAX_VALUE, Long.MAX_VALUE);
        AliveRefutation decoded = AliveRefutation.deserialize(original.serialize());
        assertEquals(original, decoded);
    }

    @Test
    void deserialize_rejectsWrongSubtypeByte() {
        byte[] bytes = new AliveRefutation(SUBJECT, 1L, 2L).serialize();
        bytes[0] = 0x06;
        assertThrows(IllegalArgumentException.class, () -> AliveRefutation.deserialize(bytes));
    }

    @Test
    void deserialize_rejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> AliveRefutation.deserialize(new byte[0]));
    }

    @Test
    void deserialize_rejectsNull() {
        assertThrows(NullPointerException.class, () -> AliveRefutation.deserialize(null));
    }

    @Test
    void deserialize_rejectsTruncatedPayload() {
        byte[] bytes = new AliveRefutation(SUBJECT, 1L, 2L).serialize();
        for (int cut = 1; cut < bytes.length; cut++) {
            byte[] truncated = Arrays.copyOf(bytes, cut);
            assertThrows(IllegalArgumentException.class,
                    () -> AliveRefutation.deserialize(truncated),
                    "truncation at " + truncated.length + " bytes should fail");
        }
    }

    @Test
    void serialize_isDeterministic() {
        AliveRefutation a = new AliveRefutation(SUBJECT, 9L, 11L);
        AliveRefutation b = new AliveRefutation(SUBJECT, 9L, 11L);
        assertArrayEquals(a.serialize(), b.serialize());
    }
}
