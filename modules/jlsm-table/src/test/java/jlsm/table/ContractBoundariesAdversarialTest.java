package jlsm.table;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.io.MemorySerializer;
import jlsm.encryption.BoldyrevaOpeEncryptor;
import jlsm.encryption.EncryptionKeyHolder;
import jlsm.encryption.EncryptionSpec;
import jlsm.table.internal.FieldIndex;
import jlsm.table.internal.FieldValueCodec;
import jlsm.table.internal.IndexRegistry;
import jlsm.table.internal.PositionalPostingCodec;
import jlsm.table.internal.QueryExecutor;
import jlsm.table.internal.ResultMerger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for contract boundary violations in jlsm-table.
 */
class ContractBoundariesAdversarialTest {

    // Finding: F-R1.cb.1.3
    // Bug: Predicate.Between accepts mismatched Comparable types for low and high — no
    // type-equality check
    // Correct behavior: Constructor should throw IllegalArgumentException when low and high have
    // different types
    // Fix location: Predicate.Between compact constructor (Predicate.java:72-78)
    // Regression watch: Ensure fix does not reject valid same-type bounds (e.g., Integer/Integer,
    // String/String)
    @Test
    void test_Between_mismatchedTypes_throwsOnConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new Predicate.Between("age", "hello", 42),
                "Between should reject low and high of different types");
    }

    // Finding: F-R1.cb.1.6
    // Bug: Predicate.And/Or accept list containing null elements — List.copyOf defers NPE to a
    // generic message
    // Correct behavior: Constructor should throw NullPointerException with descriptive message
    // identifying null child
    // Fix location: Predicate.And compact constructor (Predicate.java:120-127), Predicate.Or
    // (Predicate.java:131-138)
    // Regression watch: Ensure fix does not reject valid non-null children lists
    @Test
    void test_And_nullChildElement_throwsDescriptiveNPE() {
        var validPredicate = new Predicate.Eq("name", "Alice");
        // Arrays.asList allows null elements, unlike List.of
        var childrenWithNull = Arrays.<Predicate>asList(validPredicate, null);

        var ex = assertThrows(NullPointerException.class, () -> new Predicate.And(childrenWithNull),
                "And should reject list containing null children");

        // The error message should mention "children" to identify the source
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("children"),
                "Error message should identify null child in 'children' — got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.1.7
    // Bug: FieldClause.fullTextMatch accepts empty string query — semantically meaningless
    // Correct behavior: Predicate.FullTextMatch constructor should reject empty/blank query strings
    // Fix location: Predicate.FullTextMatch compact constructor (Predicate.java:89-93)
    // Regression watch: Ensure non-empty queries still work; only empty/blank rejected
    @Test
    void test_FullTextMatch_emptyQuery_throwsOnConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new Predicate.FullTextMatch("content", ""),
                "FullTextMatch should reject empty query string");
    }

    @Test
    void test_FullTextMatch_blankQuery_throwsOnConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new Predicate.FullTextMatch("content", "   "),
                "FullTextMatch should reject blank query string");
    }

    // Finding: F-R1.cb.1.8
    // Bug: VectorNearest accepts zero-length queryVector — meaningless for nearest-neighbor search
    // Correct behavior: Constructor should throw IllegalArgumentException when queryVector is empty
    // Fix location: Predicate.VectorNearest compact constructor (Predicate.java:104-111)
    // Regression watch: Ensure non-empty vectors still accepted; only zero-length rejected
    @Test
    void test_VectorNearest_zeroLengthVector_throwsOnConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new Predicate.VectorNearest("vec", new float[0], 5),
                "VectorNearest should reject zero-length queryVector");
    }

    // Finding: F-R1.cb.2.3
    // Bug: FieldValueCodec.decode() uses assert-only byte size validation — wrong-sized segments
    // throw AssertionError (with -ea) or IndexOutOfBoundsException/silent wrong value (without -ea)
    // Correct behavior: decode() should throw IllegalArgumentException with descriptive message for
    // wrong-sized input
    // Fix location: FieldValueCodec decode methods (lines 105, 121, 139, 160, 183, 211, 241, 282)
    // Regression watch: Ensure correctly-sized segments still decode without error
    @Test
    void test_FieldValueCodec_decode_wrongSizeSegment_throwsIAE() {
        // 2-byte segment passed to INT32 decode (expects 4 bytes)
        MemorySegment tooShort = Arena.ofAuto().allocate(2);
        assertThrows(IllegalArgumentException.class,
                () -> FieldValueCodec.decode(tooShort, FieldType.Primitive.INT32),
                "decode should throw IAE for wrong-sized segment, not AssertionError or IOOBE");

        // 8-byte segment passed to INT32 decode (expects 4 bytes) — too long, silent wrong value
        MemorySegment tooLong = Arena.ofAuto().allocate(8);
        assertThrows(IllegalArgumentException.class,
                () -> FieldValueCodec.decode(tooLong, FieldType.Primitive.INT32),
                "decode should throw IAE for oversized segment, not silently read partial data");
    }

    // Finding: F-R1.cb.2.6
    // Bug: FieldIndex IndexType validation uses assert, not runtime check — with -da,
    // VECTOR/FULL_TEXT accepted
    // Correct behavior: Constructor should throw IllegalArgumentException for unsupported IndexType
    // values
    // Fix location: FieldIndex constructor (FieldIndex.java:40-42)
    // Regression watch: Ensure EQUALITY, RANGE, UNIQUE still accepted without error
    @Test
    void test_FieldIndex_unsupportedIndexType_throwsIAE() {
        var vectorDef = new IndexDefinition("embedding", IndexType.VECTOR,
                SimilarityFunction.COSINE);
        assertThrows(IllegalArgumentException.class,
                () -> new FieldIndex(vectorDef, FieldType.Primitive.FLOAT32),
                "FieldIndex should reject VECTOR IndexType with IAE, not rely on assert");
    }

    // Finding: F-R1.cb.3.2 (resolved by WD-02)
    // Historical bug: VectorFieldIndex.onInsert threw UnsupportedOperationException, making
    // any table with a VECTOR index unusable for mutations.
    // Post-WD-02 behavior: VectorFieldIndex delegates to a factory-supplied backing; inserts
    // must succeed. A null vector field is a no-op per R56; a non-null vector gets indexed.
    // This regression watch keeps the invariant that inserts do not throw.
    @Test
    void test_VectorFieldIndex_onInsert_doesNotThrowOnTableWithVectorIndex() throws Exception {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 4)).build();

        List<IndexDefinition> definitions = List.of(new IndexDefinition("name", IndexType.EQUALITY),
                new IndexDefinition("embedding", IndexType.VECTOR, SimilarityFunction.COSINE));

        try (IndexRegistry registry = new IndexRegistry(schema, definitions, null,
                jlsm.table.internal.InMemoryVectorFactories.ivfFlatFake())) {
            byte[] pkBytes = "pk-1".getBytes(StandardCharsets.UTF_8);
            MemorySegment pk = Arena.ofAuto().allocate(pkBytes.length);
            pk.copyFrom(MemorySegment.ofArray(pkBytes));

            JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice");

            // Must NOT throw — VectorFieldIndex routes to a real backing; null vector is no-op.
            assertDoesNotThrow(() -> registry.onInsert(pk, doc),
                    "Insert into table with VECTOR index should succeed");

            // The document should be in the store after successful insert
            assertNotNull(registry.resolveEntry(pk),
                    "Document should be stored after successful insert");
        }
    }

    @Test
    void test_Or_nullChildElement_throwsDescriptiveNPE() {
        var validPredicate = new Predicate.Eq("name", "Alice");
        var childrenWithNull = Arrays.<Predicate>asList(validPredicate, null);

        var ex = assertThrows(NullPointerException.class, () -> new Predicate.Or(childrenWithNull),
                "Or should reject list containing null children");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("children"),
                "Error message should identify null child in 'children' — got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.1.1
    // Bug: ObjectType.toSchema() calls 2-arg builder.field(name, type), dropping encryption spec
    // Correct behavior: toSchema() should preserve FieldDefinition encryption specs in the
    // resulting schema
    // Fix location: FieldType.ObjectType.toSchema() line 58 — use 3-arg field(name, type,
    // encryption)
    // Regression watch: Ensure non-encrypted ObjectType fields still produce NONE encryption in
    // schema
    @Test
    void test_ObjectType_toSchema_preservesEncryptionSpecs() {
        // Create FieldDefinitions with non-default encryption
        List<FieldDefinition> fields = List.of(
                new FieldDefinition("public_name", FieldType.string()),
                new FieldDefinition("secret", FieldType.string(), EncryptionSpec.opaque()),
                new FieldDefinition("sortable", FieldType.Primitive.INT32,
                        EncryptionSpec.orderPreserving()));

        FieldType.ObjectType objectType = new FieldType.ObjectType(fields);
        JlsmSchema schema = objectType.toSchema("test", 1);

        // The resulting schema must preserve encryption specs from the original FieldDefinitions
        List<FieldDefinition> schemaFields = schema.fields();

        assertEquals(EncryptionSpec.NONE, schemaFields.get(0).encryption(),
                "public_name should have NONE encryption");
        assertEquals(EncryptionSpec.opaque(), schemaFields.get(1).encryption(),
                "secret should preserve Opaque encryption — toSchema() must not drop it");
        assertEquals(EncryptionSpec.orderPreserving(), schemaFields.get(2).encryption(),
                "sortable should preserve OrderPreserving encryption — toSchema() must not drop it");
    }

    // Finding: F-R1.cb.1.1 (original finding ID reused in file — this is the
    // FieldEncryptionDispatch finding)
    // Bug: encryptorFor/decryptorFor bounds check is assert-only — throws AssertionError (with -ea)
    // or ArrayIndexOutOfBoundsException (without -ea) instead of IllegalArgumentException
    // Correct behavior: Should throw IllegalArgumentException for out-of-bounds fieldIndex
    // Fix location: FieldEncryptionDispatch.encryptorFor/decryptorFor (lines 151-168)
    // Regression watch: Ensure valid indices still return correct encryptor/decryptor (including
    // null)
    @Test
    void test_FieldEncryptionDispatch_encryptorFor_negativeIndex_throwsIAE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.Primitive.INT32).build();
        var dispatch = new FieldEncryptionDispatch(schema, null);

        // Negative index must throw IllegalArgumentException, not AssertionError or AIOOBE
        assertThrows(IllegalArgumentException.class, () -> dispatch.encryptorFor(-1),
                "encryptorFor(-1) should throw IllegalArgumentException, not assert-only error");
    }

    @Test
    void test_FieldEncryptionDispatch_encryptorFor_tooLargeIndex_throwsIAE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        var dispatch = new FieldEncryptionDispatch(schema, null);

        // Index == fieldCount must throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> dispatch.encryptorFor(1),
                "encryptorFor(fieldCount) should throw IllegalArgumentException");
    }

    @Test
    void test_FieldEncryptionDispatch_decryptorFor_negativeIndex_throwsIAE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        var dispatch = new FieldEncryptionDispatch(schema, null);

        assertThrows(IllegalArgumentException.class, () -> dispatch.decryptorFor(-1),
                "decryptorFor(-1) should throw IllegalArgumentException, not assert-only error");
    }

    // Finding: F-R1.cb.2.1
    // Bug: Schema match between document and serializer enforced only by assert — with -da,
    // a document created with schema A is silently serialized under schema B's field types
    // Correct behavior: serialize() should throw IllegalArgumentException when document schema
    // does not match the serializer's schema
    // Fix location: DocumentSerializer.serialize (DocumentSerializer.java:168-169)
    // Regression watch: Ensure same-schema documents still serialize without error
    @Test
    void test_DocumentSerializer_serialize_schemaMismatch_throwsIAE() {
        // Schema A: STRING, INT32, BOOLEAN
        JlsmSchema schemaA = JlsmSchema.builder("shared-name", 1).field("f1", FieldType.string())
                .field("f2", FieldType.Primitive.INT32).field("f3", FieldType.Primitive.BOOLEAN)
                .build();

        // Schema B: same name, different field types — INT64, STRING, STRING
        JlsmSchema schemaB = JlsmSchema.builder("shared-name", 1)
                .field("x1", FieldType.Primitive.INT64).field("x2", FieldType.string())
                .field("x3", FieldType.string()).build();

        // Build serializer for schema B
        MemorySerializer<JlsmDocument> serializerB = DocumentSerializer.forSchema(schemaB);

        // Create document with schema A
        JlsmDocument docA = JlsmDocument.of(schemaA, "f1", "hello", "f2", 42, "f3", true);

        // Serializing docA with serializerB must throw IAE, not silently corrupt
        assertThrows(IllegalArgumentException.class, () -> serializerB.serialize(docA),
                "serialize() should reject document whose schema differs from serializer's schema — "
                        + "assert-only check is skipped with -da, causing silent data corruption");
    }

    // Finding: F-R1.CB.4.4
    // Bug: Between predicate uses getClass() equality rejecting logically comparable cross-types —
    // INT64 field returns Long, but Integer bounds silently fail the class check, returning false
    // Correct behavior: Numeric type widening should allow Integer bounds to match Long field
    // values
    // Fix location: QueryExecutor.matchesPredicate Between case (QueryExecutor.java:185-191)
    // Regression watch: Ensure same-type comparisons still work; only cross-numeric types affected
    @Test
    void test_Between_integerBoundsOnLongField_matchesCorrectly() throws Exception {
        JlsmSchema schema = JlsmSchema.builder("people", 1).field("name", FieldType.string())
                .field("age", FieldType.Primitive.INT64).build();

        try (IndexRegistry registry = new IndexRegistry(schema, List.of())) {
            byte[] pkBytes = "pk-1".getBytes(StandardCharsets.UTF_8);
            MemorySegment pk = Arena.ofAuto().allocate(pkBytes.length);
            pk.copyFrom(MemorySegment.ofArray(pkBytes));

            // Insert document with age=25 (stored as Long via INT64 field)
            JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 25L);
            registry.onInsert(pk, doc);

            QueryExecutor<String> executor = QueryExecutor.forStringKeys(schema, registry);

            // Between with Integer bounds (10, 50) against Long field value (25L)
            // Bug: getClass() == check fails (Long.class != Integer.class), silently returns empty
            var results = executor.execute(new Predicate.Between("age", 10, 50));
            assertTrue(results.hasNext(),
                    "Between with Integer bounds should match Long field value 25 in range [10, 50] — "
                            + "getClass() equality rejects logically comparable cross-types");
        }
    }

    // Finding: F-R1.CB.4.3
    // Bug: extractFieldValue uses assert-only guard for schema.fieldIndex result —
    // with -ea throws AssertionError, without -ea throws IndexOutOfBoundsException;
    // neither is the correct IAE with a descriptive message
    // Correct behavior: should throw IllegalArgumentException identifying the unknown field
    // Fix location: QueryExecutor.extractFieldValue (QueryExecutor.java:224-225)
    // Regression watch: Ensure valid field names still resolve correctly in scan-and-filter
    @Test
    void test_extractFieldValue_unknownField_throwsIAE() throws Exception {
        // Schema with two fields: "name" and "extra"
        JlsmSchema wideSchema = JlsmSchema.builder("wide", 1).field("name", FieldType.string())
                .field("extra", FieldType.string()).build();

        // Schema with only "name" — missing "extra"
        JlsmSchema narrowSchema = JlsmSchema.builder("narrow", 1).field("name", FieldType.string())
                .build();

        // Insert a document with the wide schema (has "extra" field set)
        try (IndexRegistry registry = new IndexRegistry(wideSchema, List.of())) {
            byte[] pkBytes = "pk-1".getBytes(StandardCharsets.UTF_8);
            MemorySegment pk = Arena.ofAuto().allocate(pkBytes.length);
            pk.copyFrom(MemorySegment.ofArray(pkBytes));

            JlsmDocument doc = JlsmDocument.of(wideSchema, "name", "Alice", "extra", "data");
            registry.onInsert(pk, doc);

            // Create QueryExecutor with the NARROW schema — it doesn't know about "extra"
            QueryExecutor<String> executor = QueryExecutor.forStringKeys(narrowSchema, registry);

            // Query for "extra" field — document.isNull("extra") returns false (field exists in
            // doc's wide schema), but narrowSchema.fieldIndex("extra") returns -1.
            // With assert-only guard: throws AssertionError (with -ea) or IndexOutOfBoundsException
            // (without -ea). Correct behavior: IllegalArgumentException.
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> executor.execute(new Predicate.Eq("extra", "data")),
                    "extractFieldValue should throw IAE for field unknown to executor's schema, "
                            + "not AssertionError or IndexOutOfBoundsException");

            assertTrue(ex.getMessage() != null && ex.getMessage().contains("extra"),
                    "Error message should identify the unknown field name — got: "
                            + ex.getMessage());
        }
    }

    // Finding: F-R1.CB.4.6
    // Bug: QueryExecutor.extractFieldValue silently returns null for ArrayType, VectorType, and
    // ObjectType fields
    // Correct behavior: extractFieldValue should throw UnsupportedOperationException for complex
    // field types
    // that cannot be meaningfully compared in scan-and-filter mode
    // Fix location: QueryExecutor.extractFieldValue final return (QueryExecutor.java:~267)
    // Regression watch: Primitive and BoundedString fields must still work in scan-and-filter
    @Test
    void test_extractFieldValue_vectorTypeField_throwsUnsupported() throws Exception {
        JlsmSchema schema = JlsmSchema.builder("vectors", 1)
                .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 3)).build();

        try (IndexRegistry registry = new IndexRegistry(schema, List.of())) {
            byte[] pkBytes = "pk-1".getBytes(StandardCharsets.UTF_8);
            MemorySegment pk = Arena.ofAuto().allocate(pkBytes.length);
            pk.copyFrom(MemorySegment.ofArray(pkBytes));

            JlsmDocument doc = JlsmDocument.of(schema, "embedding",
                    new float[]{ 1.0f, 2.0f, 3.0f });
            registry.onInsert(pk, doc);

            QueryExecutor<String> executor = QueryExecutor.forStringKeys(schema, registry);

            // Eq predicate on a VectorType field — scan-and-filter cannot extract vector values
            // Bug: extractFieldValue returned null, causing matchesPredicate to yield false
            // silently, producing an empty result set with no error
            assertThrows(UnsupportedOperationException.class,
                    () -> executor.execute(new Predicate.Eq("embedding", "irrelevant")),
                    "extractFieldValue must throw UnsupportedOperationException for VectorType, "
                            + "not silently return null causing empty results");
        }
    }

    // Finding: F-R1.cb.2.4
    // Bug: deserialize performs no structural validation of segment size before reading header
    // Correct behavior: deserialize should throw IllegalArgumentException for segments smaller
    // than the 4-byte minimum header (2 bytes version + 2 bytes field count)
    // Fix location: DocumentSerializer.deserialize (DocumentSerializer.java:312-321)
    // Regression watch: Ensure valid serialized documents still deserialize correctly
    @Test
    void test_DocumentSerializer_deserialize_truncatedSegment_throwsIAE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();

        MemorySerializer<JlsmDocument> serializer = DocumentSerializer.forSchema(schema);

        // 2-byte segment — less than the 4-byte minimum header
        MemorySegment tooSmall = MemorySegment.ofArray(new byte[]{ 0x00, 0x01 });

        var ex = assertThrows(IllegalArgumentException.class,
                () -> serializer.deserialize(tooSmall),
                "deserialize should throw IAE for segment smaller than 4-byte header, "
                        + "not ArrayIndexOutOfBoundsException");

        assertTrue(ex.getMessage() != null && ex.getMessage().toLowerCase().contains("header"),
                "Error message should mention header — got: " + ex.getMessage());
    }

    @Test
    void test_DocumentSerializer_deserialize_emptySegment_throwsIAE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();

        MemorySerializer<JlsmDocument> serializer = DocumentSerializer.forSchema(schema);

        // 0-byte segment — empty
        MemorySegment empty = MemorySegment.ofArray(new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> serializer.deserialize(empty),
                "deserialize should throw IAE for empty segment");
    }

    // Finding: F-R1.cb.2.2
    // Bug: Pre-encrypted value type check enforced only by assert — with -da, a non-byte[]
    // value in an encrypted field causes ClassCastException with generic message instead
    // of IllegalArgumentException with domain context (field name, pre-encrypted contract)
    // Correct behavior: serializePreEncrypted should throw IllegalArgumentException naming
    // the field and explaining the pre-encrypted contract
    // Fix location: DocumentSerializer.serializePreEncrypted (DocumentSerializer.java:266-271)
    // Regression watch: Ensure valid byte[] ciphertext in pre-encrypted docs still serializes
    // Finding: F-R1.cb.2.5 — superseded by F03 v2 (2026-04-17)
    //
    // Original bug: DistancePreserving pre-encrypted round-trip was broken because
    // serializePreEncrypted wrote the ciphertext as a length-prefixed blob but deserialize
    // fell through to the plain decoder (identity-passthrough encryptor in the dispatch).
    //
    // Current design (F03.R50/R51 + F41.R22): the serializer itself encrypts DCPE float
    // arrays and produces a [8B seed | 4N encrypted floats | 16B HMAC tag] blob. The
    // pre-encrypted path lets the caller supply this same blob shape directly. On
    // deserialization, both paths go through DcpeSapEncryptor.decrypt which verifies the
    // MAC before returning the plaintext float[].
    //
    // This test now verifies the new contract: a pre-encrypted DCPE document round-trips
    // through the serializer and the plaintext recovers exactly (byte-equal MAC → valid
    // decrypt). The caller must produce ciphertext with the same key that the reader will
    // use, mirroring real client-side-encryption-SDK workflows.
    @Test
    void test_DocumentSerializer_distancePreserving_preEncryptedRoundTrip_preservesCiphertext() {
        JlsmSchema schema = JlsmSchema.builder("dp-test", 1).field("label", FieldType.string())
                .field("vec", FieldType.vector(FieldType.Primitive.FLOAT32, 3),
                        EncryptionSpec.distancePreserving())
                .build();

        final byte[] rawKey = new byte[64];
        java.util.Arrays.fill(rawKey, (byte) 0xBE);
        try (var keyHolder = jlsm.encryption.EncryptionKeyHolder.of(rawKey);
                var dcpe = new jlsm.encryption.DcpeSapEncryptor(keyHolder, 3)) {
            final MemorySerializer<JlsmDocument> serializer = DocumentSerializer.forSchema(schema,
                    keyHolder);

            // Caller produces ciphertext themselves via the same key and the public encryptor.
            final float[] plaintext = { 1.25f, -0.5f, 3.75f };
            final byte[] fieldAd = "vec".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            final var ev = dcpe.encrypt(plaintext, fieldAd);
            final byte[] preEncryptedBlob = jlsm.encryption.DcpeSapEncryptor.toBlob(ev);
            assertEquals(8 + 3 * 4 + 16, preEncryptedBlob.length,
                    "pre-encrypted DCPE blob must be 8+4N+16 bytes");

            final Object[] values = new Object[]{ "test-vector", preEncryptedBlob };
            final JlsmDocument preEncDoc = new JlsmDocument(schema, values, true);

            final MemorySegment serialized = serializer.serialize(preEncDoc);
            final JlsmDocument deserialized = serializer.deserialize(serialized);

            final int vecIdx = schema.fieldIndex("vec");
            final Object vecValue = deserialized.values()[vecIdx];
            assertNotNull(vecValue,
                    "Deserialized DCPE vector must not be null — MAC should verify and decrypt"
                            + " should succeed round-trip");
            assertInstanceOf(float[].class, vecValue,
                    "Deserialized DCPE vector must be float[], got "
                            + vecValue.getClass().getSimpleName());
            final float[] recovered = (float[]) vecValue;
            assertEquals(3, recovered.length,
                    "Deserialized vector should have 3 dimensions matching the schema");
            assertArrayEquals(plaintext, recovered, 1e-4f,
                    "DCPE round-trip must recover the original plaintext within float tolerance");
        }
    }

    // Finding: F-R1.cb.3.1
    // Bug: IndexRegistry allows BoundedString of any length for OrderPreserving but
    // FieldEncryptionDispatch rejects maxLength > 2 (MAX_OPE_BYTES)
    // Correct behavior: IndexRegistry should reject BoundedString(maxLength > 2) with
    // OrderPreserving encryption at schema validation time
    // Fix location: IndexRegistry.validateOrderPreservingFieldType (line 378-379)
    // Regression watch: BoundedString(maxLength=1) and BoundedString(maxLength=2) must still be
    // accepted
    @Test
    void test_IndexRegistry_orderPreservingBoundedString_rejectsMaxLengthExceedingOpeLimit() {
        // BoundedString(maxLength=10) with OrderPreserving encryption — OPE limit is 2 bytes
        JlsmSchema schema = JlsmSchema.builder("ope-test", 1)
                .field("tag", FieldType.string(10), EncryptionSpec.orderPreserving()).build();

        // IndexRegistry should reject this at construction time, not defer to
        // FieldEncryptionDispatch
        assertThrows(IllegalArgumentException.class,
                () -> new IndexRegistry(schema,
                        List.of(new IndexDefinition("tag", IndexType.RANGE))),
                "IndexRegistry should reject BoundedString(maxLength=10) with OrderPreserving — "
                        + "OPE is limited to 2-byte values; deferring rejection to FieldEncryptionDispatch "
                        + "causes a confusing late failure");
    }

    @Test
    void test_IndexRegistry_orderPreservingBoundedString_acceptsMaxLength2() throws Exception {
        // BoundedString(maxLength=2) with OrderPreserving encryption — exactly at the limit
        JlsmSchema schema = JlsmSchema.builder("ope-test", 1)
                .field("tag", FieldType.string(2), EncryptionSpec.orderPreserving()).build();

        // Should succeed — maxLength=2 fits within MAX_OPE_BYTES
        try (var registry = new IndexRegistry(schema,
                List.of(new IndexDefinition("tag", IndexType.RANGE)))) {
            assertNotNull(registry);
        }
    }

    @Test
    void test_IndexRegistry_orderPreservingBoundedString_acceptsMaxLength1() throws Exception {
        // BoundedString(maxLength=1) with OrderPreserving encryption — below the limit
        JlsmSchema schema = JlsmSchema.builder("ope-test", 1)
                .field("tag", FieldType.string(1), EncryptionSpec.orderPreserving()).build();

        // Should succeed — maxLength=1 fits within MAX_OPE_BYTES
        try (var registry = new IndexRegistry(schema,
                List.of(new IndexDefinition("tag", IndexType.RANGE)))) {
            assertNotNull(registry);
        }
    }

    // Finding: F-R1.cb.4.1
    // Bug: JlsmDocument package-private constructor uses assert-only validation —
    // null schema, null values, or mismatched values length pass silently with -da
    // Correct behavior: Constructor should throw NullPointerException for null schema/values
    // and IllegalArgumentException for values length mismatch
    // Fix location: JlsmDocument constructor (JlsmDocument.java:58-65)
    // Regression watch: Ensure valid construction still works (e.g., existing tests using the
    // constructor)
    @Test
    void test_JlsmDocument_packagePrivateConstructor_nullSchema_throwsNPE() {
        Object[] values = new Object[]{ "Alice" };

        var ex = assertThrows(NullPointerException.class,
                () -> new JlsmDocument(null, values, false),
                "Package-private constructor should reject null schema with NPE, "
                        + "not rely on assert-only validation");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("schema"),
                "Error message should identify 'schema' — got: " + ex.getMessage());
    }

    @Test
    void test_JlsmDocument_packagePrivateConstructor_nullValues_throwsNPE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();

        var ex = assertThrows(NullPointerException.class,
                () -> new JlsmDocument(schema, null, false),
                "Package-private constructor should reject null values with NPE, "
                        + "not rely on assert-only validation");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("values"),
                "Error message should identify 'values' — got: " + ex.getMessage());
    }

    @Test
    void test_JlsmDocument_packagePrivateConstructor_valuesLengthMismatch_throwsIAE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.Primitive.INT32).build();

        // Schema has 2 fields but values array has 1 element
        Object[] values = new Object[]{ "Alice" };

        var ex = assertThrows(IllegalArgumentException.class,
                () -> new JlsmDocument(schema, values, false),
                "Package-private constructor should reject values length mismatch with IAE, "
                        + "not rely on assert-only validation");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("length"),
                "Error message should mention 'length' — got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.4.2
    // Bug: JlsmDocument.of uses assert-only null check on nameValuePairs — with -da,
    // null varargs produces bare NullPointerException at .length with no message
    // Correct behavior: Should throw NullPointerException with descriptive message
    // Fix location: JlsmDocument.of (JlsmDocument.java:85)
    // Regression watch: Ensure valid non-null varargs still work (empty array is valid)
    @Test
    void test_JlsmDocument_of_nullNameValuePairs_throwsDescriptiveNPE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();

        var ex = assertThrows(NullPointerException.class,
                () -> JlsmDocument.of(schema, (Object[]) null),
                "JlsmDocument.of should reject null nameValuePairs with descriptive NPE, "
                        + "not bare NPE from .length access");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("nameValuePairs"),
                "Error message should identify 'nameValuePairs' — got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.4.3
    // Bug: JlsmDocument.preEncrypted uses assert-only null check on nameValuePairs —
    // with -da, null varargs produces bare NullPointerException at .length with no message
    // Correct behavior: Should throw NullPointerException with descriptive message
    // Fix location: JlsmDocument.preEncrypted (JlsmDocument.java:129)
    // Regression watch: Ensure valid non-null varargs still work (empty array is valid)
    @Test
    void test_JlsmDocument_preEncrypted_nullNameValuePairs_throwsDescriptiveNPE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("secret", FieldType.string(), EncryptionSpec.opaque()).build();

        var ex = assertThrows(NullPointerException.class,
                () -> JlsmDocument.preEncrypted(schema, (Object[]) null),
                "JlsmDocument.preEncrypted should reject null nameValuePairs with descriptive NPE, "
                        + "not bare NPE from .length access");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("nameValuePairs"),
                "Error message should identify 'nameValuePairs' — got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.4.4
    // Bug: preEncrypted() calls defensiveCopyIfVector() on encrypted VectorType fields,
    // which casts byte[] ciphertext to float[], throwing ClassCastException
    // Correct behavior: preEncrypted() should skip defensive vector copy for encrypted fields
    // since the value is byte[] ciphertext, not float[]/short[]
    // Fix location: JlsmDocument.preEncrypted (JlsmDocument.java:162) — skip defensiveCopyIfVector
    // when the field is encrypted
    // Regression watch: Unencrypted VectorType fields in preEncrypted() should still get defensive
    // copy
    @Test
    void test_preEncrypted_encryptedVectorField_doesNotThrowClassCastException() {
        JlsmSchema schema = JlsmSchema
                .builder("vec-enc", 1).field("label", FieldType.string()).field("embedding",
                        FieldType.vector(FieldType.Primitive.FLOAT32, 4), EncryptionSpec.opaque())
                .build();

        // 16 bytes of fake ciphertext for a 4-dimension float32 vector
        byte[] ciphertext = new byte[]{ 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A,
                0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10 };

        // Bug: defensiveCopyIfVector sees VectorType and casts byte[] to float[],
        // throwing ClassCastException: [B cannot be cast to [F
        JlsmDocument doc = assertDoesNotThrow(
                () -> JlsmDocument.preEncrypted(schema, "label", "test", "embedding", ciphertext),
                "preEncrypted should accept byte[] for encrypted VectorType field — "
                        + "defensiveCopyIfVector must not cast ciphertext to float[]");

        // Verify the ciphertext is stored correctly
        int embeddingIdx = schema.fieldIndex("embedding");
        Object storedValue = doc.values()[embeddingIdx];
        assertInstanceOf(byte[].class, storedValue,
                "Encrypted VectorType field should store byte[] ciphertext, not float[]");
    }

    @Test
    void test_DocumentSerializer_serializePreEncrypted_nonByteArrayValue_throwsIAE() {
        // Schema with one encrypted field (Opaque = AES-GCM, minimum ciphertext 28 bytes)
        JlsmSchema schema = JlsmSchema.builder("enc-test", 1).field("name", FieldType.string())
                .field("secret", FieldType.string(), EncryptionSpec.opaque()).build();

        MemorySerializer<JlsmDocument> serializer = DocumentSerializer.forSchema(schema);

        // Bypass JlsmDocument.preEncrypted() validation by using the package-private constructor
        // directly, injecting a String where byte[] is required for the encrypted field.
        Object[] values = new Object[]{ "Alice", "not-a-byte-array" };
        JlsmDocument badDoc = new JlsmDocument(schema, values, true);

        // Must throw IllegalArgumentException (not ClassCastException) with field name context
        var ex = assertThrows(IllegalArgumentException.class, () -> serializer.serialize(badDoc),
                "serializePreEncrypted should throw IAE for non-byte[] value in encrypted field, "
                        + "not ClassCastException from cast at runtime with -da");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("secret"),
                "Error message should identify the field name — got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.4.5
    // Bug: JlsmSchema private constructor uses assert-only validation for name, version,
    // fields, and maxDepth — with assertions disabled (production default), null name
    // or negative version pass silently, creating an invalid schema
    // Correct behavior: Constructor should use runtime checks (Objects.requireNonNull,
    // explicit if/throw) so validation holds regardless of -ea/-da
    // Fix location: JlsmSchema constructor (JlsmSchema.java:30-34)
    // Regression watch: Ensure valid schemas still construct correctly through the Builder
    // Finding: F-R1.cb.4.6
    // Bug: JlsmSchema.Builder.maxDepth() on nested builder silently ignored — nested builder's
    // maxDepth field is set but objectField() uses root.maxDepth, discarding the value
    // Correct behavior: maxDepth() on a nested builder should throw IllegalStateException
    // since only the root builder's maxDepth is used
    // Fix location: JlsmSchema.Builder.maxDepth (JlsmSchema.java:245-254)
    // Regression watch: maxDepth() on root builder must still work normally
    @Test
    void test_Builder_maxDepthOnNestedBuilder_throwsIllegalState() {
        // Track whether maxDepth was called on the nested builder
        boolean[] nestedMaxDepthCalled = { false };

        // Build a schema with an objectField; inside the consumer, call maxDepth on the
        // nested builder — this should throw because only the root builder owns maxDepth
        assertThrows(IllegalStateException.class, () -> JlsmSchema.builder("test", 1).maxDepth(5)
                .objectField("nested", nestedBuilder -> {
                    nestedBuilder.field("inner", FieldType.string());
                    nestedBuilder.maxDepth(3); // Bug: silently ignored
                    nestedMaxDepthCalled[0] = true;
                }).build(), "maxDepth() on a nested builder should throw IllegalStateException — "
                        + "the value is silently ignored because objectField uses root.maxDepth");
    }

    @Test
    void test_JlsmSchema_privateConstructor_nullName_throwsNPE() throws Exception {
        // Access the private constructor via reflection to bypass the Builder's validation
        Constructor<JlsmSchema> ctor = JlsmSchema.class.getDeclaredConstructor(String.class,
                int.class, List.class, int.class);
        ctor.setAccessible(true);

        List<FieldDefinition> fields = List.of(new FieldDefinition("f1", FieldType.string()));

        // Null name should throw NullPointerException via runtime check, not assert
        var ex = assertThrows(InvocationTargetException.class,
                () -> ctor.newInstance(null, 1, fields, 10));

        assertInstanceOf(NullPointerException.class, ex.getCause(),
                "Private constructor should throw NPE for null name via runtime check, "
                        + "not rely on assert — got: " + ex.getCause());
        assertTrue(
                ex.getCause().getMessage() != null && ex.getCause().getMessage().contains("name"),
                "Error message should identify 'name' — got: " + ex.getCause().getMessage());
    }

    @Test
    void test_JlsmSchema_privateConstructor_negativeVersion_throwsIAE() throws Exception {
        Constructor<JlsmSchema> ctor = JlsmSchema.class.getDeclaredConstructor(String.class,
                int.class, List.class, int.class);
        ctor.setAccessible(true);

        List<FieldDefinition> fields = List.of(new FieldDefinition("f1", FieldType.string()));

        // Negative version should throw IllegalArgumentException via runtime check
        var ex = assertThrows(InvocationTargetException.class,
                () -> ctor.newInstance("test", -1, fields, 10));

        assertInstanceOf(IllegalArgumentException.class, ex.getCause(),
                "Private constructor should throw IAE for negative version via runtime check, "
                        + "not rely on assert — got: " + ex.getCause());
    }

    @Test
    void test_JlsmSchema_privateConstructor_nullFields_throwsNPE() throws Exception {
        Constructor<JlsmSchema> ctor = JlsmSchema.class.getDeclaredConstructor(String.class,
                int.class, List.class, int.class);
        ctor.setAccessible(true);

        // Null fields should throw NullPointerException via runtime check
        var ex = assertThrows(InvocationTargetException.class,
                () -> ctor.newInstance("test", 1, null, 10));

        assertInstanceOf(NullPointerException.class, ex.getCause(),
                "Private constructor should throw NPE for null fields via runtime check, "
                        + "not rely on assert — got: " + ex.getCause());
    }

    // Finding: F-R1.cb.4.7
    // Bug: values() exposes mutable internal array without defensive copy
    // Correct behavior: values() should return a clone so callers cannot mutate document internals
    // Fix location: JlsmDocument.values() (JlsmDocument.java:211-212)
    // Regression watch: Ensure serializer and other read-only callers still work correctly
    // Documented limitation — NOT a bug
    // ObjectType fields are validated by Java type only (JlsmDocument), not by
    // conformance to the ObjectType's declared inner-schema field list. A caller
    // can store a sub-document whose schema's fields disagree with the
    // ObjectType's fields, and of() will accept it. This test pins that
    // limitation so a future change that adds deeper validation (a likely
    // enhancement) will deliberately touch this assertion.
    // @spec schema.document-field-access.R18 — nested ObjectType values NOT validated against inner schema
    @Test
    void test_JlsmDocument_objectType_notValidatedAgainstInnerSchema() {
        JlsmSchema outerSchema = JlsmSchema.builder("outer", 1).objectField("addr", inner -> inner
                .field("city", FieldType.Primitive.STRING).field("zip", FieldType.Primitive.INT32))
                .build();

        // Build a sub-document with a DIFFERENT schema (fields don't match the
        // declared ObjectType's inner field list at all).
        JlsmSchema mismatchedInner = JlsmSchema.builder("other", 1)
                .field("unrelated_field", FieldType.Primitive.STRING).build();
        JlsmDocument mismatched = JlsmDocument.of(mismatchedInner, "unrelated_field", "value");

        // of() must NOT throw — only Java type JlsmDocument is checked
        JlsmDocument outer = assertDoesNotThrow(
                () -> JlsmDocument.of(outerSchema, "addr", mismatched),
                "ObjectType validation only checks Java type, not inner schema conformance — "
                        + "a schema-mismatched JlsmDocument must be accepted by of()");
        assertSame(mismatched, outer.getObject("addr"),
                "nested JlsmDocument is stored by reference without conformance check");
    }

    // @spec schema.document-invariants.R1 — regression test for values() defensive clone
    @Test
    void test_JlsmDocument_values_returnsDefensiveCopy() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.Primitive.INT32).build();

        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 42);

        // Obtain the values array and mutate it
        Object[] exposed = doc.values();
        exposed[0] = "MUTATED";

        // The document's typed getter should still return the original value —
        // if values() returned the internal array, this mutation is visible through the getter
        assertEquals("Alice", doc.getString("name"),
                "Mutating array returned by values() must not affect document internals — "
                        + "values() should return a defensive copy, not the internal array");
    }

    // Finding: F-R1.cb.5.1
    // Bug: detEncryptor is dead state — stored but never used in encode/decode
    // Correct behavior: PositionalPostingCodec should not require an AesSivEncryptor parameter
    // that it never uses; the constructor should accept only BoldyrevaOpeEncryptor
    // Fix location: PositionalPostingCodec constructor and field
    // (PositionalPostingCodec.java:29,38-44)
    // Regression watch: Ensure encode/decode still function correctly after removing detEncryptor
    @Test
    void test_PositionalPostingCodec_detEncryptor_isDeadState() {
        // The constructor should accept only BoldyrevaOpeEncryptor, not require
        // an AesSivEncryptor that is never used. Verify the constructor signature
        // has exactly one parameter (BoldyrevaOpeEncryptor), not two.
        Constructor<?>[] constructors = PositionalPostingCodec.class.getConstructors();
        assertEquals(1, constructors.length,
                "PositionalPostingCodec should have exactly one public constructor");

        Class<?>[] paramTypes = constructors[0].getParameterTypes();
        assertEquals(1, paramTypes.length,
                "PositionalPostingCodec constructor should accept only BoldyrevaOpeEncryptor — "
                        + "detEncryptor (AesSivEncryptor) is dead state: stored in field but never "
                        + "referenced in encode() or decode(). Got parameter types: "
                        + java.util.Arrays.toString(paramTypes));
    }

    // Finding: F-R1.cb.5.2
    // Bug: Empty docId accepted in encode() — assert-only guard bypassed in production
    // Correct behavior: encode() should throw IllegalArgumentException for zero-length docId
    // Fix location: PositionalPostingCodec.encode (PositionalPostingCodec.java:58)
    // Regression watch: Ensure non-empty docId still encodes successfully
    @Test
    void test_PositionalPostingCodec_encode_emptyDocId_throwsIAE() {
        byte[] keyMaterial = new byte[64];
        for (int i = 0; i < 64; i++) {
            keyMaterial[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder keyHolder = EncryptionKeyHolder.of(keyMaterial);
        try {
            BoldyrevaOpeEncryptor ope = new BoldyrevaOpeEncryptor(keyHolder, 10_000L, 1_000_000L);
            PositionalPostingCodec codec = new PositionalPostingCodec(ope);

            // Empty docId — zero-length byte array
            assertThrows(IllegalArgumentException.class,
                    () -> codec.encode(new byte[0], new long[]{ 1L }),
                    "encode() should reject empty docId with IAE — "
                            + "assert-only guard at line 58 is bypassed when assertions are disabled, "
                            + "producing a posting that decode() rejects (round-trip contract violation)");
        } finally {
            keyHolder.close();
        }
    }

    // Finding: F-R1.cb.5.3
    // Bug: No domain-bounds validation on positions before delegating to OPE encryptor
    // Correct behavior: encode() should throw IllegalArgumentException with codec-level context
    // for position values outside OPE domain [1..domainSize], not leak encryptor internals
    // Fix location: PositionalPostingCodec.encode (PositionalPostingCodec.java:64-67)
    // Regression watch: Ensure valid positions (>= 1 and within domain) still encode successfully
    @Test
    void test_PositionalPostingCodec_encode_zeroPosition_throwsIAEWithCodecContext() {
        byte[] keyMaterial = new byte[64];
        for (int i = 0; i < 64; i++) {
            keyMaterial[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder keyHolder = EncryptionKeyHolder.of(keyMaterial);
        try {
            BoldyrevaOpeEncryptor ope = new BoldyrevaOpeEncryptor(keyHolder, 10_000L, 1_000_000L);
            PositionalPostingCodec codec = new PositionalPostingCodec(ope);

            byte[] docId = new byte[]{ 0x01, 0x02 };

            // Position 0 is outside OPE domain [1..M] — codec should catch and wrap
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> codec.encode(docId, new long[]{ 0L }),
                    "encode() should reject position 0 with codec-level IAE, "
                            + "not leak encryptor internal domain-bounds message");

            // The error message should mention "position" so the caller knows the problem
            assertTrue(
                    ex.getMessage() != null && ex.getMessage().toLowerCase().contains("position"),
                    "Error message should mention 'position' — got: " + ex.getMessage());
        } finally {
            keyHolder.close();
        }
    }

    // Finding: F-R1.cb.5.5
    // Bug: Integer overflow in decode position-count validation — positionCount * 8 overflows
    // to a negative value, bypassing the truncation check at line 129
    // Correct behavior: decode() should throw IllegalArgumentException for a positionCount
    // whose byte requirement overflows int, not bypass validation via integer overflow
    // Fix location: PositionalPostingCodec.decode (PositionalPostingCodec.java:129)
    // Regression watch: Ensure normal-sized postings still decode correctly
    @Test
    void test_PositionalPostingCodec_decode_positionCountIntOverflow_throwsIAE() {
        // Craft a malicious posting with positionCount = 0x20000001 (536,870,913).
        // positionCount * 8 = 0x100000008, truncated to int = 8.
        // buffer.remaining() = 8 (one long), so the check (8 < 8) is false → passes.
        // The code then tries new long[536_870_913] which is ~4 GiB → OutOfMemoryError.
        //
        // Posting layout: [4-byte docId len=1][1-byte docId][4-byte positionCount=0x20000001][8
        // bytes fake]
        java.nio.ByteBuffer crafted = java.nio.ByteBuffer.allocate(4 + 1 + 4 + 8);
        crafted.putInt(1); // docIdLength = 1
        crafted.put((byte) 0x42); // docId = one byte
        crafted.putInt(0x20000001); // positionCount = 536,870,913 (overflows * 8 to 8)
        crafted.putLong(0L); // one fake position (buffer.remaining after this = 0)

        byte[] malicious = crafted.array();

        byte[] keyMaterial = new byte[64];
        for (int i = 0; i < 64; i++) {
            keyMaterial[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder keyHolder = EncryptionKeyHolder.of(keyMaterial);
        try {
            BoldyrevaOpeEncryptor ope = new BoldyrevaOpeEncryptor(keyHolder, 10_000L, 1_000_000L);
            PositionalPostingCodec codec = new PositionalPostingCodec(ope);

            // Should throw IAE about truncated/invalid posting. Before the fix,
            // positionCount * 8 overflows to 8, the truncation check passes,
            // and new long[536_870_913] throws OutOfMemoryError (crashing the JVM).
            // We catch Throwable to handle both outcomes without crashing the test executor.
            Throwable thrown = null;
            try {
                codec.decode(malicious);
            } catch (Throwable t) {
                thrown = t;
            }
            assertNotNull(thrown,
                    "decode() should not succeed for positionCount whose byte requirement overflows int");
            assertInstanceOf(IllegalArgumentException.class, thrown,
                    "decode() should throw IllegalArgumentException for overflow positionCount, "
                            + "not " + thrown.getClass().getSimpleName() + ": "
                            + thrown.getMessage());
        } finally {
            keyHolder.close();
        }
    }

    // Finding: F-R1.cb.5.6
    // Bug: decode() silently accepts trailing bytes when assertions disabled
    // Correct behavior: decode() should throw IllegalArgumentException when the posting
    // contains bytes beyond the expected format (trailing garbage = possible corruption)
    // Fix location: PositionalPostingCodec.decode (PositionalPostingCodec.java:140)
    // Regression watch: Ensure valid postings with no trailing bytes still decode correctly
    @Test
    void test_PositionalPostingCodec_decode_trailingBytes_throwsIAE() {
        byte[] keyMaterial = new byte[64];
        for (int i = 0; i < 64; i++) {
            keyMaterial[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder keyHolder = EncryptionKeyHolder.of(keyMaterial);
        try {
            BoldyrevaOpeEncryptor ope = new BoldyrevaOpeEncryptor(keyHolder, 10_000L, 1_000_000L);
            PositionalPostingCodec codec = new PositionalPostingCodec(ope);

            // Encode a valid posting first
            byte[] docId = new byte[]{ 0x01, 0x02, 0x03 };
            long[] positions = new long[]{ 1L, 2L };
            byte[] validPosting = codec.encode(docId, positions);

            // Append 100 trailing garbage bytes
            byte[] corruptPosting = new byte[validPosting.length + 100];
            System.arraycopy(validPosting, 0, corruptPosting, 0, validPosting.length);
            for (int i = validPosting.length; i < corruptPosting.length; i++) {
                corruptPosting[i] = (byte) 0xDE;
            }

            // decode() should reject the trailing bytes as a malformed posting
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> codec.decode(corruptPosting),
                    "decode() should reject posting with trailing bytes — "
                            + "assert-only check at line 140 is bypassed with -da, "
                            + "silently accepting possibly-corrupt data");

            assertTrue(
                    ex.getMessage() != null && ex.getMessage().toLowerCase().contains("trailing"),
                    "Error message should mention 'trailing' bytes — got: " + ex.getMessage());
        } finally {
            keyHolder.close();
        }
    }

    @Test
    void test_PositionalPostingCodec_encode_negativePosition_throwsIAEWithCodecContext() {
        byte[] keyMaterial = new byte[64];
        for (int i = 0; i < 64; i++) {
            keyMaterial[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder keyHolder = EncryptionKeyHolder.of(keyMaterial);
        try {
            BoldyrevaOpeEncryptor ope = new BoldyrevaOpeEncryptor(keyHolder, 10_000L, 1_000_000L);
            PositionalPostingCodec codec = new PositionalPostingCodec(ope);

            byte[] docId = new byte[]{ 0x01, 0x02 };

            // Negative position is outside OPE domain [1..M]
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> codec.encode(docId, new long[]{ -5L }),
                    "encode() should reject negative position with codec-level IAE");

            assertTrue(
                    ex.getMessage() != null && ex.getMessage().toLowerCase().contains("position"),
                    "Error message should mention 'position' — got: " + ex.getMessage());
        } finally {
            keyHolder.close();
        }
    }

    // Finding: F-R1.cb.1.2
    // Bug: Builder.objectField() accepts blank field names — bypasses blank-check in field()
    // Correct behavior: objectField() should throw IllegalArgumentException for blank names
    // Fix location: JlsmSchema.Builder.objectField() (JlsmSchema.java:220-235)
    // Regression watch: Ensure fix does not reject valid non-blank object field names
    @Test
    void test_Builder_objectField_blankName_throwsIAE() {
        var builder = JlsmSchema.builder("test", 1);

        assertThrows(IllegalArgumentException.class,
                () -> builder.objectField("   ", b -> b.field("inner", FieldType.int32())),
                "objectField should reject blank field names");
    }

    // Finding: F-R1.cb.1.3
    // Bug: ObjectType.toSchema() hardcodes default maxDepth (10), losing parent schema depth
    // configuration
    // Correct behavior: toSchema() should accept a maxDepth parameter so callers can propagate the
    // parent's depth
    // Fix location: FieldType.ObjectType.toSchema() (FieldType.java:54-61) — add maxDepth overload
    // Regression watch: Ensure the 2-arg toSchema(name, version) still works with the default
    // maxDepth
    @Test
    void test_ObjectType_toSchema_propagatesMaxDepth() {
        // Create an ObjectType with simple fields
        List<FieldDefinition> fields = List.of(new FieldDefinition("name", FieldType.string()),
                new FieldDefinition("count", FieldType.Primitive.INT32));
        FieldType.ObjectType objectType = new FieldType.ObjectType(fields);

        // Parent schema configured with maxDepth=3
        int parentMaxDepth = 3;

        // toSchema should accept and propagate the parent's maxDepth
        JlsmSchema subSchema = objectType.toSchema("sub", 1, parentMaxDepth);

        assertEquals(parentMaxDepth, subSchema.maxDepth(),
                "Sub-schema maxDepth should match the specified depth (3), "
                        + "not the hardcoded default (10)");
    }

    // Finding: F-R1.cb.1.4
    // Bug: FieldType.arrayOf() and objectOf() use assert-only null checks — with -ea these
    // throw AssertionError instead of NullPointerException; with -da the assert is skipped
    // entirely and the constructor's requireNonNull fires with a misleading stack trace
    // Correct behavior: Factory methods should use Objects.requireNonNull (runtime check) so
    // callers always get NullPointerException regardless of -ea/-da flag
    // Fix location: FieldType.java lines 185 (arrayOf) and 196 (objectOf)
    // Regression watch: Ensure non-null arguments still create valid ArrayType/ObjectType
    @Test
    void test_FieldType_arrayOf_nullElementType_throwsNPE() {
        // With assert-only guard and -ea enabled (as in test JVM), this throws AssertionError.
        // Correct behavior: NullPointerException from a runtime check.
        var ex = assertThrows(NullPointerException.class, () -> FieldType.arrayOf(null),
                "arrayOf(null) should throw NullPointerException, not AssertionError — "
                        + "assert-only null check is not a valid input guard for a public API");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("elementType"),
                "Error message should identify 'elementType' — got: " + ex.getMessage());
    }

    @Test
    void test_FieldType_objectOf_nullFields_throwsNPE() {
        // With assert-only guard and -ea enabled (as in test JVM), this throws AssertionError.
        // Correct behavior: NullPointerException from a runtime check.
        var ex = assertThrows(NullPointerException.class, () -> FieldType.objectOf(null),
                "objectOf(null) should throw NullPointerException, not AssertionError — "
                        + "assert-only null check is not a valid input guard for a public API");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("fields"),
                "Error message should identify 'fields' — got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.3.3
    // Bug: encodeVector uses assert-only dimension check — with assertions disabled,
    // a short vector causes ArrayIndexOutOfBoundsException instead of IllegalArgumentException,
    // and a long vector is silently truncated (data loss with no signal)
    // Correct behavior: encodeVector should throw IllegalArgumentException when vector length
    // does not match VectorType.dimensions()
    // Fix location: DocumentSerializer.encodeVector (DocumentSerializer.java:612-630)
    // Regression watch: Correctly-sized vectors must still encode without error
    @Test
    void test_encodeVector_shortVector_throwsIAE() {
        // Schema declares a 4-dimension FLOAT32 vector field
        JlsmSchema schema = JlsmSchema.builder("vec-test", 1)
                .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 4)).build();

        MemorySerializer<JlsmDocument> serializer = DocumentSerializer.forSchema(schema);

        // Bypass JlsmDocument.of() validation by using the package-private constructor
        // to create a document with a 2-element vector (schema expects 4)
        float[] shortVector = new float[]{ 1.0f, 2.0f };
        Object[] values = new Object[]{ shortVector };
        JlsmDocument doc = new JlsmDocument(schema, values, false);

        // Should throw IllegalArgumentException, not ArrayIndexOutOfBoundsException
        var ex = assertThrows(IllegalArgumentException.class, () -> serializer.serialize(doc),
                "encodeVector should throw IAE for vector shorter than declared dimensions, "
                        + "not raw ArrayIndexOutOfBoundsException");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("dimensions"),
                "Error message should mention 'dimensions' — got: " + ex.getMessage());
    }

    @Test
    void test_encodeVector_longVector_throwsIAE() {
        // Schema declares a 2-dimension FLOAT32 vector field
        JlsmSchema schema = JlsmSchema.builder("vec-test", 1)
                .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 2)).build();

        MemorySerializer<JlsmDocument> serializer = DocumentSerializer.forSchema(schema);

        // Bypass JlsmDocument.of() validation — 4-element vector, schema expects 2
        float[] longVector = new float[]{ 1.0f, 2.0f, 3.0f, 4.0f };
        Object[] values = new Object[]{ longVector };
        JlsmDocument doc = new JlsmDocument(schema, values, false);

        // Should throw IllegalArgumentException for excess dimensions (silent truncation)
        var ex = assertThrows(IllegalArgumentException.class, () -> serializer.serialize(doc),
                "encodeVector should throw IAE for vector longer than declared dimensions, "
                        + "not silently truncate extra elements");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("dimensions"),
                "Error message should mention 'dimensions' — got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.3.5
    // Bug: encodeField assumes value type matches FieldType — casts without verification,
    // producing raw ClassCastException with no field name or expected-vs-actual type info
    // Correct behavior: serialize should throw IllegalArgumentException identifying the field
    // name and the type mismatch when a value's runtime type doesn't match FieldType
    // Fix location: DocumentSerializer.encodeField / measureField (DocumentSerializer.java:416-448,
    // 536-578)
    // Regression watch: Correctly-typed values must still serialize without error
    @Test
    void test_serializeFieldTypeMismatch_throwsIAEWithFieldContext() {
        // Schema declares a FLOAT32 field
        JlsmSchema schema = JlsmSchema.builder("mismatch-test", 1)
                .field("score", FieldType.Primitive.FLOAT32).build();

        MemorySerializer<JlsmDocument> serializer = DocumentSerializer.forSchema(schema);

        // Bypass JlsmDocument.of() validation by using the package-private constructor
        // to inject an Integer where Float is expected
        Object[] values = new Object[]{ Integer.valueOf(42) };
        JlsmDocument doc = new JlsmDocument(schema, values, false);

        // Bug: raw ClassCastException at (Float) value cast with no field context
        // Correct: IllegalArgumentException identifying the field name and type mismatch
        var ex = assertThrows(IllegalArgumentException.class, () -> serializer.serialize(doc),
                "serialize should throw IAE with field context for type mismatch, "
                        + "not raw ClassCastException from unchecked cast");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("score"),
                "Error message should identify the field name 'score' — got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.3.4
    // Bug: decodeVector has no bounds check on buffer — corrupt data causes raw AIOOBE
    // Correct behavior: Should throw IllegalArgumentException indicating truncated/corrupt vector
    // data
    // Fix location: DocumentSerializer.decodeVector (DocumentSerializer.java:842-859)
    // Regression watch: Ensure valid-length vectors still decode correctly
    @Test
    void test_decodeVector_truncatedBuffer_throwsIAENotAIOOBE() {
        // Schema with a single 4-dimension FLOAT32 vector field (requires 16 bytes of vector data)
        JlsmSchema schema = JlsmSchema.builder("trunc-vec", 1)
                .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 4)).build();

        MemorySerializer<JlsmDocument> serializer = DocumentSerializer.forSchema(schema);

        // Serialize a valid document to get the correct format
        float[] validVector = new float[]{ 1.0f, 2.0f, 3.0f, 4.0f };
        JlsmDocument doc = JlsmDocument.of(schema, "embedding", validVector);
        MemorySegment validSerialized = serializer.serialize(doc);

        // Truncate the serialized form — remove the last 8 bytes so the vector data
        // is incomplete (only 2 of 4 floats present instead of all 4)
        byte[] fullBytes = validSerialized.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        byte[] truncatedBytes = java.util.Arrays.copyOf(fullBytes, fullBytes.length - 8);
        MemorySegment truncated = MemorySegment.ofArray(truncatedBytes);

        // Bug: decodeVector reads d elements without checking buffer bounds,
        // causing raw ArrayIndexOutOfBoundsException
        // Correct: should throw IllegalArgumentException indicating corrupt/truncated data
        var ex = assertThrows(IllegalArgumentException.class,
                () -> serializer.deserialize(truncated),
                "decodeVector should throw IAE for truncated vector data, "
                        + "not raw ArrayIndexOutOfBoundsException");

        assertTrue(ex.getMessage() != null && (ex.getMessage().contains("truncat")
                || ex.getMessage().contains("vector") || ex.getMessage().contains("corrupt")),
                "Error message should indicate data corruption — got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.3.6
    // Bug: Deserialization writeBoolCount derived from current schema, not write-time schema
    // Correct behavior: Deserializer should detect bool count mismatch and throw IAE,
    // or store bool count in the header so it can be read correctly
    // Fix location: DocumentSerializer.SchemaSerializer.deserialize (DocumentSerializer.java:340)
    // Regression watch: Ensure fix does not break valid append-only schema evolution
    @Test
    void test_SchemaSerializer_deserialize_fieldTypeChangeBoolMismatch_throwsIAE() {
        // Schema v1: fields [A:INT32, B:STRING] — 0 boolean fields
        JlsmSchema v1 = JlsmSchema.builder("evolve", 1).field("a", FieldType.Primitive.INT32)
                .field("b", FieldType.Primitive.STRING).build();

        // Serialize a document with v1
        MemorySerializer<JlsmDocument> serV1 = DocumentSerializer.forSchema(v1);
        JlsmDocument doc = JlsmDocument.of(v1, "a", 42, "b", "hello");
        MemorySegment serialized = serV1.serialize(doc);

        // Schema v2: fields [A:INT32, B:BOOLEAN] — field B changed type from STRING to BOOLEAN
        // This is an incompatible schema change (not append-only), but JlsmSchema
        // has no mechanism to prevent it
        JlsmSchema v2 = JlsmSchema.builder("evolve", 2).field("a", FieldType.Primitive.INT32)
                .field("b", FieldType.Primitive.BOOLEAN).build();

        // Deserializer built with v2 — prefixBoolCount[2] = 1 (v2 thinks B is boolean),
        // but v1-serialized data has 0 boolean fields → bool bitmask is absent.
        // The deserializer will try to read a bool bitmask that doesn't exist,
        // misaligning the cursor and causing silent corruption or a raw exception.
        MemorySerializer<JlsmDocument> serV2 = DocumentSerializer.forSchema(v2);

        // The deserializer should detect the incompatibility and throw a descriptive error,
        // not silently corrupt data or throw a raw ArrayIndexOutOfBoundsException
        var ex = assertThrows(IllegalArgumentException.class, () -> serV2.deserialize(serialized),
                "Deserializer should reject data when schema field types changed "
                        + "(bool count mismatch), not silently corrupt");

        assertTrue(
                ex.getMessage() != null
                        && (ex.getMessage().contains("bool") || ex.getMessage().contains("schema")
                                || ex.getMessage().contains("mismatch")
                                || ex.getMessage().contains("corrupt")),
                "Error message should describe the schema evolution incompatibility — got: "
                        + ex.getMessage());
    }

    // Finding: F-R1.cb.4.1 (updated for jlsm-core JSON parser delegation)
    // NaN is not valid JSON — the core parser rejects it at the parse stage
    @Test
    void test_JsonParser_parseVector_nanElement_throwsIAE() {
        JlsmSchema schema = JlsmSchema.builder("vec-test", 1)
                .field("vec", FieldType.vector(FieldType.Primitive.FLOAT32, 3)).build();

        // NaN is not valid JSON — should be rejected
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.fromJson("{\"vec\": [1.0, 2.0, 3.0e999]}", schema),
                "fromJson should reject non-finite vector element values");
    }

    // Finding: F-R1.cb.4.3 (updated for jlsm-core JSON parser delegation)
    @Test
    void test_JsonParser_parsePrimitive_float32OverflowToInfinity_throwsIAE() {
        JlsmSchema schema = JlsmSchema.builder("prim-test", 1)
                .field("val", FieldType.Primitive.FLOAT32).build();

        // 1e999 is valid JSON number text but overflows to Infinity for FLOAT32
        var ex = assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.fromJson("{\"val\": 1e999}", schema),
                "fromJson FLOAT32 should reject values that overflow to Infinity");

        assertTrue(
                ex.getMessage() != null && (ex.getMessage().toLowerCase().contains("finite")
                        || ex.getMessage().toLowerCase().contains("infinity")
                        || ex.getMessage().toLowerCase().contains("overflow")),
                "Error message should mention non-finite/infinity/overflow — got: "
                        + ex.getMessage());
    }

    // Finding: F-R1.cb.4.4 (updated for jlsm-core JSON parser delegation)
    @Test
    void test_JsonParser_parsePrimitive_float16OverflowToInfinity_throwsIAE() {
        JlsmSchema schema = JlsmSchema.builder("f16-prim", 1)
                .field("val", FieldType.Primitive.FLOAT16).build();

        // 1e999 overflows to Infinity for FLOAT16
        var ex = assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.fromJson("{\"val\": 1e999}", schema),
                "fromJson FLOAT16 should reject values that overflow to Infinity");

        assertTrue(
                ex.getMessage() != null && (ex.getMessage().toLowerCase().contains("finite")
                        || ex.getMessage().toLowerCase().contains("infinity")
                        || ex.getMessage().toLowerCase().contains("overflow")),
                "Error message should mention non-finite/infinity/overflow — got: "
                        + ex.getMessage());
    }

    // Finding: F-R1.contract_boundaries.2.2
    // Bug: getLong() missing null check — unboxing NPE without descriptive message
    // Correct behavior: getLong() should throw NullPointerException with "Field 'x' is null"
    // message
    // Fix location: JlsmDocument.getLong (JlsmDocument.java:270)
    // Regression watch: Ensure non-null INT64 and TIMESTAMP fields still return correctly
    @Test
    void test_JlsmDocument_getLong_nullValue_throwsDescriptiveNPE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("ts", FieldType.Primitive.INT64).build();

        // Create document with ts field left null (only set "name")
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice");

        // getLong("ts") should throw NPE with descriptive message like requireValue does,
        // not a bare NPE from auto-unboxing (Long) null -> long
        var ex = assertThrows(NullPointerException.class, () -> doc.getLong("ts"),
                "getLong() on null INT64 field should throw NPE with descriptive message, "
                        + "not bare NPE from auto-unboxing");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("ts"),
                "Error message should identify the field name 'ts' — got: " + ex.getMessage());
    }

    // Finding: F-R1.cb.2.1
    // Bug: Builder collects schema but silently discards it during build — schema field is
    // never passed to the PartitionedTable constructor
    // Correct behavior: Schema set on Builder should be preserved and accessible on the
    // built PartitionedTable via schema() accessor
    // Fix location: PartitionedTable.Builder.build() (PartitionedTable.java) and
    // PartitionedTable constructor
    // Regression watch: Ensure build() without schema still works (schema is optional)
    @Test
    void test_Builder_schemaPreservedAfterBuild() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("test-schema", 1).field("name", FieldType.string())
                .field("age", FieldType.Primitive.INT32).build();

        MemorySegment lowKey = MemorySegment.ofArray("a".getBytes(StandardCharsets.UTF_8));
        MemorySegment highKey = MemorySegment.ofArray("{".getBytes(StandardCharsets.UTF_8));
        PartitionDescriptor desc = new PartitionDescriptor(1L, lowKey, highKey, "local", 0L);
        PartitionConfig config = PartitionConfig.of(List.of(desc));

        // Build with schema set — a no-op PartitionClient is sufficient
        try (PartitionedTable table = PartitionedTable.builder().partitionConfig(config)
                .schema(schema).partitionClientFactory(_ -> new NoOpPartitionClient()).build()) {

            // The schema we set on the builder must be retrievable from the built table
            Optional<JlsmSchema> retrievedSchema = table.schema();
            assertTrue(retrievedSchema.isPresent(),
                    "Schema set on Builder should be preserved in built PartitionedTable — "
                            + "currently silently discarded during build()");
            assertSame(schema, retrievedSchema.get(),
                    "Retrieved schema must be the same instance set on Builder");
        }
    }

    @Test
    void test_Builder_noSchema_returnsEmpty() throws IOException {
        MemorySegment lowKey = MemorySegment.ofArray("a".getBytes(StandardCharsets.UTF_8));
        MemorySegment highKey = MemorySegment.ofArray("{".getBytes(StandardCharsets.UTF_8));
        PartitionDescriptor desc = new PartitionDescriptor(1L, lowKey, highKey, "local", 0L);
        PartitionConfig config = PartitionConfig.of(List.of(desc));

        // Build WITHOUT schema — should still work, schema() returns empty
        try (PartitionedTable table = PartitionedTable.builder().partitionConfig(config)
                .partitionClientFactory(_ -> new NoOpPartitionClient()).build()) {

            Optional<JlsmSchema> retrievedSchema = table.schema();
            assertTrue(retrievedSchema.isEmpty(),
                    "When no schema is set on Builder, schema() should return empty Optional");
        }
    }

    /**
     * Minimal no-op PartitionClient for Builder contract tests.
     */
    private static final class NoOpPartitionClient implements PartitionClient {

        @Override
        public PartitionDescriptor descriptor() {
            return null;
        }

        @Override
        public void doCreate(String key, JlsmDocument doc) {
        }

        @Override
        public Optional<JlsmDocument> doGet(String key) {
            return Optional.empty();
        }

        @Override
        public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) {
        }

        @Override
        public void doDelete(String key) {
        }

        @Override
        public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey) {
            return List.<TableEntry<String>>of().iterator();
        }

        @Override
        public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    // Finding: F-R1.cb.2.5
    // Bug: closeAllClients assert-only null check on cause parameter — with assertions
    // disabled, null cause causes NPE at cause.addSuppressed(e), aborting the cleanup
    // loop and leaking remaining clients
    // Correct behavior: Should throw NullPointerException eagerly with descriptive message
    // before entering the cleanup loop
    // Fix location: PartitionedTable.closeAllClients (PartitionedTable.java:257-266)
    // Regression watch: Ensure normal error-path usage (non-null cause) still works
    @Test
    void test_closeAllClients_nullCause_throwsNPEBeforeLoop() throws Exception {
        // Use reflection to access the private static closeAllClients method
        Method closeAllClients = PartitionedTable.class.getDeclaredMethod("closeAllClients",
                Map.class, Exception.class);
        closeAllClients.setAccessible(true);

        // Create a map with a client that throws on close — if the null cause is not
        // caught eagerly, the loop would NPE at addSuppressed and leak remaining clients
        Map<Long, PartitionClient> clients = new LinkedHashMap<>();
        clients.put(1L, new ThrowingClosePartitionClient());
        clients.put(2L, new ThrowingClosePartitionClient());

        // With assert-only null check, calling with null cause produces:
        // - AssertionError when -ea is on (test environment)
        // - Silent acceptance when -ea is off, then NPE at addSuppressed if any close fails
        // Correct: should throw NullPointerException eagerly identifying 'cause'
        var thrown = assertThrows(InvocationTargetException.class,
                () -> closeAllClients.invoke(null, clients, null));

        // The method must reject null cause with NullPointerException (runtime check),
        // NOT AssertionError (assert-only). AssertionError means the guard is assert-only
        // and vanishes when assertions are disabled in production.
        assertInstanceOf(NullPointerException.class, thrown.getCause(),
                "closeAllClients should reject null cause with NullPointerException (runtime check), "
                        + "not AssertionError (assert-only) — got: "
                        + thrown.getCause().getClass().getSimpleName());
    }

    /**
     * A PartitionClient stub that always throws IOException on close. Used to exercise
     * error-handling paths.
     */
    private static final class ThrowingClosePartitionClient implements PartitionClient {
        @Override
        public PartitionDescriptor descriptor() {
            return null;
        }

        @Override
        public void doCreate(String key, JlsmDocument doc) {
        }

        @Override
        public Optional<JlsmDocument> doGet(String key) {
            return Optional.empty();
        }

        @Override
        public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) {
        }

        @Override
        public void doDelete(String key) {
        }

        @Override
        public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey) {
            return List.<TableEntry<String>>of().iterator();
        }

        @Override
        public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
            return List.of();
        }

        @Override
        public void close() throws IOException {
            throw new IOException("simulated close failure");
        }
    }

    // Finding: F-R1.cb.4.2
    // Bug: InProcessPartitionClient.query() masks null predicate with UnsupportedOperationException
    // Correct behavior: Should throw NullPointerException("predicate must not be null") before
    // the UnsupportedOperationException, consistent with all other methods' null validation
    // Fix location: InProcessPartitionClient.query (InProcessPartitionClient.java:111-114)
    // Regression watch: Ensure non-null predicate still throws UnsupportedOperationException
    @Test
    void test_InProcessPartitionClient_query_nullPredicate_throwsNPE() throws Exception {
        var arena = Arena.ofConfined();
        var lowKey = arena.allocateFrom("a");
        var highKey = arena.allocateFrom("z");
        var descriptor = new PartitionDescriptor(1L, lowKey, highKey, "node-1", 0L);

        // Stub JlsmTable.StringKeyed — query() should never reach the table
        JlsmTable.StringKeyed stubTable = new JlsmTable.StringKeyed() {
            @Override
            public void create(String key, JlsmDocument doc) {
            }

            @Override
            public Optional<JlsmDocument> get(String key) {
                return Optional.empty();
            }

            @Override
            public void update(String key, JlsmDocument doc, UpdateMode mode) {
            }

            @Override
            public void delete(String key) {
            }

            @Override
            public Iterator<TableEntry<String>> getAllInRange(String from, String to) {
                return List.<TableEntry<String>>of().iterator();
            }

            @Override
            public void close() {
            }
        };

        var client = new jlsm.table.internal.InProcessPartitionClient(descriptor, stubTable);

        var ex = assertThrows(NullPointerException.class, () -> client.query(null, 10),
                "query(null, 10) should throw NullPointerException, not UnsupportedOperationException");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("predicate"),
                "Error message should identify 'predicate' — got: " + ex.getMessage());

        arena.close();
    }

    // Finding: F-R1.cb.4.3
    // Bug: PartitionClient.query() limit parameter has no contract for non-positive values —
    // InProcessPartitionClient accepts limit=0 or limit=-1 without validation
    // Correct behavior: Should throw IllegalArgumentException when limit <= 0, before any
    // other exception (including UnsupportedOperationException)
    // Fix location: InProcessPartitionClient.query (InProcessPartitionClient.java:111-117)
    // Regression watch: Ensure positive limit values still reach normal execution path
    @Test
    void test_InProcessPartitionClient_query_nonPositiveLimit_throwsIAE() throws Exception {
        var arena = Arena.ofConfined();
        var lowKey = arena.allocateFrom("a");
        var highKey = arena.allocateFrom("z");
        var descriptor = new PartitionDescriptor(1L, lowKey, highKey, "node-1", 0L);

        JlsmTable.StringKeyed stubTable = new JlsmTable.StringKeyed() {
            @Override
            public void create(String key, JlsmDocument doc) {
            }

            @Override
            public Optional<JlsmDocument> get(String key) {
                return Optional.empty();
            }

            @Override
            public void update(String key, JlsmDocument doc, UpdateMode mode) {
            }

            @Override
            public void delete(String key) {
            }

            @Override
            public Iterator<TableEntry<String>> getAllInRange(String from, String to) {
                return List.<TableEntry<String>>of().iterator();
            }

            @Override
            public void close() {
            }
        };

        var client = new jlsm.table.internal.InProcessPartitionClient(descriptor, stubTable);
        var validPredicate = new Predicate.Eq("name", "Alice");

        // limit = 0 should throw IllegalArgumentException, not UnsupportedOperationException
        var ex0 = assertThrows(IllegalArgumentException.class,
                () -> client.query(validPredicate, 0),
                "query(predicate, 0) should throw IllegalArgumentException for non-positive limit");

        assertTrue(ex0.getMessage() != null && ex0.getMessage().contains("limit"),
                "Error message should identify 'limit' — got: " + ex0.getMessage());

        // limit = -1 should also throw IllegalArgumentException
        var exNeg = assertThrows(IllegalArgumentException.class,
                () -> client.query(validPredicate, -1),
                "query(predicate, -1) should throw IllegalArgumentException for negative limit");

        assertTrue(exNeg.getMessage() != null && exNeg.getMessage().contains("limit"),
                "Error message should identify 'limit' — got: " + exNeg.getMessage());

        arena.close();
    }

    // Finding: F-R1.cb.4.4
    // Bug: PartitionClient.getRange() has no contract for inverted key ranges —
    // calling getRange("z", "a") where fromKey > toKey is undefined behavior
    // Correct behavior: Should throw IllegalArgumentException when fromKey >= toKey
    // Fix location: InProcessPartitionClient.getRange (InProcessPartitionClient.java:96-101)
    // Regression watch: Ensure valid ascending ranges (e.g., "a" to "z") still work
    @Test
    void test_InProcessPartitionClient_getRange_invertedRange_throwsIAE() throws Exception {
        var arena = Arena.ofConfined();
        var lowKey = arena.allocateFrom("a");
        var highKey = arena.allocateFrom("z");
        var descriptor = new PartitionDescriptor(1L, lowKey, highKey, "node-1", 0L);

        JlsmTable.StringKeyed stubTable = new JlsmTable.StringKeyed() {
            @Override
            public void create(String key, JlsmDocument doc) {
            }

            @Override
            public Optional<JlsmDocument> get(String key) {
                return Optional.empty();
            }

            @Override
            public void update(String key, JlsmDocument doc, UpdateMode mode) {
            }

            @Override
            public void delete(String key) {
            }

            @Override
            public Iterator<TableEntry<String>> getAllInRange(String from, String to) {
                return List.<TableEntry<String>>of().iterator();
            }

            @Override
            public void close() {
            }
        };

        var client = new jlsm.table.internal.InProcessPartitionClient(descriptor, stubTable);

        // fromKey > toKey lexicographically — should throw IllegalArgumentException
        var ex = assertThrows(IllegalArgumentException.class, () -> client.getRange("z", "a"),
                "getRange(\"z\", \"a\") should throw IllegalArgumentException for inverted range");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("fromKey"),
                "Error message should identify 'fromKey' — got: " + ex.getMessage());

        // Equal keys (empty range) should also be rejected
        var exEqual = assertThrows(IllegalArgumentException.class, () -> client.getRange("m", "m"),
                "getRange(\"m\", \"m\") should throw IllegalArgumentException for empty range");

        assertTrue(exEqual.getMessage() != null && exEqual.getMessage().contains("fromKey"),
                "Error message should identify 'fromKey' — got: " + exEqual.getMessage());

        arena.close();
    }

    // Finding: F-R1.contract_boundaries.1.8
    // Bug: PartitionClient interface has no null-rejection contract — a bare implementation
    // that omits null checks allows null parameters to propagate silently
    // Correct behavior: The interface should enforce null rejection via default method guards
    // so that ANY implementation rejects null key/doc/mode/predicate
    // Fix location: PartitionClient.java — all abstract CRUD method signatures (lines 38, 47, 58,
    // 66, 76, 91)
    // Regression watch: Ensure InProcessPartitionClient still works correctly with valid inputs
    @Test
    void test_PartitionClient_interface_rejectsNullParameters() throws Exception {
        var arena = Arena.ofConfined();
        var lowKey = arena.allocateFrom("a");
        var highKey = arena.allocateFrom("z");
        var descriptor = new PartitionDescriptor(1L, lowKey, highKey, "node-1", 0L);

        // A bare-bones implementation that does NOT add its own null checks.
        // If the interface enforces null rejection, these will throw NullPointerException
        // before reaching the implementation body.
        PartitionClient bareClient = new PartitionClient() {
            @Override
            public PartitionDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public void doCreate(String key, JlsmDocument doc) {
            }

            @Override
            public Optional<JlsmDocument> doGet(String key) {
                return Optional.empty();
            }

            @Override
            public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) {
            }

            @Override
            public void doDelete(String key) {
            }

            @Override
            public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey) {
                return List.<TableEntry<String>>of().iterator();
            }

            @Override
            public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
                return List.of();
            }

            @Override
            public void close() {
            }
        };

        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        var validDoc = JlsmDocument.of(schema, "name", "Alice");

        // create: null key
        assertThrows(NullPointerException.class, () -> bareClient.create(null, validDoc),
                "PartitionClient.create should reject null key at the interface level");

        // create: null doc
        assertThrows(NullPointerException.class, () -> bareClient.create("key1", null),
                "PartitionClient.create should reject null doc at the interface level");

        // get: null key
        assertThrows(NullPointerException.class, () -> bareClient.get(null),
                "PartitionClient.get should reject null key at the interface level");

        // update: null key
        assertThrows(NullPointerException.class,
                () -> bareClient.update(null, validDoc, UpdateMode.REPLACE),
                "PartitionClient.update should reject null key at the interface level");

        // update: null doc
        assertThrows(NullPointerException.class,
                () -> bareClient.update("key1", null, UpdateMode.REPLACE),
                "PartitionClient.update should reject null doc at the interface level");

        // update: null mode
        assertThrows(NullPointerException.class, () -> bareClient.update("key1", validDoc, null),
                "PartitionClient.update should reject null mode at the interface level");

        // delete: null key
        assertThrows(NullPointerException.class, () -> bareClient.delete(null),
                "PartitionClient.delete should reject null key at the interface level");

        // getRange: null fromKey
        assertThrows(NullPointerException.class, () -> bareClient.getRange(null, "z"),
                "PartitionClient.getRange should reject null fromKey at the interface level");

        // getRange: null toKey
        assertThrows(NullPointerException.class, () -> bareClient.getRange("a", null),
                "PartitionClient.getRange should reject null toKey at the interface level");

        // query: null predicate
        assertThrows(NullPointerException.class, () -> bareClient.query(null, 10),
                "PartitionClient.query should reject null predicate at the interface level");

        arena.close();
    }

    // Finding: F-R1.contract_boundaries.3.3
    // Bug: Inverted range (fromKey > toKey) silently returns empty results
    // Correct behavior: Should throw IllegalArgumentException when fromKey >= toKey
    // Fix location: PartitionedTable.getRange (PartitionedTable.java:136-141)
    // Regression watch: Ensure valid ascending ranges (e.g., "a" to "z") still work
    @Test
    void test_PartitionedTable_getRange_invertedRange_throwsIAE() throws IOException {
        MemorySegment lowKey = MemorySegment.ofArray("a".getBytes(StandardCharsets.UTF_8));
        MemorySegment highKey = MemorySegment.ofArray("{".getBytes(StandardCharsets.UTF_8));
        PartitionDescriptor desc = new PartitionDescriptor(1L, lowKey, highKey, "local", 0L);
        PartitionConfig config = PartitionConfig.of(List.of(desc));

        try (PartitionedTable table = PartitionedTable.builder().partitionConfig(config)
                .partitionClientFactory(_ -> new NoOpPartitionClient()).build()) {

            // fromKey > toKey lexicographically — should throw IllegalArgumentException
            var ex = assertThrows(IllegalArgumentException.class, () -> table.getRange("z", "a"),
                    "getRange(\"z\", \"a\") should throw IllegalArgumentException for inverted range");

            assertTrue(ex.getMessage() != null && ex.getMessage().contains("fromKey"),
                    "Error message should identify 'fromKey' — got: " + ex.getMessage());

            // Equal keys (empty range) should also be rejected
            assertThrows(IllegalArgumentException.class, () -> table.getRange("m", "m"),
                    "getRange(\"m\", \"m\") should throw IllegalArgumentException for equal keys");
        }
    }

    // Finding: F-R1.contract_boundaries.3.5
    // Bug: Null ScoredEntry elements in partition lists cause NPE in mergeTopK comparator
    // Correct behavior: mergeTopK should throw NullPointerException with descriptive message
    // when a partition list contains a null ScoredEntry element
    // Fix location: ResultMerger.mergeTopK null element check before heap.addAll
    // (ResultMerger.java:74-78)
    // Regression watch: Ensure fix does not reject valid non-null ScoredEntry elements
    @Test
    void test_mergeTopK_nullScoredEntryElement_throwsDescriptiveNPE() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice");
        ScoredEntry<String> valid = new ScoredEntry<>("k1", doc, 0.9);

        // Partition list containing a null element — Arrays.asList allows nulls
        List<ScoredEntry<String>> partitionWithNull = Arrays.asList(valid, null);
        List<List<ScoredEntry<String>>> partitions = List.of(partitionWithNull);

        var ex = assertThrows(NullPointerException.class,
                () -> ResultMerger.mergeTopK(partitions, 5),
                "mergeTopK should reject null ScoredEntry elements within partition lists");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("element"),
                "Error message should identify null 'element' — got: " + ex.getMessage());
    }

}
