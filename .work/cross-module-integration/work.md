---
group: cross-module-integration
goal: Wire stub index implementations through module boundaries to resolve F10 and F05 obligations
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
- OBL-F10-fulltext: Wire LsmFullTextIndex from jlsm-indexing through jlsm-core
  (F10.R79-R84, R5 partial)
- OBL-F10-vector: Wire LsmVectorIndex (IvfFlat/Hnsw) from jlsm-vector through
  jlsm-core (F10.R85-R90, R6 partial)
- OBL-F05-R37: Wire Table.query() through StringKeyedTable to QueryExecutor
  (F05.R37), blocked on F10 query-binding infrastructure

### Out of scope
- New index types or algorithms
- F10 requirements unrelated to fulltext/vector integration (R1-R78 already work)
- F05 requirements unrelated to query binding

## Ordering Constraints

F05 query binding depends on F10 query infrastructure — specifically on
TableQuery becoming instantiable outside jlsm.table. Either fulltext or
vector integration must land first to validate the index wiring pattern,
then query binding can follow.

## Shared Interfaces
IndexPersistence interface between jlsm-core and jlsm-indexing/jlsm-vector
may need to be defined or formalized as part of this work.

## Success Criteria
- All three obligation IDs resolved (status: resolved in _obligations.json)
- F10 spec promoted from DRAFT to APPROVED (fulltext + vector requirements met)
- F05.R37 no longer throws UnsupportedOperationException
- No regressions in existing F10 or F05 tests
