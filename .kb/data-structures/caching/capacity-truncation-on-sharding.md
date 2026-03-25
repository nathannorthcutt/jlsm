---
title: "Capacity truncation on sharding"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Capacity truncation on sharding

## What happens
When a total capacity is divided across N shards/stripes using integer division (`capacity / N`), the remainder is silently lost. If `capacity()` returns the originally configured value rather than the effective value (`perShard * N`), the reported capacity exceeds the actual maximum entries the cache can hold. Callers relying on `capacity()` for pre-warming, monitoring, or load balancing decisions over-estimate available space.

## Why implementations default to this
Integer division truncation is expected behavior, but developers store the original configured value in the `capacity` field for simplicity. The mismatch between advertised and effective capacity is only observable when `capacity % shardCount != 0`, which may not appear in tests using round numbers (e.g., capacity=100, stripes=4).

## Test guidance
- For any sharded/partitioned structure: create with `capacity % shardCount != 0` and verify `capacity()` returns the effective value, not the configured one
- Verify that inserting `effective + 1` entries triggers eviction even though `capacity()` would suggest room remains
- Use non-round capacity values in tests (e.g., 7, 11, 13) to expose truncation

## Found in
- striped-block-cache (audit round 1, 2026-03-25): `StripedBlockCache.capacity()` returned configured total but effective capacity was `(capacity / stripeCount) * stripeCount`
