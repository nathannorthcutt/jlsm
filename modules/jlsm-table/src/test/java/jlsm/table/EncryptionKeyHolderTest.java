package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.table.internal.EncryptionKeyHolder;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * Tests for {@link EncryptionKeyHolder}: off-heap key storage, zeroing, close semantics.
 */
class EncryptionKeyHolderTest {

    private static byte[] key256() {
        final byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    private static byte[] key512() {
        final byte[] key = new byte[64];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    // ── Construction ─────────────────────────────────────────────────────

    @Test
    void of_accepts256BitKey() {
        final byte[] key = key256();
        try (final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key)) {
            assertEquals(32, holder.keyLength());
        }
    }

    @Test
    void of_accepts512BitKey() {
        final byte[] key = key512();
        try (final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key)) {
            assertEquals(64, holder.keyLength());
        }
    }

    @Test
    void of_zerosCallerArray() {
        final byte[] key = key256();
        try (final EncryptionKeyHolder ignored = EncryptionKeyHolder.of(key)) {
            final byte[] expected = new byte[32]; // all zeros
            assertArrayEquals(expected, key,
                    "Caller's key array must be zeroed after construction");
        }
    }

    @Test
    void of_rejectsNull() {
        assertThrows(NullPointerException.class, () -> EncryptionKeyHolder.of(null));
    }

    @Test
    void of_rejectsTooShortKey() {
        assertThrows(IllegalArgumentException.class, () -> EncryptionKeyHolder.of(new byte[16]));
    }

    @Test
    void of_rejectsTooLongKey() {
        assertThrows(IllegalArgumentException.class, () -> EncryptionKeyHolder.of(new byte[128]));
    }

    @Test
    void of_rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> EncryptionKeyHolder.of(new byte[0]));
    }

    @Test
    void of_rejects48ByteKey() {
        assertThrows(IllegalArgumentException.class, () -> EncryptionKeyHolder.of(new byte[48]));
    }

    // ── getKeyBytes round-trip ───────────────────────────────────────────

    @Test
    void getKeyBytes_returnsCorrectContent() {
        final byte[] original = key256();
        final byte[] copy = Arrays.copyOf(original, original.length);
        try (final EncryptionKeyHolder holder = EncryptionKeyHolder.of(original)) {
            final byte[] retrieved = holder.getKeyBytes();
            assertArrayEquals(copy, retrieved, "Retrieved key must match the original");
        }
    }

    @Test
    void getKeyBytes_returnsFreshCopy() {
        final byte[] key = key256();
        try (final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key)) {
            final byte[] a = holder.getKeyBytes();
            final byte[] b = holder.getKeyBytes();
            assertNotSame(a, b, "Each call must return a fresh copy");
            assertArrayEquals(a, b);
        }
    }

    // ── keySegment ───────────────────────────────────────────────────────

    @Test
    void keySegment_returnsNonNullSegment() {
        final byte[] key = key256();
        try (final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key)) {
            assertNotNull(holder.keySegment());
            assertEquals(32, holder.keySegment().byteSize());
        }
    }

    // ── Close semantics ──────────────────────────────────────────────────

    @Test
    void close_isIdempotent() {
        final byte[] key = key256();
        final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key);
        holder.close();
        assertDoesNotThrow(holder::close, "Double close must not throw");
    }

    @Test
    void getKeyBytes_afterClose_throws() {
        final byte[] key = key256();
        final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key);
        holder.close();
        assertThrows(IllegalStateException.class, holder::getKeyBytes);
    }

    @Test
    void keySegment_afterClose_throws() {
        final byte[] key = key256();
        final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key);
        holder.close();
        assertThrows(IllegalStateException.class, holder::keySegment);
    }

    @Test
    void keyLength_afterClose_throws() {
        final byte[] key = key256();
        final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key);
        holder.close();
        assertThrows(IllegalStateException.class, holder::keyLength);
    }
}
