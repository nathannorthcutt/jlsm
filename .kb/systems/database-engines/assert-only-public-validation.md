---
title: "Assert-only validation on public API inputs"
type: adversarial-finding
domain: "data-integrity"
severity: confirmed
applies_to:
  - "modules/jlsm-engine/src/main/**"
  - "modules/jlsm-core/src/main/**"
research_status: active
last_researched: "2026-03-26"
---

# Assert-only validation on public API inputs

## What happens

Builder methods and constructors use `assert value > 0` as the sole validation for
numeric configuration parameters. With assertions enabled (test time), this throws
`AssertionError` — wrong exception type for callers expecting `IllegalArgumentException`.
With assertions disabled (production), invalid values (0, negative) are silently accepted,
leading to hard-to-diagnose failures downstream (e.g., division by zero in eviction logic,
unbounded resource growth).

## Why implementations default to this

Project rules say "use assert statements throughout all code to document and enforce
assumptions." Developers conflate documenting invariants (correct use of assert) with
validating external inputs (requires explicit exception). The distinction is subtle:
asserts verify things that should be impossible if the code is correct; input validation
guards against incorrect caller usage.

## Test guidance

- For every builder setter accepting numeric limits: pass 0 and -1, assert
  `IllegalArgumentException.class` (not `AssertionError`, not silent acceptance)
- For every constructor accepting configuration: same pattern
- Verify the exception message includes the parameter name and actual value
- The assert should remain AFTER the explicit check as a secondary invariant guard,
  not as the sole validation

## Found in

- in-process-database-engine (audit round 1, 2026-03-26): HandleTracker.Builder and
  LocalEngine.Builder used assert-only for maxHandlesPerSourcePerTable,
  maxHandlesPerTable, maxTotalHandles, memTableFlushThresholdBytes. Fixed by adding
  explicit IAE checks before the existing asserts.
- block-compression (audit round 2, 2026-03-26): `TrieSSTableReader.readAndDecompressBlock`
  and `readAndDecompressBlockNoCache` used assert for codec-not-null check. NPE in
  production instead of IOException. Fixed by replacing assert with runtime check.
