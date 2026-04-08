---
title: "Soft-delete reindex tombstone persistence"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Soft-delete reindex tombstone persistence

## What happens
When an index uses soft-delete tombstones (writing a marker key like
`[0xFF][docId]` instead of eagerly removing all references), the insert/update
path must clear the tombstone for any previously removed doc ID. If index()
only writes the new data without deleting the soft-delete key, the doc appears
to be re-indexed but search() still filters it out via the persistent tombstone.

## Why implementations default to this
Soft-delete is chosen for lazy cleanup (avoiding expensive graph/link updates
on remove). The insert path is written for the common case — indexing a new doc
that was never removed. The remove-then-reindex sequence is rare in tests but
common in production (e.g., updating a document triggers remove + re-insert).

## Test guidance
- Always test the sequence: index(X) → remove(X) → index(X, newVec) → search
- Verify the re-indexed doc is visible in search results after the sequence
- Test with both the same vector and a different vector on re-index
- Check that the tombstone key namespace is clear after re-index

## Found in
- float16-vector-support (audit round 2, 2026-03-25): Hnsw.index() did not delete [0xFF][docId] soft-delete tombstone, making re-indexed docs invisible in search
