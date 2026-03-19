package jlsm.table.internal;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jlsm.encryption.EncryptionSpec;
import jlsm.table.FieldDefinition;
import jlsm.table.FieldType;
import jlsm.table.IndexDefinition;
import jlsm.table.IndexType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.Predicate;

/**
 * Manages all secondary indices for a table. Routes write operations to the appropriate indices and
 * provides index lookup for query execution.
 */
public final class IndexRegistry implements Closeable {

    private final JlsmSchema schema;
    private final List<SecondaryIndex> indices;
    private final Map<PkKey, StoredEntry> documentStore;
    private volatile boolean closed;

    public IndexRegistry(JlsmSchema schema, List<IndexDefinition> definitions) throws IOException {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(definitions, "definitions");
        this.schema = schema;
        this.indices = new ArrayList<>();
        this.documentStore = new LinkedHashMap<>();

        for (IndexDefinition def : definitions) {
            validate(schema, def);
            this.indices.add(createIndex(def));
        }
    }

    public void onInsert(MemorySegment primaryKey, JlsmDocument document) throws IOException {
        assert !closed : "Registry is closed";
        Objects.requireNonNull(primaryKey, "primaryKey");
        Objects.requireNonNull(document, "document");

        // Unique checks first
        for (final SecondaryIndex idx : indices) {
            if (idx.definition().indexType() == IndexType.UNIQUE) {
                final Object fieldValue = extractFieldValue(document, idx.definition().fieldName());
                idx.onInsert(primaryKey, fieldValue);
            }
        }
        // Non-unique inserts
        for (final SecondaryIndex idx : indices) {
            if (idx.definition().indexType() != IndexType.UNIQUE) {
                final Object fieldValue = extractFieldValue(document, idx.definition().fieldName());
                idx.onInsert(primaryKey, fieldValue);
            }
        }

        documentStore.put(toPkKey(primaryKey), new StoredEntry(copySegment(primaryKey), document));
    }

    public void onUpdate(MemorySegment primaryKey, JlsmDocument oldDocument,
            JlsmDocument newDocument) throws IOException {
        assert !closed : "Registry is closed";
        for (final SecondaryIndex idx : indices) {
            final String fieldName = idx.definition().fieldName();
            final Object oldValue = oldDocument != null ? extractFieldValue(oldDocument, fieldName)
                    : null;
            final Object newValue = extractFieldValue(newDocument, fieldName);
            idx.onUpdate(primaryKey, oldValue, newValue);
        }

        documentStore.put(toPkKey(primaryKey),
                new StoredEntry(copySegment(primaryKey), newDocument));
    }

    public void onDelete(MemorySegment primaryKey, JlsmDocument document) throws IOException {
        assert !closed : "Registry is closed";
        for (final SecondaryIndex idx : indices) {
            final Object fieldValue = extractFieldValue(document, idx.definition().fieldName());
            idx.onDelete(primaryKey, fieldValue);
        }

        documentStore.remove(toPkKey(primaryKey));
    }

    public SecondaryIndex findIndex(Predicate predicate) {
        for (SecondaryIndex idx : indices) {
            if (idx.supports(predicate)) {
                return idx;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return indices.isEmpty();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        IOException deferred = null;
        for (SecondaryIndex idx : indices) {
            try {
                idx.close();
            } catch (IOException e) {
                if (deferred == null) {
                    deferred = e;
                } else {
                    deferred.addSuppressed(e);
                }
            }
        }
        if (deferred != null) {
            throw deferred;
        }
    }

    /**
     * Resolves a primary key to its stored document, or null if not found.
     */
    public StoredEntry resolveEntry(MemorySegment primaryKey) {
        return documentStore.get(toPkKey(primaryKey));
    }

    /**
     * Returns an iterator over all stored entries. Used for scan-and-filter queries.
     */
    public Iterator<StoredEntry> allEntries() {
        return List.copyOf(documentStore.values()).iterator();
    }

    /**
     * Returns the schema this registry was created with.
     */
    public JlsmSchema schema() {
        return schema;
    }

    // ── Document store types ────────────────────────────────────────────

    /**
     * A stored entry associating a primary key segment with its document.
     */
    public record StoredEntry(MemorySegment primaryKey, JlsmDocument document) {
        public StoredEntry {
            assert primaryKey != null : "primaryKey must not be null";
            assert document != null : "document must not be null";
        }
    }

    /**
     * Byte-array wrapper with content-based equals/hashCode for use as map keys.
     */
    private record PkKey(byte[] data) {
        @Override
        public boolean equals(Object obj) {
            return obj instanceof PkKey other && Arrays.equals(this.data, other.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }

    private static PkKey toPkKey(MemorySegment seg) {
        return new PkKey(seg.toArray(ValueLayout.JAVA_BYTE));
    }

    private static MemorySegment copySegment(MemorySegment src) {
        MemorySegment copy = Arena.ofAuto().allocate(src.byteSize());
        copy.copyFrom(src);
        return copy;
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private static void validate(JlsmSchema schema, IndexDefinition def) {
        final int fieldIdx = schema.fieldIndex(def.fieldName());
        if (fieldIdx < 0) {
            throw new IllegalArgumentException("Field '" + def.fieldName()
                    + "' does not exist in schema '" + schema.name() + "'");
        }

        final FieldDefinition fieldDef = schema.fields().get(fieldIdx);
        final FieldType fieldType = fieldDef.type();

        final boolean isPrimitiveOrBoundedString = fieldType instanceof FieldType.Primitive
                || fieldType instanceof FieldType.BoundedString;

        switch (def.indexType()) {
            case RANGE, UNIQUE -> {
                if (fieldType == FieldType.Primitive.BOOLEAN) {
                    throw new IllegalArgumentException(
                            "RANGE/UNIQUE index is not supported on BOOLEAN field '"
                                    + def.fieldName() + "'");
                }
                if (!isPrimitiveOrBoundedString) {
                    throw new IllegalArgumentException(
                            "RANGE/UNIQUE index requires a primitive field type, got: " + fieldType
                                    + " for field '" + def.fieldName() + "'");
                }
            }
            case EQUALITY -> {
                if (!isPrimitiveOrBoundedString) {
                    throw new IllegalArgumentException(
                            "EQUALITY index requires a primitive field type, got: " + fieldType
                                    + " for field '" + def.fieldName() + "'");
                }
            }
            case FULL_TEXT -> {
                if (fieldType != FieldType.Primitive.STRING
                        && !(fieldType instanceof FieldType.BoundedString)) {
                    throw new IllegalArgumentException(
                            "FULL_TEXT index requires STRING field, got: " + fieldType
                                    + " for field '" + def.fieldName() + "'");
                }
            }
            case VECTOR -> {
                if (!(fieldType instanceof FieldType.VectorType)) {
                    throw new IllegalArgumentException(
                            "VECTOR index requires VectorType field, got: " + fieldType
                                    + " for field '" + def.fieldName() + "'");
                }
            }
        }

        // Validate OrderPreserving × field type compatibility
        validateOrderPreservingFieldType(fieldDef, def);

        // Validate encryption × index compatibility using the capability matrix
        validateEncryptionCompatibility(fieldDef, def);
    }

    /**
     * Validates that OrderPreserving encryption is only used on compatible field types.
     * OrderPreserving requires a numeric primitive (INT8, INT16, INT32, INT64, TIMESTAMP) or a
     * BoundedString. Unbounded STRING, BOOLEAN, FLOAT*, VECTOR, ARRAY, OBJECT are rejected.
     */
    private static void validateOrderPreservingFieldType(FieldDefinition fieldDef,
            IndexDefinition def) {
        if (!(fieldDef.encryption() instanceof EncryptionSpec.OrderPreserving)) {
            return; // only relevant for OrderPreserving
        }

        final FieldType fieldType = fieldDef.type();
        final String fieldName = def.fieldName();

        if (fieldType instanceof FieldType.BoundedString) {
            return; // allowed
        }
        if (fieldType instanceof FieldType.Primitive p) {
            switch (p) {
                case INT8, INT16, INT32, INT64, TIMESTAMP -> {
                    /* allowed */ }
                case STRING -> throw new IllegalArgumentException(
                        "OrderPreserving encryption on unbounded STRING field '" + fieldName
                                + "' is not supported; use FieldType.string(maxLength) for bounded string");
                case BOOLEAN, FLOAT16, FLOAT32, FLOAT64 ->
                    throw new IllegalArgumentException("OrderPreserving encryption on " + p
                            + " field '" + fieldName + "' is not supported");
            }
            return;
        }
        throw new IllegalArgumentException("OrderPreserving encryption on " + fieldType + " field '"
                + fieldName + "' is not supported");
    }

    /**
     * Validates that the field's encryption specification is compatible with the requested index
     * type. Uses the capability methods on {@link jlsm.table.EncryptionSpec} to check support.
     *
     * @throws IllegalArgumentException if the encryption spec does not support the index type
     */
    private static void validateEncryptionCompatibility(FieldDefinition fieldDef,
            IndexDefinition def) {
        final EncryptionSpec encryption = fieldDef.encryption();
        assert encryption != null : "encryption must not be null (FieldDefinition enforces this)";

        final String fieldName = def.fieldName();
        switch (def.indexType()) {
            case EQUALITY, UNIQUE -> {
                if (!encryption.supportsEquality()) {
                    throw new IllegalArgumentException(def.indexType() + " index on field '"
                            + fieldName + "' is incompatible with "
                            + encryption.getClass().getSimpleName()
                            + " encryption (does not support equality)");
                }
            }
            case RANGE -> {
                if (!encryption.supportsRange()) {
                    throw new IllegalArgumentException("RANGE index on field '" + fieldName
                            + "' is incompatible with " + encryption.getClass().getSimpleName()
                            + " encryption (does not support range queries)");
                }
            }
            case FULL_TEXT -> {
                if (!encryption.supportsKeywordSearch()) {
                    throw new IllegalArgumentException("FULL_TEXT index on field '" + fieldName
                            + "' is incompatible with " + encryption.getClass().getSimpleName()
                            + " encryption (does not support keyword search)");
                }
            }
            case VECTOR -> {
                if (!encryption.supportsANN()) {
                    throw new IllegalArgumentException("VECTOR index on field '" + fieldName
                            + "' is incompatible with " + encryption.getClass().getSimpleName()
                            + " encryption (does not support ANN)");
                }
            }
        }
    }

    private static SecondaryIndex createIndex(IndexDefinition def) throws IOException {
        return switch (def.indexType()) {
            case EQUALITY, RANGE, UNIQUE -> new FieldIndex(def);
            case FULL_TEXT -> new FullTextFieldIndex(def);
            case VECTOR -> new VectorFieldIndex(def);
        };
    }

    private Object extractFieldValue(JlsmDocument document, String fieldName) {
        if (document.isNull(fieldName)) {
            return null;
        }
        final int idx = schema.fieldIndex(fieldName);
        assert idx >= 0 : "Field must exist — validated at construction";
        final FieldDefinition fieldDef = schema.fields().get(idx);
        final FieldType fieldType = fieldDef.type();

        if (fieldType instanceof FieldType.Primitive p) {
            return switch (p) {
                case STRING -> document.getString(fieldName);
                case INT8 -> document.getByte(fieldName);
                case INT16 -> document.getShort(fieldName);
                case INT32 -> document.getInt(fieldName);
                case INT64 -> document.getLong(fieldName);
                case FLOAT16 -> document.getFloat16Bits(fieldName);
                case FLOAT32 -> document.getFloat(fieldName);
                case FLOAT64 -> document.getDouble(fieldName);
                case BOOLEAN -> document.getBoolean(fieldName);
                case TIMESTAMP -> document.getTimestamp(fieldName);
            };
        } else if (fieldType instanceof FieldType.BoundedString) {
            return document.getString(fieldName);
        } else if (fieldType instanceof FieldType.ArrayType) {
            return document.getArray(fieldName);
        } else if (fieldType instanceof FieldType.VectorType) {
            return DocumentAccess.get().values(document)[idx];
        } else if (fieldType instanceof FieldType.ObjectType) {
            return document.getObject(fieldName);
        }
        return null;
    }
}
