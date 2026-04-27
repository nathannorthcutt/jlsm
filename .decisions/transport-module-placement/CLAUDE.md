---
problem: "transport-module-placement"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-26"
---

# Transport Module Placement — Decision Index

**Problem:** Where should the new multiplexed-framing transport implementation live in the JPMS module DAG?
**Status:** confirmed
**Current recommendation:** Option A — new `jlsm-cluster` Gradle subproject below jlsm-engine in the DAG, with public `jlsm.cluster` package + non-exported `jlsm.cluster.internal` package; mirrors existing `jlsm-engine` + `jlsm-engine.internal` pattern; migrate ClusterTransport SPI + value types + InJvmTransport from jlsm-engine; amend `connection-pooling` and `transport-abstraction-design` ADR `files:` fields.
**Last activity:** 2026-04-26 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-26 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (4 candidates: A=58, B=58, C=35-disqualified, D=rejected on cohesion) | 2026-04-26 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-26 |
| [log.md](log.md) | Full decision history + deliberation summary | 2026-04-26 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Module-DAG Spec Anticipation | Pattern for single-module API/impl boundary | [`.kb/architecture/jpms/module-dag-spec-anticipation.md`](../../.kb/architecture/jpms/module-dag-spec-anticipation.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-26 | active | jlsm-cluster single new module with public/internal package split |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| (none) | — | — | — |
