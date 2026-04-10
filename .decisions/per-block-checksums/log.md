## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** sstable-block-compression-format
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-10 — accepted

**Agent:** Architect Agent (batch evaluation)
**Event:** accepted
**Summary:** CRC32C per-block checksum added to CompressionMap.Entry. 4-byte
hardware-accelerated checksum, verified on read, computed on write. SSTable
footer version bumps to v3 with v2 backward compatibility.

---
