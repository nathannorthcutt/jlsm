package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link DekNotFoundException} — validates R24/R57 shape. */
class DekNotFoundExceptionTest {

    @Test
    void extendsIllegalStateException() {
        assertTrue(IllegalStateException.class.isAssignableFrom(DekNotFoundException.class));
    }

    @Test
    void messageConstructor_setsMessage() {
        final DekNotFoundException e = new DekNotFoundException("boom");
        assertEquals("boom", e.getMessage());
    }

    @Test
    void forHandle_rejectsNullHandle() {
        assertThrows(NullPointerException.class, () -> DekNotFoundException.forHandle(null));
    }

    @Test
    void forHandle_messageContainsScopeIdentifiers() {
        final DekHandle h = new DekHandle(new TenantId("t1"), new DomainId("d1"),
                new TableId("tbl"), new DekVersion(7));
        final DekNotFoundException e = DekNotFoundException.forHandle(h);
        final String m = e.getMessage();
        assertTrue(m.contains("t1"), "message should include tenantId");
        assertTrue(m.contains("d1"), "message should include domainId");
        assertTrue(m.contains("tbl"), "message should include tableId");
        assertTrue(m.contains("7"), "message should include version");
    }

    @Test
    void forHandle_messageMustNotLeakKeyBytes() {
        // Explicit: no key material in messages. We cannot assert "not present" for all
        // possible key bytes, but we can assert the message has the expected scope-only shape.
        final DekHandle h = new DekHandle(new TenantId("tenant1"), new DomainId("domain1"),
                new TableId("table1"), new DekVersion(1));
        final String m = DekNotFoundException.forHandle(h).getMessage();
        assertFalse(m.contains("wrappedBytes"));
        assertFalse(m.contains("plaintext"));
    }
}
