package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests for {@link DomainId} — validates R17 tier-2 identifier + R75 _wal reserved name. */
class DomainIdTest {

    @Test
    void constructor_rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new DomainId(null));
    }

    @Test
    void constructor_rejectsEmptyValue() {
        assertThrows(IllegalArgumentException.class, () -> new DomainId(""));
    }

    @Test
    void constructor_acceptsNonEmptyString() {
        assertEquals("users", new DomainId("users").value());
    }

    @Test
    void constructor_accepts_walReservedName() {
        // R75: synthetic "_wal" domain is valid; encryption layer treats as opaque.
        final DomainId wal = new DomainId("_wal");
        assertEquals("_wal", wal.value());
    }

    @Test
    void valueEquality() {
        assertEquals(new DomainId("d1"), new DomainId("d1"));
    }
}
