package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link KekRevokedException} sealing (R83) and subclass relationships.
 *
 * @spec encryption.primitives-lifecycle R83
 * @spec encryption.primitives-lifecycle R83b
 * @spec encryption.primitives-lifecycle R83c
 */
class KekRevokedExceptionTest {

    @Test
    void extendsKmsPermanentException() {
        final KekRevokedException e = new TenantKekRevokedException("revoked");
        assertTrue(e instanceof KmsPermanentException,
                "KekRevokedException must extend KmsPermanentException");
    }

    @Test
    void isCheckedException() {
        final KekRevokedException e = new TenantKekRevokedException("revoked");
        assertTrue(e instanceof IOException, "KekRevokedException must extend IOException");
    }

    @Test
    void messageIsPropagated() {
        final KekRevokedException e = new TenantKekRevokedException("scope-A revoked");
        assertEquals("scope-A revoked", e.getMessage());
    }

    @Test
    void causeIsPropagated() {
        final RuntimeException cause = new RuntimeException("upstream");
        final KekRevokedException e = new TenantKekRevokedException("revoked", cause);
        assertSame(cause, e.getCause());
    }

    @Test
    void permitsExactlyTwoSubclasses() {
        final Class<?>[] permitted = KekRevokedException.class.getPermittedSubclasses();
        assertEquals(2, permitted.length,
                "KekRevokedException must permit TenantKekRevokedException + DomainKekRevokedException");
    }

    @Test
    void tenantKekRevokedExceptionIsFinal() {
        assertTrue(
                java.lang.reflect.Modifier.isFinal(TenantKekRevokedException.class.getModifiers()),
                "TenantKekRevokedException must be final");
    }

    @Test
    void domainKekRevokedExceptionIsFinal() {
        assertTrue(
                java.lang.reflect.Modifier.isFinal(DomainKekRevokedException.class.getModifiers()),
                "DomainKekRevokedException must be final");
    }
}
