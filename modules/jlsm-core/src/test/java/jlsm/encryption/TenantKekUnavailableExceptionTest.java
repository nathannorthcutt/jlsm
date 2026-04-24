package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link TenantKekUnavailableException} — validates R76 three-state exception type. */
class TenantKekUnavailableExceptionTest {

    @Test
    void isCheckedException() {
        assertTrue(Exception.class.isAssignableFrom(TenantKekUnavailableException.class));
        assertTrue(!RuntimeException.class.isAssignableFrom(TenantKekUnavailableException.class),
                "must not be unchecked");
    }

    @Test
    void twoArgConstructor_setsTenantAndMessage() {
        final TenantId tid = new TenantId("t1");
        final TenantKekUnavailableException e = new TenantKekUnavailableException(tid,
                "kms unreachable");
        assertSame(tid, e.tenantId());
        assertEquals("kms unreachable", e.getMessage());
    }

    @Test
    void threeArgConstructor_setsTenantMessageAndCause() {
        final TenantId tid = new TenantId("t1");
        final Throwable cause = new RuntimeException("socket");
        final TenantKekUnavailableException e = new TenantKekUnavailableException(tid, "kms down",
                cause);
        assertSame(tid, e.tenantId());
        assertEquals("kms down", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void twoArgConstructor_rejectsNullTenant() {
        assertThrows(NullPointerException.class,
                () -> new TenantKekUnavailableException(null, "msg"));
    }

    @Test
    void threeArgConstructor_rejectsNullTenant() {
        assertThrows(NullPointerException.class,
                () -> new TenantKekUnavailableException(null, "msg", new RuntimeException()));
    }
}
