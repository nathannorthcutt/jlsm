# encryption-disable-policy — Decision Log

## 2026-04-24 — deferred

**Agent:** Architect Agent
**Event:** deferred
**Summary:** Scoped out during `table-handle-scope-exposure` decision. User confirmed encryption is one-way. Industry precedent (CockroachDB, TiKV, MySQL InnoDB TDE, MongoDB CSFLE, DuckDB) either doesn't support in-place disable or implements as copy-under-the-hood. Compliance risk (plaintext-during-drain window) and state-machine cost are not justified without concrete user demand.

**Files written/updated:**
- `adr.md` — deferred stub

**Originating decision:** [`.decisions/table-handle-scope-exposure/adr.md`](../table-handle-scope-exposure/adr.md)

---
