---
title: "TDD Failure-First Degeneracy for Pure-Record / Pure-Enum Foundation Work"
aliases:
  - "failure-first degeneracy pure-type work"
  - "TDD protocol step-2 exception for records and enums"
  - "planner-landed artifact tests"
topic: "patterns"
category: "testing"
tags: ["testing", "TDD", "records", "enums", "sealed-hierarchies", "foundation-layer", "test-writer", "cycle-log", "methodology"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-23"
applies_to:
  - ".claude/rules/testing.md"
  - ".claude/rules/tdd-protocol.md"
  - ".feature/implement-encryption-lifecycle--wd-01/units/WU-1/cycle-log.md"
related:
  - "stale-test-after-exception-type-tightening"
  - "static-mutable-state-test-pollution"
  - "wall-clock-dependency-in-duration-logic"
decision_refs: []
sources:
  - url: ".claude/rules/testing.md"
    title: "jlsm TDD protocol — write tests first, confirm failure, implement, verify"
    accessed: "2026-04-23"
    type: "docs"
  - url: ".feature/implement-encryption-lifecycle--wd-01/units/WU-1/cycle-log.md"
    title: "WU-1 cycle log — first documented degeneracy of the failure-first gate"
    accessed: "2026-04-23"
    type: "docs"
  - url: ".feature/implement-encryption-lifecycle--wd-01/cycle-log.md"
    title: "WD-01 parent cycle log — WU-1 summary entry"
    accessed: "2026-04-23"
    type: "docs"
---

# TDD Failure-First Degeneracy for Pure-Record / Pure-Enum Foundation Work

## summary

The jlsm TDD protocol requires tests to **fail before implementation begins**
(step 2 of `.claude/rules/testing.md`). That gate is **structurally
unsatisfiable** when a WU is dominated by records, enums, and sealed
hierarchies: a record's compact constructor *is* the implementation, a sealed
enum's constants exist at compile time, and a sealed exception hierarchy is a
declarative type relationship. There is no meaningful "empty stub" form of
these constructs. The correct response is to **recognise the pattern, run the
tests against the planner-landed artifacts, and document the degeneracy in
the cycle-log** — not to retrofit a broken stub body just to force a failure.

## how-it-works

The TDD protocol's failure-first gate exists so that a test which passes
*before* an implementation has been written is caught as a bad test. For
constructs with behaviour this is honoured by stubbing with
`throw new UnsupportedOperationException()` and watching the test fail.
Pure-type constructs have no body to stub:

- **Records with validation-only compact constructors** — the whole logic is
  `Objects.requireNonNull` + `if`/`throw` + defensive copy; replacing the body
  leaves the record unusable by other units.
- **Sealed enums** — named constants are part of the language-level declaration.
- **Sealed exception hierarchies** — the `sealed` / `permits` / `final` /
  `non-sealed` structure *is* the behaviour under test.

In WU-1 of WD-01 (2026-04-23), **20 of 20 new constructs** were pure types;
the planner landed the full bodies during planning because the planning-time
artifact *is* the simplest possible correct artifact for these types.

### decision-matrix

| Construct kind | Has behaviour beyond validation? | Failure-first applicable? | Stub form |
|----------------|----------------------------------|---------------------------|-----------|
| record (validation-only compact ctor) | no | **no** — degenerate | none (planner lands full body) |
| enum (no methods or only accessor methods) | no | **no** — degenerate | none |
| sealed exception hierarchy | no | **no** — degenerate | none |
| record with behaviour method (e.g. `toString`, computed field) | yes | **yes** (for the behaviour method) | stub the method body only |
| utility / static-helper class | yes | **yes** | stub every method |
| service facade / SPI implementation | yes | **yes** | stub every method |
| I/O handler / WAL / SSTable component | yes | **yes** | stub every method |

## algorithm-steps

1. **Before test-writing, classify the WU's constructs.** If 100% (or nearly
   100%) of new constructs are records, enums, or sealed hierarchies, this
   recipe applies. If any construct has behaviour beyond validation, that
   construct must still honour failure-first with a body stub.
2. **Write tests against the planner-landed artifacts.** Tests must cover:
   - Null-reject / empty-reject for record components.
   - Value equality on records.
   - Closed-set membership and stable ordinals on enums.
   - `sealed` / `permits` / `final` / `non-sealed` shape on sealed hierarchies.
   - Defensive-copy and masked-`toString` invariants on byte-carrying records.
   - Factory-method required/forbidden-attribute contracts where specified.
3. **Run the tests.** Two outcomes are acceptable:
   - **Any test fails** — the planner-landed artifact deviates from the
     work-plan contract (validation message wording, accessor signature, enum
     member naming, sealed permits set). Treat this as a contract deviation:
     fix the planner-landed artifact (this is the "implement" phase) and
     re-run.
   - **All tests pass immediately** — the planner-landed artifact is already
     correct. For pure-type work, this is the expected and correct outcome.
     Do **not** retrofit a broken stub body to manufacture a failure.
4. **Document the deviation in the unit's `cycle-log.md`.** Append a
   `test-writer — cycle-N-tests-authored` entry that cites
   `.claude/rules/testing.md` step 2, names the construct kinds in scope,
   and states that tests validated planner-landed artifacts. Canonical note
   form is in the companion detail file.
5. **Do not treat this as a free pass.** The recipe applies only when the
   planner-landed artifact cannot be meaningfully stubbed. A mixed WU with
   even one behavioural method in scope must still honour failure-first for
   that method.

## implementation-notes

### recognition-signals

- Work Plan lists `kind: record`, `kind: enum`, or `kind: sealed class /
  interface` for every construct in the WU.
- Contract definitions list null-reject, empty-reject, value-equality,
  closed-set, and permits-set as the entire test surface.
- No construct has `throws IOException`, computation beyond trivial accessors,
  or branching logic beyond validation.

### cycle-log-note-discipline

The note is **required** when the recipe is applied — without it a reviewer
cannot distinguish "skipped for a good reason" from "forgotten". The note must
cite `.claude/rules/testing.md` step 2, name the construct kinds present in
the WU, and state what the tests *do* validate (contract vs empty stub).

### interpreting-first-run-outcomes

A failing-first test on a pure-type WU is evidence the planner got a contract
wrong and the test caught it — the implement stage corrects the artifact. A
passing-first test is also correct — the planner got it right. The only
failure mode is **retrofitting** a broken stub body to force a failure.

## tradeoffs

### strengths

- **Honest** — acknowledges the structural limit instead of performing theatre.
- **Auditable** — cycle-log note makes the deviation visible to reviewers and retros.
- **Narrow** — exempts only pure-type constructs; behaviour still honours failure-first.
- **Cheap** — no throwaway stub bodies to write and revert.

### weaknesses

- **Planner discipline required** — if the planner lands an incorrect artifact and
  the test does not catch it, the failure-first gate would have been a second line
  of defence. Mitigation: tests must cover the full contract surface (validation
  messages, accessor signatures, permits sets, ordinal stability).
- **Classifier discipline required** — a mixed WU with one behaviour construct must
  still honour failure-first for that construct. Classify per-construct, not per-WU.
- **Note can become ritual** — cite specific construct kinds in every note so the
  degeneracy claim remains auditable.

### compared-to-alternatives

- **Retrofit a broken stub body** — produces a failing test for the wrong
  reason, breaks every consumer of the construct, and adds a revert step. Do not.
- **Skip tests until the behavioural stage** — loses contract-validation coverage;
  a typo'd validation message would ship unchallenged. Do not.
- **Demote TDD protocol to best-effort for foundation WUs** — over-broad; the
  protocol applies construct-by-construct.

## practical-usage

### when-to-use

A TDD work unit where the entire contract surface is validation + type
relationships, and the planner lands full compact-constructor bodies / enum
constants / sealed `permits` clauses — i.e. the "stub" is indistinguishable from
the final implementation because nothing can be stripped without breaking
compilation.

### when-not-to-use

- A WU containing any construct with a non-trivial method body (I/O, crypto,
  merge, scan, serialize) — those must honour failure-first per the TDD protocol.
- A WU where the planner landed only skeletal signatures (no compact constructor
  body, no `permits` clause populated) — stub form exists; failure-first applies.
- A WU with ambiguous construct kinds (e.g. a record with a computed accessor
  that does more than trivial field access) — stub the computed accessor and
  honour failure-first for that method.

## reference-implementations, code-skeleton, and detection

Extracted to companion file to keep this subject file under the 200-line cap.

@./tdd-failure-first-degeneracy-for-pure-record-enum-work-detail.md

## sources

1. [jlsm TDD protocol (`.claude/rules/testing.md`)](.claude/rules/testing.md) — step 2 mandates failure-confirmation before implementation; this article documents the structural exception for pure-type constructs.
2. [WU-1 cycle log](.feature/implement-encryption-lifecycle--wd-01/units/WU-1/cycle-log.md) — first documented application of the recipe; contains the canonical form of the cycle-log note.
3. [WD-01 parent cycle log](.feature/implement-encryption-lifecycle--wd-01/cycle-log.md) — WU-1 summary records "TDD failure-first step is degenerate for pure-type work — records + enums cannot be meaningfully empty-stubbed."

---
*Researched: 2026-04-23 | Next review: 2027-04-23*
