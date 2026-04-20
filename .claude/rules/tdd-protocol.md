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
- `bash .claude/scripts/adr-validate.sh` — warns if contradictory accepted ADRs exist
All are advisory — never block on their output.

## Never suggest ending a session (CRITICAL)
During pipeline execution, NEVER suggest stopping, taking a break, or ending
the session. The pipeline has explicit handoff points (AskUserQuestion with
"Proceed" / "Stop" options) — those are the ONLY places where stopping is
offered. Between those handoff points, execute the current stage to completion.
Do not insert unsolicited suggestions like "this has been a productive session"
or "you might want to stop here." The user decides when to stop.

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
                       .work/<group>/WD-*.md (status→COMPLETE, work-group features only)
                       .spec/registry/_obligations.json (resolve obligations, if referenced in brief)
                       .spec/domains/*/*.md (remove resolved IDs from open_obligations)
  PR command         → .feature/<slug>/pr-draft.md
  Retro command      → .feature/<slug>/cycle-log.md (retro-complete entry)
                       (invokes /architect, /decisions revisit, /research as sub-agents)

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

## Test execution timeout (CRITICAL)
  When running test commands via Bash, ALWAYS set a timeout:
  - Use the Bash tool's `timeout` parameter: 300000 (5 minutes)
  - If the command times out, do NOT simply re-run the same command
  - Instead: check what happened — look for hung processes (`ps aux | grep`
    for the test runner), kill them if found, then investigate the root cause
    (deadlock, infinite loop, missing resource, blocking I/O)
  - After investigating, either fix the issue and re-run, or report the hang
    to the user with the process state and any partial output
  - Never wait silently for more than 5 minutes on a test command — a test
    suite that hasn't produced output in 5 minutes is almost certainly hung

## Adversarial TDD (aTDD) pipeline
  /atdd-round "<slug>" — adversarial cycle: Analyst → Breaker → Implementer
  /atdd-audit "<slug|path>" — audit existing code, bootstrap known_issues, enter cycle
  /atdd-refactor "<slug>" — constrained refactor + targeted regression verification

## aTDD write authority
  All aTDD agents     → .feature/<slug>/atdd-status.md (round tracking)
  Spec Analyst        → .feature/<slug>/breaker-prompt.md
                        .feature/<slug>/known_issues.md (RESOLVED/TENDENCY/WATCH)
                        .feature/<slug>/cycle-log.md (analyst entries)
  Breaker             → adversarial test files + .feature/<slug>/cycle-log.md (breaker entries)
  Code Writer         → implementation files (same authority as standard pipeline)
                        .feature/<slug>/cycle-log.md (implementer entries)
  Constrained Refactorer → implementation files + .feature/<slug>/refactor-diff.md
                           .feature/<slug>/cycle-log.md (refactor entries)
  Audit (/atdd-audit) → .feature/<slug>/spec.md, .feature/<slug>/gaps.md
                         .feature/<slug>/known_issues.md (bootstrap)

## aTDD shared read
  Spec Analyst reads: brief.md (or spec.md), work-plan.md, test files,
  implementation files, known_issues.md
  Breaker reads: breaker-prompt.md (or refactor-diff.md), test files,
  implementation files
  Constrained Refactorer reads: implementation files, test files,
  known_issues.md, project-config.md, CONTRIBUTING.md

## aTDD escalation paths
  Breaker never modifies implementation — Implementer fixes failing tests.
  Constrained Refactorer never modifies tests — Targeted Breaker writes
  regression tests if refactoring weakened a resolved pattern.
  Spec Analyst never writes tests or implementation — only analysis and prompts.

## .feature/ gitignore policy
  .feature/project-config.md  → committed
  .feature/CLAUDE.md           → committed
  .feature/_archive/           → gitignored
  .feature/<slug>/             → gitignored
