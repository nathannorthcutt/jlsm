package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** Tests for {@link Purpose} — validates R80a closed-set requirement. */
class PurposeTest {

    @Test
    void enum_hasExactlyFourValues() {
        // R80a: closed set. Adding/removing a value is a breaking change across WD-02/03/04/05.
        assertEquals(4, Purpose.values().length);
    }

    @Test
    void enum_containsDomainKek() {
        assertNotNull(Purpose.valueOf("DOMAIN_KEK"));
    }

    @Test
    void enum_containsDek() {
        assertNotNull(Purpose.valueOf("DEK"));
    }

    @Test
    void enum_containsRekeySentinel() {
        assertNotNull(Purpose.valueOf("REKEY_SENTINEL"));
    }

    @Test
    void enum_containsHealthCheck() {
        assertNotNull(Purpose.valueOf("HEALTH_CHECK"));
    }

    @Test
    void ordinals_stableOrderingMatchesDeclaration() {
        // AAD encoding binds to ordinals — changing order is a wire-format break.
        assertEquals(0, Purpose.DOMAIN_KEK.ordinal());
        assertEquals(1, Purpose.DEK.ordinal());
        assertEquals(2, Purpose.REKEY_SENTINEL.ordinal());
        assertEquals(3, Purpose.HEALTH_CHECK.ordinal());
    }
}
