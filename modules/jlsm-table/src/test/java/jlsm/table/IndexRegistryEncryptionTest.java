package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;

import jlsm.core.indexing.SimilarityFunction;
import jlsm.encryption.EncryptionSpec;
import jlsm.table.internal.IndexRegistry;
import org.junit.jupiter.api.Test;

class IndexRegistryEncryptionTest {

    // ── EQUALITY index on Deterministic field → allowed ─────────────────

    @Test
    void equalityIndex_deterministicField_allowed() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.deterministic()).build();

        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);
        assertFalse(registry.isEmpty());
        registry.close();
    }

    // ── EQUALITY index on Opaque field → throws IAE ─────────────────────

    @Test
    void equalityIndex_opaqueField_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("secret", FieldType.string(), EncryptionSpec.opaque()).build();

        var defs = List.of(new IndexDefinition("secret", IndexType.EQUALITY));

        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs),
                "EQUALITY index on opaque field should throw");
    }

    // ── RANGE index on OrderPreserving field → allowed ──────────────────

    @Test
    void rangeIndex_orderPreservingField_allowed() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("score", FieldType.Primitive.INT16, EncryptionSpec.orderPreserving())
                .build();

        var defs = List.of(new IndexDefinition("score", IndexType.RANGE));
        var registry = new IndexRegistry(schema, defs);
        assertFalse(registry.isEmpty());
        registry.close();
    }

    // ── RANGE index on Deterministic field → throws IAE ─────────────────

    @Test
    void rangeIndex_deterministicField_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.deterministic()).build();

        var defs = List.of(new IndexDefinition("name", IndexType.RANGE));

        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs),
                "RANGE index on deterministic field should throw");
    }

    // ── UNIQUE index on OrderPreserving field → allowed ─────────────────

    @Test
    void uniqueIndex_orderPreservingField_allowed() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("id", FieldType.Primitive.INT16, EncryptionSpec.orderPreserving()).build();

        var defs = List.of(new IndexDefinition("id", IndexType.UNIQUE));
        var registry = new IndexRegistry(schema, defs);
        assertFalse(registry.isEmpty());
        registry.close();
    }

    // ── UNIQUE index on Opaque field → throws IAE ───────────────────────

    @Test
    void uniqueIndex_opaqueField_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("id", FieldType.int64(), EncryptionSpec.opaque()).build();

        var defs = List.of(new IndexDefinition("id", IndexType.UNIQUE));

        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs),
                "UNIQUE index on opaque field should throw");
    }

    // ── VECTOR index on DistancePreserving field → allowed ──────────────

    @Test
    void vectorIndex_distancePreservingField_allowed() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 4),
                        EncryptionSpec.distancePreserving())
                .build();

        var defs = List
                .of(new IndexDefinition("embedding", IndexType.VECTOR, SimilarityFunction.COSINE));
        var registry = new IndexRegistry(schema, defs, null,
                jlsm.table.internal.InMemoryVectorFactories.ivfFlatFake());
        assertFalse(registry.isEmpty());
        registry.close();
    }

    // ── VECTOR index on Opaque field → throws IAE ───────────────────────

    @Test
    void vectorIndex_opaqueField_throws() {
        JlsmSchema schema = JlsmSchema
                .builder("test", 1).field("embedding",
                        FieldType.vector(FieldType.Primitive.FLOAT32, 4), EncryptionSpec.opaque())
                .build();

        var defs = List
                .of(new IndexDefinition("embedding", IndexType.VECTOR, SimilarityFunction.COSINE));

        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs),
                "VECTOR index on opaque field should throw");
    }

    // ── FULL_TEXT index on Deterministic field → allowed ─────────────────

    // Updated by WD-01 (cross-module-integration): FullTextFieldIndex is no longer a stub —
    // it delegates to a real FullTextIndex obtained from an injected FullTextIndex.Factory.
    // When no factory is supplied at construction, IndexRegistry fails fast with
    // IllegalArgumentException rather than deferring the failure to the first write.
    // Encryption validation still passes for Deterministic fields; we verify the missing-factory
    // IAE is the reason for rejection, not an encryption validation failure.
    @Test
    void fullTextIndex_deterministicField_allowed() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("bio", FieldType.string(), EncryptionSpec.deterministic()).build();

        var defs = List.of(new IndexDefinition("bio", IndexType.FULL_TEXT));
        var ex = assertThrows(IllegalArgumentException.class,
                () -> new IndexRegistry(schema, defs));
        assertTrue(ex.getMessage().contains("FullTextIndex.Factory"),
                "Message should explain the missing factory, got: " + ex.getMessage());
    }

    // ── FULL_TEXT index on Opaque field → throws IAE ─────────────────────

    @Test
    void fullTextIndex_opaqueField_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("bio", FieldType.string(), EncryptionSpec.opaque()).build();

        var defs = List.of(new IndexDefinition("bio", IndexType.FULL_TEXT));

        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs),
                "FULL_TEXT index on opaque field should throw");
    }

    // ── Index on unencrypted field → allowed (same as before) ───────────

    @Test
    void index_unencryptedField_allowed() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();

        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY),
                new IndexDefinition("age", IndexType.RANGE));
        var registry = new IndexRegistry(schema, defs);
        assertFalse(registry.isEmpty());
        registry.close();
    }

    // ── EQUALITY index on OrderPreserving → allowed (supports equality) ─

    @Test
    void equalityIndex_orderPreservingField_allowed() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("rank", FieldType.Primitive.INT16, EncryptionSpec.orderPreserving()).build();

        var defs = List.of(new IndexDefinition("rank", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);
        assertFalse(registry.isEmpty());
        registry.close();
    }
}
