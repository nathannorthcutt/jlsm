# sstable-active-tamper-defence — Decision Log

## 2026-04-24 — deferred

**Agent:** Architect Agent
**Event:** deferred
**Summary:** Scoped out during `sstable.footer-encryption-scope` spec authoring. User chose Model 2 threat-model boundary: active on-disk tamper is delegated to the storage substrate (local FS ACLs + authenticated object store IAM). Partial integrity patches (e.g., catalog per-SSTable encryption-era tracking to close the specific v5-swap attack) rejected as worse than clean boundary because they give false confidence.

**Files written/updated:**
- `adr.md` — deferred stub

**Originating spec:** [`.spec/domains/sstable/footer-encryption-scope.md`](../../.spec/domains/sstable/footer-encryption-scope.md) R13

---
