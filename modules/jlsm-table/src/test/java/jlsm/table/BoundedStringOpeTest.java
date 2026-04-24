package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;

import jlsm.core.io.MemorySerializer;

import jlsm.encryption.internal.OffHeapKeyMaterial;
import jlsm.encryption.EncryptionSpec;
import jlsm.table.internal.IndexRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for BoundedString field type, OPE type-aware domain derivation, IndexRegistry OPE
 * validation, JlsmDocument BoundedString validation, and DocumentSerializer BoundedString
 * round-trip.
 */
class BoundedStringOpeTest {

    private OffHeapKeyMaterial keyHolder;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[64];
        Arrays.fill(key, (byte) 0xAB);
        keyHolder = OffHeapKeyMaterial.of(key);
    }

    @AfterEach
    void tearDown() {
        if (keyHolder != null) {
            keyHolder.close();
        }
    }

    // =========================================================================
    // BoundedString construction and factory
    // =========================================================================

    @Test
    void boundedString_positiveMaxLength_succeeds() {
        FieldType.BoundedString bs = new FieldType.BoundedString(10);
        assertEquals(10, bs.maxLength());
    }

    @Test
    void boundedString_maxLengthOne_succeeds() {
        FieldType.BoundedString bs = new FieldType.BoundedString(1);
        assertEquals(1, bs.maxLength());
    }

    @Test
    void boundedString_zeroMaxLength_throws() {
        assertThrows(IllegalArgumentException.class, () -> new FieldType.BoundedString(0));
    }

    @Test
    void boundedString_negativeMaxLength_throws() {
        assertThrows(IllegalArgumentException.class, () -> new FieldType.BoundedString(-1));
    }

    @Test
    void fieldTypeStringFactory_withMaxLength_returnsBoundedString() {
        FieldType ft = FieldType.string(64);
        assertInstanceOf(FieldType.BoundedString.class, ft);
        assertEquals(64, ((FieldType.BoundedString) ft).maxLength());
    }

    @Test
    void fieldTypeStringFactory_noArg_returnsPrimitiveString() {
        FieldType ft = FieldType.string();
        assertSame(FieldType.Primitive.STRING, ft);
    }

    @Test
    void boundedString_equality() {
        assertEquals(FieldType.string(10), FieldType.string(10));
        assertNotEquals(FieldType.string(10), FieldType.string(20));
        assertNotEquals(FieldType.string(10), FieldType.Primitive.STRING);
    }

    // =========================================================================
    // OPE domain derivation — FieldEncryptionDispatch
    // =========================================================================

    @Test
    void opeDispatch_int8Field_encryptDecryptRoundTrip() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("val", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        var encryptor = dispatch.encryptorFor(0);
        var decryptor = dispatch.decryptorFor(0);
        assertNotNull(encryptor, "INT8 OPE encryptor should exist");
        assertNotNull(decryptor, "INT8 OPE decryptor should exist");

        // Encrypt a small value (single byte)
        byte[] plaintext = new byte[]{ 42 };
        byte[] ciphertext = encryptor.encrypt(plaintext);
        byte[] recovered = decryptor.decrypt(ciphertext);
        assertArrayEquals(plaintext, recovered, "INT8 OPE round-trip must recover original");
    }

    @Test
    void opeDispatch_int16Field_encryptDecryptRoundTrip() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("val", FieldType.Primitive.INT16, EncryptionSpec.orderPreserving()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        byte[] plaintext = new byte[]{ 0x01, 0x23 };
        byte[] ciphertext = dispatch.encryptorFor(0).encrypt(plaintext);
        byte[] recovered = dispatch.decryptorFor(0).decrypt(ciphertext);
        assertArrayEquals(plaintext, recovered, "INT16 OPE round-trip must recover original");
    }

    @Test
    void opeDispatch_int32Field_rejectedToPreventTruncation() {
        // INT32 with OPE would silently truncate to 2 bytes — must be rejected
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("val", FieldType.Primitive.INT32, EncryptionSpec.orderPreserving()).build();

        assertThrows(IllegalArgumentException.class,
                () -> new FieldEncryptionDispatch(schema, keyHolder),
                "INT32 OPE must be rejected — would cause data truncation");
    }

    @Test
    void opeDispatch_int16Field_completesQuickly() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("val", FieldType.Primitive.INT16, EncryptionSpec.orderPreserving()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        var encryptor = dispatch.encryptorFor(0);

        // With type-aware bounds (domain=65536 for 2 bytes),
        // OPE should complete in well under 1 second.
        long start = System.nanoTime();
        byte[] pt = new byte[]{ 0x00, 0x01 };
        encryptor.encrypt(pt);
        long elapsed = System.nanoTime() - start;

        assertTrue(elapsed < 5_000_000_000L, // 5 seconds max — generous timeout
                "INT16 OPE should complete in under 5s, took " + (elapsed / 1_000_000) + "ms");
    }

    @Test
    void opeDispatch_boundedString2_encryptDecryptRoundTrip() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("code", FieldType.string(2), EncryptionSpec.orderPreserving()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        byte[] plaintext = new byte[]{ 0x41, 0x42 }; // "AB"
        byte[] ciphertext = dispatch.encryptorFor(0).encrypt(plaintext);
        byte[] recovered = dispatch.decryptorFor(0).decrypt(ciphertext);
        assertArrayEquals(plaintext, recovered,
                "BoundedString(2) OPE round-trip must recover original");
    }

    @Test
    void opeDispatch_boundedString6_rejectedToPreventTruncation() {
        // BoundedString(6) exceeds MAX_OPE_BYTES=2 — must be rejected
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("code", FieldType.string(6), EncryptionSpec.orderPreserving()).build();

        assertThrows(IllegalArgumentException.class,
                () -> new FieldEncryptionDispatch(schema, keyHolder),
                "BoundedString(6) OPE must be rejected — maxLength exceeds OPE limit of 2");
    }

    @Test
    void opeDispatch_orderPreserved_int16() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("val", FieldType.Primitive.INT16, EncryptionSpec.orderPreserving()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        var encryptor = dispatch.encryptorFor(0);

        byte[] low = new byte[]{ 0x00, 0x01 };
        byte[] high = new byte[]{ 0x00, 0x10 };

        byte[] ctLow = encryptor.encrypt(low);
        byte[] ctHigh = encryptor.encrypt(high);

        // OPE preserves order — ciphertext of lower value should be <= ciphertext of higher value
        long ctLowLong = extractEncryptedLong(ctLow);
        long ctHighLong = extractEncryptedLong(ctHigh);
        assertTrue(Long.compareUnsigned(ctLowLong, ctHighLong) < 0,
                "OPE must preserve order: enc(1) < enc(16)");
    }

    // =========================================================================
    // IndexRegistry — OrderPreserving field type validation
    // =========================================================================

    @Test
    void rangeIndex_unboundedString_orderPreserving_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.orderPreserving()).build();

        var defs = List.of(new IndexDefinition("name", IndexType.RANGE));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> new IndexRegistry(schema, defs));
        assertTrue(
                ex.getMessage().contains("OrderPreserving") || ex.getMessage().contains("bounded"),
                "Error should mention OrderPreserving or bounded: " + ex.getMessage());
    }

    // Updated by audit F-R1.shared_state.8.3: BoundedString(6) with OPE was incorrectly
    // allowed — maxLength > 2 exceeds OPE limit and causes silent data truncation. Now
    // correctly rejected by IndexRegistry.validateOrderPreservingFieldType.
    @Test
    void rangeIndex_boundedString6_orderPreserving_rejected() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("code", FieldType.string(6), EncryptionSpec.orderPreserving()).build();

        var defs = List.of(new IndexDefinition("code", IndexType.RANGE));
        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs),
                "BoundedString(6) with OPE must be rejected — maxLength exceeds OPE limit of 2");
    }

    @Test
    void rangeIndex_boundedString2_orderPreserving_allowed() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("code", FieldType.string(2), EncryptionSpec.orderPreserving()).build();

        var defs = List.of(new IndexDefinition("code", IndexType.RANGE));
        var registry = new IndexRegistry(schema, defs);
        assertFalse(registry.isEmpty());
        registry.close();
    }

    @Test
    void rangeIndex_int32_orderPreserving_rejected() {
        // INT32 with OPE is now rejected to prevent data truncation
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("score", FieldType.int32(), EncryptionSpec.orderPreserving()).build();

        var defs = List.of(new IndexDefinition("score", IndexType.RANGE));
        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs));
    }

    @Test
    void rangeIndex_boolean_orderPreserving_throws() {
        // BOOLEAN + OrderPreserving should fail at existing RANGE/BOOLEAN check
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("flag", FieldType.boolean_(), EncryptionSpec.orderPreserving()).build();

        var defs = List.of(new IndexDefinition("flag", IndexType.RANGE));
        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs));
    }

    // =========================================================================
    // JlsmDocument — BoundedString field validation
    // =========================================================================

    @Test
    void document_boundedStringField_acceptsValidString() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("code", FieldType.string(10))
                .build();

        JlsmDocument doc = JlsmDocument.of(schema, "code", "hello");
        assertEquals("hello", doc.getString("code"));
    }

    @Test
    void document_boundedStringField_acceptsNullValue() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("code", FieldType.string(10))
                .build();

        JlsmDocument doc = JlsmDocument.of(schema, "code", null);
        assertTrue(doc.isNull("code"));
    }

    @Test
    void document_boundedStringField_rejectsExceedingLength() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("code", FieldType.string(3))
                .build();

        // "hello" is 5 bytes in UTF-8, exceeds maxLength 3
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.of(schema, "code", "hello"));
    }

    @Test
    void document_boundedStringField_acceptsExactLength() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("code", FieldType.string(5))
                .build();

        // "hello" is exactly 5 bytes in UTF-8
        JlsmDocument doc = JlsmDocument.of(schema, "code", "hello");
        assertEquals("hello", doc.getString("code"));
    }

    @Test
    void document_boundedStringField_rejectsNonString() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("code", FieldType.string(10))
                .build();

        assertThrows(IllegalArgumentException.class, () -> JlsmDocument.of(schema, "code", 42));
    }

    // =========================================================================
    // DocumentSerializer — BoundedString round-trip
    // =========================================================================

    @Test
    void serializer_boundedStringField_roundTrip() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("code", FieldType.string(10))
                .field("value", FieldType.int32()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "code", "ABC", "value", 42);

        MemorySerializer<JlsmDocument> serializer = DocumentSerializer.forSchema(schema);
        MemorySegment serialized = serializer.serialize(doc);

        JlsmDocument deserialized = serializer.deserialize(serialized);
        assertEquals("ABC", deserialized.getString("code"));
        assertEquals(42, deserialized.getInt("value"));
    }

    @Test
    void serializer_boundedStringField_nullValue_roundTrip() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("code", FieldType.string(10))
                .field("value", FieldType.int32()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "value", 99);

        MemorySerializer<JlsmDocument> serializer = DocumentSerializer.forSchema(schema);
        MemorySegment serialized = serializer.serialize(doc);

        JlsmDocument deserialized = serializer.deserialize(serialized);
        assertTrue(deserialized.isNull("code"));
        assertEquals(99, deserialized.getInt("value"));
    }

    // =========================================================================
    // JSON round-trip with BoundedString
    // =========================================================================

    @Test
    void json_boundedStringField_roundTrip() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("code", FieldType.string(10))
                .field("name", FieldType.string()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "code", "XY", "name", "hello");
        String json = doc.toJson();

        JlsmDocument parsed = JlsmDocument.fromJson(json, schema);
        assertEquals("XY", parsed.getString("code"));
        assertEquals("hello", parsed.getString("name"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Extracts the 8-byte encrypted long from OPE ciphertext, skipping the 1-byte length prefix at
     * position 0 and ignoring the trailing 16-byte HMAC-SHA256 tag (bytes 9..24). OPE ciphertext is
     * 25 bytes total per F03.R39 / F41.R22.
     */
    private static long extractEncryptedLong(byte[] ct) {
        assert ct.length == 25 : "OPE ciphertext must be 25 bytes (1B len + 8B OPE + 16B MAC)";
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long) (ct[1 + i] & 0xFF)) << (56 - i * 8);
        }
        return result;
    }
}
