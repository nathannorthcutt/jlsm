# TDD Workflow Protocol

Feature work follows a strict agent pipeline. Each agent reads and writes only
its designated files. No agent may skip a stage or act outside its write authority.

## Entry points
  /quick "<description>"   — small changes: test → implement → refactor, minimal setup
  /feature "<description>" — full pipeline for new functionality with design decisions

## Full pipeline order
  /feature → /feature-domains → /feature-plan → /feature-test →
  /feature-implement → /feature-refactor → /feature-pr → /feature-complete

## Utility commands (any time)
  /feature-resume "<slug>"  — where am I, what do I run next
  /feature-resume "<slug>" --status  — human-readable session briefing and next-session agenda
  /feature-resume "<slug>" --share  — condensed standup/team format

## Idempotency rule (CRITICAL)
Every command MUST read status.md before doing any work.
If the stage it would perform is already complete, report and stop.
No command re-does completed work without explicit user confirmation.

## Write authority
  All agents         → .feature/<slug>/status.md (stage marker updates only)
  Quick command      → .feature/<q-slug>/ (all files for that task)
  Scoping Agent      → .feature/<slug>/brief.md
  Domain Scout       → .feature/<slug>/domains.md
  Work Planner       → .feature/<slug>/work-plan.md + stub files in src/
  Test Writer        → test files + .feature/<slug>/cycle-log.md (test entries)
  Code Writer        → implementation files + .feature/<slug>/cycle-log.md (code entries)
  Refactor Agent     → implementation files + .feature/<slug>/cycle-log.md (refactor entries)
  PR command         → .feature/<slug>/pr-draft.md

## Shared read
  All TDD agents read: .feature/project-config.md, .feature/<slug>/status.md,
  .feature/<slug>/brief.md, .feature/<slug>/domains.md (where relevant),
  .feature/<slug>/work-plan.md
  Refactor Agent additionally reads: CONTRIBUTING.md or docs/coding-standards.md

## status.md is the restart checkpoint
  Updated in-place throughout. Always reflects true current state.
  cycle-log.md is the append-only narrative history.
  On restart: read status.md first.

## cycle-log.md is append-only
  No agent may edit or delete existing entries.

## Tests are the specification
  No agent except the Test Writer may modify test files.
  Code Writer escalates contract conflicts to Test Writer.
  Test Writer escalates contract changes to Work Planner.

## .feature/ gitignore policy
  .feature/project-config.md  → committed
  .feature/CLAUDE.md           → committed
  .feature/_archive/           → gitignored
  .feature/<slug>/             → gitignored
