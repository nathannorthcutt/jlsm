---
title: "Composite-key re-index orphan"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Composite-key re-index orphan

## What happens
When a key-value store uses composite keys that embed a mutable assignment
(e.g., `[prefix][assignment_id][entity_id] -> data`), re-indexing an entity
with a different assignment creates a new key while the old key persists.
The reverse-lookup (entity -> assignment) is overwritten, so remove() only
cleans up the new assignment. The old posting becomes a phantom that appears
in scans and search results.

## Why implementations default to this
The happy-path write is a simple put() — fast and correct for first-time
indexing. The update path requires an extra read (reverse lookup) + conditional
delete before writing. It's easy to overlook because first-index tests pass
and update-specific tests are rarely written during initial TDD.

## Test guidance
- Always test re-indexing the same entity with a value that changes its
  assignment (e.g., a vector that moves to a different centroid/partition)
- After re-index, search from the OLD assignment's perspective and verify
  the entity does NOT appear under the old assignment
- Count total occurrences of the entity across all results — must be <= 1

## Found in
- float16-vector-support (audit round 1, 2026-03-25): IvfFlat.index() left orphan posting under old centroid when re-indexing same docId
