---
{
  "id": "F10",
  "version": 3,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["query", "engine"],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": ["OBL-F10-vector"]
}
---

# F10 — Table Indices and Queries

## Requirements

### IndexType enum

R1. `IndexType` must be a public enum in `jlsm.table` with exactly five constants: `EQUALITY`, `RANGE`, `UNIQUE`, `FULL_TEXT`, `VECTOR`.

R2. `EQUALITY` must support `Eq` and `Ne` predicate lookups on any primitive or bounded-string field type.

R3. `RANGE` must support `Eq`, `Ne`, `Gt`, `Gte`, `Lt`, `Lte`, and `Between` predicate lookups on naturally ordered field types.

R4. `UNIQUE` must support the same predicate lookups as `RANGE` and additionally enforce a uniqueness constraint at write time.

R5. `FULL_TEXT` must support only `FullTextMatch` predicate lookups and must require a `STRING` or `BoundedString` field type. The `FullTextFieldIndex` adapter must delegate to a `FullTextIndex<MemorySegment>` backing supplied by a `FullTextIndex.Factory` so that write-path mutations propagate to a real index implementation without a direct static dependency from `jlsm-table` on `jlsm-indexing`.

R6. `VECTOR` must support only `VectorNearest` predicate lookups and must require a `VectorType` field type.

### IndexDefinition record

R7. `IndexDefinition` must be a public record in `jlsm.table` with components `fieldName` (String), `indexType` (IndexType), and `similarityFunction` (SimilarityFunction, nullable).

R8. `IndexDefinition` must reject a null `fieldName` with a `NullPointerException`.

R9. `IndexDefinition` must reject a blank `fieldName` with an `IllegalArgumentException`.

R10. `IndexDefinition` must reject a null `indexType` with a `NullPointerException`.

R11. `IndexDefinition` must require a non-null `similarityFunction` when `indexType` is `VECTOR`, rejecting null with a `NullPointerException`.

R12. `IndexDefinition` must reject a non-null `similarityFunction` when `indexType` is not `VECTOR` with an `IllegalArgumentException`.

R13. `IndexDefinition` must provide a two-argument convenience constructor `(fieldName, indexType)` that passes null for `similarityFunction`.

### Predicate sealed interface

R14. `Predicate` must be a public sealed interface in `jlsm.table` permitting exactly eleven implementations: `Eq`, `Ne`, `Gt`, `Gte`, `Lt`, `Lte`, `Between`, `FullTextMatch`, `VectorNearest`, `And`, `Or`.

R15. Each leaf predicate record (`Eq`, `Ne`, `Gt`, `Gte`, `Lt`, `Lte`, `Between`, `FullTextMatch`) must reject a null `field` with a `NullPointerException`.

R16. Each leaf predicate record (`Eq`, `Ne`) must reject a null `value` with a `NullPointerException`.

R17. Each range predicate record (`Gt`, `Gte`, `Lt`, `Lte`) must accept a `Comparable<?>` value and reject null with a `NullPointerException`.

R18. `Between` must reject a null `low` or `high` with a `NullPointerException`.

R19. `FullTextMatch` must reject a null `query` string with a `NullPointerException`.

R20. `VectorNearest` must reject a null `field` with a `NullPointerException`.

R21. `VectorNearest` must reject a null `queryVector` with a `NullPointerException`.

R22. `VectorNearest` must reject a `topK` value of zero or negative with an `IllegalArgumentException`.

R23. `VectorNearest` must defensively copy the `queryVector` array at construction and return a defensive copy from the accessor.

R24. `And` must reject a null `children` list with a `NullPointerException`.

R25. `And` must reject a `children` list with fewer than two elements with an `IllegalArgumentException`.

R26. `And` must store a defensively copied immutable list of its children.

R27. `Or` must reject a null `children` list with a `NullPointerException`.

R28. `Or` must reject a `children` list with fewer than two elements with an `IllegalArgumentException`.

R29. `Or` must store a defensively copied immutable list of its children.

### TableQuery fluent builder

R30. `TableQuery<K>` must be a public final class in `jlsm.table` parameterized by the primary key type `K`.

R31. `TableQuery.where(fieldName)` must reject a null `fieldName` with a `NullPointerException`.

R32. `TableQuery.where(fieldName)` must return a `FieldClause<K>` that provides comparison operator methods.

R33. `FieldClause<K>` must provide methods `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `between`, `fullTextMatch`, and `vectorNearest`, each returning the owning `TableQuery<K>` for chaining.

R34. `TableQuery.and(fieldName)` must combine the next predicate with the existing root using `Predicate.And`.

R35. `TableQuery.or(fieldName)` must combine the next predicate with the existing root using `Predicate.Or`.

R36. `TableQuery.predicate()` must return the current root predicate, or null if no predicates have been added.

R37. `TableQuery.execute()` must return an `Iterator<TableEntry<K>>` of matching entries.

R38. Calling `execute()` on an unbound `TableQuery` (not obtained from a table) must throw `UnsupportedOperationException`.

### FieldValueCodec — sort-preserving binary encoding

R39. `FieldValueCodec.encode` must accept a non-null value and a non-null `FieldType`, and return a `MemorySegment` containing the sort-preserving binary encoding.

R40. `FieldValueCodec.encode` must reject a null `fieldType` with a `NullPointerException`.

R41. `FieldValueCodec.encode` must reject a null `value` with a `NullPointerException`.

R42. `FieldValueCodec.encode` must reject non-primitive and non-BoundedString field types with an `IllegalArgumentException`.

R43. For signed integer types (INT8, INT16, INT32, INT64), `FieldValueCodec` must apply sign-bit-flip encoding so that the unsigned byte comparison of encoded forms preserves the signed numeric ordering.

R44. For FLOAT32, `FieldValueCodec` must apply IEEE 754 sort-preserving encoding: if the raw int bits are negative, invert all bits; otherwise set the sign bit. This must preserve ordering for negative values, positive values, negative zero, positive zero, and positive/negative infinity.

R45. For FLOAT64, `FieldValueCodec` must apply the same IEEE 754 sort-preserving encoding as FLOAT32, using long raw bits.

R46. For FLOAT16, `FieldValueCodec` must apply IEEE 754 sort-preserving encoding on the 2-byte raw representation: if the sign bit is set, invert all bits; otherwise set the sign bit. This preserves ordering across negative and positive half-precision values.

R47. For STRING and BoundedString, `FieldValueCodec` must encode as raw UTF-8 bytes with no transformation.

R48. For BOOLEAN, `FieldValueCodec` must encode false as `0x00` and true as `0x01`.

R49. For TIMESTAMP, `FieldValueCodec` must encode identically to INT64 (sign-bit-flipped big-endian 8 bytes).

R50. `FieldValueCodec.decode` must round-trip: for every supported field type, `decode(encode(value, type), type)` must return a value equal to the original.

R51. For FLOAT32, the encoding must sort NaN values above positive infinity in unsigned byte order.

R52. For FLOAT64, the encoding must sort NaN values above positive infinity in unsigned byte order.

### SecondaryIndex sealed interface

R53. `SecondaryIndex` must be a sealed interface in `jlsm.table.internal` extending `Closeable`, permitting exactly three implementations: `FieldIndex`, `FullTextFieldIndex`, `VectorFieldIndex`.

R54. `SecondaryIndex.definition()` must return the `IndexDefinition` this index was created from.

R55. `SecondaryIndex.onInsert(primaryKey, fieldValue)` must index the field value associated with the given primary key.

R56. `SecondaryIndex.onInsert` must treat a null `fieldValue` as a no-op (null fields are not indexed).

R57. `SecondaryIndex.onUpdate(primaryKey, oldFieldValue, newFieldValue)` must remove the old field value from the index and insert the new field value.

R58. `SecondaryIndex.onUpdate` must handle null `oldFieldValue` (insert-only) and null `newFieldValue` (delete-only) independently.

R59. `SecondaryIndex.onDelete(primaryKey, fieldValue)` must remove the field value entry for the given primary key from the index.

R60. `SecondaryIndex.onDelete` must treat a null `fieldValue` as a no-op.

R61. `SecondaryIndex.lookup(predicate)` must return an `Iterator<MemorySegment>` of matching primary keys for the given predicate.

R62. `SecondaryIndex.supports(predicate)` must return true only when the predicate's field matches the index's field and the predicate type is compatible with the index type.

### FieldIndex — equality, range, and unique lookups

R63. `FieldIndex` must implement `SecondaryIndex` and must be a final class in `jlsm.table.internal`.

R64. `FieldIndex` must reject at construction an `IndexDefinition` whose type is not `EQUALITY`, `RANGE`, or `UNIQUE` with an `IllegalArgumentException`.

R65. `FieldIndex` must maintain a sorted map keyed by sort-preserving encoded field values (via `FieldValueCodec`) with lists of primary key segments as values.

R66. For `EQUALITY` index type, `FieldIndex.supports` must return true only for `Eq` and `Ne` predicates on its field.

R67. For `RANGE` and `UNIQUE` index types, `FieldIndex.supports` must return true for `Eq`, `Ne`, `Gt`, `Gte`, `Lt`, `Lte`, and `Between` predicates on its field.

R68. `FieldIndex.lookup` for `Eq` must return all primary keys whose encoded field value equals the encoded query value.

R69. `FieldIndex.lookup` for `Ne` must return all primary keys whose encoded field value does not equal the encoded query value.

R70. `FieldIndex.lookup` for `Gt` must return all primary keys whose encoded field value is strictly greater than the encoded query value.

R71. `FieldIndex.lookup` for `Between` must return all primary keys whose encoded field value falls within the inclusive range `[low, high]`.

R72. `FieldIndex.lookup` for `Between` must return an empty iterator when `low` compares greater than `high` in encoded form.

R73. `FieldIndex` must use unsigned byte-wise comparison of encoded keys (`ByteArrayKey.compareTo`) so that the sort-preserving encoding of `FieldValueCodec` produces correct ordering.

### Unique index constraint enforcement

R74. When `IndexType` is `UNIQUE`, `FieldIndex.onInsert` must throw `DuplicateKeyException` if the encoded field value already exists in the index with a non-empty primary key list.

R75. When `IndexType` is `UNIQUE`, `FieldIndex.onUpdate` must throw `DuplicateKeyException` if the new field value already exists for a different primary key, but must not reject an update that retains the same field value.

R76. `IndexRegistry.onInsert` must validate all unique constraints across all unique indices before mutating any index, preventing orphan entries if the Nth unique check fails.

R77. `IndexRegistry.onUpdate` must validate all unique constraints for changed values across all unique indices before mutating any index, preventing partial updates.

R78. Unique constraint checks must skip null field values (null values are not subject to uniqueness enforcement).

### FullTextFieldIndex

R79. `FullTextFieldIndex` must implement `SecondaryIndex` and must be a final class in `jlsm.table.internal`. It must be constructed with a non-null `IndexDefinition` of type `FULL_TEXT` and a non-null backing `jlsm.core.indexing.FullTextIndex<MemorySegment>` supplied by the caller (typically via `FullTextIndex.Factory`). Any other index type must be rejected with `IllegalArgumentException`.

R80. `FullTextFieldIndex.supports` must return true only for `FullTextMatch` predicates whose `field()` equals the index's field, and must return false for all other predicates and after `close()`.

R81. `FullTextFieldIndex.onInsert` must route `(fieldName -> String.valueOf(fieldValue))` to `FullTextIndex.index(primaryKey, fields)` on the backing index so that the backing implementation's tokenisation pipeline indexes each term for the primary key. Null `fieldValue` is a no-op per R56.

R82. `FullTextFieldIndex.onUpdate` must invoke `FullTextIndex.remove` with the old field value (when non-null) and then `FullTextIndex.index` with the new field value (when non-null), so that old terms are deindexed and new terms indexed for the given primary key.

R83. `FullTextFieldIndex.onDelete` must route `(fieldName -> String.valueOf(fieldValue))` to `FullTextIndex.remove(primaryKey, fields)` on the backing index so that all terms for the primary key are removed. Null `fieldValue` is a no-op per R60.

R84. `FullTextFieldIndex.lookup` for `FullTextMatch` must translate the predicate to `jlsm.core.indexing.Query.TermQuery(field, query)` and return the iterator produced by `FullTextIndex.search`; it must reject any other predicate shape with `UnsupportedOperationException`. `FullTextFieldIndex.close()` must be idempotent and must close the backing index exactly once; subsequent calls are no-ops.

### VectorFieldIndex

R85. `VectorFieldIndex` must implement `SecondaryIndex` and must be a final class in `jlsm.table.internal`.

R86. `VectorFieldIndex.supports` must return true only for `VectorNearest` predicates whose field matches the index's field.

R87. `VectorFieldIndex.onInsert` must extract the vector from the field value and insert it into the backing vector index keyed by primary key.

R88. `VectorFieldIndex.onUpdate` must remove the old vector and insert the new vector for the given primary key.

R89. `VectorFieldIndex.onDelete` must remove the vector associated with the given primary key from the backing index.

R90. `VectorFieldIndex.lookup` for `VectorNearest` must return the `topK` closest primary keys to the query vector according to the configured similarity function.

### IndexRegistry — index lifecycle management

R91. `IndexRegistry` must be a final class in `jlsm.table.internal` implementing `Closeable`.

R92. `IndexRegistry` must accept a `JlsmSchema` and a list of `IndexDefinition` values at construction, validating each definition against the schema.

R93. `IndexRegistry` must reject an `IndexDefinition` whose `fieldName` does not exist in the schema with an `IllegalArgumentException`.

R94. `IndexRegistry` must reject an `EQUALITY`, `RANGE`, or `UNIQUE` index on a non-primitive, non-BoundedString field type with an `IllegalArgumentException`.

R95. `IndexRegistry` must reject a `RANGE` or `UNIQUE` index on a `BOOLEAN` field with an `IllegalArgumentException`.

R96. `IndexRegistry` must reject a `FULL_TEXT` index on a field that is not `STRING` or `BoundedString` with an `IllegalArgumentException`.

R97. `IndexRegistry` must reject a `VECTOR` index on a field that is not `VectorType` with an `IllegalArgumentException`.

R98. `IndexRegistry.onInsert(primaryKey, document)` must route the insert to all registered indices, extracting the field value from the document for each index.

R99. `IndexRegistry.onUpdate(primaryKey, oldDocument, newDocument)` must route the update to all registered indices with the old and new field values.

R100. `IndexRegistry.onDelete(primaryKey, document)` must route the delete to all registered indices.

R101. `IndexRegistry.findIndex(predicate)` must return the first registered `SecondaryIndex` that supports the given predicate, or null if no index supports it.

R102. `IndexRegistry.close()` must close all registered indices, accumulating exceptions via the deferred pattern and throwing after all indices have been closed.

R103. `IndexRegistry` must maintain a document store mapping primary keys to their documents for scan-and-filter query execution.

R104. `IndexRegistry.resolveEntry(primaryKey)` must return the stored entry for the given primary key, or null if not found.

R105. `IndexRegistry.allEntries()` must return an iterator over a snapshot of all stored entries, safe against concurrent modification during iteration.

### Null field value handling

R106. When a document is inserted with a null value for an indexed field, no entry must be added to that field's index.

R107. When a document is updated and the new value for an indexed field is null, the old entry must be removed from the index and no new entry must be added.

R108. When a document is deleted and the field value is null, the delete must be a no-op for that field's index.

### Update atomicity

R109. `FieldIndex.onUpdate` must remove the old field value entry before inserting the new field value entry.

R110. If the old and new field values are identical, `FieldIndex.onUpdate` must still perform the remove-then-insert cycle to ensure index consistency.

### QueryExecutor — query planning and execution

R111. `QueryExecutor<K>` must be a final class in `jlsm.table.internal` parameterized by the primary key type `K`.

R112. `QueryExecutor.execute(predicate)` must reject a null predicate with a `NullPointerException`.

R113. For a leaf predicate where an index exists (determined by `IndexRegistry.findIndex`), `QueryExecutor` must use the index's `lookup` method to retrieve matching primary keys.

R114. For a leaf predicate where no index exists, `QueryExecutor` must fall back to scan-and-filter: iterate all entries in the `IndexRegistry` document store and evaluate the predicate against each entry's field values.

R115. For `Predicate.And`, `QueryExecutor` must compute the intersection of results from all child predicates.

R116. For `Predicate.Or`, `QueryExecutor` must compute the union of results from all child predicates.

R117. For `FullTextMatch` and `VectorNearest` predicates without a corresponding index, the scan-and-filter fallback must throw `UnsupportedOperationException` with a message identifying the field and the required index type. These predicates require index backing and cannot be evaluated row-by-row.

R118. `QueryExecutor` must deduplicate results by primary key so that no entry appears more than once in the output iterator.

### Scan-and-filter predicate evaluation

R119. Scan-and-filter for `Eq` must match entries where the field value is non-null and equals the query value.

R120. Scan-and-filter for `Ne` must match entries where the field value is non-null and does not equal the query value.

R121. Scan-and-filter for `Gt`, `Gte`, `Lt`, `Lte` must match entries where the field value is a `Comparable` and compares appropriately against the query value. When field value and query value share a class, use natural `compareTo`; when both are numeric (`java.lang.Number` subtypes) but differ in class, widen to `double` if either is floating-point, otherwise to `long`. Non-numeric class mismatches must produce no match.

R122. Scan-and-filter for `Between` must match entries where the field value is a `Comparable` and falls within the inclusive range `[low, high]` using the same coercion rules as R121. The `Between` record itself enforces that `low` and `high` share a class at construction (R18 guardrail).

R123. Scan-and-filter must treat null field values as non-matching for all comparison predicates.

### Index-schema type compatibility

R124. `FieldValueCodec.encode` must reject a value whose Java type does not match the expected type for the field type (e.g., passing a String for an INT32 field) with an `IllegalArgumentException`.

R125. `FieldValueCodec` must use the schema-declared field type for encoding rather than inferring from the runtime value type, when the schema field type is available.

### Index close and resource cleanup

R126. `FieldIndex.close()` must set a closed flag and clear its internal data structures.

R127. After `FieldIndex.close()`, all mutation and lookup operations must throw `IllegalStateException` indicating the index is closed.

R128. `IndexRegistry.close()` must close all registered indices and must not leak resources even if one index close throws an exception.

### JPMS module boundaries

R129. `SecondaryIndex`, `FieldIndex`, `FullTextFieldIndex`, `VectorFieldIndex`, `FieldValueCodec`, `IndexRegistry`, and `QueryExecutor` must reside in `jlsm.table.internal`, which must not be exported in the `jlsm-table` module descriptor.

R130. `IndexType`, `IndexDefinition`, `Predicate`, `TableQuery`, `TableEntry`, and `DuplicateKeyException` must reside in `jlsm.table`, which must be exported in the `jlsm-table` module descriptor.

### Thread safety

R131. `IndexRegistry` must expose closed-state transitions atomically to all threads without external synchronization. Implementations must use either a `volatile` flag or a stronger primitive such as `AtomicBoolean` with `compareAndSet` to guarantee a single close winner.

R132. `FieldIndex` must use a volatile closed flag so that the closed state is visible to all threads.

### FieldIndex key comparison

R133. `ByteArrayKey.compareTo` must compare byte arrays using unsigned byte values, so that sort-preserving encoded keys produce correct ordering across all primitive types.

R134. `ByteArrayKey` must implement `equals` and `hashCode` based on array content, not reference identity.

### Audit-hardened requirements

R135. `IndexRegistry` read-only query methods (`findIndex`, `isEmpty`, `resolveEntry`, `allEntries`, `schema`) must acquire the read lock before checking the closed flag and accessing internal state.

R136. `IndexRegistry.onUpdate` and `IndexRegistry.onDelete` must place the `documentStore` mutation inside the try/catch rollback scope, matching the transactional consistency pattern used by `onInsert`.

R137. `IndexRegistry.close()` must accumulate exceptions from all resources (indices and arena) using the deferred exception pattern, never silently losing an exception when multiple resources fail.

R138. `IndexRegistry` must reject an `EQUALITY` index on a `BOOLEAN` field with an `IllegalArgumentException`, matching the rejection behavior of `RANGE` and `UNIQUE` index types on `BOOLEAN`.

R139. `IndexRegistry.extractFieldValue` must return a defensive copy of vector arrays (`float[]` and `short[]`), not a reference to the document's internal array.

---

## Design Narrative

### Intent

Enable secondary index support and a fluent query API for `JlsmTable`. Users define indices via `IndexDefinition` records passed to the table builder. Five index types cover the common query patterns: point lookups (EQUALITY), range scans (RANGE), uniqueness constraints (UNIQUE), full-text search (FULL_TEXT), and vector similarity search (VECTOR). Indices are maintained synchronously on every write (create, update, delete) so they are always consistent with the primary data. The `TableQuery` fluent API provides a type-safe way to build predicate trees and execute them against the table's indices with automatic fallback to scan-and-filter when no index covers a predicate.

### Why this approach

**Synchronous index maintenance over async rebuilds:** Synchronous maintenance keeps indices always consistent with primary data. Async index building introduces a consistency window where queries return stale results, adds background thread management complexity, and requires a replay mechanism for mutations during the build. The synchronous approach is simpler and correct by construction.

**Two-phase unique validation in IndexRegistry:** Unique constraints are validated across all unique indices before any mutation begins. This prevents a partial insert where index A succeeds but index B rejects the value, leaving index A with an orphan entry. The two-phase approach (validate all, then mutate all) ensures atomicity without requiring rollback logic.

**Sort-preserving binary encoding via FieldValueCodec:** Encoding field values into a sort-preserving binary form allows the index to use a single `TreeMap<ByteArrayKey, ...>` for all field types. Sign-bit-flip for integers and IEEE 754 reinterpretation for floats are well-established techniques that map signed numeric ordering to unsigned byte ordering. This avoids type-specific comparators and enables a uniform index implementation.

**Sealed SecondaryIndex interface:** The sealed hierarchy (`FieldIndex`, `FullTextFieldIndex`, `VectorFieldIndex`) makes it explicit which index implementations exist. Each implementation wraps a different backing structure (sorted map, inverted index, vector index) while presenting a uniform mutation and lookup interface to `IndexRegistry`.

**Scan-and-filter fallback:** When no index covers a predicate, the query executor scans all documents and evaluates the predicate in memory. This ensures every valid predicate produces results even without index coverage, at the cost of a full scan. The user controls performance by choosing which fields to index.

### What was ruled out

- **Composite indices (multi-field):** Significantly increases key encoding complexity, index maintenance cost, and planner logic. Single-field indices with And/Or composition cover the initial use cases. Composite indices are deferred to a future spec.
- **Index-only queries:** Would require the index to store the full document or selected fields, increasing storage and maintenance cost. All queries currently resolve to the primary document store after index lookup.
- **Async index building:** See above — introduces consistency complexity disproportionate to the benefit for the current in-process use case.
- **Cost-based query planner:** The current planner uses the first matching index. A cost-based approach would require cardinality estimation and index selectivity tracking, which are premature at this stage.
- **Aggregation support:** COUNT, SUM, AVG, etc. require a different execution model (accumulate-and-reduce rather than iterate-and-filter). Out of scope for the initial query API.

### Out of scope

- Composite (multi-field) indices
- SQL parsing and translation (covered by F07)
- Index-only queries (queries that avoid primary key lookup)
- Async or background index building
- Aggregation queries (COUNT, SUM, AVG, MIN, MAX, GROUP BY)
- Index-level locking or concurrent write coordination beyond volatile flags
- Persistent index storage independent of the primary LSM tree
- Query result ordering or pagination

---

## Verification Notes

### Verified: v2 — 2026-04-17

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | SATISFIED | `IndexType` enum — five constants |
| R2–R4 | SATISFIED | `FieldIndex.supports` per-type dispatch |
| R5 | SATISFIED | `IndexRegistry.validate` enforces STRING/BoundedString; `FullTextFieldIndex` delegates to `FullTextIndex.Factory`-supplied backing (WD-01) |
| R6 | DEFERRED (OBL-F10-vector) | `IndexRegistry.validate` enforces VectorType; `VectorFieldIndex` is a stub |
| R7–R13 | SATISFIED | `IndexDefinition` record + compact ctor validation |
| R14 | SATISFIED | `Predicate` sealed interface with 11 nested record permits |
| R15–R29 | SATISFIED | leaf record compact ctors; `And`/`Or` defensive copy via `List.copyOf` |
| R30–R38 | SATISFIED | `TableQuery` fluent builder; unbound `execute()` throws UOE |
| R39–R52 | SATISFIED | `FieldValueCodec` — sort-preserving encoding verified by `FieldValueCodecTest` |
| R53–R62 | SATISFIED | `SecondaryIndex` sealed interface; FieldIndex satisfies all; stub impls tracked by obligations |
| R63–R73 | SATISFIED | `FieldIndex` — `ConcurrentSkipListMap<ByteArrayKey, List<MemorySegment>>` |
| R74–R78 | SATISFIED | Two-phase unique validation in `IndexRegistry.onInsert`/`onUpdate` with rollback |
| R79–R84 | SATISFIED | `FullTextFieldIndex` delegates to `FullTextIndex<MemorySegment>` supplied by `FullTextIndex.Factory`; `LsmFullTextIndexFactory` provides the LSM-backed implementation in `jlsm-indexing` (WD-01) |
| R85–R90 | DEFERRED (OBL-F10-vector) | `VectorFieldIndex` is a stub — mutation is no-op, lookup throws |
| R91–R105 | SATISFIED | `IndexRegistry` — `ReentrantReadWriteLock`, `AtomicBoolean`, deferred-exception close |
| R106–R110 | SATISFIED | `FieldIndex.onUpdate` remove-then-insert; null-field early returns |
| R111–R118 | SATISFIED | `QueryExecutor` — index routing via `findAndLookup`, `LinkedHashSet` dedup; R117 code throws UOE (amended) |
| R119–R123 | SATISFIED | `QueryExecutor.matchesPredicate` — null-safe, `compareCoerced` widens numerics (amended) |
| R124–R125 | SATISFIED | `FieldValueCodec` per-type instanceof checks; `FieldIndex.resolveFieldType` prefers schema |
| R126–R128 | SATISFIED | `FieldIndex.close` + `IndexRegistry.close` — deferred-exception pattern |
| R129–R130 | SATISFIED | `jlsm.table` module exports `jlsm.table` only; `internal` package not exported |
| R131 | SATISFIED | `IndexRegistry` uses `AtomicBoolean` with `compareAndSet` (amended — stronger than volatile) |
| R132 | SATISFIED | `FieldIndex` — `volatile boolean closed` |
| R133–R134 | SATISFIED | `ByteArrayKey.compareTo` uses `Byte.toUnsignedInt`; equals/hashCode via `Arrays` |
| R135–R139 | SATISFIED | audit-hardened read-lock scopes, documentStore in rollback scope, deferred close exceptions, EQUALITY-on-BOOLEAN reject, defensive vector-array copy |

**Overall: PASS_WITH_NOTES** — 128 SATISFIED, 11 DEFERRED via obligations (FullText and Vector index stubs).

**Amendments applied (v1 → v2):**
- **R46:** "sign-bit-flip encoding" → "IEEE 754 sort-preserving encoding on the 2-byte raw representation: if the sign bit is set, invert all bits; otherwise set the sign bit." Reason: simple sign-bit-flip does not preserve ordering across negative float values; the implementation has always done the correct IEEE 754 transform for 2-byte half-precision.
- **R64:** "assert on construction" → "reject ... with an `IllegalArgumentException`". Reason: project `code-quality.md` requires runtime checks for input guards; `assert` alone is disabled in production.
- **R117:** "fallback must return no results" → "fallback must throw `UnsupportedOperationException` with a message identifying the field and the required index type". Reason: explicit failure is preferable to silent empty results for predicates that cannot be scanned.
- **R121, R122:** "same class" → coercion rules (widen to `double` if either is floating-point, else `long`) with non-numeric mismatches producing no match. Reason: `QueryExecutor.compareCoerced` enables useful cross-type numeric comparisons; original wording forbade this.
- **R127:** "rejected (via assert)" → "throw `IllegalStateException`". Reason: same as R64.
- **R131:** "must use a volatile closed flag" → "must use either `volatile` or a stronger primitive such as `AtomicBoolean` with `compareAndSet`". Reason: `IndexRegistry` uses `AtomicBoolean` to guarantee a single close winner, which is stricter than volatile.

**Obligations opened:**
- **OBL-F10-fulltext** — R5 (PARTIAL) and R79–R84 (VIOLATED). `FullTextFieldIndex` is a stub; wiring to `LsmFullTextIndex` from `jlsm-indexing` is deferred to a future feature.
- **OBL-F10-vector** — R6 (PARTIAL) and R85–R90 (VIOLATED). `VectorFieldIndex` is a stub; wiring to `LsmVectorIndex` from `jlsm-vector` is deferred to a future feature.

**Test coverage:** existing behavioral tests cover ~90% of SATISFIED requirements (IndexDefinitionTest, PredicateTest, TableQueryTest, FieldValueCodecTest, FieldIndexTest, IndexRegistryTest, QueryExecutorTest, plus TableIndicesAdversarialTest, TableIndicesRound2AdversarialTest). `@spec F10.RN` annotations are added to representative enforcement points and tests; structural-reflection test coverage for enum arity, sealed permits, and package visibility is a separate follow-up.

**Regression tests added:** none (no VIOLATED requirements repaired — all violations deferred per user direction).

**Code fixes applied:** none.

#### Amendments (v2 → v3)

Landed under `cross-module-integration/WD-01` (2026-04-20); resolves `OBL-F10-fulltext`.

- **R5 (PARTIAL → SATISFIED):** amended to codify that the FULL_TEXT integration path delegates to a `FullTextIndex<MemorySegment>` supplied by a `FullTextIndex.Factory` SPI. This keeps the one-way dependency arrow from `jlsm-table` → `jlsm-core` intact — `jlsm-table` never imports `jlsm-indexing`.
- **R79 (VIOLATED → SATISFIED):** amended to require constructor injection of the backing `FullTextIndex<MemorySegment>` and to reject non-FULL_TEXT index types with `IllegalArgumentException`. The prior stub that threw `UnsupportedOperationException` on every method is replaced by a real adapter.
- **R80 (VIOLATED → SATISFIED):** amended to explicitly require `supports` to return false for all non-FullTextMatch predicates and after close.
- **R81, R82, R83 (VIOLATED → SATISFIED):** amended to describe the mutation-routing contract in terms of the `FullTextIndex.index` / `FullTextIndex.remove` batch API rather than lower-level term tokenisation (tokenisation lives inside the backing implementation).
- **R84 (VIOLATED → SATISFIED):** amended to describe the `FullTextMatch → Query.TermQuery` translation path, to reject non-FullTextMatch predicates with `UnsupportedOperationException`, and to require idempotent `close()`.

Supporting artefacts:
- `jlsm.core.indexing.FullTextIndex.Factory` — factory SPI in `jlsm-core`.
- `jlsm.indexing.LsmFullTextIndexFactory` — concrete factory in `jlsm-indexing` producing LSM-backed indices keyed by `(tableName, fieldName)`.
- `StandardJlsmTable.StringKeyedBuilder.addIndex(...)` + `.fullTextFactory(...)` — table-builder surface for registering FULL_TEXT indices. The builder rejects FULL_TEXT definitions with no factory at `build()` time (fail-fast at table creation, not on first write).

Open obligation after WD-01: `OBL-F10-vector` remains (VectorFieldIndex wiring — scope of WD-02).
