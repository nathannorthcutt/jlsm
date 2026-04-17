package jlsm.table.internal;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.Objects;

import jlsm.table.IndexDefinition;
import jlsm.table.Predicate;

/**
 * Secondary index for vector nearest-neighbour search on a VectorType field. Wraps
 * {@code LsmVectorIndex} (IvfFlat or Hnsw) from jlsm-vector.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Supports: VectorNearest predicate only</li>
 * <li>The indexed field must be a VectorType with FLOAT32 or FLOAT16 element type</li>
 * <li>On insert: extracts the vector from the document and inserts into the vector index</li>
 * <li>On update: removes old vector, inserts new vector</li>
 * <li>On delete: removes the vector from the index</li>
 * <li>Caller chooses IvfFlat or Hnsw algorithm at table build time</li>
 * </ul>
 *
 * <p>
 * <b>Stub:</b> operations are not yet implemented. The constructor accepts the definition so that
 * IndexRegistry validation can complete, but all mutation and query operations throw
 * {@link UnsupportedOperationException}.
 */
// @spec F10.R85 — final class in jlsm.table.internal implementing SecondaryIndex
// @spec F10.R6,R86,R87,R88,R89,R90 — STUB: mutation is no-op, supports() false, lookup() throws
// UOE;
// deferred to OBL-F10-vector (LsmVectorIndex wiring)
public final class VectorFieldIndex implements SecondaryIndex {

    private final IndexDefinition definition;

    /**
     * Creates a new vector field index stub.
     *
     * @param definition the index definition (must be VECTOR type with similarity function)
     * @throws IOException if the backing index cannot be created
     */
    public VectorFieldIndex(IndexDefinition definition) throws IOException {
        Objects.requireNonNull(definition, "definition");
        assert definition.indexType() == jlsm.table.IndexType.VECTOR
                : "VectorFieldIndex requires VECTOR index type";
        this.definition = definition;
    }

    @Override
    public IndexDefinition definition() {
        return definition;
    }

    @Override
    public void onInsert(MemorySegment primaryKey, Object fieldValue) throws IOException {
        // Stub: no-op until real vector index implementation ships.
        // Silently accepts mutations so tables with VECTOR indices can store documents.
    }

    @Override
    public void onUpdate(MemorySegment primaryKey, Object oldFieldValue, Object newFieldValue)
            throws IOException {
        // Stub: no-op until real vector index implementation ships.
    }

    @Override
    public void onDelete(MemorySegment primaryKey, Object fieldValue) throws IOException {
        // Stub: no-op until real vector index implementation ships.
    }

    @Override
    public Iterator<MemorySegment> lookup(Predicate predicate) throws IOException {
        throw new UnsupportedOperationException("VectorFieldIndex.lookup not implemented");
    }

    @Override
    public boolean supports(Predicate predicate) {
        // Stub: lookup() throws UnsupportedOperationException, so supports() must return false.
        // When lookup() is implemented, restore: predicate instanceof VectorNearest vn
        // && vn.field().equals(definition.fieldName())
        return false;
    }

    @Override
    public void close() throws IOException {
        // No resources to release in the stub
    }
}
