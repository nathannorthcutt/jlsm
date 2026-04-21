package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import jlsm.core.io.MemorySerializer;
import jlsm.encryption.EncryptionKeyHolder;
import jlsm.encryption.EncryptionSpec;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for F03.R53 (v2, 2026-04-17): the serializer constructed without a key holder
 * must refuse to silently store plaintext for fields declared to require encryption.
 *
 * <p>
 * Two allowed paths with a null key holder:
 * <ol>
 * <li>Schema contains no encrypted fields — serializer behaves identically to a keyed serializer.
 * <li>Document is pre-encrypted (JlsmDocument.preEncrypted) — caller supplies ciphertext.
 * </ol>
 *
 * <p>
 * Rejected path: non-pre-encrypted document with a non-null value in an encrypted field. The
 * serializer must throw IllegalStateException naming the field.
 */
class DocumentSerializerNullKeyHolderTest {

    // ── Allowed: unencrypted schema → byte-identical to plain serializer ──────

    // @spec serialization.encrypted-field-serialization.R6 — null keyHolder + unencrypted schema
    // behaves as plain serializer
    @Test
    void unencryptedSchema_nullKeyHolder_serializesNormally() {
        JlsmSchema schema = JlsmSchema.builder("plain", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();

        MemorySerializer<JlsmDocument> withoutKey = DocumentSerializer.forSchema(schema);
        JlsmDocument doc = JlsmDocument.of(schema, "name", "alice", "age", 30);

        MemorySegment bytes = withoutKey.serialize(doc);
        JlsmDocument recovered = withoutKey.deserialize(bytes);

        assertEquals("alice", recovered.getString("name"));
        assertEquals(30, recovered.getInt("age"));
    }

    // ── Rejected: deterministic field, no keyHolder, not pre-encrypted ────────

    // @spec serialization.encrypted-field-serialization.R6 — library-encrypted Deterministic field
    // requires a key holder
    @Test
    void deterministicField_nullKeyHolder_serializeRejects() {
        JlsmSchema schema = JlsmSchema.builder("s", 1)
                .field("email", FieldType.string(), EncryptionSpec.deterministic()).build();

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument doc = JlsmDocument.of(schema, "email", "alice@example.com");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ser.serialize(doc),
                "Serializer without key holder must reject Deterministic non-pre-encrypted doc");
        assertTrue(ex.getMessage().contains("email"), "error must name the field");
    }

    // ── Rejected: opaque field, no keyHolder, not pre-encrypted ───────────────

    // @spec serialization.encrypted-field-serialization.R6 — library-encrypted Opaque field
    // requires a key holder
    @Test
    void opaqueField_nullKeyHolder_serializeRejects() {
        JlsmSchema schema = JlsmSchema.builder("s", 1)
                .field("secret", FieldType.string(), EncryptionSpec.opaque()).build();

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument doc = JlsmDocument.of(schema, "secret", "password123");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ser.serialize(doc),
                "Serializer without key holder must reject Opaque non-pre-encrypted doc");
        assertTrue(ex.getMessage().contains("secret"), "error must name the field");
    }

    // ── Rejected: OPE field, no keyHolder, not pre-encrypted ──────────────────

    // @spec serialization.encrypted-field-serialization.R6 — library-encrypted OrderPreserving
    // field requires a key holder
    @Test
    void orderPreservingField_nullKeyHolder_serializeRejects() {
        JlsmSchema schema = JlsmSchema.builder("s", 1)
                .field("rank", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving()).build();

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument doc = JlsmDocument.of(schema, "rank", (byte) 42);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ser.serialize(doc),
                "Serializer without key holder must reject OrderPreserving non-pre-encrypted doc");
        assertTrue(ex.getMessage().contains("rank"), "error must name the field");
    }

    // ── Rejected: DCPE field, no keyHolder, not pre-encrypted ─────────────────

    // @spec serialization.encrypted-field-serialization.R6 — library-encrypted DistancePreserving
    // field requires a key holder
    @Test
    void distancePreservingField_nullKeyHolder_serializeRejects() {
        JlsmSchema schema = JlsmSchema.builder("s", 1)
                .field("vec", FieldType.vector(FieldType.Primitive.FLOAT32, 4),
                        EncryptionSpec.distancePreserving())
                .build();

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument doc = JlsmDocument.of(schema, "vec", new float[]{ 1.0f, 2.0f, 3.0f, 4.0f });

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ser.serialize(doc),
                "Serializer without key holder must reject DistancePreserving non-pre-encrypted doc");
        assertTrue(ex.getMessage().contains("vec"), "error must name the field");
    }

    // ── Allowed: null value in encrypted field ────────────────────────────────

    // @spec serialization.encrypted-field-serialization.R6 — null values for encrypted fields
    // bypass encryption entirely (bitmask path)
    @Test
    void encryptedField_nullValue_nullKeyHolder_serializesOk() {
        JlsmSchema schema = JlsmSchema.builder("s", 1)
                .field("email", FieldType.string(), EncryptionSpec.deterministic()).build();

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        // email is null — no encryption needed, no plaintext leaked
        JlsmDocument doc = JlsmDocument.of(schema, "email", null);

        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument recovered = ser.deserialize(bytes);
        assertTrue(recovered.isNull("email"));
    }

    // ── Allowed: library encryption with key holder ───────────────────────────

    // @spec serialization.encrypted-field-serialization.R6 — library-encrypted path works normally
    // when key holder is supplied
    @Test
    void encryptedField_withKeyHolder_serializesOk() {
        JlsmSchema schema = JlsmSchema.builder("s", 1)
                .field("email", FieldType.string(), EncryptionSpec.deterministic()).build();

        byte[] key = new byte[64];
        Arrays.fill(key, (byte) 0xAA);
        try (var keyHolder = EncryptionKeyHolder.of(key)) {
            MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder);
            JlsmDocument doc = JlsmDocument.of(schema, "email", "alice@example.com");
            MemorySegment bytes = ser.serialize(doc);
            JlsmDocument recovered = ser.deserialize(bytes);
            assertEquals("alice@example.com", recovered.getString("email"));
        }
    }

    // ── Mixed schema: plain fields serialize even if encrypted fields are null ──

    // @spec serialization.encrypted-field-serialization.R6 — null-keyHolder can serialize
    // unencrypted fields alongside null encrypted
    @Test
    void mixedSchema_nullKeyHolder_nullEncryptedFields_serializesOk() {
        JlsmSchema schema = JlsmSchema.builder("s", 1).field("id", FieldType.int64())
                .field("email", FieldType.string(), EncryptionSpec.deterministic())
                .field("name", FieldType.string()).build();

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        // email is null (encrypted field absent) — plain fields should still work
        JlsmDocument doc = JlsmDocument.of(schema, "id", 42L, "name", "bob");

        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument recovered = ser.deserialize(bytes);
        assertEquals(42L, recovered.getLong("id"));
        assertEquals("bob", recovered.getString("name"));
        assertTrue(recovered.isNull("email"));
    }
}
