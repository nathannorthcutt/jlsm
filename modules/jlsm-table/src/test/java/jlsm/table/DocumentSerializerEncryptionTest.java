package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

import jlsm.core.io.MemorySerializer;
import jlsm.encryption.EncryptionKeyHolder;
import jlsm.encryption.EncryptionSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentSerializerEncryptionTest {

    private EncryptionKeyHolder keyHolder64;
    private EncryptionKeyHolder keyHolder32;

    @BeforeEach
    void setUp() {
        byte[] key64 = new byte[64];
        Arrays.fill(key64, (byte) 0xAB);
        keyHolder64 = EncryptionKeyHolder.of(key64);

        byte[] key32 = new byte[32];
        Arrays.fill(key32, (byte) 0xCD);
        keyHolder32 = EncryptionKeyHolder.of(key32);
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

    // ── Round-trip with deterministic encrypted fields ───────────────────

    @Test
    void roundTrip_deterministicEncryptedString() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.deterministic())
                .field("age", FieldType.int32()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        assertEquals("Alice", out.getString("name"));
        assertEquals(30, out.getInt("age"));
    }

    // ── Round-trip with opaque encrypted fields ─────────────────────────

    @Test
    void roundTrip_opaqueEncryptedString() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("secret", FieldType.string(), EncryptionSpec.opaque())
                .field("public_field", FieldType.string()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "secret", "top-secret", "public_field",
                "visible");

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder32);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        assertEquals("top-secret", out.getString("secret"));
        assertEquals("visible", out.getString("public_field"));
    }

    // ── Mixed schema: some encrypted, some not ──────────────────────────

    @Test
    void roundTrip_mixedEncryptedAndPlainFields() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.deterministic())
                .field("age", FieldType.int32()).field("email", FieldType.string())
                .field("score", FieldType.float64()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "name", "Bob", "age", 25, "email",
                "bob@example.com", "score", 98.5);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        assertEquals("Bob", out.getString("name"));
        assertEquals(25, out.getInt("age"));
        assertEquals("bob@example.com", out.getString("email"));
        assertEquals(98.5, out.getDouble("score"), 0.001);
    }

    // ── Deterministic encrypted field produces same ciphertext ──────────

    @Test
    void deterministicEncryption_sameCiphertextForSameValue() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.deterministic()).build();

        JlsmDocument doc1 = JlsmDocument.of(schema, "name", "Alice");
        JlsmDocument doc2 = JlsmDocument.of(schema, "name", "Alice");

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment bytes1 = ser.serialize(doc1);
        MemorySegment bytes2 = ser.serialize(doc2);

        byte[] b1 = bytes1.toArray(ValueLayout.JAVA_BYTE);
        byte[] b2 = bytes2.toArray(ValueLayout.JAVA_BYTE);
        assertArrayEquals(b1, b2,
                "Deterministic encryption should produce identical serialized bytes for same value");
    }

    // ── Unencrypted serialization unchanged (backward compat) ───────────

    @Test
    void noEncryption_backwardCompatible() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "name", "Charlie", "age", 40);

        // Serializer without encryption
        MemorySerializer<JlsmDocument> serPlain = DocumentSerializer.forSchema(schema);
        // Serializer with null key (no encryption)
        MemorySerializer<JlsmDocument> serNull = DocumentSerializer.forSchema(schema, null);

        MemorySegment bytesPlain = serPlain.serialize(doc);
        MemorySegment bytesNull = serNull.serialize(doc);

        byte[] b1 = bytesPlain.toArray(ValueLayout.JAVA_BYTE);
        byte[] b2 = bytesNull.toArray(ValueLayout.JAVA_BYTE);
        assertArrayEquals(b1, b2,
                "Serialization with null keyHolder should match unencrypted serialization");

        // Both should deserialize correctly
        JlsmDocument out1 = serPlain.deserialize(bytesPlain);
        JlsmDocument out2 = serNull.deserialize(bytesNull);
        assertEquals("Charlie", out1.getString("name"));
        assertEquals("Charlie", out2.getString("name"));
    }

    // ── Off-heap segment deserialization with encrypted fields ───────────

    @Test
    void offHeapSegment_roundTripWithEncryption() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.deterministic())
                .field("count", FieldType.int64()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "name", "Dana", "count", 12345L);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment heapBytes = ser.serialize(doc);

        // Copy to off-heap segment
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment offHeap = arena.allocate(heapBytes.byteSize());
            offHeap.copyFrom(heapBytes);

            JlsmDocument out = ser.deserialize(offHeap);
            assertEquals("Dana", out.getString("name"));
            assertEquals(12345L, out.getLong("count"));
        }
    }

    // ── Null fields with encryption ─────────────────────────────────────

    @Test
    void nullEncryptedField_roundTrip() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.deterministic())
                .field("age", FieldType.int32()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "name", null, "age", 30);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        assertTrue(out.isNull("name"));
        assertEquals(30, out.getInt("age"));
    }

    // ── Encrypted int fields round trip ─────────────────────────────────

    @Test
    void roundTrip_deterministicEncryptedInt32() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("value", FieldType.int32(), EncryptionSpec.deterministic()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "value", 42);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        assertEquals(42, out.getInt("value"));
    }

    // ── Encrypted long field ────────────────────────────────────────────

    @Test
    void roundTrip_deterministicEncryptedInt64() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("ts", FieldType.int64(), EncryptionSpec.deterministic()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "ts", 1700000000000L);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        assertEquals(1700000000000L, out.getLong("ts"));
    }

    // ── Boolean fields unaffected by encryption dispatch ────────────────

    @Test
    void booleanFields_unaffectedByEncryption() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.deterministic())
                .field("active", FieldType.boolean_()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "name", "Eve", "active", true);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        assertEquals("Eve", out.getString("name"));
        assertTrue(out.getBoolean("active"));
    }
}
