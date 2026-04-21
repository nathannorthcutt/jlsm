---
{
  "id": "query.vector-index",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "query"
  ],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_migrated_from": [
    "F10"
  ]
}
---

# query.vector-index — Vector Index

## Requirements

### VectorFieldIndex

R1. `VectorFieldIndex` must implement `SecondaryIndex` and must be a final class in `jlsm.table.internal`.

R2. `VectorFieldIndex.supports` must return true only for `VectorNearest` predicates whose field matches the index's field.

R3. `VectorFieldIndex.onInsert` must extract the vector from the field value and insert it into the backing vector index keyed by primary key. The backing implementation is obtained via `VectorIndex.Factory.create(tableName, fieldName, dimensions, precision, similarityFunction)`; when a table registers a `VECTOR` index without a configured factory, `build()` must fail with `IllegalArgumentException` rather than silently accepting unindexed writes.

R4. `VectorFieldIndex.onUpdate` must remove the old vector and insert the new vector for the given primary key. When the old vector is absent (field previously unset), the removal step is a no-op and the insert still proceeds.

R5. `VectorFieldIndex.onDelete` must remove the vector associated with the given primary key from the backing index.

R6. `VectorFieldIndex.lookup` for `VectorNearest` must return the `topK` closest primary keys to the query vector according to the configured similarity function, using the backing implementation's `search(queryVector, topK)` call.
