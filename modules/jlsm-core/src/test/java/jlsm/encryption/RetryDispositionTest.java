package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RetryDisposition} (R83-1). The sealed marker interface must be implemented by
 * all four read-path failure exception families so callers can write a single
 * {@code catch (RetryDisposition.NonRetryable e)} clause.
 *
 * @spec encryption.primitives-lifecycle R83-1
 */
class RetryDispositionTest {

    @Test
    void kekRevokedExceptionIsNonRetryable() {
        final KekRevokedException e = new TenantKekRevokedException("revoked");
        assertTrue(e instanceof RetryDisposition.NonRetryable,
                "TenantKekRevokedException must implement RetryDisposition.NonRetryable");
    }

    @Test
    void domainKekRevokedExceptionIsNonRetryable() {
        final KekRevokedException e = new DomainKekRevokedException("revoked");
        assertTrue(e instanceof RetryDisposition.NonRetryable,
                "DomainKekRevokedException must implement RetryDisposition.NonRetryable");
    }

    @Test
    void registryStateExceptionIsNonRetryable() {
        final RegistryStateException e = new RegistryStateException("CODE", "msg");
        assertTrue(e instanceof RetryDisposition.NonRetryable,
                "RegistryStateException must implement RetryDisposition.NonRetryable");
    }

    @Test
    void dekNotFoundExceptionIsNonRetryable() {
        final DekNotFoundException e = new DekNotFoundException("missing");
        assertTrue(e instanceof RetryDisposition.NonRetryable,
                "DekNotFoundException must implement RetryDisposition.NonRetryable");
    }

    @Test
    void tenantKekUnavailableExceptionIsNonRetryable() {
        final TenantKekUnavailableException e = new TenantKekUnavailableException(
                new TenantId("tenant-x"), "offline");
        assertTrue(e instanceof RetryDisposition.NonRetryable,
                "TenantKekUnavailableException must implement RetryDisposition.NonRetryable");
    }

    @Test
    void uniformInstanceOfCheckPicksUpAllFourFamilies() {
        // R83-1 rationale: a single instanceof / catch clause must pick up every member of
        // the closed set. Java permits a catch-by-interface only when the compiler can prove
        // the interface itself extends Throwable; RetryDisposition.NonRetryable is a marker
        // that does NOT extend Throwable (the actual Throwable-ness is on its permitted
        // subclasses), so we exercise the alarm pattern via instanceof here.
        final Throwable[] throwables = new Throwable[]{ new TenantKekRevokedException("t"),
                new DomainKekRevokedException("d"), new RegistryStateException("CODE", "r"),
                new DekNotFoundException("missing"),
                new TenantKekUnavailableException(new TenantId("u"), "u"), };
        for (final Throwable t : throwables) {
            assertTrue(t instanceof RetryDisposition.NonRetryable,
                    "expected NonRetryable: " + t.getClass().getName());
        }
    }

    @Test
    void unrelatedIoExceptionDoesNotImplementMarker() {
        final IOException e = new IOException("plain io");
        assertFalse(e instanceof RetryDisposition.NonRetryable,
                "plain IOException must NOT implement RetryDisposition.NonRetryable");
    }

    @Test
    void sealedInterfaceCannotBeAnonymouslyImplemented() {
        // The sealed interface's permits clause restricts subtypes; this test documents that
        // intent. There's no practical way to assert "compile error" at runtime, so we
        // verify the seal-permitted set via reflection.
        final Class<?>[] permitted = RetryDisposition.NonRetryable.class.getPermittedSubclasses();
        assertTrue(permitted.length == 4,
                "RetryDisposition.NonRetryable must permit exactly 4 subclasses (R83-1), got "
                        + permitted.length);
    }
}
