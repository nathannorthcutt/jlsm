package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link KmsException} sealed hierarchy — validates R76a permanent-vs-transient
 * classification at the exception-type level.
 */
class KmsExceptionHierarchyTest {

    @Test
    void kmsException_isSealed() {
        assertTrue(KmsException.class.isSealed(), "KmsException must be sealed");
    }

    @Test
    void kmsException_isAbstract() {
        assertTrue(java.lang.reflect.Modifier.isAbstract(KmsException.class.getModifiers()),
                "KmsException must be abstract");
    }

    @Test
    void kmsException_permitsTransientAndPermanentOnly() {
        final List<Class<?>> permitted = Arrays.asList(KmsException.class.getPermittedSubclasses());
        final Set<String> names = permitted.stream().map(Class::getSimpleName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("KmsTransientException", "KmsPermanentException"), names,
                "KmsException must permit exactly KmsTransientException and KmsPermanentException");
    }

    @Test
    void kmsException_extendsException() {
        assertTrue(Exception.class.isAssignableFrom(KmsException.class));
    }

    @Test
    void transient_isNonSealed() {
        // Non-sealed so KmsRateLimitExceededException can extend it.
        assertFalse(KmsTransientException.class.isSealed(),
                "KmsTransientException must be non-sealed for observability subclasses");
    }

    @Test
    void permanent_isFinal() {
        assertTrue(java.lang.reflect.Modifier.isFinal(KmsPermanentException.class.getModifiers()));
    }

    @Test
    void rateLimit_isTransientSubclass() {
        assertTrue(
                KmsTransientException.class.isAssignableFrom(KmsRateLimitExceededException.class),
                "KmsRateLimitExceededException must be a transient subclass");
    }

    @Test
    void transient_constructors_takeMessageAndCause() {
        final Throwable cause = new RuntimeException("boom");
        final KmsTransientException e = new KmsTransientException("msg", cause);
        assertEquals("msg", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void permanent_constructors_takeMessageAndCause() {
        final Throwable cause = new RuntimeException("boom");
        final KmsPermanentException e = new KmsPermanentException("msg", cause);
        assertEquals("msg", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void rateLimit_constructors_takeMessageAndCause() {
        final Throwable cause = new RuntimeException("boom");
        final KmsRateLimitExceededException e = new KmsRateLimitExceededException("rate-limited",
                cause);
        assertEquals("rate-limited", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void classifyByPatternMatching_transientVsPermanent() {
        // Callers choose retry policy by matching the sealed subtypes.
        final KmsException t = new KmsTransientException("t");
        final KmsException p = new KmsPermanentException("p");
        assertTrue(classify(t));
        assertFalse(classify(p));
    }

    private static boolean classify(KmsException e) {
        if (e instanceof KmsTransientException) {
            return true;
        }
        if (e instanceof KmsPermanentException) {
            return false;
        }
        throw new AssertionError("unreachable due to sealed hierarchy");
    }
}
