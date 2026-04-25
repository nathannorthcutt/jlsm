# Decision Log — pre-ga-format-deprecation-policy

> Append-only. Each architect / decisions-revisit run appends an entry here.

## 2026-04-24 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Constraint profile captured for pre-GA format deprecation
policy. Scope widened from SSTable-only to all versioned on-disk and
on-wire artefacts. Six dimensions answered (some explicitly, some
derived). Six required mechanisms identified during constraint
collection (Prefer-current rule, C, D′, A″, E, F). Top binding
constraints: automatic forward migration required; no en-masse
auto-rewrite without operator request; cross-artefact uniform
application; pre-GA + post-GA dual-regime support.

**Files written/updated:**
- `.decisions/pre-ga-format-deprecation-policy/constraints.md` —
  initial profile

**KB files read:**
- None yet — KB survey is Step 2.

---

## 2026-04-24 — research-commissioned

**Agent:** Architect Agent
**Event:** research-commissioned
**Summary:** KB survey identified tangential coverage only (key-rotation
patterns + version-dispatch anti-patterns + catalog-persistence) but no
direct entry on format-version deprecation policy. User chose research
before evaluating. One subject commissioned.

**Files written/updated:**
- `.decisions/pre-ga-format-deprecation-policy/research-brief.md` —
  brief for `format-version deprecation strategies in production
  database systems` at
  `.kb/systems/database-engines/format-version-deprecation-strategies.md`

**KB files read during survey:**
- `.kb/CLAUDE.md`
- `.kb/systems/CLAUDE.md`
- `.kb/systems/security/CLAUDE.md`
- `.kb/systems/database-engines/CLAUDE.md`
- `.kb/patterns/CLAUDE.md`
- `.kb/patterns/validation/CLAUDE.md`
- `.kb/architecture/CLAUDE.md`
- (subject files NOT loaded — Step 4 only after research returns)

**Coverage decision:** research-first per architect-skill heuristic for
long-term-consequence decisions. Aligns with existing project feedback
memory (`feedback_research_before_assumptions.md`,
`feedback_never_deprioritize_research.md`).

---

## 2026-04-24 — research-received

**Agent:** Architect Agent
**Event:** research-received
**Summary:** Research subagent returned. KB entry written at
`.kb/systems/database-engines/format-version-deprecation-strategies.md`
covering 5 production systems (PostgreSQL, CockroachDB, RocksDB,
MongoDB, MySQL) + SQLite, with comparison matrix on 8 axes, 5 patterns
extracted, and explicit anti-patterns from production. Cross-linked
4 existing KB entries (encryption-key-rotation-patterns,
catalog-persistence-patterns, dispatch-discriminant-corruption-bypass,
version-discovery-self-only-no-external-cross-check). KB indexes
updated bottom-up.

**Files written/updated:**
- `.kb/systems/database-engines/format-version-deprecation-strategies.md` (new)
- 4 cross-link updates to existing KB entries
- `.kb/systems/database-engines/CLAUDE.md` (Tags + Contents row + research gap note)
- `.kb/systems/CLAUDE.md` (database-engines row updated)
- `.kb/CLAUDE.md` (Recently Added + systems row updated)

---

## 2026-04-24 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Candidate C (Full mechanism set — Prefer-current rule +
bounded sweep + inventory + watermark + targeted operator command)
confirmed after deliberation. Composes RocksDB-style declarative
compat-matrix with jlsm's existing compaction-driven rewrite vector;
adds per-collection format watermark for downgrade defence (novel —
no surveyed production system has this combination). Pre-GA exercise
target: SSTable v1–v4 collapse.

### Deliberation Summary

**Rounds of deliberation:** 1
**Recommendation presented:** Candidate C (post-falsification weighted total 65)
**Final decision:** Candidate C *(same as presented)*

**Topics raised during deliberation:**
- Falsification weakened two scores (Accuracy 5→4 on downgrade-attack
  delegation; Operational 5→4 on sweep keep-up assumption). Margin
  narrowed from 14 → 8 weighted points. Recommendation held.
- Two missing candidates surfaced by falsification:
  C+D hybrid (no-op cluster-version hook for embedders) and
  Lazy-on-demand (degenerate C without sweep). Both captured as
  revision conditions in adr.md rather than alternative recommendations.

**Constraints updated during deliberation:** None.

**Assumptions explicitly confirmed by user:**
- Sweep keep-up assumption (most dangerous) — pre-GA hedge accepted;
  monitor and adjust post-ship.
- "≥ 1 major release cycle" window anchored to a cadence not yet
  defined for jlsm — deferred to `jlsm-release-cadence`.
- Format-version downgrade-attack defence delegated to per-format
  integrity specs — `sstable-active-tamper-defence` already deferred;
  WAL/catalog/envelope per-format defences open as needed.

**Override:** None.

**Confirmation:** User confirmed with: "yes"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `evaluation.md` — falsification pass section appended
- `constraints.md` — no changes (falsification additions captured here)

**Deferred stubs created (per Step 7c rule for "What This Decision Does
NOT Solve" items):**
- `cluster-format-version-coexistence` — cluster-mode coexistence
- `jlsm-release-cadence` — major/minor release cadence definition
- (sstable-active-tamper-defence already exists from WD-02; not duplicated)

**KB files read during evaluation:**
- [`.kb/systems/database-engines/format-version-deprecation-strategies.md`](../../.kb/systems/database-engines/format-version-deprecation-strategies.md) (primary)
- [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md)
- [`.kb/systems/database-engines/catalog-persistence-patterns.md`](../../.kb/systems/database-engines/catalog-persistence-patterns.md)
- [`.kb/patterns/validation/dispatch-discriminant-corruption-bypass.md`](../../.kb/patterns/validation/dispatch-discriminant-corruption-bypass.md)
- [`.kb/patterns/validation/version-discovery-self-only-no-external-cross-check.md`](../../.kb/patterns/validation/version-discovery-self-only-no-external-cross-check.md)

---


