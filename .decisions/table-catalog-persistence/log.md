## 2026-03-19 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Constraint profile captured for table catalog persistence model. All six dimensions specified. Key narrowing constraints: lazy incremental recovery at 100K+ scale, per-table failure isolation, resource-constrained containers.

**Files written/updated:**
- `constraints.md` — full constraint profile

**KB files read:**
- None yet — KB survey pending

---

## 2026-03-19 — research-commissioned

**Agent:** Architect Agent
**Event:** research-commissioned
**Summary:** KB had no direct coverage of catalog persistence patterns. Commissioned /research systems database-engines "catalog-persistence-patterns". Research completed successfully, covering 4 patterns: manifest log (RocksDB), per-table directories, pointer swap (Iceberg), superblock (TigerBeetle).

**Files written/updated:**
- `.kb/systems/database-engines/catalog-persistence-patterns.md` — new KB entry

**KB files read:**
- [`.kb/systems/database-engines/catalog-persistence-patterns.md`](../../.kb/systems/database-engines/catalog-persistence-patterns.md)

---

## 2026-03-19 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Per-Table Metadata Directories confirmed after deliberation. User validated clustering compatibility — pattern is the most cluster-friendly of the four candidates.

### Deliberation Summary

**Rounds of deliberation:** 2
**Recommendation presented:** Per-Table Metadata Directories
**Final decision:** Per-Table Metadata Directories *(same as presented)*

**Topics raised during deliberation:**
- User asked about clustering gaps with distributed tables across engine nodes via consensus protocols
  Response: No hard gaps identified. Per-table directory is the natural unit of distribution. Future cluster-level catalog service layers on top. Recommended structuring engine API so cluster layer can intercept DDL calls.

**Constraints updated during deliberation:**
- None

**Assumptions explicitly confirmed by user:**
- Atomic cross-table DDL is not required
- Per-table directory is acceptable as the unit of distribution for future clustering
- Engine API should be structured for future cluster interception

**Override:** None
**Confirmation:** User confirmed with: "That seems good, approved"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — no changes

**KB files read during evaluation:**
- [`.kb/systems/database-engines/catalog-persistence-patterns.md`](../../.kb/systems/database-engines/catalog-persistence-patterns.md)

---

## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Items:** atomic-multi-table-ddl, cross-table-transaction-coordination, catalog-replication, table-migration-protocol
**Summary:** 4 out-of-scope items promoted to tracked deferred decisions during /curate session.

---
