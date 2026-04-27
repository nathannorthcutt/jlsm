---
problem: "transport-module-placement"
evaluated: "2026-04-26"
candidates:
  - path: "(architectural option A)"
    name: "jlsm-cluster single new module"
  - path: "(architectural option B)"
    name: "Split jlsm-cluster-api + jlsm-cluster-impl"
  - path: "(architectural option C)"
    name: "Keep transport in jlsm-engine"
constraint_weights:
  scale: 3
  resources: 1
  complexity: 1
  accuracy: 3
  operational: 2
  fit: 3
---

# Evaluation — transport-module-placement

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: [`.kb/architecture/jpms/module-dag-spec-anticipation.md`](../../.kb/architecture/jpms/module-dag-spec-anticipation.md)
- Related ADR: [`.decisions/module-dag-sealed-type-public-factory-carve-out/adr.md`](../module-dag-sealed-type-public-factory-carve-out/adr.md)

## Constraint Summary

The transport must be consumable by WG2 (membership) and WG3 (scatter-gather)
without those modules pulling jlsm-engine — this is the binding scale
constraint. The 69 requirements must be independently testable. Migration
of existing types from jlsm-engine is a one-time cost; the long-term
module DAG is what matters.

## Weighted Constraint Priorities

| Constraint | Weight (1–3) | Why this weight |
|------------|--------------|-----------------|
| Scale | 3 | Downstream-consumer constraint is THE binding factor |
| Resources | 1 | All options have identical FD/memory profiles |
| Complexity | 1 | "Complexity is not a constraint" per user directive |
| Accuracy | 3 | 69-requirement spec must be testable in isolation |
| Operational | 2 | Migration cost is real but one-time |
| Fit | 3 | Module DAG cleanliness pays compounding interest |

---

## Candidate: jlsm-cluster single new module (Option A)

**Description:** New module `modules/jlsm-cluster/` peer of jlsm-engine in
the DAG, but BELOW it (jlsm-engine depends on jlsm-cluster). Public
package `jlsm.cluster` (SPI + value types: `ClusterTransport`, `Message`,
`MessageType`, `NodeAddress`, `MessageHandler`); non-exported package
`jlsm.cluster.internal` (impl: `MultiplexedTransport`,
`PeerConnection`, `FrameCodec`, `Handshake`, accept-loop, reader-loop,
writer-lock; also test-stub `InJvmTransport`). Mirrors the
jlsm-engine + jlsm-engine.internal split.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 3 | 4 | 12 | WG2/WG3 can `requires jlsm.cluster` cleanly without engine. **Would be a 2 if:** WG2/WG3 also need engine APIs in the same call site (not currently planned). |
| Resources | 1 | 5 | 5 | Identical to all options |
| Complexity | 1 | 5 | 5 | Single module, lowest config overhead |
| Accuracy | 3 | 5 | 15 | Tests run as `:jlsm-cluster:test` standalone. Existing project pattern with `--add-exports` for internal package |
| Operational | 2 | 3 | 6 | One-time migration: 5 type files + InJvmTransport + module-info update + 2 ADR file: amendments. ~1 day work. **Would be a 2 if:** existing engine.clustering spec references a class that moves (need to verify). |
| Fit | 3 | 5 | 15 | Mirrors jlsm-engine pattern; consistent with `module-dag-spec-anticipation` KB; sealed-type + factory pattern from `module-dag-sealed-type-public-factory-carve-out` ADR applies if needed |
| **Total** | | | **58** | |

**Hard disqualifiers:** none.

**Strengths:** simplest path that satisfies binding constraints; matches
existing project module pattern exactly; no over-engineering.

**Weaknesses:** if API surface ever needs to be consumed by a module
that should NOT see the impl (e.g. a thin client jar), a future split
would still be needed. Low probability given scope.

---

## Candidate: Split jlsm-cluster-api + jlsm-cluster-impl (Option B)

**Description:** Two new modules: `jlsm-cluster-api` (SPI, value types,
contracts only) and `jlsm-cluster-impl` (NIO transport, accept loop,
internal). Downstream modules `requires jlsm.cluster.api` (and
optionally `jlsm.cluster.impl` if they need to wire a concrete instance).
Cleanest JPMS boundary; supports the sealed-type + non-exported-package
trust pattern at module level rather than package level.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 3 | 5 | 15 | Same as A plus thin-API consumers possible. **Would be a 2 if:** API is so co-evolving with impl that two-module overhead breaks every change into two PRs. (Risk: real, given new spec, expect amendments.) |
| Resources | 1 | 5 | 5 | Identical |
| Complexity | 1 | 4 | 4 | Two Gradle subprojects, two module-info, dependency edge between them. Real but bounded |
| Accuracy | 3 | 5 | 15 | Tests can run on impl module standalone; API module can have contract tests separately |
| Operational | 2 | 2 | 4 | Migration is more involved: file split between API and impl, naming convention decision, double the JPMS exports config. **Would be a 2 if:** future test infrastructure needs cross-module fixtures (likely). |
| Fit | 3 | 5 | 15 | Aligned with module-dag-aware patterns; could anchor a "clean architecture" pattern across cluster, query, encryption modules going forward |
| **Total** | | | **58** | |

**Hard disqualifiers:** none.

**Strengths:** strongest long-term boundary; supports thin-client or
embedded scenarios; encourages clean API stability discipline.

**Weaknesses:** every spec amendment that touches both contract and impl
requires touching two modules; for a spec at v3 still maturing
(integration-frontier findings still emerging from membership/scatter-gather
implementation), this drag is real. Premature given scope.

---

## Candidate: Keep transport in jlsm-engine (Option C)

**Description:** Implement `transport.multiplexed-framing` directly under
`modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/` as a
new `MultiplexedTransport` impl alongside the existing `InJvmTransport`.
No new module, no migration. Existing ADRs' `files:` fields stay valid.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 3 | 1 | 3 | DISQUALIFYING: WG2/WG3 modules would have to depend on jlsm-engine, contradicting the layered DAG. Engine sits at the top — making it a transport-layer dependency inverts the architecture. |
| Resources | 1 | 5 | 5 | Identical |
| Complexity | 1 | 5 | 5 | Zero new module overhead |
| Accuracy | 3 | 3 | 9 | Tests run within :jlsm-engine:test which is large and slow; harder to isolate transport test cycles |
| Operational | 2 | 5 | 10 | Zero migration cost |
| Fit | 3 | 1 | 3 | Architecturally regressive: existing jlsm-engine CLAUDE.md cleanly separates `jlsm.engine.cluster` (clustering surface) from `jlsm.engine.cluster.internal` (impl); adding a new NIO transport into engine.cluster.internal works at file-level but blocks downstream module-DAG cleanup |
| **Total** | | | **35** | |

**Hard disqualifiers:** Scale=1 — WG2/WG3 cannot depend on jlsm-engine
without violating the layered architecture. This rules C out unless
WG2/WG3 are also folded into jlsm-engine, which contradicts the
work-group goals.

**Strengths:** zero migration cost; preserves existing ADR `files:` fields.

**Weaknesses:** disqualifies on Scale; defers a problem that becomes
harder to undo as more code accretes in jlsm-engine.

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| A: jlsm-cluster single | 12 | 5 | 5 | 15 | 6 | 15 | **58** |
| B: split api+impl | 15 | 5 | 4 | 15 | 4 | 15 | **58** |
| C: keep in engine | 3 | 5 | 5 | 9 | 10 | 3 | **35** (disqualified) |

## Preliminary Recommendation

**Option A (jlsm-cluster single new module)** — tied with B on weighted
total, wins on tiebreaker. Rationale:

- Both A and B satisfy all binding constraints. C is disqualified by
  Scale.
- A's complexity edge (1-module setup) and operational edge (one-time
  migration to one place rather than two) outweigh B's marginal Scale
  advantage (5 vs 4).
- The spec is still maturing (v3 with integration-frontier amendments
  expected as WG2/WG3 land). API/impl co-evolution is high; the two-module
  overhead would tax every iteration.
- Future split is always available — A → B is a refactoring, not an
  architectural rewrite. The reverse (B → A) is rare and unmotivated.
- Existing project pattern (jlsm-engine + jlsm-engine.internal) already
  models the API/impl boundary at the package level within a single
  module. A continues that pattern; B introduces a new pattern.

## Risks and Open Questions

- **Risk:** A future thin-client consumer (e.g. CLI tool that ships only
  the transport SPI) would force a B-shape split. Currently no such
  consumer is planned. Conditions-for-revision should call this out.
- **Open:** During migration, do `engine.clustering` `@spec` annotations
  on `ClusterTransport` and `Message` need updating to point at
  `transport.multiplexed-framing`? The annotations were authored when
  engine.clustering was the relevant spec; moving the file does not
  invalidate the annotations, but a v3-aware update is reasonable.
  Tracked as part of WD-01 implementation, not this ADR.
