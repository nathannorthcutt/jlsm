---
group: f04-obligation-resolution
goal: Resolve 12 open engine.clustering engine-clustering obligations identified by spec-verify
status: active
created: 2026-04-19
---

## Goal

Fix the code bugs and implement the missing subsystems that spec-verify
identified as violations of the engine.clustering engine-clustering specification. 12
obligations are implementable now; 3 remain blocked on external dependencies
(replication layer, network transport, measurement data) and are excluded.

## Scope

### In scope
- Protocol infrastructure: async listener executor (R39), monotonic clock (R53)
- Engine lifecycle: Builder API + join orchestration (R56-57-79), local short-circuit (R60)
- Remote dispatch: payload format (R68), parallel scatter (R77)
- RAPID consensus: multi-process cut detection, quorum rounds, refutation (R34-38),
  expander-graph overlay (R35)
- Fault tolerance: split-brain handling (R41-43), grace-gated rebalancing (R47-50),
  partition pruning (R63)

### Out of scope
- OBL-engine.clustering-R10 (dedup) — blocked on replication layer
- OBL-engine.clustering-R20 (phi init) — blocked on measurement data
- OBL-engine.clustering-R29 (transport timeout) — blocked on network transport (transport.multiplexed-framing-transport.scatter-gather-flow-control)
- encryption.primitives-lifecycle Encryption Lifecycle — separate multi-week project
- query.index-types/engine.in-process-database-engine cross-module integration stubs — separate work group

## Ordering Constraints

WD-01 (protocol infra) must complete before WD-04 (consensus) — async
listeners are needed for consensus notifications.

WD-02 (engine lifecycle) must complete before WD-03 (remote dispatch) —
local routing must work before remote dispatch is meaningful.

WD-04 (consensus) must complete before WD-05 (fault tolerance) — consensus
defines quorum semantics used by split-brain detection.

## Shared Interfaces
None — all changes are within engine.clustering's existing module boundaries (jlsm-core
clustering, jlsm-table clustered).

## Success Criteria
- All 12 obligation IDs resolved (status: resolved in _obligations.json)
- engine.clustering spec promoted from DRAFT to APPROVED
- All new code has adversarial tests covering the spec requirements
- No regressions in existing engine.clustering tests
