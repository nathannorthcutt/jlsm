package jlsm.table.internal;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jlsm.core.indexing.FullTextIndex;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.indexing.VectorPrecision;
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
// @spec query.index-registry.R1 — final class in jlsm.table.internal implementing Closeable
// @spec query.index-registry.R13 — maintains document store mapping primary keys to documents
// @spec query.query-executor.R21 — AtomicBoolean closed flag with compareAndSet guarantees single close winner
// @spec vector.field-type.R18,R19,R20,R21 — rejects VECTOR index on non-VectorType field with IAE
// @spec vector.field-type.R22 — accepts VECTOR index on VectorType field
// @spec vector.field-type.R23 — vector dimensions derive from schema's VectorType, not IndexDefinition
public final class IndexRegistry implements Closeable {

    private final JlsmSchema schema;
    private final List<SecondaryIndex> indices;
    private final Map<PkKey, StoredEntry> documentStore;
    private final Arena segmentArena;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    // @spec query.index-registry.R2 — accept JlsmSchema and List<IndexDefinition>; validate each against schema
    public IndexRegistry(JlsmSchema schema, List<IndexDefinition> definitions) throws IOException {
        this(schema, definitions, null);
    }

    /**
     * Constructs an IndexRegistry with an optional {@link FullTextIndex.Factory} for building
     * FULL_TEXT indices. If any definition in {@code definitions} has index type FULL_TEXT and the
     * factory is {@code null}, construction fails fast with {@link IllegalArgumentException} so
     * mis-wiring surfaces at table creation rather than on the first write. Delegates to the 4-arg
     * constructor with {@code vectorFactory = null}; this overload is retained for callers that do
     * not register VECTOR indices.
     *
     * @param schema the table schema; must not be null
     * @param definitions the index definitions; must not be null
     * @param fullTextFactory the factory for FULL_TEXT indices; may be null if no FULL_TEXT
     *            definitions are present
     * @throws IOException if an index fails to initialise
     */
    public IndexRegistry(JlsmSchema schema, List<IndexDefinition> definitions,
            FullTextIndex.Factory fullTextFactory) throws IOException {
        this(schema, definitions, fullTextFactory, null);
    }

    /**
     * Constructs an IndexRegistry with optional factories for both FULL_TEXT and VECTOR indices. If
     * any definition in {@code definitions} has index type FULL_TEXT and {@code fullTextFactory} is
     * {@code null}, or index type VECTOR and {@code vectorFactory} is {@code null}, construction
     * fails fast with {@link IllegalArgumentException} so mis-wiring surfaces at table creation
     * rather than on the first write.
     *
     * @param schema the table schema; must not be null
     * @param definitions the index definitions; must not be null
     * @param fullTextFactory the factory for FULL_TEXT indices; may be null if no FULL_TEXT
     *            definitions are present
     * @param vectorFactory the factory for VECTOR indices; may be null if no VECTOR definitions are
     *            present
     * @throws IOException if an index fails to initialise
     */
    // @spec query.index-registry.R2 — schema-validated definitions; factories are injected so FULL_TEXT
    // resolves OBL-F10-fulltext via LsmFullTextIndex in jlsm-indexing and VECTOR resolves
    // OBL-F10-vector via LsmVectorIndex in jlsm-vector
    public IndexRegistry(JlsmSchema schema, List<IndexDefinition> definitions,
            FullTextIndex.Factory fullTextFactory, VectorIndex.Factory vectorFactory)
            throws IOException {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(definitions, "definitions");
        this.schema = schema;
        this.indices = new ArrayList<>();
        this.documentStore = new ConcurrentHashMap<>();
        this.segmentArena = Arena.ofShared();

        for (IndexDefinition def : definitions) {
            validate(schema, def);
            if (def.indexType() == IndexType.FULL_TEXT && fullTextFactory == null) {
                // Clean up already-created indices and the arena before failing.
                closePartial(null);
                throw new IllegalArgumentException("FULL_TEXT index on field '" + def.fieldName()
                        + "' requires a FullTextIndex.Factory — pass one to the table builder");
            }
            if (def.indexType() == IndexType.VECTOR && vectorFactory == null) {
                closePartial(null);
                throw new IllegalArgumentException("VECTOR index on field '" + def.fieldName()
                        + "' requires a VectorIndex.Factory — pass one to the table builder");
            }
            final int fieldIdx = schema.fieldIndex(def.fieldName());
            final FieldType fieldType = schema.fields().get(fieldIdx).type();
            try {
                this.indices.add(
                        createIndex(def, fieldType, schema.name(), fullTextFactory, vectorFactory));
            } catch (Exception e) {
                // Close the managed arena before propagating the failure.
                segmentArena.close();
                // Close already-created indices before propagating the failure.
                if (!this.indices.isEmpty()) {
                    final int cleanedUp = this.indices.size();
                    for (SecondaryIndex idx : this.indices) {
                        try {
                            idx.close();
                        } catch (IOException suppressed) {
                            e.addSuppressed(suppressed);
                        }
                    }
                    this.indices.clear();
                    if (e instanceof IOException ioe) {
                        throw ioe;
                    }
                    throw new IOException("Index creation failed; cleaned up " + cleanedUp
                            + " already-created indices", e);
                }
                if (e instanceof IOException ioe) {
                    throw ioe;
                }
                throw new IOException("Index creation failed", e);
            }
        }
    }

    // @spec query.field-index.R24,R26 — two-phase: validate all unique constraints across indices (skip
    // @spec query.index-registry.R8 — two-phase: validate all unique constraints across indices (skip
    // @spec query.query-executor.R1 — two-phase: validate all unique constraints across indices (skip
    // null),
    // then apply inserts with rollback on failure
    public void onInsert(MemorySegment primaryKey, JlsmDocument document) throws IOException {
        rwLock.readLock().lock();
        try {
            if (closed.get())
                throw new IllegalStateException("Registry is closed");
            Objects.requireNonNull(primaryKey, "primaryKey");
            Objects.requireNonNull(document, "document");

            // Phase 1: validate ALL unique constraints before any mutation.
            // This prevents orphan entries if the Nth unique check fails after
            // the first N-1 unique indices already inserted.
            for (final SecondaryIndex idx : indices) {
                if (idx instanceof FieldIndex fi
                        && fi.definition().indexType() == IndexType.UNIQUE) {
                    final Object fieldValue = extractFieldValue(document,
                            fi.definition().fieldName());
                    fi.checkUnique(fieldValue);
                }
            }

            // Phase 2: all unique checks passed — now insert into all indices.
            // Track how many indices we've inserted into so we can roll back on failure.
            int insertedCount = 0;
            try {
                for (final SecondaryIndex idx : indices) {
                    final Object fieldValue = extractFieldValue(document,
                            idx.definition().fieldName());
                    idx.onInsert(primaryKey, fieldValue);
                    insertedCount++;
                }
                // documentStore.put must be inside the rollback scope — if copySegment or
                // toPkKey throws after indices are updated, the rollback removes orphaned
                // index entries that would otherwise reference a missing documentStore entry.
                documentStore.put(toPkKey(primaryKey),
                        new StoredEntry(copySegment(primaryKey), document));
            } catch (IOException | RuntimeException e) {
                // Roll back indices that already received the insert
                for (int i = 0; i < insertedCount; i++) {
                    try {
                        final SecondaryIndex idx = indices.get(i);
                        final Object fieldValue = extractFieldValue(document,
                                idx.definition().fieldName());
                        idx.onDelete(primaryKey, fieldValue);
                    } catch (IOException | RuntimeException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                }
                throw e;
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // @spec query.field-index.R25,R26 — two-phase unique check on changed values; rollback scope
    // @spec query.index-registry.R9,R20 — two-phase unique check on changed values; rollback scope
    // @spec query.query-executor.R2 — two-phase unique check on changed values; rollback scope
    // wraps documentStore mutation
    public void onUpdate(MemorySegment primaryKey, JlsmDocument oldDocument,
            JlsmDocument newDocument) throws IOException {
        rwLock.readLock().lock();
        try {
            if (closed.get())
                throw new IllegalStateException("Registry is closed");

            // Phase 1: validate ALL unique constraints for the new values before any mutation.
            // This prevents partial updates if the Nth unique check fails after the first
            // N-1 indices have already been updated.
            for (final SecondaryIndex idx : indices) {
                if (idx instanceof FieldIndex fi
                        && fi.definition().indexType() == IndexType.UNIQUE) {
                    final String fieldName = fi.definition().fieldName();
                    final Object oldValue = oldDocument != null
                            ? extractFieldValue(oldDocument, fieldName)
                            : null;
                    final Object newValue = extractFieldValue(newDocument, fieldName);
                    // Only check uniqueness if the value actually changed
                    if (newValue != null && !newValue.equals(oldValue)) {
                        fi.checkUnique(newValue);
                    }
                }
            }

            // Phase 2: all unique checks passed — now apply updates to all indices.
            // Track how many indices we've updated so we can roll back on failure.
            int updatedCount = 0;
            try {
                for (final SecondaryIndex idx : indices) {
                    final String fieldName = idx.definition().fieldName();
                    final Object oldValue = oldDocument != null
                            ? extractFieldValue(oldDocument, fieldName)
                            : null;
                    final Object newValue = extractFieldValue(newDocument, fieldName);
                    idx.onUpdate(primaryKey, oldValue, newValue);
                    updatedCount++;
                }
                // documentStore.put must be inside the rollback scope — if copySegment or
                // toPkKey throws after indices are updated, the rollback reverses orphaned
                // index entries that would otherwise reference stale documentStore data.
                documentStore.put(toPkKey(primaryKey),
                        new StoredEntry(copySegment(primaryKey), newDocument));
            } catch (IOException | RuntimeException e) {
                // Roll back indices that already received the update by reversing the operation
                for (int i = 0; i < updatedCount; i++) {
                    try {
                        final SecondaryIndex idx = indices.get(i);
                        final String fieldName = idx.definition().fieldName();
                        final Object oldValue = oldDocument != null
                                ? extractFieldValue(oldDocument, fieldName)
                                : null;
                        final Object newValue = extractFieldValue(newDocument, fieldName);
                        idx.onUpdate(primaryKey, newValue, oldValue);
                    } catch (IOException | RuntimeException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                }
                throw e;
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // @spec query.index-registry.R10,R20 — route delete to all indices; rollback scope wraps documentStore
    // @spec query.query-executor.R3 — route delete to all indices; rollback scope wraps documentStore
    // mutation
    public void onDelete(MemorySegment primaryKey, JlsmDocument document) throws IOException {
        rwLock.readLock().lock();
        try {
            if (closed.get())
                throw new IllegalStateException("Registry is closed");

            // Track how many indices we've deleted from so we can roll back on failure.
            int deletedCount = 0;
            try {
                for (final SecondaryIndex idx : indices) {
                    final Object fieldValue = extractFieldValue(document,
                            idx.definition().fieldName());
                    idx.onDelete(primaryKey, fieldValue);
                    deletedCount++;
                }
                // documentStore.remove must be inside the rollback scope — if toPkKey
                // or remove throws after indices are updated, the rollback re-inserts
                // orphaned index entries that would otherwise reference a still-present
                // documentStore entry.
                documentStore.remove(toPkKey(primaryKey));
            } catch (IOException | RuntimeException e) {
                // Roll back indices that already received the delete by re-inserting
                for (int i = 0; i < deletedCount; i++) {
                    try {
                        final SecondaryIndex idx = indices.get(i);
                        final Object fieldValue = extractFieldValue(document,
                                idx.definition().fieldName());
                        idx.onInsert(primaryKey, fieldValue);
                    } catch (IOException | RuntimeException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                }
                throw e;
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // @spec query.index-registry.R11,R19 — return first SecondaryIndex supporting predicate, or null; acquire read
    // lock before check
    public SecondaryIndex findIndex(Predicate predicate) {
        rwLock.readLock().lock();
        try {
            if (closed.get())
                throw new IllegalStateException("Registry is closed");
            for (SecondaryIndex idx : indices) {
                if (idx.supports(predicate)) {
                    return idx;
                }
            }
            return null;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Atomically finds an index supporting the predicate and executes lookup on it, returning the
     * results as a snapshot list. The read lock is held for the entire operation, preventing
     * {@link #close()} from tearing down indices between the find and the lookup.
     *
     * @return the lookup results, or {@code null} if no index supports the predicate
     */
    public List<MemorySegment> findAndLookup(Predicate predicate) throws IOException {
        rwLock.readLock().lock();
        try {
            if (closed.get())
                throw new IllegalStateException("Registry is closed");
            SecondaryIndex idx = null;
            for (SecondaryIndex candidate : indices) {
                if (candidate.supports(predicate)) {
                    idx = candidate;
                    break;
                }
            }
            if (idx == null)
                return null;
            List<MemorySegment> results = new ArrayList<>();
            Iterator<MemorySegment> it = idx.lookup(predicate);
            while (it.hasNext()) {
                results.add(it.next());
            }
            return results;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public boolean isEmpty() {
        rwLock.readLock().lock();
        try {
            if (closed.get())
                throw new IllegalStateException("Registry is closed");
            return indices.isEmpty();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    // @spec query.index-registry.R12,R18,R21 — deferred-exception pattern across indices + arena; never leaks on
    // partial failure
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true))
            return;
        // Acquire write lock to ensure all in-flight operations (which hold the
        // read lock) complete before tearing down indices, documentStore, and arena.
        rwLock.writeLock().lock();
        try {
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
            documentStore.clear();
            try {
                segmentArena.close();
            } catch (Exception arenaEx) {
                if (deferred == null) {
                    deferred = new IOException("Failed to close segment arena", arenaEx);
                } else {
                    deferred.addSuppressed(arenaEx);
                }
            }
            if (deferred != null) {
                throw deferred;
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Resolves a primary key to its stored document, or null if not found.
     */
    // @spec query.index-registry.R14,R19 — return stored entry or null; acquire read lock before check
    public StoredEntry resolveEntry(MemorySegment primaryKey) {
        rwLock.readLock().lock();
        try {
            if (closed.get())
                throw new IllegalStateException("Registry is closed");
            return documentStore.get(toPkKey(primaryKey));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns an iterator over all stored entries. Used for scan-and-filter queries. The read lock
     * is held while building the snapshot, preventing {@link #close()} from tearing down the arena
     * while entries are being copied.
     */
    // @spec query.index-registry.R15,R19 — snapshot iterator via List.copyOf safe against concurrent modification
    public Iterator<StoredEntry> allEntries() {
        rwLock.readLock().lock();
        try {
            if (closed.get())
                throw new IllegalStateException("Registry is closed");
            return List.copyOf(documentStore.values()).iterator();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns the schema this registry was created with.
     */
    public JlsmSchema schema() {
        rwLock.readLock().lock();
        try {
            if (closed.get())
                throw new IllegalStateException("Registry is closed");
            return schema;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ── Document store types ────────────────────────────────────────────

    /**
     * A stored entry associating a primary key segment with its document.
     */
    public record StoredEntry(MemorySegment primaryKey, JlsmDocument document) {
        public StoredEntry {
            Objects.requireNonNull(primaryKey, "primaryKey must not be null");
            Objects.requireNonNull(document, "document must not be null");
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

    private MemorySegment copySegment(MemorySegment src) {
        MemorySegment copy = segmentArena.allocate(src.byteSize());
        copy.copyFrom(src);
        return copy;
    }

    // ── Private helpers ─────────────────────────────────────────────────

    // @spec query.index-registry.R3,R4,R5,R6,R7,R22 — reject unknown field (IAE); enforce per-IndexType
    // field-type constraints
    // including EQUALITY-on-BOOLEAN rejection alongside RANGE/UNIQUE
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
                if (fieldType == FieldType.Primitive.BOOLEAN) {
                    throw new IllegalArgumentException(
                            "EQUALITY index is not supported on BOOLEAN field '" + def.fieldName()
                                    + "'");
                }
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

        // OPE is capped at 2 bytes — must match FieldEncryptionDispatch.MAX_OPE_BYTES
        if (fieldType instanceof FieldType.BoundedString bs) {
            if (bs.maxLength() > 2) {
                throw new IllegalArgumentException(
                        "OrderPreserving encryption on BoundedString(maxLength=" + bs.maxLength()
                                + ") field '" + fieldName
                                + "' is not supported — maxLength exceeds OPE limit of 2");
            }
            return; // allowed — fits in MAX_OPE_BYTES
        }
        if (fieldType instanceof FieldType.Primitive p) {
            switch (p) {
                case INT8, INT16 -> {
                    /* allowed — fits in MAX_OPE_BYTES (2) */ }
                case INT32, INT64,
                        TIMESTAMP ->
                    throw new IllegalArgumentException(
                            "OrderPreserving encryption on " + p + " field '" + fieldName
                                    + "' is not supported — OPE is limited to 2-byte values; "
                                    + "wider types would suffer silent data truncation");
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

    private static SecondaryIndex createIndex(IndexDefinition def, FieldType fieldType,
            String tableName, FullTextIndex.Factory fullTextFactory,
            VectorIndex.Factory vectorFactory) throws IOException {
        return switch (def.indexType()) {
            case EQUALITY, RANGE, UNIQUE -> new FieldIndex(def, fieldType);
            case FULL_TEXT -> {
                assert fullTextFactory != null
                        : "fullTextFactory null despite FULL_TEXT index — constructor must reject earlier";
                yield new FullTextFieldIndex(def,
                        fullTextFactory.create(tableName, def.fieldName()));
            }
            case VECTOR -> {
                assert vectorFactory != null
                        : "vectorFactory null despite VECTOR index — constructor must reject earlier";
                if (!(fieldType instanceof FieldType.VectorType vt)) {
                    // validate() already ensures this, but keep the invariant local.
                    throw new IllegalArgumentException(
                            "VECTOR index requires VectorType field, got: " + fieldType);
                }
                final VectorPrecision precision = switch (vt.elementType()) {
                    case FLOAT32 -> VectorPrecision.FLOAT32;
                    case FLOAT16 -> VectorPrecision.FLOAT16;
                    default -> throw new IllegalArgumentException(
                            "VECTOR elementType must be FLOAT32 or FLOAT16, got "
                                    + vt.elementType());
                };
                yield new VectorFieldIndex(def, vectorFactory.create(tableName, def.fieldName(),
                        vt.dimensions(), precision, def.similarityFunction()));
            }
        };
    }

    /**
     * Closes all already-created indices and the shared arena on construction failure, suppressing
     * secondary exceptions into {@code cause} where one is supplied.
     */
    private void closePartial(Exception cause) {
        for (SecondaryIndex idx : indices) {
            try {
                idx.close();
            } catch (IOException suppressed) {
                if (cause != null) {
                    cause.addSuppressed(suppressed);
                }
            }
        }
        indices.clear();
        try {
            segmentArena.close();
        } catch (Exception arenaEx) {
            if (cause != null) {
                cause.addSuppressed(arenaEx);
            }
        }
    }

    // @spec query.index-registry.R23 — VectorType FLOAT32/FLOAT16 arrays returned as defensive copies, not internal
    // references
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
        } else if (fieldType instanceof FieldType.VectorType vt) {
            final Object raw = DocumentAccess.get().values(document)[idx];
            return switch (vt.elementType()) {
                case FLOAT32 -> ((float[]) raw).clone();
                case FLOAT16 -> ((short[]) raw).clone();
                default -> raw;
            };
        } else if (fieldType instanceof FieldType.ObjectType) {
            return document.getObject(fieldName);
        }
        return null;
    }
}
