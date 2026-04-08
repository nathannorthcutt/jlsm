---
title: "NaN at phi threshold validation"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/**"
research_status: active
last_researched: "2026-03-26"
---

# NaN at phi threshold validation

## What happens
Double parameters guarding failure detection thresholds use `<= 0.0` validation,
which passes `Double.NaN` (since `NaN <= 0.0` is false). With a NaN threshold,
`phi(node) < NaN` is always false, making every monitored node appear unavailable.
This silently breaks the failure detector without any error signal.

## Why implementations default to this
The `<= 0.0` idiom is the natural way to check "must be positive" for doubles.
The NaN asymmetry in IEEE 754 comparisons is non-obvious — NaN is neither less
than, equal to, nor greater than any value.

## Test guidance
- For every `double` parameter validated as "must be positive", test with `Double.NaN`
- Use `!(x > 0.0)` instead of `x <= 0.0` — the negated form catches NaN
- Also test `Double.POSITIVE_INFINITY` and `Double.NEGATIVE_INFINITY`
- Add `Double.isFinite()` check when infinity is not a valid value

## Found in
- engine-clustering (round 1, 2026-03-26): PhiAccrualFailureDetector.isAvailable() and ClusterConfig.phiThreshold
