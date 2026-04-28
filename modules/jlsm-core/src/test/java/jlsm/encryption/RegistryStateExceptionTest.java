package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RegistryStateException} (R83b-2, R83i-2). Stable error code accessor; non-sealed
 * for RegistryShredConfirmation token-replay variants.
 *
 * @spec encryption.primitives-lifecycle R83b-2
 * @spec encryption.primitives-lifecycle R83i-2
 */
class RegistryStateExceptionTest {

    @Test
    void extendsIoException() {
        final RegistryStateException e = new RegistryStateException(
                "JLSM-ENC-83B2-REGISTRY-DESTROYED", "destroyed");
        assertTrue(e instanceof IOException, "RegistryStateException must extend IOException");
    }

    @Test
    void implementsNonRetryableMarker() {
        final RegistryStateException e = new RegistryStateException("CODE", "msg");
        assertTrue(e instanceof RetryDisposition.NonRetryable,
                "RegistryStateException must implement RetryDisposition.NonRetryable");
    }

    @Test
    void errorCodeIsPropagated() {
        final RegistryStateException e = new RegistryStateException(
                "JLSM-ENC-83B2-REGISTRY-DESTROYED", "registry shard absent");
        assertEquals("JLSM-ENC-83B2-REGISTRY-DESTROYED", e.errorCode());
        assertEquals("registry shard absent", e.getMessage());
    }

    @Test
    void errorCodeWithCauseIsPropagated() {
        final RuntimeException cause = new RuntimeException("upstream");
        final RegistryStateException e = new RegistryStateException(
                "JLSM-ENC-83B2-MANIFEST-REBUILD", "rebuild in progress", cause);
        assertEquals("JLSM-ENC-83B2-MANIFEST-REBUILD", e.errorCode());
        assertSame(cause, e.getCause());
    }

    @Test
    void nullErrorCodeIsRejected() {
        assertThrows(NullPointerException.class, () -> new RegistryStateException(null, "msg"));
    }

    @Test
    void nullErrorCodeWithCauseIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new RegistryStateException(null, "msg", new RuntimeException()));
    }
}
