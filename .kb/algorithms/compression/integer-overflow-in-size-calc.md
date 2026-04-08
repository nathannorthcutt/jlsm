---
title: "Integer overflow in header+count*entrySize calculation"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/CompressionMap.java"
research_status: active
last_researched: "2026-03-26"
---

# Integer overflow in header+count*entrySize calculation

## What happens

When computing the expected byte length of a serialized structure as
`header + count * entrySize`, int arithmetic overflows if `count` is large
(e.g., read from corrupt on-disk data). The overflowed result can become
negative, causing a length validation check (`data.length < expectedLength`)
to pass spuriously (any positive length is >= a negative number). The
subsequent loop then reads past the array bounds, throwing
`ArrayIndexOutOfBoundsException` instead of a clear `IllegalArgumentException`.
In `serialize()`, the same overflow causes `NegativeArraySizeException` on
`new byte[size]`.

## Why implementations default to this

The multiplication `count * entrySize` looks safe because developers reason
about realistic counts (hundreds to thousands of blocks). The overflow only
occurs at ~126M entries for a 17-byte entry size, which seems unlikely. But
the count comes from untrusted on-disk data and can be any int value.

## Test guidance

- For any `deserialize(byte[])` that reads a count header and computes expected
  length via multiplication: test with `count = Integer.MAX_VALUE` in a minimal
  byte array (just the header). Verify `IllegalArgumentException`, not AIOOBE.
- For any `serialize()` that computes buffer size from collection size: verify
  the calculation uses `long` arithmetic or has an explicit overflow guard.
- The safe pattern: compute in `long`, then check `if (expectedLength > Integer.MAX_VALUE)`.

## Found in

- block-compression (audit round 2, 2026-03-26): `CompressionMap.deserialize` used
  `int blockCount * ENTRY_SIZE` which overflowed. Fixed to `long` arithmetic with
  explicit overflow guard. Fix-forward applied to `serialize()`.
