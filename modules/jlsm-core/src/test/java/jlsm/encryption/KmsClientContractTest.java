package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Structural tests for the {@link KmsClient} SPI — verifies the interface shape required by
 * work-plan.md (R80, R80a, R80b) is intact so that adapters implementing the SPI have a stable
 * contract. Behavioural tests live with implementations.
 */
class KmsClientContractTest {

    @Test
    void interface_extendsAutoCloseable() {
        assertTrue(AutoCloseable.class.isAssignableFrom(KmsClient.class));
    }

    @Test
    void declares_wrapKek_method() throws NoSuchMethodException {
        final Method m = KmsClient.class.getMethod("wrapKek", MemorySegment.class, KekRef.class,
                EncryptionContext.class);
        assertNotNull(m);
        assertEquals(WrapResult.class, m.getReturnType());
        assertTrue(declaresChecked(m, KmsException.class));
    }

    @Test
    void declares_unwrapKek_method() throws NoSuchMethodException {
        final Method m = KmsClient.class.getMethod("unwrapKek", ByteBuffer.class, KekRef.class,
                EncryptionContext.class);
        assertNotNull(m);
        assertEquals(UnwrapResult.class, m.getReturnType());
        assertTrue(declaresChecked(m, KmsException.class));
    }

    @Test
    void declares_isUsable_method() throws NoSuchMethodException {
        final Method m = KmsClient.class.getMethod("isUsable", KekRef.class);
        assertEquals(boolean.class, m.getReturnType());
        assertTrue(declaresChecked(m, KmsException.class));
    }

    @Test
    void declares_close_withoutCheckedExceptions() throws NoSuchMethodException {
        final Method m = KmsClient.class.getMethod("close");
        assertEquals(void.class, m.getReturnType());
        // AutoCloseable narrowed to remove Exception from close().
        final List<Class<?>> thrown = Arrays.asList(m.getExceptionTypes());
        assertTrue(thrown.isEmpty(), "close() must not declare checked exceptions");
    }

    @Test
    void spi_canBeStubImplementedForTestingOnly() throws Exception {
        // A minimal anonymous impl should compile & invoke; ensures the interface is callable.
        final KmsClient stub = new KmsClient() {
            @Override
            public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef,
                    EncryptionContext ctx) {
                return new WrapResult(ByteBuffer.wrap(new byte[0]), kekRef);
            }

            @Override
            public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef,
                    EncryptionContext ctx) {
                final Arena arena = Arena.ofShared();
                return new UnwrapResult(arena.allocate(0), arena);
            }

            @Override
            public boolean isUsable(KekRef kekRef) {
                return true;
            }

            @Override
            public void close() {
            }
        };

        try (stub) {
            final KekRef ref = new KekRef("k");
            final EncryptionContext ctx = EncryptionContext.forDomainKek(new TenantId("t"),
                    new DomainId("d"));
            try (Arena a = Arena.ofConfined()) {
                assertNotNull(stub.wrapKek(a.allocate(32), ref, ctx));
            }
            final UnwrapResult uw = stub.unwrapKek(ByteBuffer.allocate(0), ref, ctx);
            try (Arena ignored = uw.owner()) {
                assertNotNull(uw);
            }
            assertTrue(stub.isUsable(ref));
        }
    }

    @Test
    void spi_wrapKek_canThrowKmsException() {
        // Compile-time contract: KmsException must be catchable from wrapKek.
        final KmsClient throwing = new KmsClient() {
            @Override
            public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef,
                    EncryptionContext ctx) throws KmsException {
                throw new KmsPermanentException("nope");
            }

            @Override
            public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef,
                    EncryptionContext ctx) throws KmsException {
                throw new KmsTransientException("later");
            }

            @Override
            public boolean isUsable(KekRef kekRef) {
                return false;
            }

            @Override
            public void close() {
            }
        };

        try (throwing) {
            final KekRef ref = new KekRef("k");
            final EncryptionContext ctx = EncryptionContext.forDomainKek(new TenantId("t"),
                    new DomainId("d"));
            try (Arena a = Arena.ofConfined()) {
                assertThrows(KmsPermanentException.class,
                        () -> throwing.wrapKek(a.allocate(32), ref, ctx));
            }
            assertThrows(KmsTransientException.class,
                    () -> throwing.unwrapKek(ByteBuffer.allocate(0), ref, ctx));
        }
    }

    private static boolean declaresChecked(Method m, Class<?> expected) {
        for (Class<?> t : m.getExceptionTypes()) {
            if (expected.isAssignableFrom(t)) {
                return true;
            }
        }
        return false;
    }
}
