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
        for (SecondaryIndex idx : indices) {
            if (idx.definition().indexType() == IndexType.UNIQUE) {
                Object fieldValue = extractFieldValue(document, idx.definition().fieldName());
                idx.onInsert(primaryKey, fieldValue);
            }
        }
        // Non-unique inserts
        for (SecondaryIndex idx : indices) {
            if (idx.definition().indexType() != IndexType.UNIQUE) {
                Object fieldValue = extractFieldValue(document, idx.definition().fieldName());
                idx.onInsert(primaryKey, fieldValue);
            }
        }

        documentStore.put(toPkKey(primaryKey), new StoredEntry(copySegment(primaryKey), document));
    }

    public void onUpdate(MemorySegment primaryKey, JlsmDocument oldDocument,
            JlsmDocument newDocument) throws IOException {
        assert !closed : "Registry is closed";
        for (SecondaryIndex idx : indices) {
            String fieldName = idx.definition().fieldName();
            Object oldValue = oldDocument != null ? extractFieldValue(oldDocument, fieldName)
                    : null;
            Object newValue = extractFieldValue(newDocument, fieldName);
            idx.onUpdate(primaryKey, oldValue, newValue);
        }

        documentStore.put(toPkKey(primaryKey),
                new StoredEntry(copySegment(primaryKey), newDocument));
    }

    public void onDelete(MemorySegment primaryKey, JlsmDocument document) throws IOException {
        assert !closed : "Registry is closed";
        for (SecondaryIndex idx : indices) {
            Object fieldValue = extractFieldValue(document, idx.definition().fieldName());
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
        int fieldIdx = schema.fieldIndex(def.fieldName());
        if (fieldIdx < 0) {
            throw new IllegalArgumentException("Field '" + def.fieldName()
                    + "' does not exist in schema '" + schema.name() + "'");
        }

        FieldDefinition fieldDef = schema.fields().get(fieldIdx);
        FieldType fieldType = fieldDef.type();

        switch (def.indexType()) {
            case RANGE, UNIQUE -> {
                if (fieldType == FieldType.Primitive.BOOLEAN) {
                    throw new IllegalArgumentException(
                            "RANGE/UNIQUE index is not supported on BOOLEAN field '"
                                    + def.fieldName() + "'");
                }
                if (!(fieldType instanceof FieldType.Primitive)) {
                    throw new IllegalArgumentException(
                            "RANGE/UNIQUE index requires a primitive field type, got: " + fieldType
                                    + " for field '" + def.fieldName() + "'");
                }
            }
            case EQUALITY -> {
                if (!(fieldType instanceof FieldType.Primitive)) {
                    throw new IllegalArgumentException(
                            "EQUALITY index requires a primitive field type, got: " + fieldType
                                    + " for field '" + def.fieldName() + "'");
                }
            }
            case FULL_TEXT -> {
                if (fieldType != FieldType.Primitive.STRING) {
                    throw new IllegalArgumentException(
                            "FULL_TEXT index requires STRING field, got: " + fieldType
                                    + " for field '" + def.fieldName() + "'");
                }
            }
            case VECTOR -> {
                if (!(fieldType instanceof FieldType.ArrayType at
                        && (at.elementType() == FieldType.Primitive.FLOAT32
                                || at.elementType() == FieldType.Primitive.FLOAT16))) {
                    throw new IllegalArgumentException(
                            "VECTOR index requires ArrayType(FLOAT32) or "
                                    + "ArrayType(FLOAT16) field, got: " + fieldType + " for field '"
                                    + def.fieldName() + "'");
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
        int idx = schema.fieldIndex(fieldName);
        assert idx >= 0 : "Field must exist — validated at construction";
        FieldDefinition fieldDef = schema.fields().get(idx);
        FieldType fieldType = fieldDef.type();

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
        } else if (fieldType instanceof FieldType.ArrayType) {
            return document.getArray(fieldName);
        } else if (fieldType instanceof FieldType.ObjectType) {
            return document.getObject(fieldName);
        }
        return null;
    }
}
