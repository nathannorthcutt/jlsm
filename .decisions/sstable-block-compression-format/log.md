## 2026-03-17 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Problem directory created for SSTable block compression format decision. Constraint profile captured with performance and remote-backend efficiency as top priorities.

**Files written/updated:**
- `constraints.md` — full constraint profile

**KB files read:**
- [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md)

---

## 2026-03-17 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Compression Offset Map confirmed as the SSTable v2 format for encoding per-block compression metadata.

### Deliberation Summary

**Rounds of deliberation:** 1
**Recommendation presented:** Compression Offset Map (Candidate B)
**Final decision:** Compression Offset Map (same as presented)

**Topics raised during deliberation:**
- User noted that large scans benefit from pulling multiple blocks from remote storage while keeping blocks small enough for page caches during individual decompression.
  Response: This aligns perfectly with the offset map design — map enables batch I/O planning, blocks stay page-cache-friendly.

**Constraints updated during deliberation:**
- Complexity Budget: user clarified that performance matters more than format simplicity; diagnostic tooling can handle complex formats
- Block size: user noted blocks may need to be larger for remote backends, or multi-block reads should be used; 4 KiB is not a hard constraint

**Assumptions explicitly confirmed by user:**
- Key index format change is expected and accepted
- Performance > complexity as a design priority
- Multi-block prefetch for remote backends is a key use case

**Override:** None

**Confirmation:** User confirmed with: "that seems reasonable to me and allows for the large scans to pull multiple blocks from the remote while leaving the blocks small enough to fit well into page caches for doing individual decompression operations"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — updated with complexity deprioritized, performance prioritized

**KB files read during evaluation:**
- [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md)

---
