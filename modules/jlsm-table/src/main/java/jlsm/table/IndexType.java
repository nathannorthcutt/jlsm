package jlsm.table;

/**
 * Types of secondary indices that can be created on a table field.
 *
 * <ul>
 * <li>{@link #EQUALITY} — supports eq/ne lookups on any primitive field type</li>
 * <li>{@link #RANGE} — supports eq/ne/gt/gte/lt/lte/between on naturally ordered types</li>
 * <li>{@link #UNIQUE} — like RANGE but enforces a uniqueness constraint at write time</li>
 * <li>{@link #FULL_TEXT} — full-text search via tokenization and inverted index</li>
 * <li>{@link #VECTOR} — approximate nearest-neighbour search on float array fields</li>
 * </ul>
 */
// @spec query.index-types.R1 — exactly five constants: EQUALITY, RANGE, UNIQUE, FULL_TEXT, VECTOR
public enum IndexType {

    /** Equality lookups (eq, ne) on any primitive field type. */
    EQUALITY,

    /** Range lookups (eq, ne, gt, gte, lt, lte, between) on ordered types. */
    RANGE,

    /** Range lookups with a uniqueness constraint enforced at write time. */
    UNIQUE,

    /** Full-text search delegating to {@code LsmFullTextIndex} from jlsm-indexing. */
    FULL_TEXT,

    /** Vector nearest-neighbour search delegating to {@code LsmVectorIndex} from jlsm-vector. */
    VECTOR
}
