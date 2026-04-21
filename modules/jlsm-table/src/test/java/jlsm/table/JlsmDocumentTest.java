package jlsm.table;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JlsmDocumentTest {

    private static JlsmSchema primitiveSchema() {
        return JlsmSchema.builder("test", 1).field("strField", FieldType.Primitive.STRING)
                .field("intField", FieldType.Primitive.INT32)
                .field("longField", FieldType.Primitive.INT64)
                .field("floatField", FieldType.Primitive.FLOAT32)
                .field("doubleField", FieldType.Primitive.FLOAT64)
                .field("boolField", FieldType.Primitive.BOOLEAN)
                .field("tsField", FieldType.Primitive.TIMESTAMP).build();
    }

    // @spec schema.document-construction.R6,R10,R21,R22 — of() primitive round-trip through typed getters
    // @spec schema.document-field-access.R5,R6 — of() primitive round-trip through typed getters
    @Test
    void of_allPrimitiveTypes_getRoundTrip() {
        JlsmSchema schema = primitiveSchema();
        JlsmDocument doc = JlsmDocument.of(schema, "strField", "hello", "intField", 42, "longField",
                100L, "floatField", 3.14f, "doubleField", 2.718281828, "boolField", true, "tsField",
                1700000000000L);
        assertEquals("hello", doc.getString("strField"));
        assertEquals(42, doc.getInt("intField"));
        assertEquals(100L, doc.getLong("longField"));
        assertEquals(3.14f, doc.getFloat("floatField"), 0.0001f);
        assertEquals(2.718281828, doc.getDouble("doubleField"), 0.0000001);
        assertTrue(doc.getBoolean("boolField"));
        assertEquals(1700000000000L, doc.getTimestamp("tsField"));
    }

    // @spec schema.document-construction.R22 — FLOAT16 accepts Short; getFloat16Bits returns raw short bits
    // @spec schema.document-field-access.R7 — FLOAT16 accepts Short; getFloat16Bits returns raw short bits
    @Test
    void of_float16_storesRawBits() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("halfFloat", FieldType.Primitive.FLOAT16).build();
        short bits = Float16.fromFloat(1.5f);
        JlsmDocument doc = JlsmDocument.of(schema, "halfFloat", bits);
        assertEquals(bits, doc.getFloat16Bits("halfFloat"));
    }

    // @spec schema.document-construction.R10,R11 — null values accepted, unassigned fields default to null, isNull
    // @spec schema.document-field-access.R11 — null values accepted, unassigned fields default to null, isNull
    // reports it
    @Test
    void of_nullField_isNull() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .build();
        JlsmDocument doc = JlsmDocument.of(schema, "name", null);
        assertTrue(doc.isNull("name"));
    }

    // @spec schema.document-construction.R24 — ArrayType accepts Object[] and getArray returns defensive clone
    // @spec schema.document-field-access.R8 — ArrayType accepts Object[] and getArray returns defensive clone
    @Test
    void of_arrayField_getsArray() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("tags", FieldType.arrayOf(FieldType.Primitive.STRING)).build();
        Object[] tags = { "a", "b", "c" };
        JlsmDocument doc = JlsmDocument.of(schema, "tags", tags);
        assertArrayEquals(tags, doc.getArray("tags"));
    }

    // @spec schema.document-construction.R28 — ObjectType accepts nested JlsmDocument; getObject returns reference
    // @spec schema.document-field-access.R9 — ObjectType accepts nested JlsmDocument; getObject returns reference
    // directly
    @Test
    void of_nestedObject_getsSubDocument() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .objectField("address", inner -> inner.field("city", FieldType.Primitive.STRING))
                .build();
        JlsmSchema addrSchema = ((FieldType.ObjectType) schema.fields().get(0).type())
                .toSchema("address", schema.version());
        JlsmDocument addr = JlsmDocument.of(addrSchema, "city", "NYC");
        JlsmDocument doc = JlsmDocument.of(schema, "address", addr);
        assertEquals("NYC", doc.getObject("address").getString("city"));
    }

    // @spec schema.document-construction.R10,R29 — type mismatch during of() surfaces as IAE
    // @spec schema.document-field-access.R4 — type mismatch during of() surfaces as IAE
    @Test
    void of_typeMismatch_throwsIllegalArgument() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("num", FieldType.Primitive.INT32)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.of(schema, "num", "not an int"));
    }

    // @spec schema.document-construction.R9 — unknown field name → IAE
    // @spec schema.document-field-access.R2 — unknown field name → IAE
    @Test
    void of_unknownField_throwsIllegalArgument() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("known", FieldType.Primitive.STRING)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.of(schema, "unknown", "value"));
    }

    // @spec schema.document-construction.R22 — TIMESTAMP accepts Long; getLong works for INT64/TIMESTAMP
    // @spec schema.document-field-access.R6 — TIMESTAMP accepts Long; getLong works for INT64/TIMESTAMP
    @Test
    void of_timestamp_getLong() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("created", FieldType.Primitive.TIMESTAMP).build();
        long ts = System.currentTimeMillis();
        JlsmDocument doc = JlsmDocument.of(schema, "created", ts);
        assertEquals(ts, doc.getTimestamp("created"));
    }

    // ── Structural / absent-behavior tests ─────────────────────────────────

    // @spec schema.document-construction.R1 — JlsmDocument is a public final class in jlsm.table
    @Test
    void structural_isPublicFinalClassInJlsmTablePackage() {
        int mods = JlsmDocument.class.getModifiers();
        assertTrue(Modifier.isPublic(mods), "JlsmDocument must be public");
        assertTrue(Modifier.isFinal(mods), "JlsmDocument must be final");
        assertEquals("jlsm.table", JlsmDocument.class.getPackage().getName());
    }

    // @spec schema.document-construction.R2 — all declared constructors are package-private (no public/protected/private)
    @Test
    void structural_allConstructorsArePackagePrivate() {
        Constructor<?>[] ctors = JlsmDocument.class.getDeclaredConstructors();
        assertTrue(ctors.length > 0, "JlsmDocument must declare at least one constructor");
        for (Constructor<?> c : ctors) {
            int mods = c.getModifiers();
            assertFalse(Modifier.isPublic(mods), "ctor must not be public: " + c);
            assertFalse(Modifier.isProtected(mods), "ctor must not be protected: " + c);
            assertFalse(Modifier.isPrivate(mods), "ctor must not be private: " + c);
        }
    }

    // (formerly @spec F14.R48,R49 — dropped during migration) — no toYaml/fromYaml methods (YAML support removed per F15.R1)
    @Test
    void structural_noYamlMethods() {
        for (Method m : JlsmDocument.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            assertFalse(name.contains("yaml"),
                    "JlsmDocument must not expose any YAML-related method, found: " + m);
        }
    }

    // @spec schema.document-field-access.R16 — no toString() override; default Object.toString is used
    @Test
    void structural_doesNotOverrideToString() {
        assertThrows(NoSuchMethodException.class,
                () -> JlsmDocument.class.getDeclaredMethod("toString"),
                "JlsmDocument must not declare toString() — callers use toJson()");
    }

    // @spec schema.document-field-access.R17 — does not implement Serializable
    @Test
    void structural_doesNotImplementSerializable() {
        assertFalse(Serializable.class.isAssignableFrom(JlsmDocument.class),
                "JlsmDocument must not implement Serializable");
    }

    // @spec schema.document-field-access.R19 — no set/with mutator methods in the public API
    @Test
    void structural_noSetOrWithMutators() {
        for (Method m : JlsmDocument.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            String name = m.getName();
            assertFalse(name.startsWith("set"), "public method must not start with 'set': " + m);
            assertFalse(name.startsWith("with"), "public method must not start with 'with': " + m);
        }
    }

    // @spec schema.document-construction.R5 — package-private ctor does not defensively copy the values array;
    // @spec schema.document-invariants.R5 — package-private ctor does not defensively copy the values array;
    // the final reference points at the caller's array (document internal state = that array)
    @Test
    void trustBoundary_packagePrivateCtorDoesNotCopyValuesArray() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("a", FieldType.Primitive.INT32)
                .field("b", FieldType.Primitive.STRING).build();
        Object[] values = { 42, "hello" };
        JlsmDocument doc = new JlsmDocument(schema, values);

        values[0] = 999;
        values[1] = "mutated";

        assertEquals(999, doc.getInt("a"),
                "package-private ctor must not copy: caller-held array mutation must be visible");
        assertEquals("mutated", doc.getString("b"));
    }

    // @spec schema.document-construction.R31 — scalars, Strings, and nested JlsmDocument pass through without copy
    @Test
    void identity_scalarAndNestedDocumentPassThroughWithoutCopy() {
        JlsmSchema addrSchema = JlsmSchema.builder("addr", 1)
                .field("city", FieldType.Primitive.STRING).build();
        JlsmDocument addr = JlsmDocument.of(addrSchema, "city", "NYC");

        JlsmSchema schema = JlsmSchema.builder("test", 1).field("s", FieldType.Primitive.STRING)
                .field("i", FieldType.Primitive.INT32)
                .objectField("nested", inner -> inner.field("city", FieldType.Primitive.STRING))
                .build();
        String s = "hello";
        Integer i = 7;
        JlsmDocument doc = JlsmDocument.of(schema, "s", s, "i", i, "nested", addr);

        assertSame(s, doc.getString("s"),
                "String values must not be copied: of() returns the same reference");
        assertSame(addr, doc.getObject("nested"),
                "Nested JlsmDocument must not be copied: getObject returns the same reference");
        assertEquals(i.intValue(), doc.getInt("i"), "int round-trip");
    }

    // @spec schema.document-invariants.R7 — effectively immutable for primitive + String fields via public API
    @Test
    void immutability_primitiveAndStringStateStableAcrossGetters() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("s", FieldType.Primitive.STRING)
                .field("i", FieldType.Primitive.INT32).build();
        JlsmDocument doc = JlsmDocument.of(schema, "s", "original", "i", 42);

        String before = doc.getString("s");
        int intBefore = doc.getInt("i");
        // Repeated reads and surrounding no-op getters must never alter state
        assertEquals("original", doc.getString("s"));
        assertEquals(42, doc.getInt("i"));
        assertEquals(before, doc.getString("s"));
        assertEquals(intBefore, doc.getInt("i"));
    }

    // ── Factory guard tests ───────────────────────────────────────────────

    // @spec schema.document-construction.R7 — of() rejects odd-length nameValuePairs with IAE
    @Test
    void of_oddLengthPairs_throwsIAE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .build();
        assertThrows(IllegalArgumentException.class, () -> JlsmDocument.of(schema, "name"));
    }

    // @spec schema.document-construction.R8 — of() non-String at even index → IAE with the offending index
    @Test
    void of_nonStringAtEvenIndex_throwsIAEWithIndex() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.of(schema, 42, "value"));
        assertTrue(ex.getMessage().contains("0"),
                "exception message must include the offending index, got: " + ex.getMessage());
    }

    // @spec schema.document-construction.R33 — duplicate field names in of() are rejected
    @Test
    void of_duplicateFieldName_throwsIAE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.of(schema, "name", "first", "name", "second"));
        assertTrue(ex.getMessage().toLowerCase().contains("duplicate"),
                "exception message must mention duplicate, got: " + ex.getMessage());
    }

    // @spec schema.document-construction.R23 — BoundedString rejects values exceeding UTF-8 byte length
    @Test
    void of_boundedStringExceedsMaxByteLength_throwsIAE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("code", new FieldType.BoundedString(4)).build();
        // "héllo" has UTF-8 byte length 6 (é is two bytes), exceeds 4
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.of(schema, "code", "héllo"));
    }

    // @spec schema.document-construction.R23 — BoundedString accepts values at or below the UTF-8 byte limit
    @Test
    void of_boundedStringWithinByteLength_accepts() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("code", new FieldType.BoundedString(8)).build();
        JlsmDocument doc = JlsmDocument.of(schema, "code", "héllo"); // 6 bytes UTF-8
        assertEquals("héllo", doc.getString("code"));
    }

    // @spec schema.document-construction.R34 — validateType enforces MAX_ARRAY_NESTING_DEPTH (32) for nested ArrayType
    @Test
    void of_deeplyNestedArrayExceedsDepth_throwsIAE() {
        FieldType leaf = FieldType.Primitive.INT32;
        FieldType nested = leaf;
        for (int i = 0; i < 40; i++) {
            nested = FieldType.arrayOf(nested);
        }
        JlsmSchema schema = JlsmSchema.builder("deep", 1).field("arr", nested).build();

        // Build a value structure matching the deep schema (40 levels of Object[])
        Object deep = 1;
        for (int i = 0; i < 40; i++) {
            deep = new Object[]{ deep };
        }
        final Object deepValue = deep;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.of(schema, "arr", deepValue));
        assertTrue(
                ex.getMessage().toLowerCase().contains("nesting")
                        || ex.getMessage().toLowerCase().contains("depth"),
                "exception must mention nesting/depth, got: " + ex.getMessage());
    }

    // @spec schema.document-construction.R35 — deepCopyArray copies nested Object[] levels; inner mutations must not leak
    @Test
    void of_nestedArrayInnerMutationDoesNotLeakIntoDocument() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("matrix", FieldType.arrayOf(FieldType.arrayOf(FieldType.Primitive.INT32)))
                .build();
        Object[] row0 = { 1, 2, 3 };
        Object[] row1 = { 4, 5, 6 };
        Object[] matrix = { row0, row1 };
        JlsmDocument doc = JlsmDocument.of(schema, "matrix", matrix);

        // Mutate an inner array — a shallow top-level clone would leak this into the document
        row0[0] = 999;
        matrix[1] = new Object[]{ 7, 8, 9 };

        Object[] stored = doc.getArray("matrix");
        Object[] storedRow0 = (Object[]) stored[0];
        assertEquals(1, storedRow0[0], "inner Object[] must be deep-copied, not shared");
        Object[] storedRow1 = (Object[]) stored[1];
        assertEquals(4, storedRow1[0],
                "top-level slot must not reflect post-construction mutation of caller's array");
    }

    // ── Typed-getter guards ──────────────────────────────────────────────

    // @spec schema.document-field-access.R1 — typed getter rejects null field name with NPE
    @Test
    void getString_nullFieldName_throwsNPE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .build();
        JlsmDocument doc = JlsmDocument.of(schema, "name", "value");
        assertThrows(NullPointerException.class, () -> doc.getString(null));
    }

    // @spec schema.document-field-access.R3,R20 — typed getter throws NPE with field name when value is null
    @Test
    void getLong_nullValue_throwsDescriptiveNPE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("n", FieldType.Primitive.INT64)
                .build();
        JlsmDocument doc = JlsmDocument.of(schema, "n", null);
        NullPointerException ex = assertThrows(NullPointerException.class, () -> doc.getLong("n"));
        assertTrue(ex.getMessage().contains("n"),
                "NPE message must include field name, got: " + ex.getMessage());
    }

    // @spec schema.document-field-access.R12 — isNull rejects null field name with NPE and unknown name with IAE
    @Test
    void isNull_nullOrUnknownField_rejected() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("a", FieldType.Primitive.INT32)
                .build();
        JlsmDocument doc = JlsmDocument.of(schema, "a", 1);
        assertThrows(NullPointerException.class, () -> doc.isNull(null));
        assertThrows(IllegalArgumentException.class, () -> doc.isNull("unknown"));
    }

    // @spec schema.document-field-access.R10 — schema() returns the stored reference, not a copy
    @Test
    void schema_returnsSameReferenceAsPassedIn() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("a", FieldType.Primitive.INT32)
                .build();
        JlsmDocument doc = JlsmDocument.of(schema, "a", 1);
        assertSame(schema, doc.schema(), "schema() must return the same reference, not a copy");
    }

    // @spec schema.document-field-access.R14 — getFloat32Vector clones; wrong-type field → IAE; null value → NPE
    @Test
    void getFloat32Vector_guardsAndClone() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 3)
                .field("i", FieldType.Primitive.INT32)
                .vectorField("nullable", FieldType.Primitive.FLOAT32, 3).build();
        float[] v = { 1.0f, 2.0f, 3.0f };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", v, "i", 42);

        float[] returned = doc.getFloat32Vector("vec");
        returned[0] = 999.0f;
        assertEquals(1.0f, doc.getFloat32Vector("vec")[0],
                "getFloat32Vector must return a defensive clone");

        assertThrows(IllegalArgumentException.class, () -> doc.getFloat32Vector("i"),
                "non-VECTOR(FLOAT32) field must be rejected with IAE");
        assertThrows(NullPointerException.class, () -> doc.getFloat32Vector("nullable"),
                "null value must be rejected with NPE");
    }

    // @spec schema.document-field-access.R15 — getFloat16Vector clones; wrong-type field → IAE; null value → NPE
    @Test
    void getFloat16Vector_guardsAndClone() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT16, 3)
                .vectorField("other", FieldType.Primitive.FLOAT32, 3)
                .vectorField("nullable", FieldType.Primitive.FLOAT16, 3).build();
        short[] v = { Float16.fromFloat(1.0f), Float16.fromFloat(2.0f), Float16.fromFloat(3.0f) };
        float[] f = { 1.0f, 2.0f, 3.0f };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", v, "other", f);

        short[] returned = doc.getFloat16Vector("vec");
        returned[0] = 0;
        assertEquals(Float16.fromFloat(1.0f), doc.getFloat16Vector("vec")[0],
                "getFloat16Vector must return a defensive clone");

        assertThrows(IllegalArgumentException.class, () -> doc.getFloat16Vector("other"),
                "VECTOR(FLOAT32) must be rejected by getFloat16Vector with IAE");
        assertThrows(NullPointerException.class, () -> doc.getFloat16Vector("nullable"));
    }

    // ── equals / hashCode ────────────────────────────────────────────────

    // @spec schema.document-invariants.R8 — equals based on schema + preEncrypted + deep array equality
    @Test
    void equals_structuralEquality() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("s", FieldType.Primitive.STRING)
                .field("arr", FieldType.arrayOf(FieldType.Primitive.INT32)).build();
        JlsmDocument a = JlsmDocument.of(schema, "s", "hello", "arr", new Object[]{ 1, 2, 3 });
        JlsmDocument b = JlsmDocument.of(schema, "s", "hello", "arr", new Object[]{ 1, 2, 3 });
        JlsmDocument c = JlsmDocument.of(schema, "s", "hello", "arr", new Object[]{ 1, 2, 4 });

        assertEquals(a, b, "documents with equal fields must be equal (deepEquals)");
        assertNotEquals(a, c, "differing inner-array contents must produce inequality");
        assertNotEquals(a, null);
        assertNotEquals(a, "not a document");
    }

    // @spec schema.document-invariants.R9 — hashCode consistent with equals
    @Test
    void hashCode_consistentWithEquals() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("s", FieldType.Primitive.STRING)
                .build();
        JlsmDocument a = JlsmDocument.of(schema, "s", "x");
        JlsmDocument b = JlsmDocument.of(schema, "s", "x");
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ── Field modifiers (R54) ─────────────────────────────────────────────

    // @spec schema.document-invariants.R4 — schema, preEncrypted, values fields are private final
    @Test
    void structural_schemaAndPreEncryptedAndValuesArePrivateFinal() throws Exception {
        for (String fieldName : new String[]{ "schema", "preEncrypted", "values" }) {
            java.lang.reflect.Field f = JlsmDocument.class.getDeclaredField(fieldName);
            int mods = f.getModifiers();
            assertTrue(Modifier.isPrivate(mods), fieldName + " must be private");
            assertTrue(Modifier.isFinal(mods), fieldName + " must be final");
        }
    }

    // ── Package-private constructor behavior ──────────────────────────────

    // @spec schema.document-construction.R3 — 2-arg ctor delegates to 3-arg with preEncrypted = false
    @Test
    void packagePrivateCtor_twoArg_producesNotPreEncrypted() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("a", FieldType.Primitive.INT32)
                .build();
        JlsmDocument doc = new JlsmDocument(schema, new Object[]{ 42 });
        assertFalse(doc.isPreEncrypted(),
                "2-arg ctor must delegate to 3-arg with preEncrypted = false");
    }

    // @spec schema.document-construction.R4 — 3-arg ctor runtime-validates schema, values, length
    @Test
    void packagePrivateCtor_runtimeValidatesInputs() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("a", FieldType.Primitive.INT32)
                .field("b", FieldType.Primitive.STRING).build();

        assertThrows(NullPointerException.class,
                () -> new JlsmDocument(null, new Object[]{ 1, "x" }, false));
        assertThrows(NullPointerException.class, () -> new JlsmDocument(schema, null, false));
        // length mismatch (schema has 2 fields, values has 1)
        assertThrows(IllegalArgumentException.class,
                () -> new JlsmDocument(schema, new Object[]{ 1 }, false));
    }

    // ── DocumentAccess bridge (R52, R53) ──────────────────────────────────

    // @spec schema.document-invariants.R2,R3 — DocumentAccess.Accessor is registered; create() produces
    // non-pre-encrypted
    @Test
    void documentAccess_bridgeWiredAndCreateProducesNonPreEncrypted() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("a", FieldType.Primitive.INT32)
                .build();
        var accessor = jlsm.table.internal.DocumentAccess.get();
        assertNotNull(accessor, "DocumentAccess.get() must return registered accessor");

        JlsmDocument doc = accessor.create(schema, new Object[]{ 7 });
        assertFalse(accessor.isPreEncrypted(doc),
                "DocumentAccess.create must invoke 2-arg ctor (preEncrypted = false)");
        assertEquals(7, doc.getInt("a"));
    }
}
