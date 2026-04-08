---
title: "Negative size parameter accepted without validation"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/core/compression/*"
research_status: active
last_researched: "2026-03-25"
---

# Negative size parameter accepted without validation

## What happens

Methods accepting a size parameter (e.g. `uncompressedLength`) validate
offset/length bounds but skip validation of the size parameter itself. A negative
value reaches `new byte[negativeValue]` which throws `NegativeArraySizeException`
— an unchecked exception that violates the contract-specified `IllegalArgumentException`.
In passthrough codecs, the wrong exception type (e.g. `UncheckedIOException`) is thrown
because the mismatch check triggers before the array allocation.

## Why implementations default to this

Developers focus on validating the input array bounds (offset, length) and assume
the output size is derived from valid internal state. When the size parameter comes
from external data (e.g. a compression map entry in an SSTable), corrupt data can
inject arbitrary values including negatives.

## Test guidance

- For any method with a size/length output parameter: test with -1
- Verify `IllegalArgumentException`, not `NegativeArraySizeException` or other types
- Also test with 0 to verify the boundary behavior (should succeed for empty output)

## Found in

- block-compression (audit round 1, 2026-03-25): `DeflateCodec.decompress` and
  `NoneCodec.decompress` accepted negative `uncompressedLength`. Fixed with explicit
  IAE validation before array allocation.
