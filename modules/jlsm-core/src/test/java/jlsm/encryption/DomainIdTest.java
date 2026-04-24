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
    void constructor_rejects_walReservedName() {
        // R75: synthetic "_wal" is reserved for the WAL encryption domain. Public
        // callers must not supply it via the constructor — they obtain the
        // synthetic instance via DomainId.forWal() (the only sanctioned factory).
        // Rejecting "_wal" here prevents user-data scopes from colliding with the
        // internal WAL scope in the per-tenant key registry.
        assertThrows(IllegalArgumentException.class, () -> new DomainId("_wal"));
    }

    @Test
    void forWal_returns_walReservedDomain() {
        // The synthetic WAL domain IS a DomainId (R71), accessible only via the
        // dedicated factory.
        final DomainId wal = DomainId.forWal();
        assertEquals("_wal", wal.value());
    }

    @Test
    void valueEquality() {
        assertEquals(new DomainId("d1"), new DomainId("d1"));
    }
}
