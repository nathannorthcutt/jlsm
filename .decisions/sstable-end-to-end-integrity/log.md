## 2026-04-11 — deferred

**Agent:** Architect Agent
**Event:** deferred
**Summary:** SSTable end-to-end integrity deferred as out-of-scope from per-block-checksums. Condition: when format is next revised or when non-block corruption is observed.

---

## 2026-04-14 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Three-layer integrity confirmed: fsync discipline (prevention) + VarInt-prefixed blocks (recovery) + per-section CRC32C (detection). User challenged initial CRC-only proposal — reframed to include recovery capability.

### Deliberation Summary

**Rounds of deliberation:** 3
**Recommendation presented:** Per-section CRC32C only (initial)
**Final decision:** Three-layer integrity (revised after user challenges)

**Topics raised during deliberation:**
- User challenged CRC-only: "this detects failures but gives no way to recover"
  Response: Identified recovery-enabling metadata (block count, bloom config, key hashes, compression map redundancy)
- User challenged redundant copy approach: "writing a second entry doesn't work if we assume the first was corrupted"
  Response: Shifted to self-describing blocks (inline boundary markers) and process guarantees (fsync discipline)
- User refined: "what if we use length encoded values so it's only 4 bytes in the worst case?"
  Response: VarInt (LEB128) encoding — 2 bytes common case (4 KiB blocks), 4 bytes worst case (32 MiB)
- User confirmed three-layer approach: fsync + VarInt blocks + CRC

**Constraints updated during deliberation:**
- Added: data recoverability (compression map is single point of failure)
- Added: prevention via fsync discipline for local filesystems
- Added: minimal overhead via VarInt encoding

**Assumptions explicitly confirmed by user:**
- Remote backends have atomic writes; fsync is a local-only concern
- VarInt-prefixed blocks are acceptable overhead for recovery capability
- The footer magic number is sufficient as a commit marker

**Override:** None — recommendation revised through deliberation

**Confirmation:** User confirmed with: "Confirm" (via AskUserQuestion)

**Files written after confirmation:**
- `adr.md` — decision record v2 (was deferred v1, now confirmed)
- `constraints.md` — written during session
- `evaluation.md` — 2 candidates scored

**KB files read during evaluation:**
- [`.kb/systems/database-engines/corruption-detection-repair.md`](../../.kb/systems/database-engines/corruption-detection-repair.md)

---
