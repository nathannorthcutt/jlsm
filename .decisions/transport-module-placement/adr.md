---
problem: "transport-module-placement"
date: "2026-04-26"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-cluster/build.gradle"
  - "modules/jlsm-cluster/src/main/java/module-info.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/ClusterTransport.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/Message.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/MessageType.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/MessageHandler.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/NodeAddress.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/internal/MultiplexedTransport.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/internal/InJvmTransport.java"
  - "modules/jlsm-engine/src/main/java/module-info.java"
  - ".decisions/connection-pooling/adr.md"
  - ".decisions/transport-abstraction-design/adr.md"
  - ".spec/domains/transport/multiplexed-framing.md"
---

# ADR — Transport Module Placement

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision

| Subject | Role in decision | Link |
|---------|-----------------|------|
| Module-DAG Spec Anticipation | Pattern for sealed-type + non-exported package + factory carve-out within a single module — guides Option A's package layout | [`.kb/architecture/jpms/module-dag-spec-anticipation.md`](../../.kb/architecture/jpms/module-dag-spec-anticipation.md) |

Related ADRs surveyed:
- [`module-dag-sealed-type-public-factory-carve-out`](../module-dag-sealed-type-public-factory-carve-out/adr.md) — confirms the project pattern of public + internal packages within a single module
- [`connection-pooling`](../connection-pooling/adr.md) — the chosen multiplexing approach; `files:` field requires update
- [`transport-abstraction-design`](../transport-abstraction-design/adr.md) — the SPI shape; `files:` field requires update

---

## Files Constrained by This Decision

The frontmatter `files:` field lists the source files this decision shapes:

- `modules/jlsm-cluster/build.gradle` — new module's build config
- `modules/jlsm-cluster/src/main/java/module-info.java` — declares `jlsm.cluster` exports + `requires jlsm.core`
- `modules/jlsm-cluster/src/main/java/jlsm/cluster/{ClusterTransport,Message,MessageType,MessageHandler,NodeAddress}.java` — public API surface (migrated from jlsm-engine)
- `modules/jlsm-cluster/src/main/java/jlsm/cluster/internal/{MultiplexedTransport,InJvmTransport}.java` — non-exported impl (migrated + new)
- `modules/jlsm-engine/src/main/java/module-info.java` — drops `exports jlsm.engine.cluster`, adds `requires jlsm.cluster` (transitive if engine surfaces transport types)
- `.decisions/connection-pooling/adr.md`, `.decisions/transport-abstraction-design/adr.md` — `files:` field updates (one-time amendment)
- `.spec/domains/transport/multiplexed-framing.md` — implementation @spec annotations point into the new module

## Problem

The `transport.multiplexed-framing` v3 APPROVED spec needs an
implementation. Existing transport types (`ClusterTransport` SPI,
`Message`, `MessageType`, `MessageHandler`, `NodeAddress`,
`InJvmTransport`) live in `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/`.
Downstream work groups (WG2 membership, WG3 scatter-gather) plan to
consume the transport without pulling jlsm-engine — but currently
cannot, because the transport sits at the top of the module DAG inside
the engine module. The decision: where in the module DAG should the
new transport implementation live?

## Constraints That Drove This Decision

- **Downstream module consumption (Scale, weight 3):** WG2 and WG3
  must `requires` the transport without `requires jlsm.engine`. The
  layered DAG forbids inversions.
- **Spec test isolation (Accuracy, weight 3):** 69 spec requirements
  must be testable independently via `:module:test` invocations.
- **Module DAG cleanliness (Fit, weight 3):** the project's existing
  pattern is `<package>` (public) + `<package>.internal` (non-exported),
  with `module-info.java` exporting only the public surface. The
  decision should reinforce this pattern rather than introduce a new
  one.

## Decision

**Chosen approach: Option A — `jlsm-cluster` single new module.**

Create a new Gradle subproject `modules/jlsm-cluster/` peer to
`jlsm-engine` in directory layout but **below** it in the module DAG
(jlsm-engine `requires jlsm.cluster`, not the reverse). The module
declares two packages:

- **`jlsm.cluster`** — public, exported. Contains the `ClusterTransport`
  SPI, value types `Message`, `MessageType`, `NodeAddress`, and
  callback type `MessageHandler`. These are migrated from
  `jlsm.engine.cluster`.
- **`jlsm.cluster.internal`** — non-exported. Contains the new
  `MultiplexedTransport` (NIO impl of the spec), supporting types
  (`PeerConnection`, `FrameCodec`, `Handshake` infrastructure,
  reader-loop, writer-lock primitives), and the migrated `InJvmTransport`
  test stub. Tests reach internal types via
  `--add-exports jlsm.cluster/jlsm.cluster.internal=ALL-UNNAMED` per
  the project's standard test-config pattern.

`jlsm-engine` updates to `requires jlsm.cluster` and removes its own
`jlsm.engine.cluster` package (the cluster-domain types live in
`jlsm.cluster` now). Existing engine consumers of the SPI update their
imports from `jlsm.engine.cluster.*` to `jlsm.cluster.*`.

The `connection-pooling` and `transport-abstraction-design` ADRs have
their `files:` fields amended to point to `modules/jlsm-cluster/...`
paths. The amendment is mechanical and does not change the substance of
either decision.

## Rationale

### Why Option A

- **Satisfies the binding scale constraint.** WG2 and WG3 modules can
  cleanly `requires jlsm.cluster` without depending on jlsm-engine.
  The layered DAG remains intact.
- **Matches the existing project pattern.** Every existing module uses
  `<package>` + `<package>.internal` to express the API/impl boundary
  within a single module. A continues this pattern; B would introduce a
  new two-module pattern.
- **Spec co-evolution friendly.** The spec is v3 and amendments are
  expected as WG2/WG3 land integration-frontier findings (per the
  `module-dag-spec-anticipation` KB and the spec's Round 5 summary).
  Single-module setup means each amendment touches one module's tests
  and impl — not two.
- **Lowest migration friction.** A single migration target (the new
  jlsm-cluster module) for 5 type files + InJvmTransport + module-info
  updates + 2 ADR `files:` amendments.
- **Future-flexible.** A → B (split into api + impl modules) is a
  refactoring, not an architectural rewrite. If a thin-client or
  embedded consumer ever materializes, the split is available.

### Why not Option B (split jlsm-cluster-api + jlsm-cluster-impl)

Two-module overhead taxes every spec amendment. The spec is at v3 with
documented integration-frontier risk for amendments as membership and
scatter-gather implementations land. Premature given the absence of any
SPI-only consumer.

### Why not Option C (keep transport in jlsm-engine)

Disqualified by Scale=1. WG2 and WG3 cannot depend on jlsm-engine
without inverting the DAG. The work-group goals explicitly call for new
modules that depend on transport, not vice-versa.

### Why not Option D (SPI in jlsm-core, impl in jlsm-cluster)

`jlsm-core` is defined in CLAUDE.md as "ALL interfaces AND all
implementations for LSM-Tree primitives (bloom, wal, memtable, sstable,
compaction, cache, tree)." Adding networking SPI (`ClusterTransport`,
`Message`, `NodeAddress`) breaks that cohesion. There is no thin-client
consumer to motivate the cost. Surfaced and rejected during
falsification.

## Implementation Guidance

### Module setup checklist

1. Create `modules/jlsm-cluster/build.gradle` modeled on
   `modules/jlsm-engine/build.gradle`. Required: `requires jlsm.core`
   in the test-task `--add-exports` set; declare the test internal
   `--add-exports`.
2. Add `include 'modules:jlsm-cluster'` to `settings.gradle`.
3. Create `modules/jlsm-cluster/src/main/java/module-info.java`:
   ```java
   module jlsm.cluster {
       requires jlsm.core;
       exports jlsm.cluster;
       // jlsm.cluster.internal NOT exported
   }
   ```
4. Move types:
   - `jlsm.engine.cluster.ClusterTransport` → `jlsm.cluster.ClusterTransport`
   - `jlsm.engine.cluster.Message` → `jlsm.cluster.Message`
   - `jlsm.engine.cluster.MessageType` → `jlsm.cluster.MessageType`
   - `jlsm.engine.cluster.MessageHandler` → `jlsm.cluster.MessageHandler`
   - `jlsm.engine.cluster.NodeAddress` → `jlsm.cluster.NodeAddress`
   - `jlsm.engine.cluster.internal.NodeAddressCodec` → `jlsm.cluster.internal.NodeAddressCodec`
   - `jlsm.engine.cluster.internal.InJvmTransport` → `jlsm.cluster.internal.InJvmTransport`
5. Update jlsm-engine's `module-info.java`: drop `exports jlsm.engine.cluster`, add `requires transitive jlsm.cluster`.
6. Update all internal jlsm-engine imports referencing `jlsm.engine.cluster.*` to `jlsm.cluster.*`.
7. Implement `jlsm.cluster.internal.MultiplexedTransport` per the v3 spec (this is WD-01's main implementation work).
8. Update `connection-pooling/adr.md` and `transport-abstraction-design/adr.md` `files:` fields to reference the new paths.

### Spec @spec annotation policy

Existing `@spec engine.clustering.R27/R29/R30` annotations on the
migrated types remain valid (the engine.clustering spec is unchanged).
For the new `MultiplexedTransport` impl, add `@spec
transport.multiplexed-framing.R1`-style annotations referencing the v3
spec's R-numbers.

### JPMS pattern application

The single-module + `internal` package pattern follows
[`module-dag-spec-anticipation`](../../.kb/architecture/jpms/module-dag-spec-anticipation.md).
If `MultiplexedTransport` exposes any sealed-type permits whose
construction caller lives in the public `jlsm.cluster` package, apply
the public-static-factory pattern from
[`module-dag-sealed-type-public-factory-carve-out`](../module-dag-sealed-type-public-factory-carve-out/adr.md).

## What This Decision Does NOT Solve

- **Future API/impl module split** — if a thin-client consumer of the
  transport SPI ever materializes, A → B refactoring will be needed.
  Tracked under conditions for revision.
- **Migration of test fixtures using `InJvmTransport`** — the test stub
  moves with the SPI. Existing tests in jlsm-engine that import it must
  update their import statements; this is mechanical.
- **`engine.clustering` spec status post-migration** — the engine.clustering
  spec retains its R27/R29/R30 references to the SPI shape. That spec is
  not invalidated by this move. A future refactor of engine.clustering
  to reference transport.multiplexed-framing is out of scope.

## Conditions for Revision

This ADR should be re-evaluated if:

- A non-impl consumer of the transport SPI is requested (thin-client CLI,
  language binding, embedded mode without NIO impl, contract-test-only
  module). At that point, A → B split should be considered.
- A jlsm-cluster spec ever needs to ship without the NIO impl (e.g., for
  a remote-only deployment shape). Same trigger as above.
- The number of cluster-domain modules grows beyond 2-3. If WG2/WG3 each
  spawn their own modules and a clean cluster-API surface emerges
  organically, a re-organization may be warranted.
- The module-DAG-sealed-type pattern stops applying — e.g., if Java
  introduces a module-private access modifier that supersedes the
  carve-out pattern.

---
*Confirmed by: user deliberation | Date: 2026-04-26*
*Full scoring: [evaluation.md](evaluation.md)*
