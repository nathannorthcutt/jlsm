---
problem: "Where should the new multiplexed-framing transport implementation live in the JPMS module DAG?"
slug: "transport-module-placement"
captured: "2026-04-26"
status: "draft"
---

# Constraint Profile — transport-module-placement

## Problem Statement

The `transport.multiplexed-framing` v3 APPROVED spec (69 requirements)
needs an implementation. The work group `implement-transport`'s goal is
"net-new jlsm-cluster module"; the WD-01 Notes flag this as a decision
required before implementation begins. Existing transport types
(`ClusterTransport` SPI, `Message`, `MessageType`, `NodeAddress`,
`InJvmTransport`) currently live in `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/`,
constrained by the `connection-pooling` and `transport-abstraction-design`
ADRs (both with `files:` fields pointing to that location). Downstream
work groups (WG2 membership, WG3 scatter-gather) plan to consume the
transport without pulling jlsm-engine — that requires the transport to
live in a module that membership/query can depend on without circular
dependencies on engine.

## Constraints

### Scale
1000-node cluster target (R31 in the spec). Transport is the foundational
layer for membership heartbeats, scatter-gather query fan-out, and future
encryption-on-wire. Multiple downstream modules (WG2 membership, WG3
query) will consume the transport API without needing the engine's
catalog/schema/Engine.java surface.

### Resources
Pure Java 25 library, no external runtime dependencies. JPMS strict —
each module declares its own `module-info.java`. Test config uses
`--add-exports` for internal packages.

### Complexity Budget
Expert team; per project memory, "complexity is not a constraint."
Multi-module architecture is the project's existing pattern (6 modules
already). Adding a 7th is operationally normal.

### Accuracy / Correctness
69 spec requirements (R1-R45 with sub-numbers). Tests must be runnable
in isolation; the module structure should let `./gradlew :module:test`
exercise the transport without dragging in engine-level test fixtures.

### Operational
- Existing transport types live in `jlsm-engine/cluster/` and are imported
  by `jlsm.engine` (public package). Migration requires updating
  `module-info.java`, every importer, and the two existing ADRs'
  `files:` fields.
- `InJvmTransport` (test-stub impl) lives in `jlsm-engine/cluster/internal/`
  and is consumed by membership/clustering tests. It must continue to
  work post-migration.
- Existing `ClusterTransport` SPI ships in `jlsm.engine.cluster` (public)
  with `@spec engine.clustering.R27/R29/R30` annotations. These spec
  references survive the move.

### Fit
- Project pattern: each module has `<package>` (public) + `<package>.internal`
  (non-exported) packages, with `module-info.java` exporting only the
  public surface (per `module-dag-spec-anticipation` KB and
  `module-dag-sealed-type-public-factory-carve-out` ADR).
- Java 25, Gradle multi-project Groovy DSL, JPMS strict.
- Existing module DAG: jlsm-core (foundation) → jlsm-table, jlsm-indexing,
  jlsm-vector (parallel layer) → jlsm-engine (top of DAG, depends on
  jlsm-table). The new transport module fits naturally as a peer at the
  jlsm-engine layer, OR below it. Below is preferable so jlsm-engine
  can depend on transport (not the reverse).

## Key Constraints (most narrowing)

1. **Downstream module consumption.** WG2 and WG3 must consume the
   transport without depending on jlsm-engine. This rules out leaving
   the transport in jlsm-engine — that path forces membership and
   scatter-gather modules to pull engine.
2. **Spec test isolation.** 69 requirements need direct test annotations
   runnable independently. The module must be self-contained at the
   test level.
3. **Migration scope.** Existing types (`ClusterTransport`, `Message`,
   `MessageType`, `NodeAddress`, `InJvmTransport`) must move with
   minimal churn. Existing `@spec` annotations must remain valid (the
   `engine.clustering` references should still point to surviving spec
   IDs).

## Unknown / Not Specified

None — full profile captured from project state, KB, and WD context.
