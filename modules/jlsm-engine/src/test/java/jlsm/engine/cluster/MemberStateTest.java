package jlsm.engine.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MemberState} enum completeness.
 *
 * @spec engine.clustering.R12 — states: ALIVE, SUSPECTED, DEAD
 * @spec engine.clustering.R13 — DEAD state is terminal and tracked as a distinct value in this enum
 */
class MemberStateTest {

    @Test
    void allValuesPresent() {
        var values = MemberState.values();
        assertEquals(3, values.length);
        assertNotNull(MemberState.ALIVE);
        assertNotNull(MemberState.SUSPECTED);
        assertNotNull(MemberState.DEAD);
    }

    @Test
    void valueOfRoundTrip() {
        for (MemberState ms : MemberState.values()) {
            assertEquals(ms, MemberState.valueOf(ms.name()));
        }
    }
}
