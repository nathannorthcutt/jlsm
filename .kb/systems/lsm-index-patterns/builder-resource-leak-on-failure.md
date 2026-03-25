---
title: "Builder resource leak on partial failure"
type: adversarial-finding
domain: "memory-safety"
severity: "confirmed"
applies_to:
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Builder resource leak on partial failure

## What happens
Multi-resource builders that create N resources sequentially (e.g., partition clients, index handles) leak resources 0..N-1 when the Nth creation fails. The builder's loop does not wrap creation in try/catch with cleanup, so already-created resources are never closed.

## Why implementations default to this
Builder patterns focus on the happy path — construct all resources, return the composite. Error handling is added for the returned object's `close()` method but not for the build process itself. The leak is invisible in tests that don't inject failures mid-construction.

## Test guidance
- For any builder that creates multiple closeable resources: inject a failure on the Nth resource and verify that resources 0..N-1 are closed
- Test with N=2 (simple case) and N=3+ (verify all prior resources, not just the first)
- Check that the builder also detects duplicate identifiers to prevent silent overwrite of resources in maps

## Found in
- table-partitioning (audit round 1, 2026-03-25): PartitionedTable.Builder.build() leaked PartitionClient instances when factory threw on Nth partition
