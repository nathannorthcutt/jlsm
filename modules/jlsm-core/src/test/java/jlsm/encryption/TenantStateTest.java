package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TenantState}.
 *
 * @spec encryption.primitives-lifecycle R76b
 * @spec encryption.primitives-lifecycle R83a
 * @spec encryption.primitives-lifecycle R83c
 */
class TenantStateTest {

    @Test
    void hasThreeStates() {
        assertEquals(3, TenantState.values().length);
    }

    @Test
    void containsHealthy() {
        assertNotNull(TenantState.valueOf("HEALTHY"));
    }

    @Test
    void containsGraceReadOnly() {
        assertNotNull(TenantState.valueOf("GRACE_READ_ONLY"));
    }

    @Test
    void containsFailed() {
        assertNotNull(TenantState.valueOf("FAILED"));
    }
}
