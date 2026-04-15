---
problem: "sequential-insert-hotspot"
date: "2026-04-15"
version: 2
status: "accepted"
---

# Sequential Insert Hotspot Mitigation

## Problem

Range partitioning concentrates monotonic key inserts (timestamps, auto-increment IDs,
UUID v7) on the last partition — the partition whose key range covers the current high
end of the keyspace. Under sustained sequential write load, this partition receives all
writes while all other partitions are idle, causing a write hotspot that degrades
throughput and triggers premature range splits.

## Decision

Adopt a `WriteDistributor` interface with pluggable implementations. The default
`IdentityDistributor` preserves existing behavior (no transformation). The
`PrefixHashDistributor` mitigates hotspots by prepending a fixed-length hash prefix
to each logical key before partition routing, distributing monotonic writes across
all prefix buckets while preserving intra-salt ordering for range scans.

### Key choices

- **Hash function:** MurmurHash3 x64-128 with seed = 0 — platform-independent,
  distribution-quality, deterministic across all JVM versions and architectures.
- **Prefix length:** 1–8 bytes (default 2 bytes = 256 buckets). Configurable.
- **Salt extraction:** Caller-provided `Function<MemorySegment, MemorySegment>` selects
  which portion of the key to hash. Using a tenant ID or source system prefix as the
  salt scatters writes between salt groups while preserving range scan efficiency within
  each salt group.
- **Build-time validation:** The builder validates that configured partition ranges cover
  the full physical key space (all possible prefix values) when a non-identity distributor
  is used, catching misconfiguration at construction rather than at write time.

## Alternatives Rejected

- **Load-based hotspot detection with auto-splitting:** Requires runtime metrics, detection
  thresholds, and coordination with the split/merge pipeline. Adds runtime complexity with
  no benefit over deterministic prefix hashing for known-monotonic workloads.
- **Whole-key hashing:** Destroys range scan efficiency for all `getRange` calls.
  Salt-based extraction preserves range scan efficiency within salt groups.
- **Variable-length prefix:** Complicates partition range configuration and the reverse
  `logicalKey()` operation. Fixed-length prefix is simpler and sufficient.

## Tradeoffs

- When `PrefixHashDistributor` is active, `getRange` scatters to all partitions because
  the hash prefix destroys logical key adjacency. Callers requiring range scan efficiency
  should use the salt-based approach to limit scatter to intra-salt queries.
- Result ordering from `getRange` with prefix hash follows physical key order, not logical
  key order. Callers requiring logical-key ordering must sort results themselves.
- Partition ranges must be configured in physical key space (with prefix bytes). Build-time
  validation catches misconfigurations before they reach production.

## Spec

F43 — Sequential Insert Hotspot Mitigation (36 requirements)

## Resolved

2026-04-15 — WD-11 spec-authoring pass
