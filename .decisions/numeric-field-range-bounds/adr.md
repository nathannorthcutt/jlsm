---
problem: "numeric-field-range-bounds"
date: "2026-04-13"
version: 1
status: "deferred"
depends_on: ["parameterized-field-bounds"]
---

# Numeric Field Range Bounds — Deferred

## Problem
Min/max range constraints for numeric (INT32/INT64/FLOAT*/TIMESTAMP) field types — for OPE domain narrowing and write-time validation.

## Why Deferred
Scoped out during `parameterized-field-bounds` decision. OPE is capped at 2 bytes (INT8/INT16 only). The byte-width already defines a tight OPE domain. Custom numeric range bounds would micro-optimize an already-small domain.

## Resume When
When OPE byte cap is raised beyond 2 bytes, a new encryption scheme (ORE, FHIPE) benefits from range metadata, or application-layer validation proves insufficient.

## What Is Known So Far
Three design approaches evaluated: per-type sealed permits (rejected — too many new permits), generic Bounded wrapper (rejected — doubles switch logic), ValueBounds on FieldDefinition (viable when needed — FieldEncryptionDispatch already has FieldDefinition context). See `.decisions/parameterized-field-bounds/evaluation.md` for full scoring.

## Next Step
Run `/architect "numeric-field-range-bounds"` when ready to evaluate.
