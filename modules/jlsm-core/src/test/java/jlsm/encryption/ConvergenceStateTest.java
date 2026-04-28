package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ConvergenceState} — closed enum of {PENDING, CONVERGED, TIMED_OUT, REVOKED}
 * (R37b-1, R83d). REVOKED is terminal; allowed transitions are
 * {@code PENDING → CONVERGED → REVOKED} or {@code PENDING → TIMED_OUT → REVOKED}.
 *
 * @spec encryption.primitives-lifecycle R37b-1
 * @spec encryption.primitives-lifecycle R83d
 */
class ConvergenceStateTest {

    @Test
    void hasFourStates() {
        assertEquals(4, ConvergenceState.values().length);
    }

    @Test
    void hasCanonicalValues() {
        final Set<ConvergenceState> expected = EnumSet.of(ConvergenceState.PENDING,
                ConvergenceState.CONVERGED, ConvergenceState.TIMED_OUT, ConvergenceState.REVOKED);
        for (ConvergenceState s : ConvergenceState.values()) {
            assertTrue(expected.contains(s), "unknown state: " + s);
        }
    }

    @Test
    void valueOf_pending() {
        assertEquals(ConvergenceState.PENDING, ConvergenceState.valueOf("PENDING"));
    }

    @Test
    void valueOf_converged() {
        assertEquals(ConvergenceState.CONVERGED, ConvergenceState.valueOf("CONVERGED"));
    }

    @Test
    void valueOf_timedOut() {
        assertEquals(ConvergenceState.TIMED_OUT, ConvergenceState.valueOf("TIMED_OUT"));
    }

    @Test
    void valueOf_revoked() {
        assertEquals(ConvergenceState.REVOKED, ConvergenceState.valueOf("REVOKED"));
    }

    @Test
    void enumDeclarationOrder_isPublic() {
        // Test pins the canonical declaration order; defensive check that R37b-1's monotonic
        // ordering is observable to consumers reasoning about state position.
        final ConvergenceState[] values = ConvergenceState.values();
        assertEquals(ConvergenceState.PENDING, values[0]);
        assertEquals(ConvergenceState.CONVERGED, values[1]);
        assertEquals(ConvergenceState.TIMED_OUT, values[2]);
        assertEquals(ConvergenceState.REVOKED, values[3]);
    }

    @Test
    void each_isNonNull() {
        for (ConvergenceState s : ConvergenceState.values()) {
            assertNotNull(s.name());
        }
    }
}
