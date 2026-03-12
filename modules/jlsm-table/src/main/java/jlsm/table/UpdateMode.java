package jlsm.table;

/**
 * Controls how an existing document is updated in a {@link JlsmTable}.
 */
public enum UpdateMode {
    /** Replaces the existing document entirely with the new value. */
    REPLACE,
    /** Merges the new document fields into the existing document (patch semantics). */
    PATCH
}
