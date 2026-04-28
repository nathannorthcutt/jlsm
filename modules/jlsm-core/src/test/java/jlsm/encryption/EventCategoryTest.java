package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventCategory} (R83f, R76b-1).
 *
 * @spec encryption.primitives-lifecycle R76b-1
 * @spec encryption.primitives-lifecycle R83f
 */
class EventCategoryTest {

    @Test
    void hasFourCategories() {
        assertEquals(4, EventCategory.values().length);
    }

    @Test
    void stateTransitionIsDurable() {
        assertTrue(EventCategory.STATE_TRANSITION.isDurable(),
                "state-transition events must persist across restart per R76b-1");
    }

    @Test
    void rekeyIsDurable() {
        assertTrue(EventCategory.REKEY.isDurable(),
                "rekey events must persist across restart per R76b-1");
    }

    @Test
    void pollingIsNotDurable() {
        assertFalse(EventCategory.POLLING.isDurable(), "polling is in-process per R76b-1");
    }

    @Test
    void unclassifiedErrorIsNotDurable() {
        assertFalse(EventCategory.UNCLASSIFIED_ERROR.isDurable(),
                "unclassified-error is in-process per R76b-1");
    }
}
