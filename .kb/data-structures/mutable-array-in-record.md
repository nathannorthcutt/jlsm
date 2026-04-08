---
title: "Mutable array in Java record"
type: adversarial-finding
domain: "data-integrity"
severity: "tendency"
applies_to:
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
# Note: this finding also applies to MemorySegment fields — see below
---

# Mutable array in Java record

## What happens

### Constructor mutation (round 1)
Java records store the caller's array reference without cloning in the compact
constructor, so the caller can mutate the record's state by modifying the original
array after construction.

### Accessor mutation (round 2)
Java records generate accessors that return the field reference directly. For array
fields (byte[], float[], int[]), the caller receives a reference to the record's
internal array and can mutate it, violating the record's immutability contract.
Even if the constructor defensively copies, a raw accessor still exposes the internal
copy. Both the constructor clone AND the accessor defensive copy are needed.

## Why implementations default to this
Records are designed for immutable value types, and the language doesn't auto-clone
arrays. Developers assume record fields are immutable by default (true for
primitives and immutable objects, false for arrays). The bug is invisible in tests
that don't mutate inputs after construction.

## Test guidance
- For any record holding an array: test that mutating the original array after
  construction does not affect the record's state
- Test that mutating the array returned by the accessor does not affect the record
- Both the constructor clone AND the accessor defensive copy are needed
- For MemorySegment fields: `MemorySegment.ofArray(byte[])` wraps the backing array
  without copying — same mutation risk as direct array fields. Copy in compact
  constructor via `MemorySegment.ofArray(src.toArray(ValueLayout.JAVA_BYTE))`

## Found in
- table-indices-and-queries (audit round 1, 2026-03-25): Predicate.VectorNearest stored float[] queryVector without cloning
- table-partitioning (audit round 1, 2026-03-25): PartitionDescriptor stored MemorySegment lowKey/highKey without copying — backing array mutation corrupted range routing
- table-partitioning (audit round 2, 2026-03-25): PartitionConfig.partitions() accessor returned mutable array reference — caller could replace PartitionDescriptor entries, corrupting routing table; fixed with defensive copy in accessor
