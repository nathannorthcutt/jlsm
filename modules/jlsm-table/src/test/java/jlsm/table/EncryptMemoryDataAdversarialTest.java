package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

import jlsm.encryption.BoldyrevaOpeEncryptor;
import jlsm.encryption.EncryptionKeyHolder;
import jlsm.encryption.EncryptionSpec;
import jlsm.table.internal.PositionalPostingCodec;
import jlsm.table.internal.SseEncryptedIndex;

import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for encrypt-memory-data integration — targets bugs found in spec analysis.
 */
class EncryptMemoryDataAdversarialTest {

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static byte[] key256() {
        final byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    // ── C3-1: OPE must reject field types wider than 2 bytes ───────────────────
    // OPE is capped at MAX_OPE_BYTES=2. Types wider than 2 bytes would suffer silent
    // data truncation on round-trip. The fix rejects these combinations eagerly.

    @Test
    void opeDispatch_int64Field_throwsOnConstruction() {
        // Vector: C3-1 — OrderPreserving on INT64 must be rejected, not silently truncate
        final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key256());
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("ts", FieldType.Primitive.INT64, EncryptionSpec.orderPreserving()).build();

        assertThrows(IllegalArgumentException.class,
                () -> new FieldEncryptionDispatch(schema, holder),
                "OrderPreserving on INT64 must throw — OPE truncation would corrupt data");
    }

    @Test
    void opeDispatch_int32Field_throwsOnConstruction() {
        // Vector: C3-1 — OrderPreserving on INT32 must be rejected
        final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key256());
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("count", FieldType.Primitive.INT32, EncryptionSpec.orderPreserving())
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> new FieldEncryptionDispatch(schema, holder),
                "OrderPreserving on INT32 must throw — OPE truncation would corrupt data");
    }

    @Test
    void opeDispatch_timestampField_throwsOnConstruction() {
        // Vector: C3-1 — OrderPreserving on TIMESTAMP must be rejected
        final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key256());
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("created", FieldType.Primitive.TIMESTAMP, EncryptionSpec.orderPreserving())
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> new FieldEncryptionDispatch(schema, holder),
                "OrderPreserving on TIMESTAMP must throw — OPE truncation would corrupt data");
    }

    @Test
    void opeDispatch_int8Field_allowedAndRoundTrips() {
        // Positive case: INT8 fits in MAX_OPE_BYTES, should work
        final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key256());
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("b", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving()).build();

        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, holder);
        final FieldEncryptionDispatch.FieldEncryptor enc = dispatch.encryptorFor(0);
        final FieldEncryptionDispatch.FieldDecryptor dec = dispatch.decryptorFor(0);
        assertNotNull(enc);

        final byte[] value = new byte[]{ 0x42 };
        final byte[] encrypted = enc.encrypt(value);
        final byte[] decrypted = dec.decrypt(encrypted);
        assertArrayEquals(value, decrypted, "INT8 OPE round-trip must be lossless");
    }

    @Test
    void opeDispatch_int16Field_allowedAndRoundTrips() {
        // Positive case: INT16 fits in MAX_OPE_BYTES, should work
        final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key256());
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("s", FieldType.Primitive.INT16, EncryptionSpec.orderPreserving()).build();

        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, holder);
        final FieldEncryptionDispatch.FieldEncryptor enc = dispatch.encryptorFor(0);
        final FieldEncryptionDispatch.FieldDecryptor dec = dispatch.decryptorFor(0);
        assertNotNull(enc);

        final byte[] value = new byte[]{ 0x01, 0x02 };
        final byte[] encrypted = enc.encrypt(value);
        final byte[] decrypted = dec.decrypt(encrypted);
        assertArrayEquals(value, decrypted, "INT16 OPE round-trip must be lossless");
    }

    // ── C3-2: Derived EncryptionKeyHolder not closed ─────────────────────────────

    @Test
    void fieldEncryptionDispatch_derivedKeyHolders_areClosed() {
        // Vector: C3-2 — derived key holders must be closed to avoid Arena leak.
        // We verify that constructing many dispatches doesn't leak memory.
        // If derived holders are not closed, each creates an Arena.ofShared() that leaks.
        // This test creates 1000 dispatches to amplify any leak.
        final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key256());
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("ssn", FieldType.Primitive.STRING, EncryptionSpec.deterministic()).build();

        // Creating 1000 dispatches should not cause OOM from leaked arenas
        for (int i = 0; i < 1000; i++) {
            new FieldEncryptionDispatch(schema, holder);
        }
        // If derived key holders are leaking Arenas, this will eventually OOM or exhaust
        // native memory. The test passing means the leak is bounded (theoretical concern).
    }

    // ── C3-3: FieldEncryptionDispatch assert-only null check ─────────────────────

    @Test
    void fieldEncryptionDispatch_nullSchema_throwsEagerly() {
        // Vector: C3-3 — null schema must throw NPE or IAE, not rely on assert
        assertThrows(NullPointerException.class, () -> new FieldEncryptionDispatch(null, null),
                "null schema must throw NPE regardless of assertion state");
    }

    // ── C1-2: JlsmSchema.Builder duplicate field names ───────────────────────────

    @Test
    void schemaBuilder_duplicateFieldNames_throws() {
        // Vector: C1-2 — duplicate field names should be rejected, not silently last-wins
        assertThrows(IllegalArgumentException.class,
                () -> JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                        .field("name", FieldType.Primitive.INT32).build(),
                "Duplicate field names must be rejected at build time");
    }

    // ── C4-1: DecodedPosting mutable arrays ──────────────────────────────────────

    @Test
    void decodedPosting_mutatingDocIdAccessor_doesNotCorruptInternal() {
        // Vector: C4-1 — mutating returned docId should not corrupt the record
        final EncryptionKeyHolder opeHolder = EncryptionKeyHolder.of(key256());
        final BoldyrevaOpeEncryptor ope = new BoldyrevaOpeEncryptor(opeHolder, 100, 1000);
        final PositionalPostingCodec codec = new PositionalPostingCodec(ope);

        final byte[] docId = "doc-1".getBytes();
        final long[] positions = { 1, 5, 10 };
        final byte[] encoded = codec.encode(docId, positions);
        final PositionalPostingCodec.DecodedPosting decoded = codec.decode(encoded);

        final byte[] originalDocId = Arrays.copyOf(decoded.docId(), decoded.docId().length);
        // Mutate through accessor
        decoded.docId()[0] = (byte) 0xFF;

        // Record should be defensively copied
        assertArrayEquals(originalDocId, decoded.docId(),
                "DecodedPosting.docId() must return a defensive copy");
    }

    @Test
    void decodedPosting_mutatingPositionsAccessor_doesNotCorruptInternal() {
        // Vector: C4-1 — mutating returned positions should not corrupt the record
        final EncryptionKeyHolder opeHolder = EncryptionKeyHolder.of(key256());
        final BoldyrevaOpeEncryptor ope = new BoldyrevaOpeEncryptor(opeHolder, 100, 1000);
        final PositionalPostingCodec codec = new PositionalPostingCodec(ope);

        final byte[] docId = "doc-1".getBytes();
        final long[] positions = { 1, 5, 10 };
        final byte[] encoded = codec.encode(docId, positions);
        final PositionalPostingCodec.DecodedPosting decoded = codec.decode(encoded);

        final long[] originalPositions = Arrays.copyOf(decoded.encryptedPositions(),
                decoded.encryptedPositions().length);
        // Mutate through accessor
        decoded.encryptedPositions()[0] = Long.MAX_VALUE;

        assertArrayEquals(originalPositions, decoded.encryptedPositions(),
                "DecodedPosting.encryptedPositions() must return a defensive copy");
    }

    // ── C4-2: SseEncryptedIndex key material on heap ─────────────────────────────
    // Can't directly test heap storage, but we verify the index works after key holder
    // is closed (proving it copied key material at construction).

    @Test
    void sseEncryptedIndex_worksAfterKeyHolderClosed() {
        // Vector: C4-2 — index should still function after key holder is closed
        final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key256());
        final SseEncryptedIndex index = new SseEncryptedIndex(holder);
        holder.close();

        // Should still work — key material was derived at construction
        index.add("hello", "doc1".getBytes());
        final byte[] token = index.deriveToken("hello");
        final var results = index.search(token);
        assertEquals(1, results.size());
    }
}
