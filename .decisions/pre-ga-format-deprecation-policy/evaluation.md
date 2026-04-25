---
problem: "pre-ga-format-deprecation-policy"
evaluated: "2026-04-24"
candidates:
  - path: ".kb/systems/database-engines/format-version-deprecation-strategies.md#patterns"
    name: "A — Hands-off opportunistic"
  - path: ".kb/systems/database-engines/format-version-deprecation-strategies.md#patterns"
    name: "B — Compaction-driven + operator-only bulk upgrade"
  - path: ".kb/systems/database-engines/format-version-deprecation-strategies.md#patterns"
    name: "C — Full mechanism set (Prefer-current + sweep + inventory + watermark + targeted command)"
  - path: ".kb/systems/database-engines/format-version-deprecation-strategies.md#strategies-by-system"
    name: "D — Cluster-version-gate (CockroachDB-style)"
  - path: ".kb/systems/database-engines/format-version-deprecation-strategies.md#strategies-by-system"
    name: "E — Out-of-band utility (pg_upgrade-style)"
constraint_weights:
  scale: 2
  resources: 3
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 3
---

# Evaluation — pre-ga-format-deprecation-policy

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: [`format-version-deprecation-strategies`](../../.kb/systems/database-engines/format-version-deprecation-strategies.md)
  + analogues from [`encryption-key-rotation-patterns`](../../.kb/systems/security/encryption-key-rotation-patterns.md),
  [`dispatch-discriminant-corruption-bypass`](../../.kb/patterns/validation/dispatch-discriminant-corruption-bypass.md),
  [`version-discovery-self-only-no-external-cross-check`](../../.kb/patterns/validation/version-discovery-self-only-no-external-cross-check.md),
  [`catalog-persistence-patterns`](../../.kb/systems/database-engines/catalog-persistence-patterns.md)
- Existing migration ADRs: `unencrypted-to-encrypted-migration`,
  `string-to-bounded-string-migration`, `table-migration-protocol`

## Constraint Summary

The policy must (a) provide an automatic forward migration path tied to
a natural rewrite vector (compaction for SSTables, rotation for WAL,
metadata-write for catalog), (b) bound any background work so en-masse
rewrites only happen on explicit operator request, (c) apply uniformly
across SSTable / WAL / catalog / envelope / serializer artefacts, and
(d) work in both pre-GA (zero migration debt) and post-GA (≥ 1 major
deprecation window, read-only past-window = hard error) regimes. Pre-GA
serves as the first live exercise.

## Weighted Constraint Priorities

| Constraint | Weight | Why this weight |
|-----------|--------|-----------------|
| Scale | 2 | Mechanism must scale to dozens of artefacts × dozens of versions but isn't the binding constraint |
| Resources | 3 | "Automatic forward migration path required" is the most narrowing; rules out manual-tool-only |
| Complexity | 1 | Unbounded per project profile; lowest binding weight |
| Accuracy | 3 | Atomic commit, idempotency, no en-masse, downgrade defence — all hard requirements |
| Operational | 3 | Pre-GA + post-GA dual regime + ≥ 1 major window + read-only behaviour |
| Fit | 3 | Cross-artefact uniformity; composition with existing migration ADRs and R9a-mono |

---

## Candidate: A — Hands-off opportunistic

**KB source:** [`format-version-deprecation-strategies.md#patterns`](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#patterns)
(no system fully exemplifies this; closest: SQLite's "format never changes" + RocksDB without operator action)

| Constraint | Weight | Score | Weighted | Evidence |
|------------|--------|-------|----------|----------|
| Scale | 2 | 3 | 6 | Compaction handles any artefact count; no aggregation overhead |
| Resources | 3 | 4 | 12 | Compaction IS the automatic forward path. **Would be a 2 if:** cold-L6 files that never compact accumulate at end-of-window without a fallback |
| Complexity | 1 | 5 | 5 | Simplest possible; no new mechanisms |
| Accuracy | 3 | 3 | 9 | Magic-as-commit-marker preserved; no en-masse risk |
| Operational | 3 | 1 | 3 | No inventory; no warnings; cold files never upgrade — deprecation window cannot close cleanly. **Disqualifying** for read-only deployments per constraint profile |
| Fit | 3 | 3 | 9 | Trivially composes with existing migration ADRs but adds no value |
| **Total** | | | **44** | |

**Hard disqualifiers:** Operational (1/5) — without inventory + sweep + targeted command, the deprecation window cannot close on cold files in read-only deployments. Hard requirement violated.

**Key strengths:** simplest design.
**Key weaknesses:** read-only past-window stuck; no inventory; cold L6 files trapped.

---

## Candidate: B — Compaction-driven + operator-only bulk upgrade

**KB source:** [`format-version-deprecation-strategies.md#patterns`](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#patterns)
+ existing ADR [`unencrypted-to-encrypted-migration`](../unencrypted-to-encrypted-migration/adr.md)

| Constraint | Weight | Score | Weighted | Evidence |
|------------|--------|-------|----------|----------|
| Scale | 2 | 4 | 8 | Composes naturally; operator commands scale to per-table. **Would be a 2 if:** operator must run commands per-table-per-artefact at sufficient scale |
| Resources | 3 | 4 | 12 | Compaction is automatic; operator command is escape hatch. **Would be a 2 if:** operator command becomes load-bearing (cold-file accumulation forces frequent runs) |
| Complexity | 1 | 4 | 4 | Existing pattern from migration ADRs. **Would be a 2 if:** layering policy on top of pattern surfaces edge cases not seen in single-purpose migration |
| Accuracy | 3 | 4 | 12 | Atomic commit + no en-masse hazard from existing pattern. **Would be a 2 if:** cross-artefact application surfaces consistency holes (e.g., catalog and SSTable upgrade desynchronise) |
| Operational | 3 | 2 | 6 | No built-in inventory; no warnings; operators must build their own monitoring |
| Fit | 3 | 5 | 15 | IS the existing pattern; zero new abstractions. **Would be a 2 if:** the existing pattern's compaction-driven assumption breaks down for non-SSTable artefacts (WAL, catalog) |
| **Total** | | | **57** | |

**Hard disqualifiers:** none, but Operational 2 is borderline — read-only deployments past window have no inventory to plan an upgrade window.

**Key strengths:** zero new abstractions; trusted pattern.
**Key weaknesses:** no observability for operators; doesn't generalise cleanly to WAL/catalog/envelope.

---

## Candidate: C — Full mechanism set (RECOMMENDED)

**KB source:** [`format-version-deprecation-strategies.md#patterns`](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#patterns)
— composite of "declarative compat matrix" (RocksDB) + "compaction-driven rewrite" (jlsm's existing pattern) + per-artefact watermark (novel; no surveyed system has this) + bounded sweep (operator-controllable priority) + targeted upgrade command (operator escape hatch). Cross-checks against [`version-discovery-self-only-no-external-cross-check`](../../.kb/patterns/validation/version-discovery-self-only-no-external-cross-check.md) (catalog watermark refutes self-magic) and [`dispatch-discriminant-corruption-bypass`](../../.kb/patterns/validation/dispatch-discriminant-corruption-bypass.md) (speculative current-version hypothesis available as extension).

| Constraint | Weight | Score | Weighted | Evidence |
|------------|--------|-------|----------|----------|
| Scale | 2 | 5 | 10 | Per-artefact registry; watermark is O(1) per collection; sweep is bounded so scales sub-linearly. **Would be a 2 if:** artefact count grows past hundreds × hundreds of versions and the per-artefact-type registry needs cross-aggregation |
| Resources | 3 | 5 | 15 | Compaction-driven (automatic) + bounded sweep (automatic) + inline rewrite-on-read (automatic) + targeted command (operator escape hatch) — covers every rewrite trigger. **Would be a 2 if:** an external coordination layer (cluster-version) becomes required and library-level mechanisms become insufficient |
| Complexity | 1 | 1 | 1 | 5 mechanisms × multiple artefacts × cross-substrate uniformity = the most complex candidate. Per project profile complexity is unbounded so this scores low without disqualifying |
| Accuracy | 3 | 5 | 15 | Atomic commit + idempotent cascade + no en-masse + R9a-mono catalog watermark for downgrade defence + cross-substrate uniform via existing R39 pattern. **Would be a 2 if:** adversaries can tamper with format-version magics within authenticated storage substrates (the policy delegates this to substrate; that delegation could fail) |
| Operational | 3 | 5 | 15 | Inventory + watermark + per-process warnings + bounded sweep + targeted command — operators see and control everything. **Would be a 2 if:** sweep can't keep pace with file production rate; cold-L6 accumulation outruns the bounded budget |
| Fit | 3 | 5 | 15 | Composes with existing migration ADRs (B is a subset); reuses R9a-mono from `sstable.footer-encryption-scope`; cross-artefact uniform; spec-lifecycle metadata + ADR-lifecycle metadata + CHANGELOG entries fit naturally. **Would be a 2 if:** future jlsm features require formats that can't be expressed in the per-artefact-version-byte model (multi-version per-record encoding) |
| **Total** | | | **71** | |

**Hard disqualifiers:** none.

**Key strengths:** every binding constraint scores 5; cross-artefact uniform; composes with existing infrastructure rather than replacing it; observability built-in; operator escape hatch covers cold-L6 case.
**Key weaknesses:** highest implementation complexity (mitigated by unbounded complexity budget); novel watermark mechanism not directly proven in any surveyed production system (composition of known patterns).

---

## Candidate: D — Cluster-version-gate (CockroachDB-style)

**KB source:** [`format-version-deprecation-strategies.md#strategies-by-system`](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#strategies-by-system) (cockroachdb section)

| Constraint | Weight | Score | Weighted | Evidence |
|------------|--------|-------|----------|----------|
| Scale | 2 | 4 | 8 | Works at scale with cluster coordination overhead. **Would be a 2 if:** library-only embedders gain no benefit from cluster-version |
| Resources | 3 | 3 | 9 | Finalize is operator-triggered; not fully automatic |
| Complexity | 1 | 2 | 2 | Cluster-version variable + finalize protocol + cross-node negotiation |
| Accuracy | 3 | 4 | 12 | Rollback window is nice; transactional finalize. **Would be a 2 if:** library embedders don't run a control plane; finalize becomes a single-node no-op that adds friction without benefit |
| Operational | 3 | 3 | 9 | Cluster-version setting is observable via SQL; finalize timing is operator concern |
| Fit | 3 | 1 | 3 | DISQUALIFYING: jlsm is a library, not a cluster product. Cluster-version-gate requires a control plane that's out of scope |
| **Total** | | | **43** | |

**Hard disqualifiers:** Fit (1/5) — jlsm has no cluster control plane; cluster-version-gate is structurally inapplicable. The KB entry's "when-not-to-use" section names this case explicitly.

---

## Candidate: E — Out-of-band utility (pg_upgrade-style)

**KB source:** [`format-version-deprecation-strategies.md#strategies-by-system`](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#strategies-by-system) (postgresql, mysql sections)

| Constraint | Weight | Score | Weighted | Evidence |
|------------|--------|-------|----------|----------|
| Scale | 2 | 4 | 8 | Postgres demonstrates 12+ year compat windows. **Would be a 2 if:** utility must keep pace with rapid format evolution |
| Resources | 3 | 1 | 3 | DISQUALIFYING: primary mechanism is operator-triggered utility; fails the "automatic forward migration path required" hard constraint |
| Complexity | 1 | 3 | 3 | Separate utility binary; partial migration recovery state machine |
| Accuracy | 3 | 4 | 12 | Utilities can be made idempotent. **Would be a 2 if:** partial-migration recovery surfaces edge cases (Postgres pg_upgrade is documented to occasionally hang) |
| Operational | 3 | 2 | 6 | Operator must remember to run; partial migration risks |
| Fit | 3 | 2 | 6 | Doesn't compose with existing compaction-driven pattern; introduces parallel mechanism. User explicitly rejected separate `jlsm-legacy-reader` tool earlier |
| **Total** | | | **38** | |

**Hard disqualifiers:** Resources (1/5) — fails automatic-forward-path constraint.

---

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| A: Hands-off opportunistic | [patterns](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#patterns) | 3 | 4 | 5 | 3 | **1** | 3 | 44 |
| B: Compaction + operator-only | [patterns](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#patterns) | 4 | 4 | 4 | 4 | 2 | 5 | 57 |
| **C: Full mechanism set** | [patterns](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#patterns) | **5** | **5** | 1 | **5** | **5** | **5** | **71** |
| D: Cluster-version-gate | [strategies-by-system](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#strategies-by-system) | 4 | 3 | 2 | 4 | 3 | **1** | 43 |
| E: Out-of-band utility | [strategies-by-system](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#strategies-by-system) | 4 | **1** | 3 | 4 | 2 | 2 | 38 |

(Bold = disqualifying score or top score.)

## Preliminary Recommendation

**Candidate C — Full mechanism set** wins by 14 weighted points (71 vs B's 57)
and is the only candidate without a hard disqualifier. C composes the
RocksDB-style declarative compat matrix with jlsm's existing
compaction-driven rewrite vector, layers a per-collection watermark for
downgrade defence (no surveyed production system has this — it's a
genuine improvement over surveyed art), and keeps the bounded sweep
priority-configurable so operators retain control.

C's only weakness is highest implementation complexity, mitigated by the
project's unbounded-complexity-budget profile.

## Falsification Pass — 2026-04-24

A subagent challenged Candidate C's scores. Two were weakened:

- **Accuracy 5 → 4.** The policy *delegates* format-version downgrade-attack
  defence to per-format integrity specs (e.g., yet-to-be-written
  `sstable-active-tamper-defence`) rather than solving it directly. The
  per-collection watermark cross-check that *would* close the attack is
  novel — no surveyed production system has it — making it a real
  improvement-over-art but also untested at scale. The policy
  acknowledges the seam but doesn't close it.

- **Operational 5 → 4.** The bounded sweep's ability to keep pace with
  compaction-produced file accumulation is an *assumption*, not a
  demonstrated property. Constraints.md flags this as a pre-GA hedge;
  it's the most dangerous assumption in the recommendation (see
  Risks). If the sweep falls behind, the targeted operator command
  becomes load-bearing and C degenerates toward B.

**Revised totals:**

| Candidate | Pre-falsification | Post-falsification |
|-----------|-------------------|--------------------|
| C | 71 | **65** |
| B | 57 | 57 |
| A | 44 | 44 |
| D | 43 | 43 |
| E | 38 | 38 |

C still wins by 8 weighted points. Recommendation is **unchanged** but
the margin is smaller and the open risks are sharper.

**Strongest counter-argument** (to be raised in deliberation): B becomes
correct if (a) the sweep proves operationally disruptive in practice OR
(b) operators prefer running an explicit targeted command on a cadence
over trusting an opaque background process. In that world C ships
B-equivalent behaviour with three unused mechanisms. Plausibility:
moderate — the 8-point margin is not robust to operator preference shifting
B's Operational from 2 to 4.

**Most dangerous assumption:** the bounded sweep can keep pace with
compaction-produced cold-L6 accumulation without operator intervention
as the load-bearing mechanism.

**Missing candidates surfaced:**

- **C+D hybrid** — expose a no-op single-node cluster-version hook in
  the library that an embedder optionally drives. Resolves the
  cluster-mode coexistence concern (constraints.md "Unknown / Not
  Specified") at low cost. Worth flagging as an extension surface even
  if not adopted now.
- **Lazy upgrade on-demand (no sweep)** — degenerate C with mechanism
  A″ removed. Inline rewrite-on-read for writable storage + targeted
  operator command for cold files; no automatic background work.
  Scores Operational ~3 (loses to C's 4), Complexity ~3 (gains over
  C's 1), Resources ~4 (vs C's 5). The honest fallback if the sweep
  assumption proves wrong post-GA.

## Risks and Open Questions

- **Sweep keep-up risk** — if file production rate exceeds bounded sweep
  rate, cold-L6 accumulation outruns cleanup. Mitigation: priority is
  configurable up; operator-triggered targeted command covers the
  bulk-rewrite case. Long-term monitoring required (per pre-GA hedge).
- **Watermark composition with R9a-mono** — `sstable.footer-encryption-scope`
  R9a-mono establishes a per-table catalog format-version high-water for
  the catalog metadata format. This policy generalises that pattern to
  all artefacts. Ensure WD-02's R9a-mono implementation is shaped to be
  the first instance of the broader pattern, not a one-off.
- **Cross-substrate uniformity for inline rewrite-on-read** — relies on
  `sstable.end-to-end-integrity` R39's backend-conditional commit (atomic
  move where available, content-addressed commit where not). Already
  present; verify the deprecation policy's spec text references R39
  explicitly so future writers don't reinvent.
- **Format-version downgrade attack** — explicitly delegated to
  per-format integrity specs (`sstable-active-tamper-defence` for SSTable,
  yet to be specified for WAL/catalog/envelope). The policy notes the
  delegation but doesn't itself solve the attack.
- **Cluster-mode coexistence** — when jlsm is embedded in a cluster
  product (the consumer adds cluster coordination on top), the policy's
  per-node behaviour must not conflict with cluster-wide format
  negotiation that the consumer might layer on. Not solved here; flagged
  in constraints.md "Unknown / Not Specified."
