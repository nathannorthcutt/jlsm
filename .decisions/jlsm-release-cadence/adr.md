---
problem: "jlsm-release-cadence"
date: "2026-04-24"
version: 1
status: "deferred"
---

# jlsm Release Cadence — Deferred

## Problem

The `pre-ga-format-deprecation-policy` ADR specifies a deprecation
window of "≥ 1 major release cycle" after write support drops. This
anchors the policy to a release cadence — major / minor / patch — that
jlsm has not yet defined. SemVer convention says breaking changes
belong on majors, but jlsm has no committed cadence for major bumps,
no LTS policy, no release-notes process beyond CHANGELOG.md.

This ADR will define:

- Versioning scheme (SemVer? CalVer? other?)
- Major release cadence (every N months? feature-driven?)
- Minor / patch policy
- LTS policy if any
- The release process (how a release is cut, who reviews, where it ships)
- The relationship between jlsm versioning and the deprecation window
  in `pre-ga-format-deprecation-policy`

## Why Deferred

Scoped out during `pre-ga-format-deprecation-policy` decision. The
deprecation policy can ship pre-GA without this definition because
pre-GA has no users to deprecate against. The cadence becomes
necessary only when the project commits to a first release.

## Resume When

The project decides to cut a first release (alpha, beta, or stable).
At that point this ADR is required because the deprecation policy's
"≥ 1 major release cycle" window needs a concrete time-anchor. Likely
signals: a tag is being prepared on `main`; documentation discusses
"the 1.0 release"; consumers ask "what version do I depend on?"

## What Is Known So Far

Identified during architecture evaluation of
`pre-ga-format-deprecation-policy`. See
[`.decisions/pre-ga-format-deprecation-policy/adr.md`](../pre-ga-format-deprecation-policy/adr.md)
for the architectural context.

Constraints that will likely apply:
- Must align with the deprecation window length
- Must be machine-readable (single source of truth — likely a Gradle
  property or the `version` field in the build files)
- Must integrate with CHANGELOG.md per
  [`.claude/rules/documentation.md`](../../.claude/rules/documentation.md)

Production references for cadence patterns (not yet researched):
- PostgreSQL: yearly major releases, ~5 years backward compat
- CockroachDB: ~2 majors per year, formal LTS designations
- RocksDB: continuous (no formal majors); compatibility table is the
  contract
- SQLite: no formal cadence; format never changes (since 2004)

## Next Step

Run `/architect "jlsm release cadence"` when ready to evaluate.
