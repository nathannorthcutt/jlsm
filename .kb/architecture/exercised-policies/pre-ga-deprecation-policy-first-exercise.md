---
title: "Pre-GA Format-Version Deprecation Policy — First Exercise"
type: exercised-policy-record
tags: [deprecation-policy, pre-ga, format-versioning, sstable, encryption, process-telemetry]
exercised: 2026-04-24
exercise_context: implement-encryption-lifecycle--wd-02
adr_refs:
  - "pre-ga-format-deprecation-policy"
related:
  - ".decisions/pre-ga-format-deprecation-policy/adr.md"
  - ".kb/systems/database-engines/format-version-deprecation-strategies.md"
sources:
  - title: "ADR — Pre-GA Format-Version Deprecation Policy"
    location: ".decisions/pre-ga-format-deprecation-policy/adr.md"
    accessed: "2026-04-25"
  - title: "commit 1e850f4 — plan(decisions): pre-GA format-version deprecation policy + supporting KB"
    location: "git: 1e850f4"
    accessed: "2026-04-25"
  - title: "commit db800ca — chore(sstable): collapse v1-v4 formats per pre-GA deprecation policy"
    location: "git: db800ca"
    accessed: "2026-04-25"
  - title: "User feedback — exercise processes pre-GA"
    location: "~/.claude/projects/.../memory/feedback_exercise_processes_pre_ga.md"
    accessed: "2026-04-25"
research_status: stable
last_researched: "2026-04-25"
---

# Pre-GA Format-Version Deprecation Policy — First Exercise

## What this records

The empirical outcome of the first time the
[`pre-ga-format-deprecation-policy`](../../../.decisions/pre-ga-format-deprecation-policy/adr.md)
ADR was exercised in practice. Per the project feedback note
[`feedback_exercise_processes_pre_ga`][feedback], theoretical policies are
worse than exercised ones — this article captures what the first exercise
demonstrated and what it left untested.

[feedback]: ../../../.claude/.. "see project memory: feedback_exercise_processes_pre_ga"

## Context

The policy was authored mid-planning for `implement-encryption-lifecycle--wd-02`
(ciphertext format + SSTable footer scope signalling). Before WD-02's work
plan finalised, work paused for a detour:

1. Author the deprecation-policy ADR.
2. Collapse SSTable formats v1–v4 to a v5-only baseline per the pre-GA
   zero-window rule of the new policy.
3. Resume WD-02 against the collapsed baseline.

Two commits anchor the exercise:

| Commit | Subject |
|--------|---------|
| `1e850f4` | `plan(decisions): pre-GA format-version deprecation policy + supporting KB` |
| `db800ca` | `chore(sstable): collapse v1-v4 formats per pre-GA deprecation policy` |

WD-02 itself then landed against v5-only as commit `4df21d6`
(`feat(encryption): ciphertext format + SSTable footer scope signalling`).

## What the policy successfully bound

**Open question erased before WD-02 design closed.** Pre-detour, WD-02's
work plan carried an open design question: *"do we evolve the v3 format
optionally, or bump to v4 / v5?"* Post-collapse, that question disappeared
because v5 became the only baseline a writer could emit. The encryption
work bumped v5 → v6 cleanly, with no parallel "amend v3" path to reconcile.

**Reduced WD-02 design surface.** With v1–v4 reader/writer paths gone,
WD-02 did not need to specify ciphertext-format compatibility against
multiple ancestor formats. The spec amendment moved cleanly from a
v4 spec to a v5 spec with the v6 layer added on top, rather than a
v3 in-place amendment alongside a v5 spec authoring.

**Cleaner state machine entry.** The policy's *active → deprecated →
read-only-ok → retired* state machine could be exercised at the simplest
possible transition (active → retired with zero-window pre-GA collapse).
No deprecation warnings, no inline rewrite-on-read, no operator-triggered
migration command had to fire — these mechanisms existed in the ADR but
were not invoked.

## Cost

**Roughly one day of detour during planning.** The detour authored the ADR,
the supporting KB
[`format-version-deprecation-strategies`](../../systems/database-engines/format-version-deprecation-strategies.md),
performed the v1–v4 collapse, and re-baselined WD-02. Without the policy
authoring, the v1–v4 collapse would still have been the right move; the
policy added the framing and durability (a future "do we collapse v5–v7?"
question now has an authoritative answer).

## What the exercise did NOT cover

The first exercise covered exactly one transition flavour — **pre-GA
format collapse with zero on-disk users**. The following mechanisms in
the policy remain unexercised:

- **Compaction-driven inline rewrite.** A v_{N-k} SSTable encountered by
  the writer at v_N must be promoted on the natural rewrite vector. No
  test case has run this path end-to-end against real data.
- **Bounded low-priority background sweep.** Configuration
  (`EngineConfig.formatSweep`) exists in the ADR but the sweep loop has
  not been built or stressed against a real cold-L6 file accumulation.
- **Per-collection format-version watermark.** The catalog field exists
  in spec form (R9a-mono in `sstable.footer-encryption-scope`); the
  cross-check between self-magic and catalog watermark has not been
  exercised against a downgrade attack.
- **`Engine.upgradeFormat(scope)` operator command.** The API surface is
  declared in the ADR but no implementation or operator-flow test has
  exercised it.
- **Pre-deprecation read-time warnings.** The one-shot per-process
  per-(artefact, version) warning format is specified but not exercised.
- **Read-only past-window hard error.** Both the diagnostic message
  format and the read-only detection have not been exercised.
- **Cross-artefact application.** Only SSTable was exercised. WAL,
  catalog `table.meta`, ciphertext envelope, and document serializer
  all carry their own format-version fields under the same policy; none
  of them have been driven through a transition yet.
- **Key rotation.** A separate process governed by
  [`encryption-key-rotation`](../../../.decisions/encryption-key-rotation/adr.md);
  not exercised by this collapse.
- **KMS provider replacement.** Not exercised.

## Open question raised by the exercise

**Conflict between in-flight feature work and a deprecation collapse.**
The exercise dodged the hardest case: WD-02 had not yet emitted any v5
or v6 work when v1–v4 were collapsed. Real-world tension would arise
if a deprecation collapse landed mid-implementation of a half-finished
v_{N+1} format — the policy currently does not specify whether the
in-flight work should:

- pause until the collapse lands (the WD-02 pattern, by accident),
- absorb the collapse as part of its own changeset (compounds review surface),
- or proceed first and force the collapse to wait (delays the policy and
  risks the in-flight work having reached durable state on a now-stale
  baseline).

The ADR's *Conditions for Revision* section does not currently list this
case. A future amendment may need to address it; for now, the convention
established by this exercise is **pause in-flight work, land the collapse,
re-baseline**.

## Pattern statement

> The pre-GA zero-window collapse mechanism works for synchronous,
> single-artefact-class format retirements where no on-disk users exist.
> The policy successfully eliminated a downstream design question
> (`evolve v3 vs. bump`) and bound subsequent work to a clean baseline.
> The cost is bounded (~1 day of detour during planning) and the benefit
> compounds across all later format work that would otherwise have to
> reason about the collapsed versions.
>
> The mechanism remains unproven for compaction-driven rewrite, bounded
> sweep, watermark cross-check, operator-triggered upgrade, read-only
> past-window error, and any cross-artefact transition. Each of these
> needs its own first exercise before the post-GA regime is empirically
> trustworthy.

## Cross-references

**ADRs:**
- [`pre-ga-format-deprecation-policy`](../../../.decisions/pre-ga-format-deprecation-policy/adr.md)
  — the policy whose first exercise this records

**KB entries:**
- [`format-version-deprecation-strategies`](../../systems/database-engines/format-version-deprecation-strategies.md)
  — the survey of production strategies that fed the ADR

**Project memory:**
- `feedback_exercise_processes_pre_ga` — the principle that motivated
  exercising the policy on v1–v4 immediately rather than holding it
  theoretical

## Next exercises to schedule

To retire residual untested mechanisms, the following events should be
treated as exercise opportunities (not just feature work):

1. **Next SSTable format bump after v6** — exercises compaction-driven
   inline rewrite if any v5 files survive in test fixtures or examples.
2. **First WAL format change** — exercises the policy on a non-SSTable
   artefact and the rotation-vector rewrite path.
3. **First catalog `table.meta` field addition** — exercises
   metadata-write rewrite and per-collection watermark update.
4. **First ciphertext envelope evolution** — exercises the policy on the
   encryption layer and the cross-artefact registry uniformity claim.
5. **First post-GA deprecation** — exercises the deprecation window,
   read-time warnings, inline rewrite-on-read, and read-only hard-error
   paths. None of these can be exercised pre-GA because the deprecation
   window is structurally zero.
