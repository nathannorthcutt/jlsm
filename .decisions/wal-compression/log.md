## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** sstable-block-compression-format
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-12 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Full evaluation started. Constraint profile captured with 6 dimensions. Key tension identified: MemorySegment-first policy vs byte[]-based CompressionCodec interface.

**Files written:**
- `constraints.md` — full constraint profile with falsification

---

## 2026-04-12 — scope-widened

**Agent:** Architect Agent
**Event:** scope-widened
**Summary:** User directed that CompressionCodec API should be evolved to MemorySegment as part of this work, pulling memorysegment-codec-api into scope. This is a deliberate choice to eat the refactoring cost now rather than work around byte[].

---

## 2026-04-12 — research-commissioned

**Agent:** Architect Agent
**Event:** research-commissioned
**Summary:** Commissioned research on WAL compression patterns in LSM implementations (RocksDB, LevelDB, Cassandra, ScyllaDB). Focus: per-record format design, metadata encoding, recovery implications. Also multi-writer WAL patterns per user request.

**Files written:**
- `research-brief.md` — research commission

**KB files written:**
- `.kb/algorithms/compression/wal-compression-patterns.md`
- `.kb/distributed-systems/data-partitioning/multi-writer-wal.md`

---

## 2026-04-12 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Per-record compression with MemorySegment-native codec API confirmed after deliberation. Zero-copy path via Deflater ByteBuffer overloads + MemorySegment.asByteBuffer() validated.

### Deliberation Summary

**Rounds of deliberation:** 3
**Recommendation presented:** Per-Record + MemorySegment-Native API
**Final decision:** Per-Record + MemorySegment-Native API (same as presented)

**Topics raised during deliberation:**
- Falsification challenged Resources score of 5 — claimed DeflateCodec copies byte[] internally regardless of API
  Response: Score revised to 3 in interim recommendation
- User challenged the assumption that Deflater requires byte[] — asked about custom MemorySegment implementation
  Response: Discovered Deflater.setInput(ByteBuffer) / deflate(ByteBuffer) overloads. MemorySegment.asByteBuffer() returns isDirect=true for Arena-allocated segments. This provides true zero-copy: native zlib reads directly from MemorySegment's native memory. Score restored to 5.

**Constraints updated during deliberation:**
- MemorySegment-first constraint strengthened — user directed that CompressionCodec API must evolve, pulling memorysegment-codec-api into scope

**Assumptions explicitly confirmed by user:**
- DeflateCodec can use ByteBuffer overloads for zero-copy (verified via runtime check)
- Eat the refactoring cost — migrate all callers, not just WAL

**Override:** None

**Confirmation:** User confirmed with: "Confirmed"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — no changes after initial capture

**KB files read during evaluation:**
- [`.kb/algorithms/compression/wal-compression-patterns.md`](../../.kb/algorithms/compression/wal-compression-patterns.md)
- [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md)
- [`.kb/distributed-systems/data-partitioning/multi-writer-wal.md`](../../.kb/distributed-systems/data-partitioning/multi-writer-wal.md)

---
