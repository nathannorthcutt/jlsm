package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link KmsErrorClassifier} R76a-1 mapping.
 *
 * @spec encryption.primitives-lifecycle R76a-1
 * @spec encryption.primitives-lifecycle R76a-2
 */
class KmsErrorClassifierTest {

    @Test
    void permanentExceptionMapsToPermanent() {
        final KmsPermanentException ex = new KmsPermanentException("disabled");
        assertEquals(KmsErrorClassifier.ErrorClass.PERMANENT, KmsErrorClassifier.classify(ex));
    }

    @Test
    void permanentSubclassMapsToPermanent() {
        // WD-03: KmsPermanentException is sealed permits KekRevokedException; subclasses must
        // route through that subtree.
        final KmsPermanentException ex = new TenantKekRevokedException("subclassed via revoke");
        assertEquals(KmsErrorClassifier.ErrorClass.PERMANENT, KmsErrorClassifier.classify(ex));
    }

    @Test
    void transientExceptionMapsToTransient() {
        final KmsTransientException ex = new KmsTransientException("503");
        assertEquals(KmsErrorClassifier.ErrorClass.TRANSIENT, KmsErrorClassifier.classify(ex));
    }

    @Test
    void transientSubclassMapsToTransient() {
        final KmsTransientException ex = new KmsRateLimitExceededException("rate-limit");
        assertEquals(KmsErrorClassifier.ErrorClass.TRANSIENT, KmsErrorClassifier.classify(ex));
    }

    @Test
    void runtimeExceptionMapsToUnclassified() {
        final RuntimeException ex = new RuntimeException("plugin bug");
        assertEquals(KmsErrorClassifier.ErrorClass.UNCLASSIFIED, KmsErrorClassifier.classify(ex));
    }

    @Test
    void illegalStateExceptionMapsToUnclassified() {
        final IllegalStateException ex = new IllegalStateException("plugin bug");
        assertEquals(KmsErrorClassifier.ErrorClass.UNCLASSIFIED, KmsErrorClassifier.classify(ex));
    }

    @Test
    void plainIOExceptionMapsToUnclassified() {
        // R76a-1: only KmsPermanent / KmsTransient subclasses map directly; arbitrary
        // checked exceptions fall through to UNCLASSIFIED.
        final IOException ex = new IOException("network");
        assertEquals(KmsErrorClassifier.ErrorClass.UNCLASSIFIED, KmsErrorClassifier.classify(ex));
    }

    @Test
    void nullThrowableThrowsNpe() {
        assertThrows(NullPointerException.class, () -> KmsErrorClassifier.classify(null));
    }
}
