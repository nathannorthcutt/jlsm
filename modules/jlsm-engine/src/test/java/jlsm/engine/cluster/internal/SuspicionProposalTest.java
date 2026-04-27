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
 * TDD test suite for {@link SuspicionProposal}.
 *
 * <p>
 * Covers construction validation, byte-level framing, serialize/deserialize round-trip, and
 * rejection of truncated or mis-tagged payloads.
 */
@Timeout(5)
final class SuspicionProposalTest {

    private static final NodeAddress SUBJECT = new NodeAddress("subject", "10.0.0.2", 9002);
    private static final NodeAddress PROPOSER = new NodeAddress("proposer", "10.0.0.1", 9001);

    @Test
    void constructor_rejectsNullSubject() {
        assertThrows(NullPointerException.class,
                () -> new SuspicionProposal(null, 1L, 2L, 3L, PROPOSER));
    }

    @Test
    void constructor_rejectsNullProposer() {
        assertThrows(NullPointerException.class,
                () -> new SuspicionProposal(SUBJECT, 1L, 2L, 3L, null));
    }

    @Test
    void constructor_rejectsNegativeIncarnation() {
        assertThrows(IllegalArgumentException.class,
                () -> new SuspicionProposal(SUBJECT, -1L, 2L, 3L, PROPOSER));
    }

    @Test
    void constructor_rejectsNegativeRoundId() {
        assertThrows(IllegalArgumentException.class,
                () -> new SuspicionProposal(SUBJECT, 1L, -2L, 3L, PROPOSER));
    }

    @Test
    void constructor_rejectsNegativeEpoch() {
        assertThrows(IllegalArgumentException.class,
                () -> new SuspicionProposal(SUBJECT, 1L, 2L, -3L, PROPOSER));
    }

    @Test
    void constructor_acceptsZeroes() {
        SuspicionProposal p = new SuspicionProposal(SUBJECT, 0L, 0L, 0L, PROPOSER);
        assertEquals(0L, p.subjectIncarnation());
        assertEquals(0L, p.roundId());
        assertEquals(0L, p.epoch());
    }

    @Test
    void serialize_prefixesSubtypeByteZeroOhFive() {
        SuspicionProposal p = new SuspicionProposal(SUBJECT, 7L, 11L, 13L, PROPOSER);
        byte[] bytes = p.serialize();
        assertTrue(bytes.length > 0);
        assertEquals((byte) 0x05, bytes[0]);
    }

    @Test
    void serialize_roundTripsIdentical() {
        SuspicionProposal original = new SuspicionProposal(SUBJECT, 7L, 11L, 13L, PROPOSER);
        byte[] bytes = original.serialize();
        SuspicionProposal decoded = SuspicionProposal.deserialize(bytes);
        assertEquals(original, decoded);
    }

    @Test
    void serialize_roundTripsMaxValues() {
        SuspicionProposal original = new SuspicionProposal(SUBJECT, Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, PROPOSER);
        SuspicionProposal decoded = SuspicionProposal.deserialize(original.serialize());
        assertEquals(original, decoded);
    }

    @Test
    void deserialize_rejectsWrongSubtypeByte() {
        SuspicionProposal p = new SuspicionProposal(SUBJECT, 1L, 2L, 3L, PROPOSER);
        byte[] bytes = p.serialize();
        bytes[0] = 0x04;
        assertThrows(IllegalArgumentException.class, () -> SuspicionProposal.deserialize(bytes));
    }

    @Test
    void deserialize_rejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> SuspicionProposal.deserialize(new byte[0]));
    }

    @Test
    void deserialize_rejectsNull() {
        assertThrows(NullPointerException.class, () -> SuspicionProposal.deserialize(null));
    }

    @Test
    void deserialize_rejectsTruncatedPayload() {
        SuspicionProposal p = new SuspicionProposal(SUBJECT, 1L, 2L, 3L, PROPOSER);
        byte[] bytes = p.serialize();
        // Truncate at multiple points — every prefix shorter than the full payload must fail.
        for (int cut = 1; cut < bytes.length; cut++) {
            byte[] truncated = Arrays.copyOf(bytes, cut);
            assertThrows(IllegalArgumentException.class,
                    () -> SuspicionProposal.deserialize(truncated),
                    "truncation at " + truncated.length + " bytes should fail");
        }
    }

    @Test
    void serialize_isDeterministic() {
        SuspicionProposal a = new SuspicionProposal(SUBJECT, 7L, 11L, 13L, PROPOSER);
        SuspicionProposal b = new SuspicionProposal(SUBJECT, 7L, 11L, 13L, PROPOSER);
        assertArrayEquals(a.serialize(), b.serialize());
    }
}
