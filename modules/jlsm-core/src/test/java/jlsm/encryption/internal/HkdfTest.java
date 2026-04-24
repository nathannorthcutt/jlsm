package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.TenantId;

/**
 * Tests for {@link Hkdf} — validates R9, R10, R10a, R11, R16, R16a, R16c, R59.
 *
 * <p>
 * RFC 5869 Test Case 1 vectors are used to verify extract+expand correctness against a known-answer
 * vector. The length-prefixed info encoding is tested for canonicalization-resistance (R11).
 */
class HkdfTest {

    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final DomainId DOMAIN = new DomainId("domain-x");
    private static final DekVersion V1 = new DekVersion(1);

    // ── buildFieldKeyInfo ─────────────────────────────────────────────────

    @Test
    void buildFieldKeyInfo_rejectsNullTenant() {
        assertThrows(NullPointerException.class,
                () -> Hkdf.buildFieldKeyInfo(null, DOMAIN, "tbl", "f", V1));
    }

    @Test
    void buildFieldKeyInfo_rejectsNullDomain() {
        assertThrows(NullPointerException.class,
                () -> Hkdf.buildFieldKeyInfo(TENANT, null, "tbl", "f", V1));
    }

    @Test
    void buildFieldKeyInfo_rejectsNullTableName() {
        assertThrows(NullPointerException.class,
                () -> Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, null, "f", V1));
    }

    @Test
    void buildFieldKeyInfo_rejectsNullFieldName() {
        assertThrows(NullPointerException.class,
                () -> Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tbl", null, V1));
    }

    @Test
    void buildFieldKeyInfo_rejectsNullDekVersion() {
        assertThrows(NullPointerException.class,
                () -> Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tbl", "f", null));
    }

    @Test
    void buildFieldKeyInfo_rejectsEmptyTableName() {
        assertThrows(IllegalArgumentException.class,
                () -> Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "", "f", V1));
    }

    @Test
    void buildFieldKeyInfo_rejectsEmptyFieldName() {
        assertThrows(IllegalArgumentException.class,
                () -> Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tbl", "", V1));
    }

    @Test
    void buildFieldKeyInfo_deterministicForSameInputs() {
        final byte[] a = Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tbl", "f", V1);
        final byte[] b = Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tbl", "f", V1);
        assertArrayEquals(a, b);
    }

    @Test
    void buildFieldKeyInfo_startsWithLiteralPrefix() {
        final byte[] info = Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tbl", "f", V1);
        final byte[] expectedPrefix = "jlsm-field-key:"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(info.length > expectedPrefix.length);
        for (int i = 0; i < expectedPrefix.length; i++) {
            assertEquals(expectedPrefix[i], info[i], "prefix mismatch at byte " + i);
        }
    }

    @Test
    void buildFieldKeyInfo_lengthPrefixedComponents_blockCanonicalization() {
        // (tbl="a", fld="bc") vs (tbl="ab", fld="c"): same concatenated string "abc" but
        // length-prefixed encoding must differ. This is R11's canonicalization resistance.
        final byte[] a = Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "a", "bc", V1);
        final byte[] b = Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "ab", "c", V1);
        assertFalse(java.util.Arrays.equals(a, b),
                "length-prefixed info must distinguish (a,bc) from (ab,c)");
    }

    @Test
    void buildFieldKeyInfo_tenantChange_producesDifferentInfo() {
        final byte[] a = Hkdf.buildFieldKeyInfo(new TenantId("t1"), DOMAIN, "tbl", "f", V1);
        final byte[] b = Hkdf.buildFieldKeyInfo(new TenantId("t2"), DOMAIN, "tbl", "f", V1);
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void buildFieldKeyInfo_domainChange_producesDifferentInfo() {
        final byte[] a = Hkdf.buildFieldKeyInfo(TENANT, new DomainId("d1"), "tbl", "f", V1);
        final byte[] b = Hkdf.buildFieldKeyInfo(TENANT, new DomainId("d2"), "tbl", "f", V1);
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void buildFieldKeyInfo_dekVersionChange_producesDifferentInfo() {
        final byte[] a = Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tbl", "f", new DekVersion(1));
        final byte[] b = Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tbl", "f", new DekVersion(2));
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void buildFieldKeyInfo_tableNameChange_producesDifferentInfo() {
        final byte[] a = Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tblA", "f", V1);
        final byte[] b = Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tblB", "f", V1);
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void buildFieldKeyInfo_fieldNameChange_producesDifferentInfo() {
        final byte[] a = Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tbl", "fA", V1);
        final byte[] b = Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tbl", "fB", V1);
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void buildFieldKeyInfo_trailingDekVersion_isFourBytesBigEndian() {
        final byte[] info = Hkdf.buildFieldKeyInfo(TENANT, DOMAIN, "tbl", "f", new DekVersion(258));
        // Last 4 bytes are the dek version BE: 258 = 0x00000102
        assertEquals((byte) 0x00, info[info.length - 4]);
        assertEquals((byte) 0x00, info[info.length - 3]);
        assertEquals((byte) 0x01, info[info.length - 2]);
        assertEquals((byte) 0x02, info[info.length - 1]);
    }

    // ── extract (RFC 5869 known answer) ──────────────────────────────────

    @Test
    void extract_rejectsNullIkm() {
        assertThrows(NullPointerException.class, () -> Hkdf.extract(new byte[32], null));
    }

    @Test
    void extract_rfc5869_testCase1_knownAnswer() {
        // RFC 5869 Appendix A.1 Test Case 1:
        // IKM = 0x0b*22, salt = 0x00..0x0c, info = 0xf0..0xf9, L = 42
        // PRK = 0x077709362c2e32df0ddc3f0dc47bba63
        // 90b6c73bb50f9c3122ec844ad7c2b3e5
        final byte[] ikm = HexFormat.of().parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        final byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c");
        final byte[] expectedPrk = HexFormat.of()
                .parseHex("077709362c2e32df0ddc3f0dc47bba63" + "90b6c73bb50f9c3122ec844ad7c2b3e5");
        final byte[] prk = Hkdf.extract(salt, ikm);
        assertArrayEquals(expectedPrk, prk);
    }

    @Test
    void extract_nullSalt_usesZeroSalt() {
        // R10: salt may be null/empty → default 32 zero bytes (HKDF spec behavior).
        final byte[] ikm = new byte[32];
        final byte[] prkWithNullSalt = Hkdf.extract(null, ikm);
        final byte[] prkWithZeroSalt = Hkdf.extract(new byte[32], ikm);
        assertArrayEquals(prkWithZeroSalt, prkWithNullSalt);
        assertEquals(32, prkWithNullSalt.length);
    }

    @Test
    void extract_emptySalt_usesZeroSalt() {
        final byte[] ikm = new byte[32];
        final byte[] prkWithEmpty = Hkdf.extract(new byte[0], ikm);
        final byte[] prkWithZero = Hkdf.extract(new byte[32], ikm);
        assertArrayEquals(prkWithZero, prkWithEmpty);
    }

    // ── expand ────────────────────────────────────────────────────────────

    @Test
    void expand_rejectsNullPrk() {
        assertThrows(NullPointerException.class, () -> Hkdf.expand(null, new byte[0], 16));
    }

    @Test
    void expand_rejectsNullInfo() {
        assertThrows(NullPointerException.class, () -> Hkdf.expand(new byte[32], null, 16));
    }

    @Test
    void expand_rejectsZeroLength() {
        assertThrows(IllegalArgumentException.class,
                () -> Hkdf.expand(new byte[32], new byte[0], 0));
    }

    @Test
    void expand_rejectsNegativeLength() {
        assertThrows(IllegalArgumentException.class,
                () -> Hkdf.expand(new byte[32], new byte[0], -1));
    }

    @Test
    void expand_rejectsTooLargeLength() {
        // HKDF ceiling: 255 * HashLen = 255 * 32 = 8160 bytes for SHA-256.
        assertThrows(IllegalArgumentException.class,
                () -> Hkdf.expand(new byte[32], new byte[0], 255 * 32 + 1));
    }

    @Test
    void expand_maxAllowedLength_succeeds() {
        final byte[] okm = Hkdf.expand(new byte[32], new byte[0], 255 * 32);
        assertEquals(255 * 32, okm.length);
    }

    @Test
    void expand_rfc5869_testCase1_knownAnswer() {
        // RFC 5869 Appendix A.1 Test Case 1 OKM (42 bytes).
        final byte[] prk = HexFormat.of()
                .parseHex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
        final byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");
        final byte[] expectedOkm = HexFormat.of().parseHex("3cb25f25faacd57a90434f64d0362f2a"
                + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" + "34007208d5b887185865");
        final byte[] okm = Hkdf.expand(prk, info, 42);
        assertArrayEquals(expectedOkm, okm);
    }

    @Test
    void expand_multiCounter_producesCorrectLength() {
        // Ensure multi-block expansion works correctly.
        final byte[] okm = Hkdf.expand(new byte[32], "context".getBytes(), 100);
        assertEquals(100, okm.length);
    }

    @Test
    void expand_deterministic() {
        final byte[] prk = new byte[32];
        java.util.Arrays.fill(prk, (byte) 0x42);
        final byte[] a = Hkdf.expand(prk, "info".getBytes(), 32);
        final byte[] b = Hkdf.expand(prk, "info".getBytes(), 32);
        assertArrayEquals(a, b);
    }

    // ── deriveKey ─────────────────────────────────────────────────────────

    @Test
    void deriveKey_rejectsNullDek() {
        try (Arena arena = Arena.ofConfined()) {
            assertThrows(NullPointerException.class,
                    () -> Hkdf.deriveKey(null, new byte[32], new byte[0], 32, arena));
        }
    }

    @Test
    void deriveKey_rejectsNullInfo() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment dek = arena.allocate(32);
            assertThrows(NullPointerException.class,
                    () -> Hkdf.deriveKey(dek, new byte[32], null, 32, arena));
        }
    }

    @Test
    void deriveKey_rejectsNullArena() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment dek = arena.allocate(32);
            assertThrows(NullPointerException.class,
                    () -> Hkdf.deriveKey(dek, new byte[32], new byte[0], 32, null));
        }
    }

    @Test
    void deriveKey_rejectsDekShorterThan16Bytes() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment dek = arena.allocate(15);
            assertThrows(IllegalArgumentException.class,
                    () -> Hkdf.deriveKey(dek, new byte[32], new byte[0], 32, arena));
        }
    }

    @Test
    void deriveKey_rejectsNonPositiveOutLen() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment dek = arena.allocate(32);
            assertThrows(IllegalArgumentException.class,
                    () -> Hkdf.deriveKey(dek, new byte[32], new byte[0], 0, arena));
            assertThrows(IllegalArgumentException.class,
                    () -> Hkdf.deriveKey(dek, new byte[32], new byte[0], -1, arena));
        }
    }

    @Test
    void deriveKey_acceptsMinimumDekLength16() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment dek = arena.allocate(16);
            // Fill with non-zero bytes so derivation is well-defined.
            for (long i = 0; i < 16; i++) {
                dek.set(ValueLayout.JAVA_BYTE, i, (byte) 0x42);
            }
            final MemorySegment key = Hkdf.deriveKey(dek, new byte[32], new byte[]{ 1 }, 32, arena);
            assertEquals(32, key.byteSize());
        }
    }

    @Test
    void deriveKey_deterministic() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment dek = fillSegment(arena, 32, (byte) 0x33);
            final byte[] salt = new byte[]{ 1, 2, 3 };
            final byte[] info = new byte[]{ 9, 9, 9 };
            final MemorySegment key1 = Hkdf.deriveKey(dek, salt, info, 32, arena);
            final MemorySegment key2 = Hkdf.deriveKey(dek, salt, info, 32, arena);
            assertArrayEquals(key1.toArray(ValueLayout.JAVA_BYTE),
                    key2.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void deriveKey_differentInfo_producesDifferentKey() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment dek = fillSegment(arena, 32, (byte) 0x33);
            final byte[] salt = new byte[]{ 1, 2, 3 };
            final MemorySegment k1 = Hkdf.deriveKey(dek, salt, new byte[]{ 1 }, 32, arena);
            final MemorySegment k2 = Hkdf.deriveKey(dek, salt, new byte[]{ 2 }, 32, arena);
            assertFalse(java.util.Arrays.equals(k1.toArray(ValueLayout.JAVA_BYTE),
                    k2.toArray(ValueLayout.JAVA_BYTE)));
        }
    }

    @Test
    void deriveKey_differentDek_producesDifferentKey() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment dek1 = fillSegment(arena, 32, (byte) 0x11);
            final MemorySegment dek2 = fillSegment(arena, 32, (byte) 0x22);
            final byte[] salt = new byte[]{ 1 };
            final byte[] info = new byte[]{ 2 };
            final MemorySegment k1 = Hkdf.deriveKey(dek1, salt, info, 32, arena);
            final MemorySegment k2 = Hkdf.deriveKey(dek2, salt, info, 32, arena);
            assertFalse(java.util.Arrays.equals(k1.toArray(ValueLayout.JAVA_BYTE),
                    k2.toArray(ValueLayout.JAVA_BYTE)));
        }
    }

    @Test
    void deriveKey_segmentLifetimeBoundToCallerArena() {
        // The returned segment is owned by the caller-provided arena. When that arena closes,
        // the segment must become inaccessible.
        final Arena callerArena = Arena.ofConfined();
        final MemorySegment key;
        try (Arena dekArena = Arena.ofConfined()) {
            final MemorySegment dek = fillSegment(dekArena, 32, (byte) 0x55);
            key = Hkdf.deriveKey(dek, new byte[32], new byte[]{ 1 }, 32, callerArena);
        }
        // Should still be readable after dekArena is closed — key lives in callerArena.
        assertEquals(32, key.byteSize());
        assertTrue(key.scope().isAlive());
        callerArena.close();
        assertFalse(key.scope().isAlive());
    }

    @Test
    void deriveKey_matchesStandaloneExtractExpand() {
        // deriveKey must produce the same output as extract+expand composed manually.
        try (Arena arena = Arena.ofConfined()) {
            final byte[] dekBytes = new byte[32];
            for (int i = 0; i < 32; i++) {
                dekBytes[i] = (byte) (0x80 | i);
            }
            final MemorySegment dek = arena.allocate(32);
            MemorySegment.copy(MemorySegment.ofArray(dekBytes), 0, dek, 0, 32);

            final byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c");
            final byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");

            final byte[] prk = Hkdf.extract(salt, dekBytes);
            final byte[] expectedOkm = Hkdf.expand(prk, info, 42);

            final MemorySegment derived = Hkdf.deriveKey(dek, salt, info, 42, arena);
            assertArrayEquals(expectedOkm, derived.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    // ── R16c: intermediate-buffer zeroization (behavioral smoke test) ─────

    @Test
    void deriveKey_repeatedInvocationsDoNotCorruptEachOther() {
        // Indirect observation of zeroing: if intermediate buffers were not cleaned properly
        // between invocations, repeated calls with alternating inputs might produce outputs
        // that depend on prior state. Interleave two distinct derivations and verify they
        // each match the single-call version.
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment dekA = fillSegment(arena, 32, (byte) 0xAA);
            final MemorySegment dekB = fillSegment(arena, 32, (byte) 0xBB);
            final MemorySegment refA = Hkdf.deriveKey(dekA, new byte[32], new byte[]{ 1 }, 32,
                    arena);
            final MemorySegment refB = Hkdf.deriveKey(dekB, new byte[32], new byte[]{ 1 }, 32,
                    arena);
            for (int i = 0; i < 5; i++) {
                final MemorySegment a = Hkdf.deriveKey(dekA, new byte[32], new byte[]{ 1 }, 32,
                        arena);
                final MemorySegment b = Hkdf.deriveKey(dekB, new byte[32], new byte[]{ 1 }, 32,
                        arena);
                assertArrayEquals(refA.toArray(ValueLayout.JAVA_BYTE),
                        a.toArray(ValueLayout.JAVA_BYTE));
                assertArrayEquals(refB.toArray(ValueLayout.JAVA_BYTE),
                        b.toArray(ValueLayout.JAVA_BYTE));
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static MemorySegment fillSegment(Arena arena, int size, byte value) {
        final MemorySegment seg = arena.allocate(size);
        for (long i = 0; i < size; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, value);
        }
        return seg;
    }
}
