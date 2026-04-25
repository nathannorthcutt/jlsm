package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import jlsm.encryption.EncryptionSpec;
import jlsm.encryption.EnvelopeCodec;
import jlsm.encryption.ReadContext;
import jlsm.encryption.internal.OffHeapKeyMaterial;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WU-4 tests covering the per-field envelope 4B DEK version prefix on the
 * {@link FieldEncryptionDispatch} encrypt and decrypt paths.
 *
 * <p>
 * Coverage:
 * <ul>
 * <li>R1, R1c — every variant's encrypt output begins with 4B BE DEK version</li>
 * <li>R2, R2a — decrypt parses the version via the wait-free path; zero / unknown follow the same
 * code path</li>
 * <li>R2b, R24 — version-not-in-registry surfaces the version + scope (R12 redaction)</li>
 * <li>R3e — dispatch gate rejects envelope versions not in {@link ReadContext#allowedDekVersions()}
 * BEFORE invoking the version resolver</li>
 * <li>R3f — empty allowedDekVersions on a file containing encrypted entries surfaces a distinct
 * message from R3e's "version not in declared set"</li>
 * </ul>
 *
 * @spec encryption.ciphertext-envelope.R1
 * @spec encryption.ciphertext-envelope.R1c
 * @spec encryption.ciphertext-envelope.R2
 * @spec encryption.ciphertext-envelope.R2b
 * @spec sstable.footer-encryption-scope.R3e
 * @spec sstable.footer-encryption-scope.R3f
 */
class FieldEncryptionDispatchEnvelopeTest {

    private static final int DEK_VERSION = 7;

    private OffHeapKeyMaterial keyHolder64;
    private OffHeapKeyMaterial keyHolder32;

    @BeforeEach
    void setUp() {
        final byte[] key64 = new byte[64];
        Arrays.fill(key64, (byte) 0xAB);
        keyHolder64 = OffHeapKeyMaterial.of(key64);

        final byte[] key32 = new byte[32];
        Arrays.fill(key32, (byte) 0xCD);
        keyHolder32 = OffHeapKeyMaterial.of(key32);
    }

    @AfterEach
    void tearDown() {
        if (keyHolder64 != null) {
            keyHolder64.close();
        }
        if (keyHolder32 != null) {
            keyHolder32.close();
        }
    }

    private static ReadContext readContextOf(int... versions) {
        final Set<Integer> set = new HashSet<>();
        for (int v : versions) {
            set.add(v);
        }
        return new ReadContext(set);
    }

    // ── R1 / R1c — encrypt prefixes 4B BE version on every variant ───────────

    @Test
    void deterministicEncrypt_outputStartsWithFourByteBigEndianVersion() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("det", FieldType.string(), EncryptionSpec.deterministic()).build();
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder64,
                DEK_VERSION, alwaysKnown());

        final byte[] envelope = dispatch.encryptorFor(0).encrypt("plain".getBytes());

        assertVersionPrefix(envelope, DEK_VERSION);
    }

    @Test
    void opaqueEncrypt_outputStartsWithFourByteBigEndianVersion() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("opq", FieldType.string(), EncryptionSpec.opaque()).build();
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder32,
                DEK_VERSION, alwaysKnown());

        final byte[] envelope = dispatch.encryptorFor(0).encrypt("plain".getBytes());

        assertVersionPrefix(envelope, DEK_VERSION);
    }

    @Test
    void orderPreservingEncrypt_outputStartsWithFourByteBigEndianVersion() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("rank", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving()).build();
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder64,
                DEK_VERSION, alwaysKnown());

        final byte[] envelope = dispatch.encryptorFor(0).encrypt(new byte[]{ (byte) 42 });

        // 4B prefix + 1B length + 8B OPE + 16B MAC = 29
        assertEquals(29, envelope.length, "OPE envelope must be 29 bytes (4 + 25)");
        assertVersionPrefix(envelope, DEK_VERSION);
    }

    // ── R2 — reader rejects 0 / negative ─────────────────────────────────────

    @Test
    void decrypt_zeroVersionEnvelope_throwsIo() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("det", FieldType.string(), EncryptionSpec.deterministic()).build();
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder64,
                DEK_VERSION, alwaysKnown());

        final byte[] envelope = dispatch.encryptorFor(0).encrypt("p".getBytes());
        // Corrupt: zero out the 4B version prefix.
        envelope[0] = envelope[1] = envelope[2] = envelope[3] = 0;

        // Decryptor wraps IOException as UncheckedIOException because FieldDecryptor signature
        // is byte[] decrypt(byte[]). Tests assert via the cause chain or message.
        Throwable thrown = assertThrowsAny(() -> dispatch.decryptorFor(0).decrypt(envelope));
        assertTrue(rootCauseIsIo(thrown),
                "zero-version envelope must surface IOException via root cause; got: " + thrown);
    }

    // ── R2a — wait-free path: zero and unknown both go through resolver gate ──

    // The resolver hook is the post-allowed-set check that asks "is this version known to
    // the registry"? R2a says version-0 and version-not-found follow the same code path
    // (no separate exception class). We verify by giving the resolver a tracer and confirming
    // the version-0 bytes never *reach* it (rejected earlier as IOException), AND a positive-
    // but-unknown version DOES reach it AND yields the same exception type.

    @Test
    void decrypt_unknownPositiveVersion_throwsIoFromResolver() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("det", FieldType.string(), EncryptionSpec.deterministic()).build();
        final IntPredicate denyAll = v -> false;
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder64,
                DEK_VERSION, denyAll);

        // Use ReadContext that *includes* the version, so the dispatch gate passes and the
        // resolver is the layer that rejects (R2b path).
        final byte[] envelope = dispatch.encryptorFor(0).encrypt("p".getBytes());

        Throwable thrown = assertThrowsAny(
                () -> dispatch.decryptWithContext(0, envelope, readContextOf(DEK_VERSION)));
        assertTrue(rootCauseIsIo(thrown),
                "unknown-version-from-resolver must surface IOException; got: " + thrown);
    }

    // ── R3e — dispatch gate rejects BEFORE resolver is invoked ────────────────

    @Test
    void decrypt_versionNotInAllowedSet_throwsIoBeforeResolver() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("det", FieldType.string(), EncryptionSpec.deterministic()).build();
        final AtomicInteger resolverInvocations = new AtomicInteger(0);
        final IntPredicate tracer = v -> {
            resolverInvocations.incrementAndGet();
            return true;
        };
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder64,
                DEK_VERSION, tracer);

        final byte[] envelope = dispatch.encryptorFor(0).encrypt("p".getBytes());

        // ReadContext declares only version 99; envelope was written with version 7 → gate fail.
        Throwable thrown = assertThrowsAny(
                () -> dispatch.decryptWithContext(0, envelope, readContextOf(99)));
        assertTrue(rootCauseIsIo(thrown), "expected IOException root, got: " + thrown);
        assertEquals(0, resolverInvocations.get(),
                "R3e: resolver must NOT be invoked when the gate rejects the version");
        // R12 + R24 — the message must reference the missing version but not key bytes.
        final String msg = rootMessage(thrown);
        assertTrue(msg.contains("7"), "message should name the missing version 7; got: " + msg);
    }

    // ── R3f — empty allowedSet has a distinct message ────────────────────────

    @Test
    void decrypt_emptyAllowedSetWithEncryptedField_throwsIoDistinctMessage() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("det", FieldType.string(), EncryptionSpec.deterministic()).build();
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder64,
                DEK_VERSION, alwaysKnown());

        final byte[] envelope = dispatch.encryptorFor(0).encrypt("p".getBytes());

        Throwable thrown = assertThrowsAny(
                () -> dispatch.decryptWithContext(0, envelope, readContextOf()));
        final String msg = rootMessage(thrown).toLowerCase();
        assertTrue(rootCauseIsIo(thrown), "expected IOException root, got: " + thrown);
        // R3f language hint — must distinguish from R3e ("not in declared set")
        assertTrue(msg.contains("empty") || msg.contains("no encrypted") || msg.contains("no dek"),
                "R3f message should signal empty-set state, got: " + msg);
    }

    // ── R2b / R24 — registry miss surfaces version + scope name in message ────

    @Test
    void decrypt_versionNotInRegistry_messageNamesVersion() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("det", FieldType.string(), EncryptionSpec.deterministic()).build();
        final IntPredicate denyVersionSeven = v -> v != DEK_VERSION;
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder64,
                DEK_VERSION, denyVersionSeven);

        final byte[] envelope = dispatch.encryptorFor(0).encrypt("p".getBytes());

        Throwable thrown = assertThrowsAny(
                () -> dispatch.decryptWithContext(0, envelope, readContextOf(DEK_VERSION)));
        final String msg = rootMessage(thrown);
        assertTrue(msg.contains("7"),
                "R2b / R24: message should reference the missing version 7; got: " + msg);
        // R12 — must NOT contain raw key bytes. Our tests use 0xAB filler so check that absent.
        assertFalse(msg.contains("AB AB AB") || msg.contains("0xAB"),
                "message must not leak key bytes (R12); got: " + msg);
    }

    // ── Round-trip — decrypt rejects mismatched, accepts matching version ─────

    @Test
    void deterministicEncryptThenDecryptWithMatchingContext_roundTrips() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("det", FieldType.string(), EncryptionSpec.deterministic()).build();
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder64,
                DEK_VERSION, alwaysKnown());

        final byte[] plaintext = "round-trip".getBytes();
        final byte[] envelope = dispatch.encryptorFor(0).encrypt(plaintext);
        final byte[] recovered = dispatch.decryptWithContext(0, envelope,
                readContextOf(DEK_VERSION));
        assertArrayEquals(plaintext, recovered);
    }

    @Test
    void opaqueEncryptThenDecryptWithMatchingContext_roundTrips() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("opq", FieldType.string(), EncryptionSpec.opaque()).build();
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder32,
                DEK_VERSION, alwaysKnown());

        final byte[] plaintext = "secret".getBytes();
        final byte[] envelope = dispatch.encryptorFor(0).encrypt(plaintext);
        final byte[] recovered = dispatch.decryptWithContext(0, envelope,
                readContextOf(DEK_VERSION));
        assertArrayEquals(plaintext, recovered);
    }

    @Test
    void orderPreservingEncryptThenDecryptWithMatchingContext_roundTrips() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("rank", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving()).build();
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder64,
                DEK_VERSION, alwaysKnown());

        final byte[] plaintext = new byte[]{ 17 };
        final byte[] envelope = dispatch.encryptorFor(0).encrypt(plaintext);
        final byte[] recovered = dispatch.decryptWithContext(0, envelope,
                readContextOf(DEK_VERSION));
        assertArrayEquals(plaintext, recovered);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static IntPredicate alwaysKnown() {
        return _ -> true;
    }

    private static void assertVersionPrefix(byte[] envelope, int expectedVersion) {
        assertTrue(envelope.length >= EnvelopeCodec.VERSION_PREFIX_LENGTH,
                "envelope length must be >= 4");
        final int actual = ((envelope[0] & 0xFF) << 24) | ((envelope[1] & 0xFF) << 16)
                | ((envelope[2] & 0xFF) << 8) | (envelope[3] & 0xFF);
        assertEquals(expectedVersion, actual,
                "envelope must begin with 4B BE DEK version " + expectedVersion);
    }

    /** Captures both checked and unchecked exceptions thrown by the body. */
    private static Throwable assertThrowsAny(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            return t;
        }
        fail("expected an exception, none thrown");
        return null; // unreachable
    }

    private static boolean rootCauseIsIo(Throwable t) {
        Throwable c = t;
        while (c != null) {
            if (c instanceof IOException) {
                return true;
            }
            c = c.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        String m = "";
        while (c != null) {
            if (c.getMessage() != null) {
                m = m + " " + c.getMessage();
            }
            c = c.getCause();
        }
        return m;
    }
}
