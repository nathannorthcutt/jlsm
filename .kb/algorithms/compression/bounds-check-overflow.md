---
title: "Integer overflow in offset+length bounds check"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/core/compression/*"
research_status: active
last_researched: "2026-03-25"
---

# Integer overflow in offset+length bounds check

## What happens

The pattern `offset + length > array.length` overflows when both `offset` and
`length` are large positive ints. The sum wraps to a negative value, which is
always less than `array.length`, so the guard passes. The downstream operation
then throws `ArrayIndexOutOfBoundsException` instead of the contract-required
`IllegalArgumentException`.

## Why implementations default to this

The `offset + length > array.length` pattern reads naturally and works for all
reasonable values. The overflow only occurs when `offset + length > Integer.MAX_VALUE`,
which is rare in practice but violates fail-fast guarantees. Developers rarely test
with extreme int values.

## Test guidance

- For any method accepting `(byte[] input, int offset, int length)`:
  test with `offset = Integer.MAX_VALUE, length = 1` on a small array
- Verify `IllegalArgumentException` is thrown, not `ArrayIndexOutOfBoundsException`
- The safe pattern is: `length > input.length - offset` (no overflow since
  `offset >= 0` is checked first, so `input.length - offset >= 0`)

## Found in

- block-compression (audit round 1, 2026-03-25): All 4 codec methods in DeflateCodec
  and NoneCodec used `offset + length > input.length`. Fixed to `offset > input.length - length`.
