package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.encryption.EncryptionSpec;

import org.junit.jupiter.api.Test;

/**
 * Tests for JlsmSchema.Builder encryption spec support.
 */
class JlsmSchemaEncryptionTest {

    // ── field() with encryption spec ─────────────────────────────────────

    // @spec schema.schema-construction.R14
    @Test
    void builder_fieldWithEncryption_preservesSpec() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("ssn", FieldType.Primitive.STRING, EncryptionSpec.deterministic()).build();
        assertEquals(1, schema.fields().size());
        final FieldDefinition fd = schema.fields().get(0);
        assertEquals("ssn", fd.name());
        assertInstanceOf(EncryptionSpec.Deterministic.class, fd.encryption());
    }

    // @spec schema.schema-construction.R14
    @Test
    void builder_fieldWithOpaque_preservesSpec() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("credit_card", FieldType.Primitive.STRING, EncryptionSpec.opaque()).build();
        assertInstanceOf(EncryptionSpec.Opaque.class, schema.fields().get(0).encryption());
    }

    // ── field() without encryption spec still works ──────────────────────

    // @spec schema.schema-construction.R15
    @Test
    void builder_fieldWithoutEncryption_defaultsToNone() {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.Primitive.STRING).build();
        assertSame(EncryptionSpec.NONE, schema.fields().get(0).encryption());
    }

    // ── Mixed fields ─────────────────────────────────────────────────────

    // @spec schema.schema-construction.R9,R12,R14,R15
    @Test
    void builder_mixedFields_preserveAllSpecs() {
        final JlsmSchema schema = JlsmSchema.builder("users", 1)
                .field("id", FieldType.Primitive.INT64).field("name", FieldType.Primitive.STRING)
                .field("ssn", FieldType.Primitive.STRING, EncryptionSpec.deterministic())
                .field("salary", FieldType.Primitive.FLOAT64, EncryptionSpec.orderPreserving())
                .field("notes", FieldType.Primitive.STRING, EncryptionSpec.opaque()).build();
        assertEquals(5, schema.fields().size());
        assertSame(EncryptionSpec.NONE, schema.fields().get(0).encryption());
        assertSame(EncryptionSpec.NONE, schema.fields().get(1).encryption());
        assertInstanceOf(EncryptionSpec.Deterministic.class, schema.fields().get(2).encryption());
        assertInstanceOf(EncryptionSpec.OrderPreserving.class, schema.fields().get(3).encryption());
        assertInstanceOf(EncryptionSpec.Opaque.class, schema.fields().get(4).encryption());
    }

    // ── null encryption in builder rejects ───────────────────────────────

    // @spec schema.schema-construction.R14
    @Test
    void builder_fieldWithNullEncryption_throws() {
        assertThrows(NullPointerException.class,
                () -> JlsmSchema.builder("test", 1).field("f", FieldType.Primitive.STRING, null));
    }
}
