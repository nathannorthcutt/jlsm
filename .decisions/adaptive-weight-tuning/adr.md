---
problem: "adaptive-weight-tuning"
date: "2026-04-13"
version: 1
status: "deferred"
---

# Adaptive Weight Tuning — Deferred

## Problem
Should the DRR scheduler dynamically adjust traffic class weights based on observed
queue depths and traffic patterns, rather than using static weights?

## Why Deferred
Scoped out during `transport-traffic-priority` decision. Static weights are sufficient
for v1. Adaptive tuning adds complexity without proven need.

## Resume When
When `transport-traffic-priority` implementation is stable and static weights prove
insufficient under real workload patterns (e.g., bursty replication causing INTERACTIVE
latency spikes that static weights cannot address).

## What Is Known So Far
Identified during architecture evaluation of `transport-traffic-priority`. The KB article
[`.kb/distributed-systems/networking/transport-traffic-priority.md`](../../.kb/distributed-systems/networking/transport-traffic-priority.md)
notes adaptive priority scheduling research (arXiv 2504.12074, STREAMLINE framework) that
adjusts weights based on backpressure signals at configurable intervals (e.g., every 100ms).
See `.decisions/transport-traffic-priority/adr.md` for the architectural context.

## Next Step
Run `/architect "adaptive-weight-tuning"` when ready to evaluate.
