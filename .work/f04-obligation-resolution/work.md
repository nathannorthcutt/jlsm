---
group: f04-obligation-resolution
goal: Resolve 12 open F04 engine-clustering obligations identified by spec-verify
status: active
created: 2026-04-19
---

## Goal

Fix the code bugs and implement the missing subsystems that spec-verify
identified as violations of the F04 engine-clustering specification. 12
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
- OBL-F04-R10 (dedup) — blocked on replication layer
- OBL-F04-R20 (phi init) — blocked on measurement data
- OBL-F04-R29 (transport timeout) — blocked on network transport (F19-F21)
- F41 Encryption Lifecycle — separate multi-week project
- F10/F05 cross-module integration stubs — separate work group

## Ordering Constraints

WD-01 (protocol infra) must complete before WD-04 (consensus) — async
listeners are needed for consensus notifications.

WD-02 (engine lifecycle) must complete before WD-03 (remote dispatch) —
local routing must work before remote dispatch is meaningful.

WD-04 (consensus) must complete before WD-05 (fault tolerance) — consensus
defines quorum semantics used by split-brain detection.

## Shared Interfaces
None — all changes are within F04's existing module boundaries (jlsm-core
clustering, jlsm-table clustered).

## Success Criteria
- All 12 obligation IDs resolved (status: resolved in _obligations.json)
- F04 spec promoted from DRAFT to APPROVED
- All new code has adversarial tests covering the spec requirements
- No regressions in existing F04 tests
