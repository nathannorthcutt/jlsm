---
title: "Public record result type missing compact constructor validation"
type: adversarial-finding
domain: "data-integrity"
severity: "tendency"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/core/indexing/VectorIndex.java"
research_status: active
last_researched: "2026-03-25"
---

# Public record result type missing compact constructor validation

## What happens
Java records auto-generate public canonical constructors that accept any value
for each component. When a record carries a numeric score used for ordering (via
`Float.compare`, `Comparator.comparingDouble`, or PriorityQueue), NaN values
corrupt ordering semantics — NaN is greater than all values per `Float.compare`
but false for all `<`/`>` comparisons. When a record carries an object reference
that must never be null (e.g., a document ID in a search result), null passes
silently and surfaces as NPE at a later, less debuggable call site.

## Why implementations default to this
Records are designed as transparent data carriers. Developers add records for
brevity, forgetting that the auto-generated constructor IS the public API
boundary. Unlike hand-written classes where the constructor typically includes
validation boilerplate, records require an explicit compact constructor to add
checks — an easy step to forget. The issue is especially subtle when internal
code already filters invalid values before constructing the record, giving a
false sense of safety: the public constructor remains unprotected for external
callers or future code paths.

## Test guidance
When auditing any public record type:
1. Construct with `null` for each reference component — should throw NPE
2. Construct with `Float.NaN` / `Double.NaN` for any score/distance/priority
   component — should throw IAE
3. Verify `Float.POSITIVE_INFINITY` and `Float.NEGATIVE_INFINITY` are accepted
   (they have valid ordering)
4. Verify zero and negative values are accepted where semantically valid

## Found in
- float16-vector-support (round 4, 2026-03-25): `VectorIndex.SearchResult(D docId, float score)`
  accepted null docId and NaN score. Fixed with compact constructor adding
  `Objects.requireNonNull` and `Float.isNaN` check.
- block-compression (audit round 2, 2026-03-26): `CompressionMap.Entry` accepted
  impossible size combinations (compressedSize=0 with uncompressedSize>0). Fixed with
  compact constructor validation. Also `TrieSSTableReader.Footer` had no validation at all.
  Fixed with `validate(fileSize)` method.
