# Work Group Manifest: implement-encryption-lifecycle

**Goal:** Implement the encryption.primitives-lifecycle DRAFT spec (F41 decomposed by section) — key hierarchy, ciphertext format, DEK lifecycle, KEK rotation, compaction-driven re-encryption, and runtime concerns.
**Status:** active
**Created:** 2026-04-21
**Work definitions:** 5

## Work Definitions

| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|
| WD-01 | Key hierarchy (root KEK, data KEKs, DEKs) | COMPLETE | encryption | 0 | — |
| WD-02 | Ciphertext format + signalling | COMPLETE | encryption,  sstable,  engine | 0 | — |
| WD-03 | DEK lifecycle + KEK rotation | IMPLEMENTING | encryption | 0 | — |
| WD-04 | Compaction-driven migration | BLOCKED | encryption | 1 | — |
| WD-05 | Runtime concerns (memory handling, key caching, zeroisation) | BLOCKED | encryption | 1 | — |

## Dependency Graph

```
WD-01 (key-hierarchy)
  └→ WD-02 (ciphertext-format + signalling)
        └→ WD-03 (DEK lifecycle + KEK rotation)
              ├→ WD-04 (compaction-driven migration)
              └→ WD-05 (runtime concerns)

Critical path: WD-01 → WD-02 → WD-03. WD-04 and WD-05 parallel after WD-03.
```
