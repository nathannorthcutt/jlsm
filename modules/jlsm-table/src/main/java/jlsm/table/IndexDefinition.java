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
// @spec query.index-types.R7 — public record with components (fieldName, indexType,
// similarityFunction)
// @spec vector.field-type.R13 — record with exactly three components: fieldName, indexType,
// similarityFunction
// @spec vector.field-type.R14 — no vectorDimensions field; dimensions derive from schema's
// VectorType
// @spec query.index-types.R11 — null similarityFunction rejected when indexType is VECTOR
// @spec query.index-types.R12 — non-null similarityFunction rejected when indexType is not
// VECTOR
public record IndexDefinition(String fieldName, IndexType indexType,
        SimilarityFunction similarityFunction) {

    // @spec query.index-types.R13 — two-argument convenience constructor passes null for
    // similarityFunction
    /**
     * Creates an index definition for non-vector index types.
     */
    public IndexDefinition(String fieldName, IndexType indexType) {
        this(fieldName, indexType, null);
    }

    public IndexDefinition {
        // @spec query.index-types.R8 — reject null fieldName with NPE
        Objects.requireNonNull(fieldName, "fieldName");
        // @spec query.index-types.R10 — reject null indexType with NPE
        Objects.requireNonNull(indexType, "indexType");
        // @spec query.index-types.R9 — reject blank fieldName with IAE
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
