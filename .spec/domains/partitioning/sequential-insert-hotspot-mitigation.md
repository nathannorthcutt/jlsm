---
{
  "id": "partitioning.sequential-insert-hotspot-mitigation",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "partitioning"
  ],
  "requires": [
    "F30",
    "F11"
  ],
  "invalidates": [
    "F30.R1",
    "F30.R2",
    "F30.R3",
    "F30.R4",
    "F30.R5",
    "F30.R6",
    "F30.R7",
    "F30.R8",
    "F30.R9",
    "F30.R10",
    "F30.R11",
    "F30.R12",
    "F30.R13",
    "F30.R14",
    "F30.R15",
    "F30.R16",
    "F30.R17",
    "F30.R18",
    "F30.R19"
  ],
  "amends": [
    "F30"
  ],
  "amended_by": [],
  "displaced_by": [],
  "revives": [],
  "revived_by": [],
  "displacement_reason": "",
  "decision_refs": [
    "sequential-insert-hotspot",
    "table-partitioning"
  ],
  "kb_refs": [
    "distributed-systems/data-partitioning/partitioning-strategies"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F43"
  ]
}
---
# partitioning.sequential-insert-hotspot-mitigation — Sequential Insert Hotspot Mitigation

## Requirements

### WriteDistributor contract

R1. The system must define a `WriteDistributor` interface with a single method
`distribute(MemorySegment logicalKey)` that returns a physical key (`MemorySegment`)
suitable for partition routing.

R2. The `WriteDistributor.distribute` method must be deterministic and
platform-independent: the same logical key must always produce the same physical key
on all JVM implementations and all hardware architectures.

R3. The `WriteDistributor.distribute` method must reject a null `logicalKey` with a
`NullPointerException`.

R4. The `WriteDistributor.distribute` method must not return null. The
`PartitionedTable` coordinator must validate the return value immediately after each
`distribute` invocation; if the return value is null, the coordinator must throw an
`IllegalStateException` identifying the distributor as the source of the null.

R5. The `WriteDistributor` interface must be declared thread-safe: all implementations
must permit concurrent invocations from multiple threads without external
synchronization. Immutable implementations satisfy this requirement by immutability;
mutable implementations must declare their synchronization mechanism in their
Javadoc.

R6. The `WriteDistributor` must provide a `minPhysicalKeyBound()` method that returns
the smallest `MemorySegment` that could be produced by `distribute` for any possible
logical key, and a `maxPhysicalKeyBound()` method that returns a `MemorySegment`
that is strictly greater than any physical key the distributor can produce. These
methods are used by the builder for partition range compatibility validation.

### IdentityDistributor

R7. The system must provide an `IdentityDistributor` implementation that returns the
logical key unchanged. The returned value must have identical byte content to the
input, though it need not be the same `MemorySegment` instance.

R8. The `IdentityDistributor` must be the default `WriteDistributor` when no
distributor is explicitly configured on `PartitionedTable.Builder`.

R9. The `IdentityDistributor.minPhysicalKeyBound()` must return the zero-length
`MemorySegment` (representing the logical minimum). The
`IdentityDistributor.maxPhysicalKeyBound()` must return `null` to indicate
"unbounded above" — the builder must interpret `null` as no upper-bound constraint.

### PrefixHashDistributor

R10. The system must provide a `PrefixHashDistributor` implementation that prepends
a fixed-length hash prefix to the logical key. The resulting physical key consists
of exactly `prefixLength` prefix bytes followed by the logical key bytes.

R11. The `PrefixHashDistributor` must accept a `saltExtractor` function
(`Function<MemorySegment, MemorySegment>`) at construction that selects the bytes to
hash for computing the prefix. The constructor must reject a null `saltExtractor` with
a `NullPointerException`.

R12. If the `saltExtractor` function returns null for a given logical key,
`PrefixHashDistributor.distribute` must throw a `NullPointerException`.

R13. The `saltExtractor` function may return a zero-length `MemorySegment`.
`PrefixHashDistributor` must hash the zero-length input and produce a valid prefix
deterministically.

R14. The `PrefixHashDistributor` hash prefix must be a configurable fixed byte length.
The default prefix length must be 2 bytes. The constructor must reject values less than
1 or greater than 8 with an `IllegalArgumentException`.

R15. The `PrefixHashDistributor` must compute prefix bytes using MurmurHash3 x64-128
with seed = 0. The prefix consists of the first N bytes of the concatenation [h1 in
big-endian 8-byte representation || h2 in big-endian 8-byte representation], where N
is the configured prefix length and h1, h2 are the two 64-bit halves of the
MurmurHash3 x64-128 output.

R16. The `PrefixHashDistributor` must provide a reverse method
`logicalKey(MemorySegment physicalKey)` that strips the hash prefix and returns the
logical key bytes (the physical key bytes after the prefix).

R17. The `PrefixHashDistributor.logicalKey` method must reject a null `physicalKey`
with a `NullPointerException`.

R18. The `PrefixHashDistributor.logicalKey` method must reject a `physicalKey` whose
byte length is strictly less than `prefixLength + 1` with an `IllegalArgumentException`.
A physical key of exactly `prefixLength` bytes is not a valid physical key because it
would produce a zero-length logical key, which is not a valid document key.

R19. The `PrefixHashDistributor.minPhysicalKeyBound()` must return a `MemorySegment`
of `prefixLength` bytes, all set to `0x00`. The `maxPhysicalKeyBound()` must return a
`MemorySegment` of `prefixLength + 1` bytes where the first `prefixLength` bytes are
`0xFF` and the final byte is `0x00` — this value is strictly greater than any physical
key the distributor can produce.

R20. The `PrefixHashDistributor` must be immutable after construction. All configurable
parameters (prefix length, salt extractor) must be set at construction time and must
not be modifiable afterward.

### Read path translation

R21. When a `WriteDistributor` other than `IdentityDistributor` is configured, all
write and read paths — `create`, `get`, `update`, `delete`, `getRange` — must apply
`distribute` to transform the caller's logical key to a physical key before routing.

R22. The `WriteDistributor.distribute` method must be invoked exactly once per external
API call at the `PartitionedTable` coordinator boundary. Internal sub-operations (e.g.,
a delete-then-reinsert for an update) must not invoke `distribute` again on an already-
translated physical key.

R23. The closed check (`checkNotClosed`) must execute before `WriteDistributor.distribute`
is invoked in every public API method. An operation on a closed table must not invoke the
distributor.

R24. The `PartitionedTable` coordinator must not hold any table-level lock during
`WriteDistributor.distribute` invocation. If the coordinator uses internal synchronization
to guard the partition map, that lock must be released before calling `distribute`.

R25. When `PrefixHashDistributor` is active, `PartitionedTable.getRange` must scatter the
range request to all partitions because the hash prefix destroys logical key adjacency
across partition boundaries.

R26. When `PrefixHashDistributor` is active, all result key construction for entries
returned by `get`, `getRange`, and `query` must use the configured distributor's
`logicalKey(MemorySegment physicalKey)` method to strip the prefix. No other decode path
may be used for result key construction when a prefix-adding distributor is configured.

R27. When `PrefixHashDistributor` is active, `PartitionedTable.getRange` results are
returned in physical key order (hash prefix first). This ordering differs from logical
key order. Callers that require logical-key ordering must sort results after receiving them.

### Builder integration

R28. The `WriteDistributor` must be configurable on `PartitionedTable.Builder` via a
`writeDistributor(WriteDistributor)` method. The builder must reject a null distributor
with a `NullPointerException`.

R29. If `PartitionedTable.Builder.build()` is called without setting a `WriteDistributor`,
it must use `IdentityDistributor` automatically — not throw.

R30. During `PartitionedTable.Builder.build()`, when a non-identity `WriteDistributor` is
configured, the builder must invoke `distributor.minPhysicalKeyBound()` and
`distributor.maxPhysicalKeyBound()` and verify that the `PartitionConfig`'s first
partition `lowKey` is byte-lexicographically less than or equal to
`minPhysicalKeyBound()`, and that the last partition `highKey` is byte-lexicographically
greater than or equal to `maxPhysicalKeyBound()` (or `maxPhysicalKeyBound()` is null,
indicating no upper bound). If the validation fails, `build()` must throw
`IllegalArgumentException` with a message stating which bound is violated.

### Accessor and observability

R31. The `PartitionedTable` must expose the configured `WriteDistributor` via a
`writeDistributor()` accessor method. This method must be callable at any time,
including after `close()`, and must return the distributor without throwing.

R32. When `PrefixHashDistributor` is active and `getRange` scatters to all partitions,
the number of result entries equals the total count of matching physical entries across
all partitions. The coordinator must not apply any per-caller limit below the limit
parameter passed to `getRange` until all partition results are collected.

### Interaction with downstream subsystems

R33. Compaction operates in physical key space below the logical key translation layer.
Physical SSTable keys include the hash prefix when `PrefixHashDistributor` is active.
Compaction must not strip or alter prefix bytes.

R34. Any system component that compares partition boundary keys (`PartitionDescriptor.lowKey`,
`PartitionDescriptor.highKey`) against physical keys in SSTable or WAL data must use
byte-content comparison, not `MemorySegment` reference identity.

R35. The `WriteDistributor` must not affect vector query routing or the `PartitionPruner`
(F30 R20–R41). Vector distance is computed in embedding space, independent of key routing.
The distributor applies only to document key routing, and the pruner must operate on
partition descriptors without distributor involvement.

### Concurrency

R36. The `PartitionedTable` must invoke `WriteDistributor.distribute` without holding
the table's internal partition-map lock. Concurrent reads and writes on the table must
not be serialized through a lock that is held while `distribute` executes.

---

## Design Narrative

### Intent

Resolve the deferred `sequential-insert-hotspot` ADR. Range partitioning concentrates
monotonic key inserts (timestamps, auto-increment IDs, UUID v7) on the last partition —
the partition whose key range covers the current high end of the keyspace. Under sustained
sequential write load, this partition receives all writes while all other partitions sit
idle. The result is a write hotspot: one partition exceeds its flush/compaction budget
while others are underutilized, and the first automatic range split (F28, F29) targets a
partition that is already overloaded.

The `WriteDistributor` interface provides a pluggable key-transformation layer that sits
between the caller's logical key and the physical partition routing. The default
`IdentityDistributor` preserves existing behavior. The `PrefixHashDistributor` prepends a
short hash prefix derived from a caller-selected "salt" field within the key, distributing
writes across all prefix buckets while preserving ordering within each salt group.

### PrefixHash over automatic hotspot detection with dynamic splitting

The KB (partitioning-strategies.md) documents two mitigations: prefix hashing and
load-based hotspot detection with auto-splitting. The `PrefixHashDistributor` follows
the prefix-hash approach directly. Auto-splitting requires runtime load metrics, configurable
detection thresholds, and coordination with the split/merge pipeline (F28, F29) — adding
runtime complexity with marginal benefit over deterministic prefix hashing for
known-monotonic workloads. A prefix hash is deterministic, requires no runtime detection,
and distributes writes immediately without waiting for a hotspot threshold to be exceeded.

### Salt-based extraction over whole-key hashing

Hashing the entire logical key would destroy all key ordering, making every `getRange`
call a full scatter across all partitions. By extracting a salt field (e.g., a tenant ID
prefix, a source system ID), keys within the same salt group remain ordered relative to
each other while different salt groups are scattered. A time-series table keyed by
`[tenantId][timestamp]` can use `tenantId` as the salt: writes from different tenants
scatter across prefix buckets, but a range scan for one tenant's time range queries only
one prefix bucket — no scatter needed.

### MurmurHash3 x64-128 with seed = 0

The hash function must be platform-independent (R2) and distribution-quality (R15). JVM
default hash codes (`Arrays.hashCode`, `String.hashCode`) are not platform-independent
across JVM versions. MurmurHash3 x64-128 is widely implemented, produces uniform
output, and is defined in terms of fixed arithmetic operations with no platform-specific
behavior. Seed = 0 is the standard reference value used when no domain-specific seed is
required — it avoids per-deployment seed management while maintaining cross-node
determinism.

### Prefix byte ordering (h1 big-endian, high bytes first)

MurmurHash3 x64-128 produces two 64-bit values h1 and h2. The prefix is constructed by
concatenating h1 (big-endian 8 bytes) and h2 (big-endian 8 bytes) and taking the first N
bytes. Big-endian byte order ensures that the most-significant byte of h1 — which carries
the highest entropy for typical inputs — appears first. This maximizes the distribution
quality of short prefixes (1-2 bytes), where all N prefix values must appear with near-
equal frequency.

### Range query scatter when hash is active (R25)

This is an intentional tradeoff documented explicitly. When `PrefixHashDistributor` is
active, the physical key order no longer matches logical key order, so a logical range
query must scatter to all partitions. Consumers who need both range scan efficiency and
hotspot mitigation should use the salt-based approach to limit scatter to intra-salt range
queries. Consumers who never use range queries can hash the entire key with no penalty.

### Build-time partition range validation (R30)

Silent misconfiguration is more dangerous than a build-time failure. If the caller
configures partition ranges in logical key space and adds a `PrefixHashDistributor`,
physical keys will fall outside the configured ranges and routing will fail at write time
with a confusing error ("key falls outside all partition ranges"). The build-time
validation in R30 catches this at construction, when the context for fixing it is
available, rather than during a production write.

### What was ruled out

- **Global IVF centroid routing:** Would require a centralized centroid index decoupled
  from range partitioning. Incompatible with the co-located index topology. Vector pruning
  is handled separately in F30.
- **Load-based automatic hotspot splitting:** Requires runtime metrics infrastructure and
  coordination with split/merge pipeline. The deterministic prefix hash achieves equivalent
  write distribution without runtime complexity.
- **Whole-key hashing:** Destroys range scan efficiency for all `getRange` calls, even
  when only a subset of keys are monotonic. Salt-based extraction preserves range scan
  efficiency within salt groups.
- **Variable-length prefix:** A fixed prefix length simplifies partition range configuration
  and the build-time validation predicate. Variable-length prefixes would require
  length-prefix encoding in the physical key, complicating the `logicalKey()` reverse
  operation.
