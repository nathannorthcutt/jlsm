---
problem: "similarity-function-placement"
date: "2026-03-30"
version: 1
status: "closed"
---

# Similarity Function Placement — Closed (Won't Pursue)

## Problem
SimilarityFunction remains on IndexDefinition as an index-level concern — may
need revisiting if it proves awkward.

## Decision
**Will not pursue.** Explicitly ruled out — should not be raised again.

## Reason
Current placement is architecturally correct. SimilarityFunction is an
index-level concern (different indexes on the same vector field could use
different similarity functions), not a field-type concern. The parent ADR
(index-definition-api-simplification) already made this call explicitly.

After 6+ weeks of implementation, multiple adversarial audits, and 22 files
using this API, there is no evidence the placement is awkward. The validation
in IndexDefinition's compact constructor is clean (required for VECTOR, null
for other types).

## Context
Parent ADR: `index-definition-api-simplification` (confirmed 2026-03-17)
Deferred: 2026-03-30 as a "revisit if awkward" placeholder
Closed: 2026-04-12 — no awkwardness materialized

## Conditions for Reopening
None — treat as permanently closed.
