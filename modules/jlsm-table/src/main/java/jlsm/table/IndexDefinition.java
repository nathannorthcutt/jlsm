package jlsm.table;

import java.util.Objects;

import jlsm.core.indexing.SimilarityFunction;

/**
 * Defines a secondary index on a table field.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Receives: field name, index type, and optional similarity function for vector indices</li>
 * <li>Returns: immutable definition used by the table builder to create the index</li>
 * <li>Side effects: none — pure value holder</li>
 * <li>Validated at table build time against the schema</li>
 * </ul>
 *
 * <p>
 * Vector dimensions are derived from the schema's {@link FieldType.VectorType} at
 * {@link jlsm.table.internal.IndexRegistry} construction time, not stored here.
 *
 * @param fieldName the schema field to index — must exist in the table's schema
 * @param indexType the type of index to create
 * @param similarityFunction similarity function for VECTOR indices; null for other types
 */
public record IndexDefinition(String fieldName, IndexType indexType,
        SimilarityFunction similarityFunction) {

    /**
     * Creates an index definition for non-vector index types.
     */
    public IndexDefinition(String fieldName, IndexType indexType) {
        this(fieldName, indexType, null);
    }

    public IndexDefinition {
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(indexType, "indexType");
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }
        if (indexType == IndexType.VECTOR) {
            Objects.requireNonNull(similarityFunction,
                    "similarityFunction required for VECTOR indices");
        } else if (similarityFunction != null) {
            throw new IllegalArgumentException(
                    "similarityFunction must be null for non-VECTOR index type " + indexType);
        }
    }
}
