---
{
  "id": "engine.clustered-table-construction",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "engine"
  ],
  "requires": [
    "engine.clustering"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "scatter-gather-query-execution",
    "transport-abstraction-design"
  ],
  "kb_refs": [],
  "open_obligations": []
}
---

# engine.clustered-table-construction — Clustered Table Construction Contract

## Requirements

### Construction-time parameter contract

R1. The clustered table's construction surface must distinguish two parameter dimensions: the cluster wiring (table metadata, transport, membership protocol, local node address) and the local-routing dimension (local engine handle for in-process short-circuit, partition keyspace for scan pruning, operational-mode supplier for write gating, ownership instance for view-coherent caching). The cluster-wiring parameters must always be non-null. The local-routing parameters must each have a documented default that is applied when the parameter is omitted.

R2. The clustered table must reject construction when any of the cluster-wiring parameters (table metadata, transport, membership protocol, local node address) is null. Rejection must be a `NullPointerException` thrown at construction time, naming the offending parameter.

R3. The clustered table must reject construction when an explicitly supplied local-routing parameter (ownership instance, partition keyspace, operational-mode supplier) is null. Rejection must be a `NullPointerException` thrown at construction time, naming the offending parameter. This requirement does not apply to the local engine handle, which has explicit null semantics defined by R4.

R4. The clustered table must accept a null local engine handle at construction time without rejection. A null local engine handle is a documented configuration meaning "this clustered table has no in-process short-circuit path; local-owner CRUD or scan dispatch on this instance is undefined and must fail at dispatch time per R6." Construction-time validation must not conflate "null local engine" with "wiring error" — the contract is enforced at the dispatch boundary, not at the construction boundary, because construction does not yet know the membership view nor the keys that will be requested.

### Local-engine dispatch contract

R5. The clustered table must short-circuit a per-key CRUD operation (`create`, `get`, `update`, `delete`) directly to the local engine's table when the resolved owner of the key matches the local node address AND the local engine handle is non-null. This requirement preserves `engine.clustering.R60`.

R6. The clustered table must reject a per-key CRUD operation (`create`, `get`, `update`, `delete`) with an `IllegalStateException` when the resolved owner of the key matches the local node address AND the local engine handle is null. The exception message must name the missing local engine handle and identify the offending operation. The clustered table must not silently route the operation through the cluster transport in this case; routing a local-owner operation through `transport.request(self, ...)` produces self-addressed network traffic that loops back to the same node with serialization overhead and no observability signal. This requirement amends `engine.clustering.R60` by specifying the failure mode for the corner case where the partition resolves locally but no short-circuit path exists.

R7. The clustered table's scan operation must apply R5 / R6 per-owner during scatter-gather: for each owner in the resolved fanout set, if the owner equals the local node address AND the local engine handle is non-null, the scan must short-circuit through the local engine's `scan(fromKey, toKey)`. If the owner equals the local node address AND the local engine handle is null, the per-owner future must complete exceptionally with an `IOException` whose cause is an `IllegalStateException` naming the missing local engine handle. The exceptional per-owner completion must surface through the existing partial-result metadata (`engine.clustering.R64`) — the local owner is reported as unavailable in the unavailable-partition set — and must not abort the scan as a whole.

R8. The clustered table's R6 / R7 dispatch-time guard must be a runtime check (an explicit `if`/`throw`), not a Java `assert`. Java assertions are disabled by default at runtime under `-da`; relying on them would silently re-enable the audited fallthrough behavior in production.

### Construction overload reduction

R9. The clustered table's construction surface must expose at most one factory entry point per arity-and-parameter-set tuple. Two factory overloads with the same arity but disjoint local-routing parameters (for example, one taking a partition keyspace and one taking an operational-mode supplier at the same parameter count) are permitted only when each overload is independently exercised by callers and disambiguation is unambiguous from the parameter types.

R10. The clustered table's factory entry points must each document, in their factory-level Javadoc, which local-routing dimension defaults are applied for parameters not present in that overload. The defaults documented in Javadoc must match the runtime defaults applied by the implementation. A factory whose Javadoc omits a defaulted parameter (for example, a factory that silently passes `null` for the local engine handle without saying so in the contract) is non-compliant.

R11. The clustered table must publish a single canonical factory entry point that takes every local-routing parameter explicitly. All other factory overloads must delegate to the canonical entry point with documented defaults substituted for the omitted parameters. This requirement ensures a single source of truth for default values and prevents drift between overloads.

### Concurrency contract

R12. The clustered table is not thread-confined: instances are designed to be invoked concurrently from multiple caller threads. Per-call dispatch (resolve owner, choose short-circuit vs transport, perform I/O) must be safe under concurrent invocation; concurrent dispatch must not produce torn views of the local engine handle or the local node address. Both fields must be set before construction returns and must be observed by every subsequent dispatch.

R13. The clustered table's local engine handle, once set at construction time, must not change for the lifetime of the instance. Construction must therefore publish the handle (null or non-null) via a final field with safe initialization semantics. Mutating the handle after construction is not part of the contract; replacing the engine requires constructing a new clustered table.

### Error propagation

R14. When R6 fires, the resulting `IllegalStateException` must propagate to the caller of the CRUD method as an `IllegalStateException`, not wrapped in `IOException`. The CRUD methods declare `throws IOException` for I/O failures; an unchecked configuration-error exception propagates as an unchecked exception so callers can distinguish "the cluster is misconfigured" from "the cluster is transiently unavailable."

R15. When R7 fires for a local-owner partition during scan, the resulting per-owner failure must be wrapped in `IOException` with `IllegalStateException` as the cause, then aggregated into partial-result metadata exactly like any other per-partition failure. The scan as a whole must not abort because of one local-owner misconfiguration; the partial-result metadata is the correct surface for reporting it because the caller can already inspect that metadata to detect missing partitions.

R16. The R6 / R7 exception messages must each identify (a) the offending operation name, (b) the resolved owner address, and (c) the table name. Diagnostic information must be sufficient for an operator to identify the misconfigured construction site without re-running with a debugger.

### Migration and deprecation

R17. The clustered table's factory overloads that supply `null` as the default local engine handle (the cluster-wiring-only overloads at 4-arg and 5-arg arity) must carry a deprecation marker in their Javadoc, indicating that callers should migrate to the canonical overload with an explicit local engine handle. The marker must not be a runtime `@Deprecated` annotation alone; the Javadoc must explain the migration path.

R18. The clustered table's factory overloads at 4-arg and 5-arg arity must remain source-compatible for the duration of the deprecation window so existing test fixtures and external callers that pass through them are not broken at compile time. Compile-time removal of these overloads is out of scope of this spec; this spec governs only the runtime contract on null `localEngine`.

R19. The deprecation window for the 4-arg / 5-arg `forEngine` overloads must be tracked as an open obligation against this spec and resolved by either (a) removing the overloads after all callers have migrated, or (b) re-affirming them as supported overloads with documented "transport-only" semantics — but the second outcome must add a new requirement that explicitly defines what "transport-only" means at dispatch time, replacing the silent-fallthrough behavior the audit identified.

## Cross-References

- ADR: .decisions/scatter-gather-query-execution/adr.md
- ADR: .decisions/transport-abstraction-design/adr.md
- Spec: .spec/domains/engine/clustering.md (engine.clustering — R59, R60, R61, R64)

---

## Design Narrative

### Intent

The clustered table is constructed by `ClusteredEngine` with a non-null local engine handle so that locally-owned per-key operations short-circuit through the in-process engine instead of paying a transport round-trip. The `forEngine` factory exposes additional overloads at lower arity that omit the local engine handle, defaulting it to `null`. Those overloads exist for test fixtures that operate in cluster configurations where ownership never resolves to the local node (multi-node membership views with rendezvous hashing spreading keys to remote owners).

The audit identified a silent failure mode: when an instance is constructed via the lower-arity overloads with `localEngine == null`, AND the resolved owner of a per-key operation happens to equal the local node address (for example, a single-node membership view, or a rendezvous-hash collision), the dispatch silently routes through `transport.request(self, ...)`. This is a self-loop with serialization overhead, no observability signal, and no production use case. The behavior is documented only by a "Backward-compat" comment on a single test that codifies the behavior — but that test predates the audit and reflects the implementation's accidental state, not a designed intent.

This spec resolves the ambiguity by making the contract explicit: silent self-routing is an audit-confirmed footgun. The dispatch boundary fails fast with `IllegalStateException` whenever a local-owner key resolves on an instance with no local engine handle. Construction is permitted to proceed with a null handle because tests need that flexibility, but any subsequent per-key dispatch that happens to resolve locally fails with a clear, named exception.

### Why this approach

**Dispatch-time fail-fast over construction-time fail-fast.** Construction does not yet know the membership view, the rendezvous hash distribution, or the keys that will be requested. A configuration that passes a multi-node test suite via the 4-arg overload is legitimate when ownership never resolves locally; rejecting that configuration at construction time would force every test to plumb a stub local engine. The error surface is the dispatch boundary because that is the first point at which the misconfiguration becomes observable as a real divergence from R60.

**`IllegalStateException` over silent transport routing.** The audited behavior (silent fallthrough to `transport.request(self, ...)`) has no production caller. Production code in `ClusteredEngine.createTable` always supplies a non-null `localEngine`. The only callers that produce a null handle are tests, and the silent self-loop is a defect, not a designed feature. An explicit exception with a named cause is strictly more useful: tests that intentionally exercise the lower-arity overloads do so in multi-node configurations where the exception is unreachable, while a test that accidentally creates a single-node-LOCAL-only view with a null handle gets an immediate, actionable failure.

**Partial-result metadata for scan local-owner failure.** A scatter-gather scan that finds the local owner among its fanout set already has partial-result machinery for per-partition failures. Routing the local-owner misconfiguration through that channel keeps the scan result valid (other partitions still contribute) and makes the local-owner unavailability visible to the caller via the existing `lastPartialResultMetadata()` API. Aborting the scan would be more disruptive and inconsistent with how remote-owner failures are handled.

**`IllegalStateException` (unchecked) over `IOException` (checked).** A misconfigured local engine handle is a programmer error, not an I/O fault. The CRUD methods' `throws IOException` declaration is for transient cluster faults; wrapping a configuration error in `IOException` would force callers to catch the same checked exception for two unrelated failure classes. Scan is the exception: scan's per-partition failure aggregation already wraps everything as `IOException` for the partial-result metadata channel, so R7 follows that established convention.

**Runtime check over `assert`.** Java assertions default to disabled (`-da`); the audited behavior would silently re-emerge in production builds if the guard were `assert`. The dispatch guard must be a runtime `if`/`throw`. This decision is consistent with the broader project rule (`code-quality.md`) that any spec-mandated behavior must be enforced by a runtime check, not an assertion.

**Deprecation over removal.** Removing the 4-arg / 5-arg overloads in this spec would break a meaningful set of test fixtures (`ClusteredTableTest`, `ContractBoundariesAdversarialTest`, `EngineClusteringAdversarialTest`, `ResourceLifecycleAdversarialTest`, `SharedStateAdversarialTest`, `ConcurrencyAdversarialTest`, `ClusteredTableScanParallelTest`, `ClusteredTableScanPruningTest`, `ClusteredTableLocalShortCircuitTest`). Those tests are legitimate users of the cluster-wiring-only overloads in multi-node configurations. The spec marks the overloads for deprecation and tracks the migration as an open obligation rather than forcing a flag-day rewrite.

### What was ruled out and why

- **Document `null` local engine as a permitted "transport-only handle" mode.** Considered as the alternative interpretation in the brief. Rejected because the silent self-loop has no production caller — `ClusteredEngine` always supplies a non-null engine — so "transport-only handle" is an artifact of test-fixture convenience, not a designed feature. The mode would also require a separate semantic for "local key resolved on a transport-only handle" (forward through transport? abort? short-circuit how?), and every plausible answer is worse than failing fast.

- **Construction-time fail-fast (reject null `localEngine` always).** Considered as a strict version of the chosen approach. Rejected because legitimate tests construct lower-arity overloads in multi-node configurations where local-owner dispatch is unreachable; rejecting null at construction would force a stub-engine fixture across the entire test suite for no behavioral gain.

- **Remove the 4-arg / 5-arg `forEngine` overloads outright.** Considered to eliminate the null-engine path entirely. Rejected because (a) removal is a flag-day rewrite of nine test classes, (b) the deprecation marker plus dispatch-time guard is sufficient to close the audited divergence, and (c) future migration can remove the overloads after all callers have migrated, tracked as an open obligation.

- **Log a warning instead of throwing on null-engine local dispatch.** Rejected because logging would not let the audited test (`ClusteredTableLocalShortCircuitTest.null_localEngine_fallsBackToRemoteForAllOwners`) be updated to assert the new contract — a logging-only change preserves the silent self-loop as observable behavior, and the audit explicitly identified that as the bug. The audit's `prove-fix` attempt confirmed that any non-throwing change leaves the audited finding unfixed.

### Invalidated requirements

This spec does not invalidate any prior requirement outright. It amends `engine.clustering.R60` by specifying the previously-ambiguous failure mode for "local owner, no local engine." `engine.clustering.R60` continues to govern the short-circuit case (R5 here); the null-engine corner case is now explicitly defined by R6 and R7.

## Verification Notes

### Authored: v1 — 2026-04-25

Authored under non-interactive RELAX-2 spec-author run from the WD-02 audit pipeline. Adversarial passes (Pass 2 falsification, Pass 3 depth) executed inline by the authoring agent rather than via a subagent, per the non-interactive constraint. Findings considered:

**Pass 2 — degenerate value checklist:**
- Null `localEngine` is the central case the spec resolves (R4 + R6 + R7).
- Null cluster-wiring parameters: covered by R2.
- Null other local-routing parameters: covered by R3.
- Multi-node membership view with no local owner: R5/R6/R7 fire only when resolved owner equals local node address; multi-node-no-local-owner stays on the existing R61 transport path.
- Single-node membership view with null engine: R6 fires explicitly. This is the audited test scenario.
- Single-node membership view with non-null engine: R5 short-circuits.
- Concurrent calls during construction: R12/R13 require final-field publication.

**Pass 2 — boundary validation probe:**
- R6 guard is unconditional (R8 forbids `assert`). No configuration flag can disable it.
- All four CRUD entry points are enumerated by R5/R6.
- Scan is enumerated by R7 with explicit per-owner failure aggregation.

**Pass 2 — error propagation probe:**
- Post-R6 state of the clustered table: instance remains usable; the exception is per-call. R13 ensures the engine handle field does not change.
- R14 specifies the exception type for CRUD; R15 specifies the wrapping for scan; R16 specifies message content.

**Pass 2 — concurrency contract probe:**
- R12 explicitly declares the thread-safety model (concurrent dispatch is safe).
- R13 requires final-field publication for the local engine handle.

**Pass 2 — cross-construct atomicity probe:**
- Per-key CRUD is single-construct; no atomicity gap.
- Scan with a local-owner R7 failure aggregates via partial-result metadata (existing `engine.clustering.R64` machinery); not atomic by design and existing R65 already documents that.

**Pass 3 — depth-pass consequences of Pass 2:**
- R6's `IllegalStateException` propagates as an unchecked exception through the CRUD methods declared `throws IOException`. R14 makes this explicit so callers and test-writers know it is not wrapped.
- R7's `IOException` wrapping for scan ensures consistency with the existing scatter-gather error channel — without R15, two different per-partition failure modes (transport timeout vs configuration error) would propagate as different exception types and break partial-result aggregation.
- R8's runtime-check requirement closes the assertion-disabled-in-production gap.
- R11's "single canonical entry point" requirement prevents drift between overloads as new local-routing dimensions are added.

**Pass 3 — fix-consequence finding:**
- Adding R6's runtime guard creates a new attack surface: a caller who legitimately wanted "no short-circuit, route everything via transport" loses that mode. Mitigation: that mode has no documented contract today (it is the audited bug), and R19 explicitly tracks the open obligation to either remove the overloads or define a real "transport-only" mode with its own requirement.

**Pass 3 — fix-consequence finding (resource lifecycle):**
- R7's per-owner failure during scan must still cleanly close any resources the per-owner future was holding. The existing `engine.clustering.R100` covers this for remote-owner per-future cleanup; R7's local-owner failure produces a `CompletableFuture.failedFuture(...)` immediately, holding no resources to clean up. No new requirement needed.

No critical or high findings remained after Pass 3. Spec promoted to APPROVED.

### Open obligations

R19 tracks the deprecation window for the 4-arg / 5-arg `forEngine` overloads. Resolution requires either removal (after all callers migrate) or a re-affirmation requirement defining "transport-only" semantics. Tracked in `_obligations.json` under `OBL-engine-clustered-table-construction-R19-overload-deprecation`.
