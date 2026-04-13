---
problem: "quarantine-resolution-policy"
date: "2026-04-13"
version: 1
status: "deferred"
depends_on: ["string-to-bounded-string-migration"]
---

# Quarantine Resolution Policy — Deferred

## Problem
What to do with documents that fail schema migration validation — truncate, delete, notify, or manual review. Application-level policy for the quarantine output from compaction-time migration.

## Why Deferred
Scoped out during `string-to-bounded-string-migration` decision. jlsm defines the quarantine hook; the resolution policy is application-specific.

## Resume When
When schema migration is implemented and users need guidance on quarantine handling.

## What Is Known So Far
The migration ADR defines a quarantine callback — non-compliant documents are routed to caller-provided handling. The resolution options are: truncate to fit, delete with audit log, write to error table, or surface to application for manual review.

## Next Step
Run `/architect "quarantine-resolution-policy"` when ready to evaluate.
