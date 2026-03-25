---
title: "OPE width truncation"
type: adversarial-finding
domain: "data-integrity"
severity: "critical"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/FieldEncryptionDispatch.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/IndexRegistry.java"
research_status: active
last_researched: "2026-03-25"
---
# OPE width truncation

## What happens
When Order-Preserving Encryption (Boldyreva OPE) is capped to MAX_OPE_BYTES for performance,
applying it to field types wider than the cap causes silent data truncation. Only the
most-significant bytes participate in encryption; lower bytes are zeroed on round-trip.
This becomes a data corruption bug when OPE is used in the storage encrypt/decrypt path
(not just for index ordering).

## Why implementations default to this
OPE's hypergeometric sampling is O(range/2) per recursion level, making large domains
impractical. Capping to 2 bytes (domain=65536) keeps performance acceptable. The cap
was implemented for performance but applied to the round-trip data path without realizing
it would destroy field bytes beyond the cap.

## Test guidance
- For any OPE-encrypted field, verify `decrypt(encrypt(x)) == x` for values with
  significant bits in ALL byte positions, not just the MSBs
- Verify that field types wider than the OPE byte cap are rejected at construction
- Check both the dispatch layer (FieldEncryptionDispatch) and the index validation
  layer (IndexRegistry) for consistent rejection

## Found in
- encrypt-memory-data (round 1, 2026-03-25): INT32/INT64/TIMESTAMP fields with OrderPreserving encryption silently lost lower bytes on round-trip
