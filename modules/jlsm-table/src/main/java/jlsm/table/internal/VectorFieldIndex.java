package jlsm.table.internal;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import jlsm.core.indexing.VectorIndex;
import jlsm.table.IndexDefinition;
import jlsm.table.IndexType;
import jlsm.table.Predicate;

/**
 * Secondary index for vector nearest-neighbour search on a {@code VectorType} field. Wraps a
 * {@link VectorIndex} (e.g. {@code LsmVectorIndex.IvfFlat} or {@code LsmVectorIndex.Hnsw}) from
 * jlsm-vector and adapts the {@link SecondaryIndex} per-field mutation callbacks to the backing
 * index's vector-centric API.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Supports: {@link Predicate.VectorNearest} on the index's field only</li>
 * <li>On insert: extracts the vector from {@code fieldValue} ({@code float[]} or {@code short[]}
 * for FLOAT16) and delegates to {@link VectorIndex#index}; null values are a no-op (R56).</li>
 * <li>On update: removes the old vector (if non-null) then inserts the new vector (if non-null)
 * (R57, R58, R88)</li>
 * <li>On delete: routes to {@link VectorIndex#remove}; null values are a no-op (R60, R89)</li>
 * <li>Lookup: translates {@code VectorNearest} to {@link VectorIndex#search} and returns the
 * primary keys in descending similarity order (R90)</li>
 * <li>{@link #close()} is idempotent and propagates once to the backing index</li>
 * </ul>
 *
 * <p>
 * The adapter does not own algorithm choice, similarity computation, or storage layout — those are
 * configuration on the backing {@link VectorIndex} instance.
 */
// @spec query.vector-index.R1 — final class in jlsm.table.internal implementing SecondaryIndex
// @spec query.index-types.R6 — delegates to VectorIndex<MemorySegment> backing,
// @spec query.vector-index.R2,R3,R4,R5,R6 — delegates to VectorIndex<MemorySegment> backing,
// resolving OBL-F10-vector
public final class VectorFieldIndex implements SecondaryIndex {

    private final IndexDefinition definition;
    private final VectorIndex<MemorySegment> backing;
    private volatile boolean closed;

    /**
     * Creates a new vector field index adapter.
     *
     * @param definition the index definition; must be VECTOR type
     * @param backing the backing vector index from jlsm-vector (or a test double); must not be null
     */
    public VectorFieldIndex(IndexDefinition definition, VectorIndex<MemorySegment> backing) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(backing, "backing must not be null");
        if (definition.indexType() != IndexType.VECTOR) {
            throw new IllegalArgumentException(
                    "VectorFieldIndex requires VECTOR index type, got " + definition.indexType());
        }
        this.definition = definition;
        this.backing = backing;
    }

    @Override
    public IndexDefinition definition() {
        return definition;
    }

    // @spec query.field-index.R3,R4 — extract vector from field value and index; null value is a
    // no-op
    // @spec query.vector-index.R3 — extract vector from field value and index; null value is a
    // no-op
    @Override
    public void onInsert(MemorySegment primaryKey, Object fieldValue) throws IOException {
        ensureOpen();
        Objects.requireNonNull(primaryKey, "primaryKey must not be null");
        if (fieldValue == null) {
            return;
        }
        backing.index(primaryKey, toFloats(fieldValue));
    }

    // @spec query.field-index.R5,R6 — remove old vector (if non-null) then insert new vector (if
    // non-null)
    // @spec query.vector-index.R4 — remove old vector (if non-null) then insert new vector (if
    // non-null)
    @Override
    public void onUpdate(MemorySegment primaryKey, Object oldFieldValue, Object newFieldValue)
            throws IOException {
        ensureOpen();
        Objects.requireNonNull(primaryKey, "primaryKey must not be null");
        // Validate types up-front so a bad newFieldValue does not leave us in a partially-mutated
        // state (old removed, new not inserted).
        float[] newVec = newFieldValue == null ? null : toFloats(newFieldValue);
        if (oldFieldValue != null) {
            // Validate old as well — but we only call remove(primaryKey), so we just need the
            // type check to fail fast if the caller passes garbage.
            toFloats(oldFieldValue);
            backing.remove(primaryKey);
        }
        if (newVec != null) {
            backing.index(primaryKey, newVec);
        }
    }

    // @spec query.field-index.R7,R8 — remove the (pk, vector) entry; null value is a no-op
    // @spec query.vector-index.R5 — remove the (pk, vector) entry; null value is a no-op
    @Override
    public void onDelete(MemorySegment primaryKey, Object fieldValue) throws IOException {
        ensureOpen();
        Objects.requireNonNull(primaryKey, "primaryKey must not be null");
        if (fieldValue == null) {
            return;
        }
        // Type-check the value so a malformed input surfaces as IAE rather than silently
        // skipping the remove — but the backing only keys by primaryKey.
        toFloats(fieldValue);
        backing.remove(primaryKey);
    }

    // @spec query.field-index.R9 — translate VectorNearest → VectorIndex.search and return matching
    // PKs;
    // @spec query.vector-index.R6 — translate VectorNearest → VectorIndex.search and return
    // matching PKs;
    // reject other predicate shapes with UOE
    @Override
    public Iterator<MemorySegment> lookup(Predicate predicate) throws IOException {
        ensureOpen();
        Objects.requireNonNull(predicate, "predicate must not be null");
        if (!(predicate instanceof Predicate.VectorNearest vn)) {
            throw new UnsupportedOperationException(
                    "VectorFieldIndex.lookup requires VectorNearest predicate, got "
                            + predicate.getClass().getSimpleName());
        }
        if (!vn.field().equals(definition.fieldName())) {
            // Defensive: IndexRegistry.findIndex already gates on supports(), but a caller can
            // invoke lookup directly. Return an empty iterator rather than querying for a
            // mismatch.
            return Collections.emptyIterator();
        }
        final List<VectorIndex.SearchResult<MemorySegment>> results = backing
                .search(vn.queryVector(), vn.topK());
        return new Iterator<>() {
            private int i = 0;

            @Override
            public boolean hasNext() {
                return i < results.size();
            }

            @Override
            public MemorySegment next() {
                if (i >= results.size()) {
                    throw new java.util.NoSuchElementException();
                }
                return results.get(i++).docId();
            }
        };
    }

    // @spec query.field-index.R10 — true only for VectorNearest whose field matches this index's
    // field
    // @spec query.vector-index.R2 — true only for VectorNearest whose field matches this index's
    // field
    @Override
    public boolean supports(Predicate predicate) {
        if (closed) {
            return false;
        }
        return predicate instanceof Predicate.VectorNearest vn
                && vn.field().equals(definition.fieldName());
    }

    // @spec query.vector-index.R6 — idempotent close; propagates once to backing
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        backing.close();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("VectorFieldIndex is closed");
        }
    }

    /**
     * Converts a vector field value to a {@code float[]}. Accepts {@code float[]} (FLOAT32) and
     * {@code short[]} (FLOAT16) — the two encodings permitted by {@code FieldType.VectorType}.
     *
     * @throws IllegalArgumentException if the value is not a supported vector encoding
     */
    private static float[] toFloats(Object fieldValue) {
        if (fieldValue instanceof float[] f32) {
            return f32;
        }
        if (fieldValue instanceof short[] f16) {
            float[] out = new float[f16.length];
            for (int i = 0; i < f16.length; i++) {
                out[i] = Float.float16ToFloat(f16[i]);
            }
            return out;
        }
        throw new IllegalArgumentException(
                "VectorFieldIndex expects float[] (FLOAT32) or short[] (FLOAT16) vector, got "
                        + (fieldValue == null ? "null" : fieldValue.getClass().getName()));
    }
}
