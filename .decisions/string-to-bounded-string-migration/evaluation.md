---
problem: "string-to-bounded-string-migration"
evaluated: "2026-04-13"
candidates:
  - path: ".kb/systems/database-engines/schema-type-systems.md"
    name: "Compaction-time migration with quarantine"
  - path: ".kb/systems/database-engines/schema-type-systems.md"
    name: "Read-time lazy migration"
  - path: ".kb/systems/database-engines/schema-type-systems.md"
    name: "Immediate background scan"
constraint_weights:
  scale: 2
  resources: 1
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 2
---

# Evaluation — string-to-bounded-string-migration

## References
- Constraints: [constraints.md](constraints.md)
- KB source: [`.kb/systems/database-engines/schema-type-systems.md`](../../.kb/systems/database-engines/schema-type-systems.md) §schema-evolution-and-migration

## Constraint Summary
Zero-downtime migration when field constraints are tightened. Non-compliant
documents must be handled explicitly. Schema version tag already exists in the
serialization format. Compaction already rewrites every document.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Millions of documents across levels; can't block |
| Resources | 1 | Normal compaction budget sufficient |
| Complexity | 1 | Expert team, compaction infrastructure exists |
| Accuracy | 3 | Non-compliant handling is the core design question |
| Operational | 3 | Zero downtime, progressive, observable |
| Fit | 2 | Schema version in header, compaction rewrites |

---

## Candidate: Compaction-time migration with quarantine

**KB source:** [schema-type-systems.md §compaction-time-migration](../../.kb/systems/database-engines/schema-type-systems.md)

**Design:** On schema version bump, new writes are validated eagerly. During
compaction, each document's schema version is checked. If old: validate against
new schema. Compliant documents get version tag bumped. Non-compliant documents
are written to a quarantine SSTable (separate from normal output).

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 5 | 10 | Piggybacks on compaction — no additional I/O beyond what compaction already does |
|  |  |  |  | **Would be a 2 if:** compaction never reaches old SSTables (L0 never compacts to bottom) |
| Resources | 1 | 5 | 5 | Zero additional memory — migration happens within compaction's existing buffer |
| Complexity | 1 | 4 | 4 | Compaction merge loop adds a version-check-and-validate step. Quarantine output is a second SSTable writer. |
|  |  |  |  | **Would be a 2 if:** quarantine SSTable management became a maintenance burden |
| Accuracy | 3 | 5 | 15 | Non-compliant documents are explicitly quarantined, not silently passed or truncated. Compliant documents are upgraded. Deterministic outcome per document. |
|  |  |  |  | **Would be a 2 if:** quarantine was lossy (documents disappeared instead of being queryable) |
| Operational | 3 | 4 | 12 | Zero downtime. Progressive — migration completes when all levels compact through. |
|  |  |  |  | **Would be a 2 if:** migration took weeks because bottom levels compact rarely |
| Fit | 2 | 5 | 10 | Schema version already in header. Compaction already rewrites. Natural fit. |
|  |  |  |  | **Would be a 2 if:** compaction couldn't distinguish schema versions (it can — header byte 0-1) |
| **Total** | | | **56** | |

---

## Candidate: Read-time lazy migration

**Design:** On read, check schema version. If old version and field is compliant:
return normally (no rewrite until compaction). If non-compliant: return with a
warning/flag. No immediate data movement.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 4 | 8 | No additional I/O |
| Resources | 1 | 5 | 5 | Zero additional resources |
| Complexity | 1 | 3 | 3 | Read path needs version-check logic; warning propagation to caller |
| Accuracy | 3 | 2 | 6 | Non-compliant documents are flagged but NOT quarantined. They remain queryable indefinitely. Caller must handle the warning — easy to ignore. |
| Operational | 3 | 3 | 9 | Zero downtime but migration is invisible — no progress tracking. Non-compliant documents persist until manually addressed. |
| Fit | 2 | 3 | 6 | Adds complexity to the read path (hot path) for a migration concern |
| **Total** | | | **37** | |

**Hard disqualifiers:** Accuracy score of 2 — non-compliant documents are not
explicitly handled, just flagged. Violates the "explicit non-compliance handling"
constraint.

---

## Candidate: Immediate background scan

**Design:** On schema version bump, schedule a background task that scans all
SSTables, validates every document against the new schema, and rewrites
non-compliant ones to quarantine.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 3 | 6 | Full table scan — O(all documents) I/O even if most are compliant |
| Resources | 1 | 2 | 2 | Requires reading every SSTable regardless of compaction schedule |
| Complexity | 1 | 2 | 2 | New background task infrastructure, scan scheduling, progress tracking |
| Accuracy | 3 | 5 | 15 | Immediate identification of all non-compliant documents |
|  |  |  |  | **Would be a 2 if:** scan and compaction raced to rewrite the same SSTable |
| Operational | 3 | 4 | 12 | Proactive — migration progress is immediately observable |
|  |  |  |  | **Would be a 2 if:** scan caused I/O contention with normal reads |
| Fit | 2 | 2 | 4 | Requires new scanning infrastructure not in jlsm today |
| **Total** | | | **41** | |

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Total |
|-----------|-------|-----------|------------|----------|-------------|-----|-------|
| Compaction-time + quarantine | 10 | 5 | 4 | 15 | 12 | 10 | **56** |
| Read-time lazy | 8 | 5 | 3 | 6 | 9 | 6 | **37** |
| Background scan | 6 | 2 | 2 | 15 | 12 | 4 | **41** |

## Preliminary Recommendation
Compaction-time migration with quarantine wins decisively (56 vs 41 vs 37).
It piggybacks on existing compaction infrastructure, handles non-compliant
documents explicitly, and requires zero additional I/O or infrastructure.
