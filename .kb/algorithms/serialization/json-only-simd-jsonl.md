---
title: "json-only-simd-jsonl"
type: feature-footprint
domains: ["serialization", "engine"]
constructs: ["JsonValue", "JsonObject", "JsonArray", "JsonPrimitive", "JsonNull", "JsonParser", "JsonWriter", "JsonlReader", "JsonlWriter", "JsonValueAdapter", "StructuralIndexer", "TierDetector", "ScalarStage1", "VectorStage1", "PanamaStage1", "JsonParseException"]
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/core/json/*.java"
  - "modules/jlsm-core/src/main/java/jlsm/core/json/internal/*.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/JsonValueAdapter.java"
related:
  - "algorithms/serialization/simd-on-demand-serialization"
  - "algorithms/serialization/panama-ffm-inline-machine-code"
  - "algorithms/serialization/simd-serialization-java-fallbacks"
  - "systems/lsm-index-patterns/optimize-document-serializer"
decision_refs: []
spec_refs: ["F15"]
research_status: stable
last_researched: "2026-04-10"
---

# json-only-simd-jsonl

## What it built

Added first-class JSON processing to `jlsm-core`: a sealed `JsonValue` type hierarchy,
a two-stage SIMD on-demand JSON parser with three-tier runtime dispatch (Panama FFM /
Vector API / scalar), and JSONL streaming for bulk import/export. Removed all YAML
support from `jlsm-table` and refactored document JSON operations to delegate to the
new core infrastructure via a `JsonValueAdapter`.

## Key constructs

- `JsonValue` — sealed interface permitting JsonObject, JsonArray, JsonPrimitive, JsonNull
- `JsonObject` — immutable, insertion-ordered, duplicate-rejecting Map-like container
- `JsonArray` — immutable, List-like container with `stream()` for Stream composability
- `JsonPrimitive` — strings, numbers (raw text preserved for lossless conversion), booleans
- `JsonNull` — enum singleton (identity-safe)
- `JsonParser` — two-stage: StructuralIndexer (stage 1) + iterative materializer (stage 2)
- `StructuralIndexer` — dispatches to ScalarStage1/VectorStage1/PanamaStage1 based on TierDetector
- `TierDetector` — static final tier selection at class-load, exception-safe fallthrough
- `ScalarStage1` — byte-by-byte structural scanning with backslash-parity tracking
- `VectorStage1` — ByteVector character classification (isolated, loaded only when tier 2)
- `PanamaStage1` — carry-less multiply for quote masking (pure-Java CLMUL currently)
- `JsonWriter` — iterative stack-based serialization, compact and pretty-printed
- `JsonlReader` — lazy `Stream<JsonValue>` from InputStream, two error modes
- `JsonlWriter` — writes any JsonValue one-per-line to OutputStream
- `JsonValueAdapter` — bidirectional JsonValue ↔ JlsmDocument conversion with schema typing
- `JsonParseException` — unchecked, carries byte offset

## Adversarial findings

- Spec adversarial pass found 15 gaps (see F15 spec narrative). Key ones:
  - R13/R18 contradiction: on-demand lazy materialization vs fully materialized return
  - R16 tier detection: class-initialization failure could kill all tiers
  - R23 incubator propagation: hard `requires` would force flag on all consumers
  - R22 arbitrary depth: violated project bounded-iteration rule
- `fromJson` wraps `JsonParseException` → `IllegalArgumentException` for jlsm-table compatibility

## Cross-references

- Spec: .spec/domains/serialization/F15-json-only-simd-jsonl.md
- KB research: algorithms/serialization/ (3 entries formed implementation strategy)
- Prior footprint: systems/lsm-index-patterns/optimize-document-serializer (FieldDecoder dispatch preserved)
- Related features: in-process-database-engine (uses JlsmDocument.fromJson)
