package jlsm.engine.cluster.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import jlsm.engine.cluster.NodeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * TDD test suite for {@link SuspicionVote}.
 *
 * <p>
 * Covers construction validation, 0x06 sub-type prefix, serialize/deserialize round-trip, and
 * rejection of truncated or mis-tagged payloads.
 */
@Timeout(5)
final class SuspicionVoteTest {

    private static final NodeAddress VOTER = new NodeAddress("voter", "10.0.0.3", 9003);
    private static final NodeAddress SUBJECT = new NodeAddress("subject", "10.0.0.2", 9002);

    @Test
    void constructor_rejectsNullVoter() {
        assertThrows(NullPointerException.class,
                () -> new SuspicionVote(1L, null, SUBJECT, true, 5L));
    }

    @Test
    void constructor_rejectsNullSubject() {
        assertThrows(NullPointerException.class,
                () -> new SuspicionVote(1L, VOTER, null, true, 5L));
    }

    @Test
    void constructor_rejectsNegativeRoundId() {
        assertThrows(IllegalArgumentException.class,
                () -> new SuspicionVote(-1L, VOTER, SUBJECT, true, 5L));
    }

    @Test
    void constructor_rejectsNegativeVoterIncarnation() {
        assertThrows(IllegalArgumentException.class,
                () -> new SuspicionVote(1L, VOTER, SUBJECT, true, -5L));
    }

    @Test
    void constructor_acceptsZeroes() {
        SuspicionVote v = new SuspicionVote(0L, VOTER, SUBJECT, false, 0L);
        assertEquals(0L, v.roundId());
        assertEquals(0L, v.voterIncarnation());
    }

    @Test
    void serialize_prefixesSubtypeByteZeroOhSix() {
        SuspicionVote v = new SuspicionVote(11L, VOTER, SUBJECT, true, 3L);
        byte[] bytes = v.serialize();
        assertTrue(bytes.length > 0);
        assertEquals((byte) 0x06, bytes[0]);
    }

    @Test
    void serialize_roundTripsAgreeTrue() {
        SuspicionVote original = new SuspicionVote(11L, VOTER, SUBJECT, true, 3L);
        SuspicionVote decoded = SuspicionVote.deserialize(original.serialize());
        assertEquals(original, decoded);
        assertTrue(decoded.agree());
    }

    @Test
    void serialize_roundTripsAgreeFalse() {
        SuspicionVote original = new SuspicionVote(11L, VOTER, SUBJECT, false, 3L);
        SuspicionVote decoded = SuspicionVote.deserialize(original.serialize());
        assertEquals(original, decoded);
        assertEquals(false, decoded.agree());
    }

    @Test
    void serialize_roundTripsMaxValues() {
        SuspicionVote original = new SuspicionVote(Long.MAX_VALUE, VOTER, SUBJECT, true,
                Long.MAX_VALUE);
        SuspicionVote decoded = SuspicionVote.deserialize(original.serialize());
        assertEquals(original, decoded);
    }

    @Test
    void deserialize_rejectsWrongSubtypeByte() {
        byte[] bytes = new SuspicionVote(1L, VOTER, SUBJECT, true, 5L).serialize();
        bytes[0] = 0x05;
        assertThrows(IllegalArgumentException.class, () -> SuspicionVote.deserialize(bytes));
    }

    @Test
    void deserialize_rejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class, () -> SuspicionVote.deserialize(new byte[0]));
    }

    @Test
    void deserialize_rejectsNull() {
        assertThrows(NullPointerException.class, () -> SuspicionVote.deserialize(null));
    }

    @Test
    void deserialize_rejectsTruncatedPayload() {
        byte[] bytes = new SuspicionVote(1L, VOTER, SUBJECT, true, 5L).serialize();
        for (int cut = 1; cut < bytes.length; cut++) {
            byte[] truncated = Arrays.copyOf(bytes, cut);
            assertThrows(IllegalArgumentException.class, () -> SuspicionVote.deserialize(truncated),
                    "truncation at " + truncated.length + " bytes should fail");
        }
    }

    @Test
    void serialize_isDeterministic() {
        SuspicionVote a = new SuspicionVote(11L, VOTER, SUBJECT, true, 3L);
        SuspicionVote b = new SuspicionVote(11L, VOTER, SUBJECT, true, 3L);
        assertArrayEquals(a.serialize(), b.serialize());
    }
}
