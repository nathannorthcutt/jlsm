## 2026-04-11 — deferred

**Agent:** Architect Agent
**Event:** deferred
**Summary:** Block cache / block size interaction deferred as out-of-scope from backend-optimal-block-size. Condition: when block size variability causes observable cache performance issues.

---

## 2026-04-14 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Per-entry byte-budget eviction confirmed. Replaces entry-count capacity with byte tracking via MemorySegment.byteSize(). Handles mixed block sizes correctly.

### Deliberation Summary

**Rounds of deliberation:** 1
**Recommendation presented:** Per-entry byte accounting with eviction loop
**Final decision:** Per-entry byte accounting (same as presented)

**Topics raised during deliberation:**
- Initial evaluation favored byte-budget builder with entry-count derivation (Candidate 2/3, score 65)
- Falsification exposed mixed-block-size scenario: cache holds blocks from SSTables with different block sizes after compaction/transfer
- Falsification identified missing Candidate 5: per-entry byte accounting via MemorySegment.byteSize()
- Falsification challenged pool-aware derivation: pool buffer size (write-path) ≠ cache entry size (read-path)
- Recommendation revised to Candidate 5 based on falsification findings

**Constraints updated during deliberation:**
- Added: mixed block sizes in a single cache instance
- Added: pool buffer size ≠ cache entry size

**Assumptions explicitly confirmed by user:**
- Per-entry byte tracking is worth the small internal change
- Byte budget is a better API than entry count

**Override:** None

**Confirmation:** User confirmed with: "yes"

**Files written after confirmation:**
- `adr.md` — decision record v2 (was deferred v1, now confirmed)
- `constraints.md` — written during session
- `evaluation.md` — 5 candidates scored

**KB files read during evaluation:**
- [`.kb/data-structures/caching/concurrent-cache-eviction-strategies.md`](../../.kb/data-structures/caching/concurrent-cache-eviction-strategies.md)

---
