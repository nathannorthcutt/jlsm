// @spec query.query-executor.R19 — SecondaryIndex, FieldIndex, FullTextFieldIndex,
// VectorFieldIndex, FieldValueCodec, IndexRegistry, and QueryExecutor reside in
// jlsm.table.internal which is intentionally not exported.
// @spec query.query-executor.R20 — IndexType, IndexDefinition, Predicate, TableQuery,
// TableEntry, and DuplicateKeyException reside in jlsm.table which is exported.
module jlsm.table {
    requires transitive jlsm.core;
    requires jdk.incubator.vector;

    exports jlsm.table;
    // jlsm.table.internal intentionally not exported
}
