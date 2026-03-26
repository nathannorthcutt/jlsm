---
title: "Hardcoded invalidation reason in handle validity check"
type: adversarial-finding
domain: "data-integrity"
severity: confirmed
applies_to:
  - "modules/jlsm-engine/src/main/**"
research_status: active
last_researched: "2026-03-26"
---

# Hardcoded invalidation reason in handle validity check

## What happens

When a handle validity check (`checkValid()`) detects an invalidated registration, it
constructs a diagnostic exception with a hardcoded reason (e.g., `EVICTION`) instead
of propagating the actual invalidation cause (TABLE_DROPPED, ENGINE_SHUTDOWN, etc.).
Callers that switch on the reason to decide recovery strategy get wrong information —
for example, retrying on a dropped table instead of failing fast.

## Why implementations default to this

The registration token typically stores only a boolean `invalidated` flag for simplicity.
Adding a reason field feels like over-engineering during initial implementation because
the check only needs "yes/no". The cost surfaces later when multiple invalidation paths
exist and callers need to distinguish them.

## Test guidance

- After `invalidateTable(name, TABLE_DROPPED)`, verify exception carries `TABLE_DROPPED`
- After `invalidateAll(ENGINE_SHUTDOWN)`, verify exception carries `ENGINE_SHUTDOWN`
- After eviction, verify exception carries `EVICTION`
- Check that all three code paths produce distinct, correct reasons
- Test that the registration stores the reason atomically with the invalidated flag
  (volatile write ordering: reason before flag)

## Found in

- in-process-database-engine (audit round 1, 2026-03-26): HandleRegistration stored only
  boolean flag; LocalTable.checkValid() hardcoded Reason.EVICTION for all invalidation
  causes. Fixed by adding `invalidationReason` field to HandleRegistration and propagating
  reason through all invalidation paths.
