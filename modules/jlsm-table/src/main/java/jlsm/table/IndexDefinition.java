package jlsm.table;

import java.util.Objects;

import jlsm.core.indexing.SimilarityFunction;

/**
 * Defines a secondary index on a table field.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Receives: field name, index type, and optional configuration for vector indices</li>
 * <li>Returns: immutable definition used by the table builder to create the index</li>
 * <li>Side effects: none — pure value holder</li>
 * <li>Validated at table build time against the schema</li>
 * </ul>
 *
 * @param fieldName the schema field to index — must exist in the table's schema
 * @param indexType the type of index to create
 * @param vectorDimensions number of dimensions for VECTOR indices; ignored for other types
 * @param similarityFunction similarity function for VECTOR indices; ignored for other types
 */
public record IndexDefinition(String fieldName, IndexType indexType, int vectorDimensions,
        SimilarityFunction similarityFunction) {

    /**
     * Creates an index definition for non-vector index types.
     */
    public IndexDefinition(String fieldName, IndexType indexType) {
        this(fieldName, indexType, 0, null);
    }

    public IndexDefinition {
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(indexType, "indexType");
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }
        if (indexType == IndexType.VECTOR) {
            if (vectorDimensions <= 0) {
                throw new IllegalArgumentException(
                        "vectorDimensions must be positive for VECTOR indices");
            }
            Objects.requireNonNull(similarityFunction,
                    "similarityFunction required for VECTOR indices");
        }
    }
}
