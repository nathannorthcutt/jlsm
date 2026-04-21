---
group: cross-module-integration
goal: Wire stub index implementations through module boundaries to resolve query.index-types and engine.in-process-database-engine obligations
status: active
created: 2026-04-19
---

## Goal

Resolve three cross-module integration obligations where spec requirements
exist but implementation is a stub (throws UnsupportedOperationException or
silently no-ops). Each requires wiring a real implementation from one module
(jlsm-indexing, jlsm-vector) through the jlsm-core interface boundary into
jlsm-table.

## Scope

### In scope
- OBL-query.index-types-fulltext: Wire LsmFullTextIndex from jlsm-indexing through jlsm-core
  (query.full-text-index.R1-R84, R5 partial)
- OBL-query.index-types-vector: Wire LsmVectorIndex (IvfFlat/Hnsw) from jlsm-vector through
  jlsm-core (query.vector-index.R1-R90, R6 partial)
- OBL-engine.in-process-database-engine-R37: Wire Table.query() through StringKeyedTable to QueryExecutor
  (engine.in-process-database-engine.R37), blocked on query.index-types query-binding infrastructure

### Out of scope
- New index types or algorithms
- query.index-types requirements unrelated to fulltext/vector integration (R1-R78 already work)
- engine.in-process-database-engine requirements unrelated to query binding

## Ordering Constraints

engine.in-process-database-engine query binding depends on query.index-types query infrastructure — specifically on
TableQuery becoming instantiable outside jlsm.table. Either fulltext or
vector integration must land first to validate the index wiring pattern,
then query binding can follow.

## Shared Interfaces
IndexPersistence interface between jlsm-core and jlsm-indexing/jlsm-vector
may need to be defined or formalized as part of this work.

## Success Criteria
- All three obligation IDs resolved (status: resolved in _obligations.json)
- query.index-types spec promoted from DRAFT to APPROVED (fulltext + vector requirements met)
- engine.in-process-database-engine.R37 no longer throws UnsupportedOperationException
- No regressions in existing query.index-types or engine.in-process-database-engine tests
