package jlsm.table.internal;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import jlsm.core.indexing.FullTextIndex;
import jlsm.core.indexing.Query;
import jlsm.table.IndexDefinition;
import jlsm.table.IndexType;
import jlsm.table.Predicate;

/**
 * Secondary index for full-text search on a STRING field. Adapts the {@link SecondaryIndex} per-
 * field mutation callbacks to the batch map-based {@link FullTextIndex} API provided by
 * {@code jlsm-indexing}.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Supports: {@link Predicate.FullTextMatch} on the index's field only</li>
 * <li>On insert: routes {@code (fieldName -> String.valueOf(value))} to
 * {@link FullTextIndex#index(Object, Map)}; null values are a no-op (R56)</li>
 * <li>On update: removes old terms (if non-null) then indexes new terms (if non-null) (R57, R58,
 * R82)</li>
 * <li>On delete: routes {@code (fieldName -> String.valueOf(value))} to
 * {@link FullTextIndex#remove(Object, Map)}; null values are a no-op (R60, R83)</li>
 * <li>{@link #close()} is idempotent and propagates once to the backing index (R84)</li>
 * </ul>
 *
 * <p>
 * The adapter does not own tokenisation, stemming, or stop-word filtering — those are configuration
 * on the underlying {@link FullTextIndex} implementation.
 */
// @spec query.full-text-index.R1 — final class in jlsm.table.internal implementing SecondaryIndex
// @spec query.index-types.R5 — delegates to FullTextIndex<MemorySegment> backing,
// @spec query.full-text-index.R2,R3,R4,R5,R6 — delegates to FullTextIndex<MemorySegment> backing,
// resolving OBL-F10-fulltext
public final class FullTextFieldIndex implements SecondaryIndex {

    private final IndexDefinition definition;
    private final FullTextIndex<MemorySegment> backing;
    private volatile boolean closed;

    /**
     * Creates a new full-text field index adapter.
     *
     * @param definition the index definition; must be FULL_TEXT type
     * @param backing the backing full-text index from {@code jlsm-indexing} (or a test double);
     *            must not be null
     */
    public FullTextFieldIndex(IndexDefinition definition, FullTextIndex<MemorySegment> backing) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(backing, "backing must not be null");
        if (definition.indexType() != IndexType.FULL_TEXT) {
            throw new IllegalArgumentException(
                    "FullTextFieldIndex requires FULL_TEXT index type, got "
                            + definition.indexType());
        }
        this.definition = definition;
        this.backing = backing;
    }

    @Override
    public IndexDefinition definition() {
        return definition;
    }

    // @spec query.field-index.R3,R4 — tokenise field value and index per term; null value is a no-op
    // @spec query.full-text-index.R3 — tokenise field value and index per term; null value is a no-op
    @Override
    public void onInsert(MemorySegment primaryKey, Object fieldValue) throws IOException {
        ensureOpen();
        Objects.requireNonNull(primaryKey, "primaryKey must not be null");
        if (fieldValue == null) {
            return;
        }
        backing.index(primaryKey, Map.of(definition.fieldName(), String.valueOf(fieldValue)));
    }

    // @spec query.field-index.R5,R6 — remove old terms (if non-null) then insert new terms (if non-null)
    // @spec query.full-text-index.R4 — remove old terms (if non-null) then insert new terms (if non-null)
    @Override
    public void onUpdate(MemorySegment primaryKey, Object oldFieldValue, Object newFieldValue)
            throws IOException {
        ensureOpen();
        Objects.requireNonNull(primaryKey, "primaryKey must not be null");
        if (oldFieldValue != null) {
            backing.remove(primaryKey,
                    Map.of(definition.fieldName(), String.valueOf(oldFieldValue)));
        }
        if (newFieldValue != null) {
            backing.index(primaryKey,
                    Map.of(definition.fieldName(), String.valueOf(newFieldValue)));
        }
    }

    // @spec query.field-index.R7,R8 — remove terms for the given PK; null value is a no-op
    // @spec query.full-text-index.R5 — remove terms for the given PK; null value is a no-op
    @Override
    public void onDelete(MemorySegment primaryKey, Object fieldValue) throws IOException {
        ensureOpen();
        Objects.requireNonNull(primaryKey, "primaryKey must not be null");
        if (fieldValue == null) {
            return;
        }
        backing.remove(primaryKey, Map.of(definition.fieldName(), String.valueOf(fieldValue)));
    }

    // @spec query.field-index.R9 — translate FullTextMatch → Query.TermQuery and delegate; throw for
    // @spec query.full-text-index.R4,R6 — translate FullTextMatch → Query.TermQuery and delegate; throw for
    // unsupported predicates
    @Override
    public Iterator<MemorySegment> lookup(Predicate predicate) throws IOException {
        ensureOpen();
        Objects.requireNonNull(predicate, "predicate must not be null");
        if (!(predicate instanceof Predicate.FullTextMatch ftm)) {
            throw new UnsupportedOperationException(
                    "FullTextFieldIndex.lookup requires FullTextMatch predicate, got "
                            + predicate.getClass().getSimpleName());
        }
        if (!ftm.field().equals(definition.fieldName())) {
            // Defensive: IndexRegistry.findIndex already gates on supports(), but a caller can
            // invoke lookup directly. Return an empty iterator rather than querying for a mismatch.
            return Collections.emptyIterator();
        }
        return backing.search(new Query.TermQuery(ftm.field(), ftm.query()));
    }

    // @spec query.field-index.R10 — true only for FullTextMatch whose field matches this index's field
    // @spec query.full-text-index.R2 — true only for FullTextMatch whose field matches this index's field
    @Override
    public boolean supports(Predicate predicate) {
        if (closed) {
            return false;
        }
        return predicate instanceof Predicate.FullTextMatch ftm
                && ftm.field().equals(definition.fieldName());
    }

    // @spec query.full-text-index.R6 — idempotent close; propagates once to backing
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
            throw new IllegalStateException("FullTextFieldIndex is closed");
        }
    }
}
