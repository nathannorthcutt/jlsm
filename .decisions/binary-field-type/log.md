## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** bounded-string-field-type
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-13 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Full evaluation started. Constraint profile captured — large object support (images/video alongside embeddings) is the primary use case. KB source: schema-type-systems.md.

**Files written/updated:**
- `constraints.md` — constraint profile

---

## 2026-04-13 — scope-widened

**Agent:** Architect Agent
**Event:** scope-widened
**Summary:** User challenged recommendation during deliberation. Binary sealed permit is the right type system choice but shipping without large-object storage is half-baked. Scope widened to include blob store design. Research commissioned for blob store patterns in LSM-backed storage. User insight: blob store can use existing LSM primitives with dual-write pattern (write blob → get ref → write document).

---

## 2026-04-13 — research-commissioned

**Agent:** Architect Agent
**Event:** research-commissioned
**Summary:** Commissioned research on blob store patterns for LSM-tree storage. Covers RocksDB BlobDB, WiscKey, GridFS, chunking, GC, dual-write atomicity.

**Files written/updated:**
- `.kb/systems/database-engines/blob-store-patterns.md` — full KB article

---

## 2026-04-13 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Binary sealed permit + opaque BlobRef + BlobStore SPI confirmed after 3 rounds of deliberation. User challenged twice: (1) inline storage won't work for large objects, (2) blob store should be an abstract SPI, not prescribed LSM implementation.

### Deliberation Summary

**Rounds of deliberation:** 3
**Recommendation presented:** Binary sealed permit (inline)
**Final decision:** Binary sealed permit + BlobRef + BlobStore SPI (revised twice)

**Topics raised during deliberation:**
- Round 1: Falsification revealed DocumentSerializer byte[] buffer can't handle multi-MiB payloads. User agreed scope must widen to include blob store.
- Round 2: User pointed out blob store should be built on existing LSM primitives with dual-write. Research commissioned and completed.
- Round 3: User challenged prescribing LSM-backed implementation — BlobRef should be opaque, resolved by any BlobStore implementation. Object storage backends (S3/GCS) should upload directly, not double-write through LSM. Revised to SPI.

**Constraints updated during deliberation:**
- Scale: widened from "small and large inline" to "large objects require external storage via SPI"
- Fit: added composability requirement (BlobStore as SPI, not prescribed implementation)

**Assumptions explicitly confirmed by user:**
- BlobRef is opaque and system-resolved — storage mechanism is behind the SPI
- Object storage backends may upload blobs directly (not through LSM)
- The default LSM-backed BlobStore is a separate feature

**Override:** None — recommendation evolved through deliberation to the confirmed position.

**Confirmation:** User confirmed with: "yes"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — updated with falsification findings

**KB files read during evaluation:**
- [`.kb/systems/database-engines/schema-type-systems.md`](../../.kb/systems/database-engines/schema-type-systems.md)
- [`.kb/systems/database-engines/blob-store-patterns.md`](../../.kb/systems/database-engines/blob-store-patterns.md)

---
