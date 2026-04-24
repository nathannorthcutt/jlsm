package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests for {@link TenantId} — validates R17 tier-1 identifier contract. */
class TenantIdTest {

    @Test
    void constructor_rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new TenantId(null));
    }

    @Test
    void constructor_rejectsEmptyValue() {
        assertThrows(IllegalArgumentException.class, () -> new TenantId(""));
    }

    @Test
    void constructor_acceptsNonEmptyString() {
        final TenantId id = new TenantId("tenant-a");
        assertEquals("tenant-a", id.value());
    }

    @Test
    void valueEquality_sameValuesAreEqual() {
        assertEquals(new TenantId("t1"), new TenantId("t1"));
    }

    @Test
    void valueEquality_differentValuesAreNotEqual() {
        assertNotEquals(new TenantId("t1"), new TenantId("t2"));
    }

    @Test
    void hashCode_equalsForEqualValues() {
        assertEquals(new TenantId("t1").hashCode(), new TenantId("t1").hashCode());
    }
}
