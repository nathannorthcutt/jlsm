---
title: "MemorySegment identity equality in records"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/PartitionDescriptor.java"
research_status: active
last_researched: "2026-03-25"
---

# MemorySegment identity equality in records

## What happens
Java records auto-generate equals() and hashCode() using all component fields. MemorySegment.equals() uses address+size identity comparison, not byte content comparison. Two records constructed with identical byte content in their MemorySegment fields are NOT equal, breaking the record value-type contract. This affects any code that puts descriptors in Sets, uses them as Map keys, or asserts equality between independently-constructed instances.

## Why implementations default to this
Java records are designed for transparent data carriers where component types have natural value semantics. MemorySegment is an address-based handle (like a pointer), not a value type. The language provides no warning that record equality delegates to a non-value-semantic type. Developers expect "same bytes = same descriptor" but get "same address = same descriptor".

## Test guidance
- Construct two records with identical parameters from independent MemorySegment sources
- Assert they are equal — will fail without custom equals/hashCode
- Assert hashCode consistency — two equal records must have equal hash codes
- Test negative case: records with different content must NOT be equal
- If using records as Map keys or in Sets, verify containment works correctly

## Found in
- table-partitioning (round 2, 2026-03-25): PartitionDescriptor.equals/hashCode used MemorySegment identity — two descriptors with identical key ranges were not equal; fixed with custom content-based equals/hashCode
