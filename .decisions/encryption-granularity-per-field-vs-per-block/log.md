# encryption-granularity-per-field-vs-per-block — Decision Log

## 2026-04-24 — deferred

**Agent:** Architect Agent
**Event:** deferred
**Summary:** Scoped out during `sstable-footer-scope-format` decision. User explicitly chose not to pursue per-block AES-GCM migration after risks enumerated (breaks OPE/DCPE variants, violates ciphertext-envelope R1a, cascades across 6+ APPROVED specs, 2–3 month rework cost).

**Files written/updated:**
- `adr.md` — deferred stub

**Originating decision:** [`.decisions/sstable-footer-scope-format/adr.md`](../sstable-footer-scope-format/adr.md)

---
