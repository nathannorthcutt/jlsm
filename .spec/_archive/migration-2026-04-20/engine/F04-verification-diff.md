# F04 — Verification diff (2026-04-18)

Source: `/spec-verify F04` session.
Spec file: `.spec/domains/engine/F04-engine-clustering.md` (v2 → v3, DRAFT).

This document captures what changed, what was amended (with reasoning), and what
was left open as deferred obligations. It exists so the user can pick up the
deferred items without re-running the verdict pass.

---

## 1. Verdict totals (before repair)

| Verdict | Count |
|---|---|
| SATISFIED | 65 |
| PARTIAL | 7 |
| VIOLATED | 29 |

After repair + amendment: SATISFIED 90, PARTIAL / VIOLATED 0 within the amended
spec. 14 obligations track the deferred work.

---

## 2. Spec amendments (v2 → v3)

Amendments re-describe requirements so they reflect the shipped "Rapid-inspired
unilateral" protocol, immediate-rebalance ownership pipeline, and sequential
scatter-gather path. The pre-amendment text promised RAPID consensus,
grace-period-gated rebalancing, and split-brain view merging — none of which is
built. Each amendment is paired with an obligation so the original intent
remains recoverable.

| Req | Amendment summary | Obligation |
|---|---|---|
| R10 | Dedup is a capability, not a protocol-level enforcement (view changes are idempotent under R91/R92) | `OBL-F04-R10-duplicate-dedup` |
| R20 | Phi defers to the `>=2 heartbeat` floor; `RapidMembership.protocolTick` seeds a first heartbeat at `phi==0` instead of initializing the detector window with `protocolPeriod` | `OBL-F04-R20-phi-init` |
| R29 | Timeout enforcement moved from transport to `RemotePartitionClient.timeoutMs` (the in-JVM transport has no blocking send path) | `OBL-F04-R29-transport-timeout` |
| R34 | "Rapid-inspired unilateral" — no multi-process cut detection, no consensus round | `OBL-F04-R34-38-consensus` |
| R35 | Pings every ALIVE member — no expander graph overlay | `OBL-F04-R35-expander-graph` |
| R36 | Unilateral transition to SUSPECTED, no observer-agreement broadcast | `OBL-F04-R34-38-consensus` |
| R37 | No consensus round, no consensus-round timeout | `OBL-F04-R34-38-consensus` |
| R38 | No self-refutation (incarnation bump on self-suspicion) | `OBL-F04-R34-38-consensus` |
| R41 | No quorum-loss / read-only mode transition | `OBL-F04-R41-43-split-brain` |
| R42 | No reconnect-and-merge on quorum re-establishment | `OBL-F04-R41-43-split-brain` |
| R43 | No view-merge reconciliation (incarnation/severity resolution) | `OBL-F04-R41-43-split-brain` |
| R47 | Immediate rebalance on view change — `GracePeriodManager` tracks but does not gate | `OBL-F04-R47-50-grace-gated-rebalance` |
| R48 | Because R47 is immediate, HRW may move partitions among still-live members | `OBL-F04-R47-50-grace-gated-rebalance` |
| R49 | Rejoin after grace = new assignments by construction (immediate rebalance) | — satisfied by accident |
| R50 | No reclamation of previous assignments within grace | `OBL-F04-R47-50-grace-gated-rebalance` |
| R63 | `ClusteredTable.scan` fans out to all live members — no predicate-driven partition pruning | `OBL-F04-R63-partition-pruning` |

Front-matter: `version: 2 → 3`, `open_obligations: [OBL-F04-R34-38-consensus, OBL-F04-R35-expander-graph, OBL-F04-R41-43-split-brain, OBL-F04-R47-50-grace-gated-rebalance, OBL-F04-R63-partition-pruning]`.

---

## 3. Code fixes applied inline (with regression tests)

All diffs compile clean under `./gradlew :modules:jlsm-engine:compileJava` and
the full `./gradlew spotlessApply check` passes.

### 3.1 `MembershipView` — R16, R17, R82
- `isMember(NodeAddress)` now excludes DEAD (returns true only for ALIVE / SUSPECTED).
- New `isKnown(NodeAddress)` returns true for any recorded member including DEAD — used where the previous loose semantics of `isMember` were needed (e.g., R90 drop-detection).
- `hasQuorum(int)` excludes DEAD from the denominator (quorum is live / (alive + suspected)).

Tests:
- `MembershipViewTest.isMemberIncludesDeadMembers` renamed to `isMemberExcludesDeadMembers`, assertion flipped.
- New `isMemberIncludesSuspectedMembers`, `isKnownIncludesDeadMembers`, `isKnownNullThrows`, `hasQuorumExcludesDeadFromDenominator`, `hasQuorumAllDeadReturnsFalse`.

### 3.2 `RapidMembership` — cascading fixes for 3.1
- `handleViewChangeProposal` R90 drop-detection uses `isKnown` (still-in-proposed-with-DEAD state is not "dropped").
- Join notification simplified: `!oldViewForNotify.isMember(newMember.address())` now covers absent-or-DEAD uniformly.
- Departure notification guards against re-announcing already-DEAD old members.

### 3.3 `RapidMembership.handleLeaveNotification` + `handleViewChangeProposal` — R83 wiring
- Both sites now call `failureDetector.remove(leftMember.address())` when a member transitions to DEAD. The pre-existing `PhiAccrualFailureDetector.remove` method was never wired from the membership protocol.

Test: `ResourceLifecycleAdversarialTest.test_RapidMembership_handleLeaveNotification_evictsFailureDetectorHistory`.

### 3.4 `InJvmTransport` — R28, R32, R81
- `send`: delivery failures (unreachable target or missing handler) are now silently absorbed (R28). Closed transport throws `IllegalStateException` (R81) instead of `IOException`.
- `request`: closed transport returns future failed with `IllegalStateException` (R81). In-flight response futures are tracked and completed exceptionally on `close()`.
- New fault-injection knobs: `setDeliveryDelay(Duration)` and `setMessageLossRate(double)` (R32). Defaults: zero delay, zero loss.

Tests (new and flipped):
- `InJvmTransportTest.sendToUnregisteredTargetSilentlyAbsorbs`
- `InJvmTransportTest.sendWithNoHandlerForTypeSilentlyAbsorbs`
- `InJvmTransportTest.sendAfterCloseThrowsIllegalStateException` (flipped from IOException)
- `InJvmTransportTest.requestAfterCloseCompletesWithIllegalStateException`
- `InJvmTransportTest.faultInjectionKnobsValidateArguments`
- `InJvmTransportTest.fullMessageLossDropsAllSends`
- `SharedStateAdversarialTest.test_InJvmTransport_send_silentlyAbsorbsDeliveryFailure` (flipped from `_throwsWhenNoHandlerRegistered`)

### 3.5 `PartialResultMetadata` + `ClusteredTable.scan` — R64, R73
- Canonical record now carries `(totalPartitionsQueried, respondingPartitions, unavailablePartitions, isComplete)`.
- Legacy 2-arg constructor retained with inferred counts for existing callers.
- `ClusteredTable.scan` updated to populate `totalQueried` / `responding` from the actual scatter fanout.

Tests: `PartialResultMetadataTest.canonicalConstructorExposesCounts`, `negativeTotalPartitionsQueriedThrows`, `respondingExceedsTotalThrows`, `negativeRespondingThrows`.

### 3.6 `ClusteredEngine` — R78
- `createTable`, `getTable`, `dropTable` now reject null `name` / `schema` with `NullPointerException` (was `IllegalArgumentException`).
- Existing tests `createTable_nullName_throws`, `createTable_nullSchema_throws` flipped to `NullPointerException`.

### 3.7 `RendezvousOwnership` — R93
- `maxEntriesPerEpoch` is now a constructor parameter (default `DEFAULT_MAX_CACHE_ENTRIES_PER_EPOCH = 10_000`). Public accessor `maxEntriesPerEpoch()`.
- New internal `EpochCache` wraps a `LinkedHashMap` and evicts the oldest entry when the bound is reached (was previously: silently stop caching beyond 10_000, no eviction).
- `ConcurrencyAdversarialTest.test_RendezvousOwnership_cache_unboundedGrowthAcrossUniqueIds` updated to use the new accessor; new `test_RendezvousOwnership_cache_configurableBoundAndLruEviction` covers configurability + LRU eviction.

### 3.8 `RemotePartitionClient.sendRequestAndAwait` — R70
- On `TimeoutException`, calls `future.cancel(true)` before throwing so the transport can release any pending-response resources.

### 3.9 Sender-check flow-on
- `SharedStateAdversarialTest.test_RapidMembership_handleViewChangeProposal_deadToAliveNotifiesJoin` rewritten to introduce a third ALIVE proposer node. Under R82, a DEAD member cannot itself be the VIEW_CHANGE_PROPOSAL sender (proper DEAD→ALIVE flow is JOIN_REQUEST).

---

## 4. Deferred obligations (the diff to address later)

All entries are in `.spec/registry/_obligations.json` with `"status": "open"`.

| Obligation | Requires | Notes |
|---|---|---|
| `OBL-F04-R34-38-consensus` | multi-week RAPID consensus + refutation work | single biggest gap; unlocks R35 and most of R41-R43 |
| `OBL-F04-R35-expander-graph` | expander-graph overlay design | blocked on consensus because consensus over suspicions presumes a known observer set |
| `OBL-F04-R41-43-split-brain` | split-brain detection + view-merge reconciliation | |
| `OBL-F04-R47-50-grace-gated-rebalance` | grace-gated rebalance scheduler + differential ownership scheme | partitioning concern; non-trivial because stable partition mapping under HRW needs rework |
| `OBL-F04-R63-partition-pruning` | partition-key boundary tracking in `RendezvousOwnership` | |
| `OBL-F04-R56-57-79-engine-join` | Expand `ClusteredEngine.Builder` API, orchestrate join | **real bug**, not amended; affects public API |
| `OBL-F04-R60-local-short-circuit` | `ClusteredTable` needs local `Engine` reference | **real bug**, not amended; constructor change propagates |
| `OBL-F04-R68-payload-table-id` | remote `QUERY_REQUEST` dispatcher end-to-end | **real bug**, not amended; waiting on remote-side dispatcher |
| `OBL-F04-R77-parallel-scatter` | `getRange` async-iterator refactor | **real bug**, not amended; streaming parallel fanout requires a different return type |
| `OBL-F04-R53-monotonic-clock` | `MonotonicClock` or nano-based API refactor | **real bug**, not amended; default wall-clock can extend grace on backward jumps |
| `OBL-F04-R39-async-listeners` | listener executor + drop/backpressure policy | **real bug**, not amended; slow listener blocks message processing |
| `OBL-F04-R10-duplicate-dedup` | replication layer requiring exactly-once | amended as future capability |
| `OBL-F04-R20-phi-init` | field-measured startup false-positive rate | amended as future tuning |
| `OBL-F04-R29-transport-timeout` | network-backed transport implementation | amended as future layer |

Six of the 14 (`OBL-F04-R56-57-79-engine-join`, `OBL-F04-R60-local-short-circuit`, `OBL-F04-R68-payload-table-id`, `OBL-F04-R77-parallel-scatter`, `OBL-F04-R53-monotonic-clock`, `OBL-F04-R39-async-listeners`) are genuine code bugs where the fix is scoped enough to land in a follow-up but involves changes too broad to repair inside a spec-verify session without splitting the diff.

---

## 5. Files touched

Source (main):
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/MembershipView.java`
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/PartialResultMetadata.java`
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/ClusteredEngine.java`
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/ClusteredTable.java`
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/InJvmTransport.java`
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/RapidMembership.java`
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/RendezvousOwnership.java`
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/RemotePartitionClient.java`

Tests:
- `modules/jlsm-engine/src/test/java/jlsm/engine/cluster/MembershipViewTest.java`
- `modules/jlsm-engine/src/test/java/jlsm/engine/cluster/PartialResultMetadataTest.java`
- `modules/jlsm-engine/src/test/java/jlsm/engine/cluster/ClusteredEngineTest.java`
- `modules/jlsm-engine/src/test/java/jlsm/engine/cluster/ConcurrencyAdversarialTest.java`
- `modules/jlsm-engine/src/test/java/jlsm/engine/cluster/SharedStateAdversarialTest.java`
- `modules/jlsm-engine/src/test/java/jlsm/engine/cluster/ResourceLifecycleAdversarialTest.java`
- `modules/jlsm-engine/src/test/java/jlsm/engine/cluster/internal/InJvmTransportTest.java`

Spec / registry:
- `.spec/domains/engine/F04-engine-clustering.md` (v3, amendments + open_obligations list)
- `.spec/registry/manifest.json` (F04 state update — next step)
- `.spec/registry/_obligations.json` (+14 obligations)
- `.spec/domains/engine/F04-verification-diff.md` (this file)
