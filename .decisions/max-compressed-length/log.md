## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** compression-codec-api-design
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-10 — accepted

**Agent:** Architect Agent (batch evaluation)
**Event:** accepted
**Summary:** Add `maxCompressedLength(int)` to CompressionCodec interface.
First step toward MemorySegment/zero-copy codec API. Default method on
interface preserves backward compatibility for custom codecs.

---
