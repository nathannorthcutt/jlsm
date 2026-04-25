---
title: "Integration-frontier blind spot in per-WU TDD (audit catches cross-WU wiring gaps)"
type: adversarial-finding
tags: [TDD, work-units, integration, wiring, stubs, audit, frontier, cross-construct]
domain: [process, methodology, contract_boundaries]
topic: "patterns"
category: "adversarial-review"
research_status: "stable"
confidence: "high"
last_researched: "2026-04-25"
applies_to:
  - "any work plan that decomposes into per-WU TDD where construct ownership is split by domain"
  - ".claude/rules/tdd-protocol.md (test/implement/refactor cycle)"
  - ".claude/rules/testing.md (TDD failure-first gate)"
  - "WD-* work definitions with cross-construct wiring (writer factories, reader factories, engine integration)"
related:
  - "tdd-failure-first-degeneracy-for-pure-record-enum-work"
  - "hash-function-algebraic-probes"
decision_refs: []
sources:
  - url: ".feature/implement-encryption-lifecycle--wd-02/audit/audit-report.md"
    title: "WD-02 audit report — 45 findings, 21 confirmed bugs after 4 clean per-WU TDD passes"
    accessed: "2026-04-25"
    type: "docs"
  - url: ".feature/implement-encryption-lifecycle--wd-02/audit/run-001/prove-fix-F-R1-contract_boundaries-2-1.md"
    title: "F-R1.contract_boundaries.2.1 — LocalEngine writer factory dropped commitHook + tableNameForLock"
    accessed: "2026-04-25"
    type: "docs"
  - url: ".feature/implement-encryption-lifecycle--wd-02/audit/run-001/prove-fix-F-R1-contract_boundaries-3-1.md"
    title: "F-R1.contract_boundaries.3.1 — DocumentSerializer.deserialize had no ReadContext overload; R3e gate had zero production call sites"
    accessed: "2026-04-25"
    type: "docs"
  - url: ".feature/implement-encryption-lifecycle--wd-02/audit/run-001/prove-fix-F-R1-contract_boundaries-3-5.md"
    title: "F-R1.contract_boundaries.3.5 — TrieSSTableReader held populated ReadContext but never threaded it to deserialize"
    accessed: "2026-04-25"
    type: "docs"
---

# Integration-frontier blind spot in per-WU TDD

## Pattern

When a work plan decomposes a feature into work units (WUs) by **domain
ownership** — footer in one WU, dispatch in another, engine wiring in a
third, serializer in a fourth — and each WU passes its tests in isolation
with stubbed dependencies, the **cross-WU integration surface is
structurally invisible to TDD**. Tests stub the dependency; production
wiring may not be wired at all. The features compile, all unit tests pass,
and the feature ships with whole pipelines silently bypassed.

This is not a TDD discipline failure. It is a structural property of
per-WU TDD with stubbed boundaries: the test fixture for WU-A passes a
fake to a real constructor of construct A; the test fixture for WU-B
passes a fake to a real constructor of construct B; nothing in either
fixture proves that **production** assembles A and B together with the
right edges.

## What happens

WD-02 (`implement-encryption-lifecycle--wd-02`) was decomposed into 4 WUs.
All 4 completed cleanly:

- 205 unit tests authored and passing.
- 0 escalations across all WUs (no contract conflicts, no test/code
  re-author cycles).
- Refactor + spotless clean on every WU.
- `./gradlew check` green at WD close.

The follow-on `/audit` produced 45 findings; 21 were confirmed bugs. The
**most damaging** were "frontier" bugs invisible to per-WU TDD:

1. **`F-R1.contract_boundaries.2.1`** — `LocalEngine.createJlsmTable`
   wired the writer factory as `TrieSSTableWriter::new` (the bare 3-arg
   constructor reference). The `commitHook` and `tableNameForLock`
   parameters that carry the entire R10c TOCTOU defence were never passed.
   Encrypted writes silently committed v5 plaintext SSTables into encrypted
   table directories.

2. **`F-R1.contract_boundaries.3.1`** —
   `DocumentSerializer.SchemaSerializer.deserialize` had no
   `(MemorySegment, ReadContext)` overload. The R3e dispatch gate
   (`FieldEncryptionDispatch.decryptWithContext`) had **zero production
   call sites**. The defence existed in the code base but was unreachable
   from the SSTable read path.

3. **`F-R1.contract_boundaries.3.5`** — `LocalEngine` wired
   `TrieSSTableReader.open(...)` as the 2-arg overload (no `expectedScope`).
   The reader materialised a populated `ReadContext` and exposed it via
   `readContext()`, but no production read API consumed it to thread into
   a `MemorySerializer`'s `(MemorySegment, ReadContext)` overload.
   Encrypted reads routed through the v5-only path.

In all three cases the **constructs were correct** when viewed in
isolation:

- `TrieSSTableWriter.builder().commitHook(...)` worked. WU-2's footer
  tests passed.
- `FieldEncryptionDispatch.decryptWithContext(...)` worked. WU-4's
  dispatch tests passed.
- `TrieSSTableReader.readContext()` worked. The reader's footer-parse
  tests passed.

The bugs lived **between** constructs, in the assembly. Each WU's tests
stubbed the dependency or instantiated the construct directly with hand-
chosen parameters. Production wiring (the engine's table-creation code,
the serializer's deserialize overload set, the reader's typed get
delegation) was structurally outside every WU's test surface.

## Pattern statement (short form)

When a work plan stages constructs by domain ownership and each WU's
tests stub or directly-instantiate the dependencies it does not own,
the cross-construct wiring lives in glue code (engine integration,
factory definitions, default-method overrides) that is **not the
primary construct of any WU**. Each WU passing in isolation gives
zero evidence that production wires the pieces together correctly.
The integration surface is unverified.

(See `integration-frontier-blind-spot-detail.md` for the four-clause
conjunction, the structural reasons per-WU TDD cannot see the
frontier, and what is *not* the fix.)

## Fix pattern

The audit pipeline (`/audit`) is the structural backstop. Per-WU TDD is
correct for what it covers; it cannot cover the integration frontier
because the frontier is, by construction, no single WU's territory.

**Mitigation A — adversarial audit pass after WD close.** Run `/audit`
on the merged WD before declaring it shippable. The
`contract_boundaries` lens systematically probes cross-construct edges
and is where all three WD-02 frontier bugs were caught. **This is the
intended workflow.** A WD that has not had an audit pass has unverified
integration. Audit cost is small relative to the test/implementation
cycles audit findings would otherwise force after release.

**Mitigation B — frontier tests in the work plan.** When a work plan is
authored, identify the production data-flow paths that span multiple
WUs. For each path, name a "frontier test" WU (or a sub-task within the
last WU) whose responsibility is end-to-end exercise of the assembled
production pipeline — _without_ stubs at the cross-WU boundaries.
Frontier tests must use real constructs from each WU and must observe,
not inject, the wiring that production constructs.

**Mitigation C — reachability check on `@spec`-annotated gates.** For
any spec requirement that is "the X gate enforces Y" (R3e, R10c-step-5,
etc.), the spec-verify or feature-pr step should grep for the gate's
production call sites. A gate with zero call sites is a defence that
exists in code but is unreachable from the entry points the spec
defines as the gate's domain. This is mechanical: count call sites of
the gate method outside its own test class.

**Mitigation D — work-plan reviewer asks "where is the wiring?".** The
work plan should explicitly answer: "Which WU owns the production
assembly that wires constructs A, B, C together?" If no WU does, that
is a planning gap; add a wiring WU. WD-02 had no wiring WU — the
engine-integration calls were buried in WU-3's normal scope and did not
get a TDD frontier-test pass.

## Seen in

- **WD-02** (`implement-encryption-lifecycle--wd-02`, 2026-04-25 audit
  round-001): 4 WUs completed cleanly with 205 unit tests and 0
  escalations; `/audit` then surfaced 45 findings, 21 confirmed bugs.
  The three frontier bugs above were the most structurally damaging —
  each disabled an entire encryption-related production pipeline
  silently. All three were fixed during prove-fix without spec
  amendments; the spec was correct, the wiring was missing.

## Related

- `.kb/patterns/testing/tdd-failure-first-degeneracy-for-pure-record-enum-work.md` —
  another structural limit of per-WU TDD: the failure-first gate is
  unsatisfiable for pure-type WUs. Both entries describe TDD-protocol
  edges that the protocol alone cannot defend; both rely on a follow-on
  audit-or-cycle-log discipline. Frontier bugs are the cross-construct
  analogue of degenerate-stub bugs: per-WU TDD's blind spots come in
  two flavours — type-shape (degenerate stubs) and assembly-shape
  (frontier wiring).
- `.kb/patterns/adversarial-review/hash-function-algebraic-probes.md` —
  same meta-pattern at a different layer: spec-author missed a class of
  weakness, audit caught it. Frontier-blind-spot is the per-WU-TDD
  analogue. Both motivate audit as a structural safety net rather
  than a discretionary review step.

## detection-and-detail

Detection signals during work-plan review, the four-clause pattern
statement, what is not the fix, and a frontier-inventory work-plan
template fragment are extracted to keep this subject file under the
200-line cap.

@./integration-frontier-blind-spot-detail.md

---
*Researched: 2026-04-25 | Next review: 2027-04-25*
