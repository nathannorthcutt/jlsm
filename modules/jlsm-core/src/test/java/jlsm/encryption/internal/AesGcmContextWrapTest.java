package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.EncryptionContext;
import jlsm.encryption.TableId;
import jlsm.encryption.TenantId;

/**
 * Tests for {@link AesGcmContextWrap} — validates R17 tier-3 wrap, R29, R80a (AAD-bound context).
 *
 * <p>
 * CRITICAL: the cross-scope swap prevention tests are the heart of R80a-1. Wrap under one context,
 * attempt unwrap under another, must fail authentication.
 */
class AesGcmContextWrapTest {

    private static final TenantId T_A = new TenantId("tenant-a");
    private static final TenantId T_B = new TenantId("tenant-b");
    private static final DomainId D_X = new DomainId("domain-x");
    private static final DomainId D_Y = new DomainId("domain-y");
    private static final TableId TBL_1 = new TableId("t1");
    private static final TableId TBL_2 = new TableId("t2");
    private static final DekVersion V1 = new DekVersion(1);
    private static final DekVersion V2 = new DekVersion(2);

    private static final SecureRandom RNG = new SecureRandom();

    // ── null / zero-length guards ────────────────────────────────────────

    @Test
    void wrap_rejectsNullDomainKek() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment dek = dek(arena);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            assertThrows(NullPointerException.class,
                    () -> AesGcmContextWrap.wrap(null, dek, ctx, RNG));
        }
    }

    @Test
    void wrap_rejectsNullDek() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            assertThrows(NullPointerException.class,
                    () -> AesGcmContextWrap.wrap(kek, null, ctx, RNG));
        }
    }

    @Test
    void wrap_rejectsNullCtx() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment dek = dek(arena);
            assertThrows(NullPointerException.class,
                    () -> AesGcmContextWrap.wrap(kek, dek, null, RNG));
        }
    }

    @Test
    void wrap_rejectsNullRng() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment dek = dek(arena);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            assertThrows(NullPointerException.class,
                    () -> AesGcmContextWrap.wrap(kek, dek, ctx, null));
        }
    }

    @Test
    void wrap_rejectsZeroLengthDomainKek() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = arena.allocate(0);
            final MemorySegment dek = dek(arena);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            assertThrows(IllegalArgumentException.class,
                    () -> AesGcmContextWrap.wrap(kek, dek, ctx, RNG));
        }
    }

    @Test
    void unwrap_rejectsNullArguments() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            assertThrows(NullPointerException.class,
                    () -> AesGcmContextWrap.unwrap(null, new byte[60], ctx, 32, arena));
            assertThrows(NullPointerException.class,
                    () -> AesGcmContextWrap.unwrap(kek, null, ctx, 32, arena));
            assertThrows(NullPointerException.class,
                    () -> AesGcmContextWrap.unwrap(kek, new byte[60], null, 32, arena));
            assertThrows(NullPointerException.class,
                    () -> AesGcmContextWrap.unwrap(kek, new byte[60], ctx, 32, null));
        }
    }

    // ── round-trip ────────────────────────────────────────────────────────

    @Test
    void roundTrip_sameContext_recoversPlaintext() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment dekPlaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final byte[] wrapped = AesGcmContextWrap.wrap(kek, dekPlaintext, ctx, RNG);
            final MemorySegment recovered = AesGcmContextWrap.unwrap(kek, wrapped, ctx, 32, arena);
            assertArrayEquals(dekPlaintext.toArray(ValueLayout.JAVA_BYTE),
                    recovered.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void ciphertextLayout_is12IvPlusPlaintextPlus16Tag() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final byte[] wrapped = AesGcmContextWrap.wrap(kek, plaintext, ctx, RNG);
            // GCM: 12-byte IV + ciphertext (same length as plaintext) + 16-byte tag.
            assertEquals(12 + 32 + 16, wrapped.length);
        }
    }

    // ── CRITICAL: cross-scope context swap prevention (R80a-1) ───────────

    @Test
    void crossTable_unwrapFails() {
        // R17, R80a-1: wrapped for tbl1 cannot be unwrapped as tbl2.
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext ctxA = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final EncryptionContext ctxB = EncryptionContext.forDek(T_A, D_X, TBL_2, V1);
            final byte[] wrapped = AesGcmContextWrap.wrap(kek, plaintext, ctxA, RNG);
            assertThrows(Exception.class,
                    () -> AesGcmContextWrap.unwrap(kek, wrapped, ctxB, 32, arena));
        }
    }

    @Test
    void crossTenant_unwrapFails() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext ctxA = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final EncryptionContext ctxB = EncryptionContext.forDek(T_B, D_X, TBL_1, V1);
            final byte[] wrapped = AesGcmContextWrap.wrap(kek, plaintext, ctxA, RNG);
            assertThrows(Exception.class,
                    () -> AesGcmContextWrap.unwrap(kek, wrapped, ctxB, 32, arena));
        }
    }

    @Test
    void crossDomain_unwrapFails() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext ctxA = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final EncryptionContext ctxB = EncryptionContext.forDek(T_A, D_Y, TBL_1, V1);
            final byte[] wrapped = AesGcmContextWrap.wrap(kek, plaintext, ctxA, RNG);
            assertThrows(Exception.class,
                    () -> AesGcmContextWrap.unwrap(kek, wrapped, ctxB, 32, arena));
        }
    }

    @Test
    void crossDekVersion_unwrapFails() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext ctxA = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final EncryptionContext ctxB = EncryptionContext.forDek(T_A, D_X, TBL_1, V2);
            final byte[] wrapped = AesGcmContextWrap.wrap(kek, plaintext, ctxA, RNG);
            assertThrows(Exception.class,
                    () -> AesGcmContextWrap.unwrap(kek, wrapped, ctxB, 32, arena));
        }
    }

    @Test
    void crossPurpose_dekVsRekeySentinel_fails() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext dekCtx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final EncryptionContext sentCtx = EncryptionContext.forRekeySentinel(T_A, D_X);
            final byte[] wrapped = AesGcmContextWrap.wrap(kek, plaintext, dekCtx, RNG);
            assertThrows(Exception.class,
                    () -> AesGcmContextWrap.unwrap(kek, wrapped, sentCtx, 32, arena));
        }
    }

    @Test
    void crossPurpose_domainKekVsHealthCheck_fails() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext dkCtx = EncryptionContext.forDomainKek(T_A, D_X);
            final EncryptionContext hcCtx = EncryptionContext.forHealthCheck(T_A, D_X);
            final byte[] wrapped = AesGcmContextWrap.wrap(kek, plaintext, dkCtx, RNG);
            assertThrows(Exception.class,
                    () -> AesGcmContextWrap.unwrap(kek, wrapped, hcCtx, 32, arena));
        }
    }

    // ── tamper / cross-KEK ─────────────────────────────────────────────────

    @Test
    void tamperedCiphertext_fails() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final byte[] wrapped = AesGcmContextWrap.wrap(kek, plaintext, ctx, RNG);
            // Flip a byte in the ciphertext region (after IV, before tag).
            wrapped[20] ^= 0x01;
            assertThrows(Exception.class,
                    () -> AesGcmContextWrap.unwrap(kek, wrapped, ctx, 32, arena));
        }
    }

    @Test
    void tamperedTag_fails() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final byte[] wrapped = AesGcmContextWrap.wrap(kek, plaintext, ctx, RNG);
            wrapped[wrapped.length - 1] ^= 0x01;
            assertThrows(Exception.class,
                    () -> AesGcmContextWrap.unwrap(kek, wrapped, ctx, 32, arena));
        }
    }

    @Test
    void tamperedIv_fails() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final byte[] wrapped = AesGcmContextWrap.wrap(kek, plaintext, ctx, RNG);
            wrapped[0] ^= 0x01; // IV byte flip → GCM tag verification fails.
            assertThrows(Exception.class,
                    () -> AesGcmContextWrap.unwrap(kek, wrapped, ctx, 32, arena));
        }
    }

    @Test
    void crossKek_unwrapFails() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kekA = fill(arena, 32, (byte) 0xAA);
            final MemorySegment kekB = fill(arena, 32, (byte) 0xBB);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final byte[] wrapped = AesGcmContextWrap.wrap(kekA, plaintext, ctx, RNG);
            assertThrows(Exception.class,
                    () -> AesGcmContextWrap.unwrap(kekB, wrapped, ctx, 32, arena));
        }
    }

    // ── IV uniqueness ─────────────────────────────────────────────────────

    @Test
    void ivUniqueness_sameInputs_produceDifferentCiphertexts() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final byte[] a = AesGcmContextWrap.wrap(kek, plaintext, ctx, RNG);
            final byte[] b = AesGcmContextWrap.wrap(kek, plaintext, ctx, RNG);
            assertFalse(java.util.Arrays.equals(a, b),
                    "random IV must differ between independent wraps");
        }
    }

    @Test
    void unwrap_returnedSegmentLifetimeBoundToCallerArena() {
        final Arena callerArena = Arena.ofConfined();
        final MemorySegment recovered;
        try (Arena srcArena = Arena.ofConfined()) {
            final MemorySegment kek = kek(srcArena);
            final MemorySegment plaintext = fill(srcArena, 32, (byte) 0x77);
            final EncryptionContext ctx = EncryptionContext.forDek(T_A, D_X, TBL_1, V1);
            final byte[] wrapped = AesGcmContextWrap.wrap(kek, plaintext, ctx, RNG);
            recovered = AesGcmContextWrap.unwrap(kek, wrapped, ctx, 32, callerArena);
        }
        assertEquals(32, recovered.byteSize());
        assertTrue(recovered.scope().isAlive());
        callerArena.close();
        assertFalse(recovered.scope().isAlive());
    }

    @Test
    void roundTrip_allFourPurposes_succeed() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = kek(arena);
            final MemorySegment plaintext = fill(arena, 32, (byte) 0x55);
            final EncryptionContext[] ctxs = new EncryptionContext[]{
                    EncryptionContext.forDomainKek(T_A, D_X),
                    EncryptionContext.forDek(T_A, D_X, TBL_1, V1),
                    EncryptionContext.forRekeySentinel(T_A, D_X),
                    EncryptionContext.forHealthCheck(T_A, D_X), };
            for (EncryptionContext ctx : ctxs) {
                final byte[] wrapped = AesGcmContextWrap.wrap(kek, plaintext, ctx, RNG);
                final MemorySegment recovered = AesGcmContextWrap.unwrap(kek, wrapped, ctx, 32,
                        arena);
                assertArrayEquals(plaintext.toArray(ValueLayout.JAVA_BYTE),
                        recovered.toArray(ValueLayout.JAVA_BYTE),
                        "round-trip failed for purpose " + ctx.purpose());
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static MemorySegment fill(Arena arena, int size, byte value) {
        final MemorySegment seg = arena.allocate(size);
        for (long i = 0; i < size; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, value);
        }
        return seg;
    }

    private static MemorySegment kek(Arena arena) {
        return fill(arena, 32, (byte) 0x99);
    }

    private static MemorySegment dek(Arena arena) {
        return fill(arena, 32, (byte) 0x55);
    }
}
