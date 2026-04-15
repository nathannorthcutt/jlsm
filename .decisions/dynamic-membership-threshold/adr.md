---
problem: "dynamic-membership-threshold"
date: "2026-04-13"
version: 1
status: "closed"
---

# Dynamic Membership Threshold — Closed (Won't Pursue)

## Problem
Should the 75% consensus quorum threshold adjust dynamically at runtime as expected
cluster size changes?

## Decision
**Will not pursue.** This topic is explicitly ruled out and should not be raised again.

## Reason
F04 R2 already makes `consensus quorum percent` configurable via the engine builder.
Dynamic runtime adjustment of the quorum threshold is actively dangerous — changing the
threshold while the cluster is running creates windows where split-brain protection is
weakened or strengthened unexpectedly. A cluster operator who sets 75% at deployment
time has made a deliberate safety choice; auto-adjusting it undermines that choice.

Static configuration is safer and sufficient. If different cluster sizes need different
thresholds, the operator configures it at deployment time.

## Context
- Parent ADR: `.decisions/cluster-membership-protocol/adr.md` — 75% leaderless consensus
- F04 R2: consensus quorum percent is configurable
- F04 R4: validates range (0, 100]
- F04 R16: quorum = live members vs configured percent of total known members

## Conditions for Reopening
If production experience shows that a fixed threshold causes operational problems in
autoscaling clusters where the expected size changes frequently (e.g., 3→100→3 nodes
in a day), reconsider with an explicit operator opt-in mechanism rather than auto-tuning.
