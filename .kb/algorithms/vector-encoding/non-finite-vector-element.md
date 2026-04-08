---
title: "Non-finite vector elements accepted at construction boundary"
type: adversarial-finding
domain: data-integrity
severity: confirmed
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
  - "modules/jlsm-vector/src/main/java/**"
research_status: active
last_researched: "2026-03-25"
---

# Non-finite vector elements accepted at construction boundary

## What happens
Vector fields (FLOAT32 as `float[]`, FLOAT16 as `short[]`) accept NaN and Infinity elements without validation at `JlsmDocument.of()`. NaN elements produce garbage in similarity computations: `cosine(query, [NaN,...])` = `NaN/NaN` = NaN. NaN scores are either filtered out (making vectors invisible in search) or corrupt sorted result sets. Infinity elements cause similar issues with dot-product and euclidean distance. Additionally, JSON/YAML serialization writes NaN as `null`, which cannot be parsed back — breaking round-trip fidelity.

## Why implementations default to this
Dimension validation (checking `float[].length == dimensions`) is the obvious correctness check. Element-level finiteness validation requires iterating every element, which feels like an unnecessary cost. IEEE 754 NaN is technically a valid float representation, so type-level checks pass. The downstream impact (invisible vectors, corrupted search results) is non-obvious.

## Test guidance
- For FLOAT32: pass `Float.NaN`, `Float.POSITIVE_INFINITY`, `Float.NEGATIVE_INFINITY` as elements in a vector. Should throw `IllegalArgumentException`.
- For FLOAT16: pass raw NaN bits (`(short) 0x7E00`), +Inf bits (`(short) 0x7C00`), -Inf bits (`(short) 0xFC00`). Should throw `IllegalArgumentException`.
- Boundary finite values should be accepted: `Float.MAX_VALUE`, `Float.MIN_VALUE`, `-0.0f`, float16 max finite `(short) 0x7BFF`.
- Non-finite check: FLOAT32 uses `Float.isFinite()`; FLOAT16 uses `(bits & 0x7C00) == 0x7C00` (exponent all-ones = non-finite).
- Related KB pattern: `precision-overflow-silent-data-loss` — overflow during float32→float16 quantization also produces Infinity.

## Found in
- vector-field-type (audit round 1, 2026-03-25): NaN/Infinity in float[] and short[] vectors accepted by JlsmDocument.of()
