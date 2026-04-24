package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** Tests for {@link TenantFlavor} — validates R71/R71a per-tenant flavor selection. */
class TenantFlavorTest {

    @Test
    void enum_hasExactlyThreeValues() {
        assertEquals(3, TenantFlavor.values().length);
    }

    @Test
    void enum_containsNone() {
        assertNotNull(TenantFlavor.valueOf("NONE"));
    }

    @Test
    void enum_containsLocal() {
        assertNotNull(TenantFlavor.valueOf("LOCAL"));
    }

    @Test
    void enum_containsExternal() {
        assertNotNull(TenantFlavor.valueOf("EXTERNAL"));
    }
}
