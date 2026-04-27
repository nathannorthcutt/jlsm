package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DomainId;
import jlsm.encryption.EncryptionContext;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.KmsException;
import jlsm.encryption.KmsPermanentException;
import jlsm.encryption.TenantId;
import jlsm.encryption.UnwrapResult;
import jlsm.encryption.WrapResult;

/**
 * Tests for {@link WrapRoundtripVerifier} (R83e, R83e-1, P4-5, P4-22). Wrap → unwrap →
 * byte-equality verify → zero plaintext copy.
 *
 * @spec encryption.primitives-lifecycle R83e
 * @spec encryption.primitives-lifecycle R83e-1
 */
class WrapRoundtripVerifierTest {

    private static final KekRef KEK = new KekRef("kek-1");
    private static final EncryptionContext CTX = EncryptionContext.forDomainKek(new TenantId("t"),
            new DomainId("d"));

    /** Identity-wrap fake: wrap returns plaintext bytes unchanged; unwrap returns the same. */
    private static final class IdentityKms implements KmsClient {

        @Override
        public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef,
                EncryptionContext context) {
            final byte[] copy = new byte[(int) plaintextKek.byteSize()];
            MemorySegment.copy(plaintextKek, 0, MemorySegment.ofArray(copy), 0, copy.length);
            return new WrapResult(ByteBuffer.wrap(copy), kekRef);
        }

        @Override
        public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef,
                EncryptionContext context) {
            final ByteBuffer dup = wrappedBytes.duplicate();
            final byte[] bytes = new byte[dup.remaining()];
            dup.get(bytes);
            final Arena arena = Arena.ofShared();
            final MemorySegment seg = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0,
                    bytes.length);
            return new UnwrapResult(seg, arena);
        }

        @Override
        public boolean isUsable(KekRef kekRef) {
            return true;
        }

        @Override
        public void close() {
        }
    }

    /** Garbage-unwrap fake: returns wrong bytes on unwrap to simulate buggy plugin (R83e-1). */
    private static final class CorruptingKms implements KmsClient {

        @Override
        public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef,
                EncryptionContext context) {
            final byte[] copy = new byte[(int) plaintextKek.byteSize()];
            MemorySegment.copy(plaintextKek, 0, MemorySegment.ofArray(copy), 0, copy.length);
            return new WrapResult(ByteBuffer.wrap(copy), kekRef);
        }

        @Override
        public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef,
                EncryptionContext context) {
            // Simulate plugin returning bytes that do NOT match the wrap input
            final byte[] garbage = new byte[wrappedBytes.remaining()];
            for (int i = 0; i < garbage.length; i++) {
                garbage[i] = (byte) 0xAA;
            }
            final Arena arena = Arena.ofShared();
            final MemorySegment seg = arena.allocate(garbage.length);
            MemorySegment.copy(garbage, 0, seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0,
                    garbage.length);
            return new UnwrapResult(seg, arena);
        }

        @Override
        public boolean isUsable(KekRef kekRef) {
            return true;
        }

        @Override
        public void close() {
        }
    }

    /** Wrap-failing fake: throws on wrapKek to drive the abort path (no persistence). */
    private static final class WrapFailingKms implements KmsClient {

        @Override
        public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef,
                EncryptionContext context) throws KmsException {
            throw new KmsPermanentException("wrap failed in plugin");
        }

        @Override
        public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef,
                EncryptionContext context) {
            throw new AssertionError("unwrap must not be called when wrap fails");
        }

        @Override
        public boolean isUsable(KekRef kekRef) {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static MemorySegment plaintextOf(int len, byte fill, Arena arena) {
        final MemorySegment seg = arena.allocate(len);
        for (int i = 0; i < len; i++) {
            seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, fill);
        }
        return seg;
    }

    @Test
    void successPathInvokesPersistorAndReturnsWrappedBytes() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment plaintext = plaintextOf(32, (byte) 0x42, arena);
            final AtomicInteger persistCalls = new AtomicInteger();
            final ByteBuffer[] persistedRef = new ByteBuffer[1];
            final ByteBuffer result = WrapRoundtripVerifier.verifyAndPersist(new IdentityKms(), KEK,
                    CTX, plaintext, wrapped -> {
                        persistCalls.incrementAndGet();
                        persistedRef[0] = wrapped;
                    });
            assertNotNull(result);
            assertEquals(1, persistCalls.get(), "persistor must be called exactly once on success");
            assertSame(persistedRef[0], result, "persistor receives same ByteBuffer reference");
        }
    }

    @Test
    void roundtripMismatchThrowsAndDoesNotPersist() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment plaintext = plaintextOf(32, (byte) 0x42, arena);
            final AtomicInteger persistCalls = new AtomicInteger();
            assertThrows(KmsException.class,
                    () -> WrapRoundtripVerifier.verifyAndPersist(new CorruptingKms(), KEK, CTX,
                            plaintext, wrapped -> persistCalls.incrementAndGet()));
            assertEquals(0, persistCalls.get(),
                    "persistor must NOT be called on roundtrip mismatch (R83e-1)");
        }
    }

    @Test
    void wrapFailureSurfacesAndDoesNotPersist() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment plaintext = plaintextOf(32, (byte) 0x42, arena);
            final AtomicInteger persistCalls = new AtomicInteger();
            assertThrows(KmsException.class,
                    () -> WrapRoundtripVerifier.verifyAndPersist(new WrapFailingKms(), KEK, CTX,
                            plaintext, wrapped -> persistCalls.incrementAndGet()));
            assertEquals(0, persistCalls.get(), "persistor must NOT be called when wrap fails");
        }
    }

    @Test
    void persistorIoExceptionPropagates() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment plaintext = plaintextOf(32, (byte) 0x42, arena);
            assertThrows(IOException.class, () -> WrapRoundtripVerifier
                    .verifyAndPersist(new IdentityKms(), KEK, CTX, plaintext, wrapped -> {
                        throw new IOException("registry io failed");
                    }));
        }
    }

    @Test
    void nullArgumentsRejected() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment plaintext = plaintextOf(32, (byte) 0x01, arena);
            final WrapRoundtripVerifier.Persistor noop = wrapped -> {
            };
            assertThrows(NullPointerException.class,
                    () -> WrapRoundtripVerifier.verifyAndPersist(null, KEK, CTX, plaintext, noop));
            assertThrows(NullPointerException.class, () -> WrapRoundtripVerifier
                    .verifyAndPersist(new IdentityKms(), null, CTX, plaintext, noop));
            assertThrows(NullPointerException.class, () -> WrapRoundtripVerifier
                    .verifyAndPersist(new IdentityKms(), KEK, null, plaintext, noop));
            assertThrows(NullPointerException.class, () -> WrapRoundtripVerifier
                    .verifyAndPersist(new IdentityKms(), KEK, CTX, null, noop));
            assertThrows(NullPointerException.class, () -> WrapRoundtripVerifier
                    .verifyAndPersist(new IdentityKms(), KEK, CTX, plaintext, null));
        }
    }

    @Test
    void successPathPlaintextZeroizedAfterReturn() throws Exception {
        // R83e-1: the plaintext copy used for the comparison must be zeroed in `finally`.
        // We cannot reach into the verifier's internal copy directly, but we can assert
        // that the verifier never mutates the caller's plaintext (it must work on a copy).
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment plaintext = plaintextOf(32, (byte) 0x77, arena);
            WrapRoundtripVerifier.verifyAndPersist(new IdentityKms(), KEK, CTX, plaintext,
                    wrapped -> {
                    });
            // Caller's plaintext must remain intact — the verifier's zeroisation operates on
            // its internal copy, not the caller's segment.
            for (int i = 0; i < 32; i++) {
                assertEquals((byte) 0x77, plaintext.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i),
                        "caller's plaintext must NOT be mutated by the verifier");
            }
        }
    }
}
