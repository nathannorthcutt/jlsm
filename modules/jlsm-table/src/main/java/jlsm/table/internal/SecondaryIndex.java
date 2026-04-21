package jlsm.table.internal;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;

import jlsm.table.IndexDefinition;
import jlsm.table.Predicate;

/**
 * Internal abstraction over different secondary index implementations.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Each implementation manages a backing store (LSM tree, inverted index, or vector index)</li>
 * <li>Index maintenance is synchronous — {@code onInsert}, {@code onUpdate}, {@code onDelete} are
 * called within the table's write path</li>
 * <li>For unique indices, {@code onInsert} and {@code onUpdate} throw DuplicateKeyException if the
 * constraint would be violated</li>
 * <li>Closeable — releases the backing store on close</li>
 * </ul>
 */
// @spec query.field-index.R1 — sealed interface extending Closeable; permits FieldIndex, FullTextFieldIndex,
// VectorFieldIndex
public sealed interface SecondaryIndex extends Closeable
        permits FieldIndex, FullTextFieldIndex, VectorFieldIndex {

    /** Returns the index definition this index was created from. */
    // @spec query.field-index.R2 — definition() returns the IndexDefinition used to create this index
    IndexDefinition definition();

    /**
     * Called when a new document is inserted.
     *
     * @param primaryKey the encoded primary key of the document
     * @param fieldValue the field value to index (may be null — null values are not indexed)
     * @throws IOException on I/O error
     */
    // @spec query.field-index.R3,R4 — index the (pk, fieldValue) pair; null fieldValue is a no-op
    void onInsert(MemorySegment primaryKey, Object fieldValue) throws IOException;

    /**
     * Called when a document is updated. The old value is removed and the new value inserted.
     *
     * @param primaryKey the encoded primary key of the document
     * @param oldFieldValue the previous field value (may be null)
     * @param newFieldValue the new field value (may be null)
     * @throws IOException on I/O error
     */
    // @spec query.field-index.R5,R6 — remove old entry then insert new entry; handle null old (insert-only) and
    // null new (delete-only)
    void onUpdate(MemorySegment primaryKey, Object oldFieldValue, Object newFieldValue)
            throws IOException;

    /**
     * Called when a document is deleted.
     *
     * @param primaryKey the encoded primary key of the document
     * @param fieldValue the field value to remove from the index (may be null)
     * @throws IOException on I/O error
     */
    // @spec query.field-index.R7,R8 — remove the (pk, fieldValue) entry; null fieldValue is a no-op
    void onDelete(MemorySegment primaryKey, Object fieldValue) throws IOException;

    /**
     * Looks up primary keys matching the given predicate via this index.
     *
     * @param predicate the predicate to evaluate — must be compatible with this index type
     * @return iterator over matching primary keys (encoded)
     * @throws IOException on I/O error
     */
    // @spec query.field-index.R9 — return Iterator<MemorySegment> of matching primary keys for the predicate
    Iterator<MemorySegment> lookup(Predicate predicate) throws IOException;

    /**
     * Returns whether this index can efficiently evaluate the given predicate.
     *
     * @param predicate the predicate to check
     * @return true if this index supports the predicate
     */
    // @spec query.field-index.R10 — return true only when predicate field matches this index's field and
    // predicate type is compatible
    boolean supports(Predicate predicate);
}
