package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import javax.crypto.Cipher;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AesKeyWrap} — validates R17 tier-2 wrap, R58.
 *
 * <p>
 * Round-trip, tamper-detect, cross-KEK mismatch, and null/zero-length input rejection. If the JCE
 * provider lacks {@code "AESWrapPad"}, the test suite fails in BeforeAll with a clear message so
 * the ops team knows the target JVM is missing a required algorithm.
 */
class AesKeyWrapTest {

    @BeforeAll
    static void assertProviderAvailable() {
        try {
            Cipher.getInstance("AESWrapPad");
        } catch (Exception e) {
            throw new AssertionError(
                    "SunJCE 'AESWrapPad' (AES-KWP per RFC 5649) is required. If running on a"
                            + " minimal JLink image, include 'jdk.crypto.cryptoki' + 'java.base'"
                            + " ensuring SunJCE is registered.",
                    e);
        }
    }

    // ── wrap null / zero-length guards ────────────────────────────────────

    @Test
    void wrap_rejectsNullKek() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment pt = arena.allocate(32);
            assertThrows(NullPointerException.class, () -> AesKeyWrap.wrap(null, pt));
        }
    }

    @Test
    void wrap_rejectsNullPlaintext() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek256(arena);
            assertThrows(NullPointerException.class, () -> AesKeyWrap.wrap(kek, null));
        }
    }

    @Test
    void wrap_rejectsZeroLengthKek() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = arena.allocate(0);
            final MemorySegment pt = arena.allocate(32);
            assertThrows(IllegalArgumentException.class, () -> AesKeyWrap.wrap(kek, pt));
        }
    }

    @Test
    void wrap_rejectsZeroLengthPlaintext() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek256(arena);
            final MemorySegment pt = arena.allocate(0);
            assertThrows(IllegalArgumentException.class, () -> AesKeyWrap.wrap(kek, pt));
        }
    }

    // ── unwrap null / zero-length guards ──────────────────────────────────

    @Test
    void unwrap_rejectsNullKek() {
        try (Arena arena = Arena.ofConfined()) {
            assertThrows(NullPointerException.class,
                    () -> AesKeyWrap.unwrap(null, new byte[40], 32, arena));
        }
    }

    @Test
    void unwrap_rejectsNullWrappedBytes() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek256(arena);
            assertThrows(NullPointerException.class, () -> AesKeyWrap.unwrap(kek, null, 32, arena));
        }
    }

    @Test
    void unwrap_rejectsNullArena() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek256(arena);
            assertThrows(NullPointerException.class,
                    () -> AesKeyWrap.unwrap(kek, new byte[40], 32, null));
        }
    }

    @Test
    void unwrap_rejectsZeroLengthKek() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = arena.allocate(0);
            assertThrows(IllegalArgumentException.class,
                    () -> AesKeyWrap.unwrap(kek, new byte[40], 32, arena));
        }
    }

    @Test
    void unwrap_rejectsNonPositivePlaintextLen() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek256(arena);
            assertThrows(IllegalArgumentException.class,
                    () -> AesKeyWrap.unwrap(kek, new byte[40], 0, arena));
            assertThrows(IllegalArgumentException.class,
                    () -> AesKeyWrap.unwrap(kek, new byte[40], -1, arena));
        }
    }

    // ── round-trip ────────────────────────────────────────────────────────

    @Test
    void roundTrip_32ByteDomainKek_recoversPlaintext() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek256(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x77);
            final byte[] wrapped = AesKeyWrap.wrap(kek, plaintext);
            assertTrue(wrapped.length > 32, "AES-KWP adds at least 8 bytes of overhead");
            final MemorySegment recovered = AesKeyWrap.unwrap(kek, wrapped, 32, arena);
            assertEquals(32, recovered.byteSize());
            assertArrayEquals(plaintext.toArray(ValueLayout.JAVA_BYTE),
                    recovered.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void roundTrip_24ByteDomainKek_recoversPlaintext() {
        // AES-KWP supports non-multiple-of-8 lengths; 24-byte AES-192 key is common.
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek256(arena);
            final MemorySegment plaintext = fill(arena, 24, (byte) 0x42);
            final byte[] wrapped = AesKeyWrap.wrap(kek, plaintext);
            final MemorySegment recovered = AesKeyWrap.unwrap(kek, wrapped, 24, arena);
            assertArrayEquals(plaintext.toArray(ValueLayout.JAVA_BYTE),
                    recovered.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void roundTrip_16ByteKek_works() {
        // AES-128 (16-byte) KEK is a valid wrap key.
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = fill(arena, 16, (byte) 0xCC);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0xDD);
            final byte[] wrapped = AesKeyWrap.wrap(kek, plaintext);
            final MemorySegment recovered = AesKeyWrap.unwrap(kek, wrapped, 32, arena);
            assertArrayEquals(plaintext.toArray(ValueLayout.JAVA_BYTE),
                    recovered.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    // ── tamper detection ─────────────────────────────────────────────────

    @Test
    void tamperedCiphertext_fails() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek256(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x77);
            final byte[] wrapped = AesKeyWrap.wrap(kek, plaintext);
            wrapped[5] ^= 0x01;
            assertThrows(Exception.class, () -> AesKeyWrap.unwrap(kek, wrapped, 32, arena));
        }
    }

    @Test
    void tamperedFinalByte_fails() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek256(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x77);
            final byte[] wrapped = AesKeyWrap.wrap(kek, plaintext);
            wrapped[wrapped.length - 1] ^= 0x01;
            assertThrows(Exception.class, () -> AesKeyWrap.unwrap(kek, wrapped, 32, arena));
        }
    }

    @Test
    void crossKek_unwrapFails() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kekA = fill(arena, 32, (byte) 0xAA);
            final MemorySegment kekB = fill(arena, 32, (byte) 0xBB);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x77);
            final byte[] wrapped = AesKeyWrap.wrap(kekA, plaintext);
            assertThrows(Exception.class, () -> AesKeyWrap.unwrap(kekB, wrapped, 32, arena));
        }
    }

    @Test
    void lengthMismatch_fails() {
        // Unwrap with claimed plaintext length that does not match true length must fail.
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek256(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x77);
            final byte[] wrapped = AesKeyWrap.wrap(kek, plaintext);
            // Wrong plaintextLen claimed by caller:
            assertThrows(Exception.class, () -> AesKeyWrap.unwrap(kek, wrapped, 24, arena));
        }
    }

    // ── determinism: AES-KWP has no randomness ─────────────────────────────

    @Test
    void wrap_isDeterministic_noRandomness() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek256(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x77);
            final byte[] a = AesKeyWrap.wrap(kek, plaintext);
            final byte[] b = AesKeyWrap.wrap(kek, plaintext);
            assertArrayEquals(a, b, "AES-KWP is deterministic (RFC 5649)");
        }
    }

    @Test
    void unwrap_returnedSegmentLifetimeBoundToCallerArena() {
        final Arena callerArena = Arena.ofConfined();
        final MemorySegment recovered;
        try (Arena srcArena = Arena.ofConfined()) {
            final MemorySegment kek = kek256(srcArena);
            final MemorySegment plaintext = fill(srcArena, 32, (byte) 0x77);
            final byte[] wrapped = AesKeyWrap.wrap(kek, plaintext);
            recovered = AesKeyWrap.unwrap(kek, wrapped, 32, callerArena);
        }
        assertEquals(32, recovered.byteSize());
        assertTrue(recovered.scope().isAlive());
        callerArena.close();
        assertFalse(recovered.scope().isAlive());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static MemorySegment fill(Arena arena, int size, byte value) {
        final MemorySegment seg = arena.allocate(size);
        for (long i = 0; i < size; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, value);
        }
        return seg;
    }

    private static MemorySegment kek256(Arena arena) {
        return fill(arena, 32, (byte) 0x99);
    }
}
