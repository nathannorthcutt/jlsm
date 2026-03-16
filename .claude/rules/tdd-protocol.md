# TDD Workflow Protocol

Feature work follows a strict agent pipeline. Each agent reads and writes only
its designated files. No agent may skip a stage or act outside its write authority.

## Entry points
  /feature-quick "<description>"   — small changes: test → implement → refactor, minimal setup
  /feature "<description>" — full pipeline for new functionality with design decisions

## Full pipeline order (sequential / cost mode)
  /feature → /feature-domains → /feature-plan → /feature-test →
  /feature-implement → /feature-refactor → /feature-pr → /feature-retro (optional) → /feature-complete

## Parallel pipeline (balanced/speed mode)
  /feature → /feature-domains → /feature-plan → /feature-coordinate
  (coordinator launches batches of: /feature-test → /feature-implement → /feature-refactor)
  → /feature-pr → /feature-retro (optional) → /feature-complete

## Utility commands (any time)
  /feature-resume "<slug>"  — where am I, what do I run next
  /feature-resume "<slug>" --status  — human-readable session briefing and next-session agenda
  /feature-resume "<slug>" --share  — condensed standup/team format

## Pre-flight checks
Before starting any pipeline command, run these scripts and display any output:
- `bash .claude/scripts/version-check.sh` — warns if branch is behind main
- `bash .claude/scripts/ensure-merge-driver.sh` — registers index merge driver if missing
- `bash .claude/scripts/kb-freshness-check.sh` — warns if KB/decisions are behind main
All are advisory — never block on their output.

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
  Coordinator        → .feature/<slug>/status.md (batch tracking, unit status)
                       .feature/<slug>/cycle-log.md (merged log)
  All TDD agents     → .feature/<slug>/units/WU-N/status.md (parallel mode)
  Test Writer        → test files + .feature/<slug>/cycle-log.md (test entries)
                       .feature/<slug>/units/WU-N/cycle-log.md (parallel: test entries)
  Code Writer        → implementation files + .feature/<slug>/cycle-log.md (code entries)
                       .feature/<slug>/units/WU-N/cycle-log.md (parallel: code entries)
  Refactor Agent     → implementation files + .feature/<slug>/cycle-log.md (refactor entries)
                       .feature/<slug>/units/WU-N/cycle-log.md (parallel: refactor entries)
  PR command         → .feature/<slug>/pr-draft.md
  Retro command      → .feature/<slug>/cycle-log.md (retro-complete entry)
                       (invokes /architect, /decisions review, /research as sub-agents)

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
  Tail-read rule: read only the last 30 lines unless the command explicitly
  needs full history (PR draft, feature-complete archival). Most operations
  only need the most recent entry of a specific type — scan from the tail.

## Tests are the specification
  No agent except the Test Writer may modify test files.
  Code Writer escalates contract conflicts to Test Writer.
  Test Writer escalates contract changes to Work Planner.


## .feature/ gitignore policy
  .feature/project-config.md  → committed
  .feature/CLAUDE.md           → committed
  .feature/_archive/           → gitignored
  .feature/<slug>/             → gitignored
