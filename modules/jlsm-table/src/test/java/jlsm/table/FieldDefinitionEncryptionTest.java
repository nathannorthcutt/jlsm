package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.encryption.EncryptionSpec;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FieldDefinition} encryption spec extension.
 */
class FieldDefinitionEncryptionTest {

    // ── 3-arg construction ───────────────────────────────────────────────

    // @spec F13.R39,R40
    @Test
    void threeArgConstructor_preservesEncryptionSpec() {
        final FieldDefinition fd = new FieldDefinition("secret", FieldType.Primitive.STRING,
                EncryptionSpec.deterministic());
        assertEquals("secret", fd.name());
        assertEquals(FieldType.Primitive.STRING, fd.type());
        assertInstanceOf(EncryptionSpec.Deterministic.class, fd.encryption());
    }

    @Test
    void threeArgConstructor_withNone() {
        final FieldDefinition fd = new FieldDefinition("public", FieldType.Primitive.INT64,
                EncryptionSpec.NONE);
        assertSame(EncryptionSpec.NONE, fd.encryption());
    }

    // @spec F13.R40
    @Test
    void threeArgConstructor_rejectsNullEncryption() {
        assertThrows(NullPointerException.class,
                () -> new FieldDefinition("f", FieldType.Primitive.STRING, null));
    }

    // ── Backward-compatible 2-arg construction ───────────────────────────

    // @spec F13.R41
    @Test
    void twoArgConstructor_defaultsToNone() {
        final FieldDefinition fd = new FieldDefinition("name", FieldType.Primitive.STRING);
        assertSame(EncryptionSpec.NONE, fd.encryption());
    }

    @Test
    void twoArgConstructor_preservesNameAndType() {
        final FieldDefinition fd = new FieldDefinition("age", FieldType.Primitive.INT32);
        assertEquals("age", fd.name());
        assertEquals(FieldType.Primitive.INT32, fd.type());
    }

    // ── Equality ─────────────────────────────────────────────────────────

    @Test
    void twoFieldDefinitions_sameEncryption_areEqual() {
        final FieldDefinition a = new FieldDefinition("x", FieldType.Primitive.STRING,
                EncryptionSpec.opaque());
        final FieldDefinition b = new FieldDefinition("x", FieldType.Primitive.STRING,
                EncryptionSpec.opaque());
        assertEquals(a, b);
    }

    @Test
    void twoFieldDefinitions_differentEncryption_areNotEqual() {
        final FieldDefinition a = new FieldDefinition("x", FieldType.Primitive.STRING,
                EncryptionSpec.NONE);
        final FieldDefinition b = new FieldDefinition("x", FieldType.Primitive.STRING,
                EncryptionSpec.opaque());
        assertNotEquals(a, b);
    }
}
