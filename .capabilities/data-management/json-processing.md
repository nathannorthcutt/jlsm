---
name: "json-processing"
domain: "data-management"
type: "core"
status: "active"
tags: ["json", "jsonl", "parsing", "simd", "streaming", "serialization"]
created: "2026-04-10"
---

# JSON Processing

First-class JSON parsing, serialization, and streaming in `jlsm-core`.

## What it provides

- **JSON value types** — sealed `JsonValue` hierarchy (`JsonObject`, `JsonArray`,
  `JsonPrimitive`, `JsonNull`) with immutable, Stream-composable APIs
- **SIMD on-demand parser** — two-stage architecture (structural indexing +
  iterative materialization) with 3-tier runtime dispatch: Panama FFM carry-less
  multiply, Vector API, scalar fallback
- **JSONL streaming** — lazy `Stream<JsonValue>` from `InputStream` via
  `JsonlReader`, line-by-line `JsonlWriter` to `OutputStream`
- **Schema-aware adaptation** — `JsonValueAdapter` in `jlsm-table` converts
  between `JsonValue` and `JlsmDocument` with full type validation

## Key properties

- All JSON infrastructure lives in `jlsm.core.json` (exported) — available to
  every module, not just `jlsm-table`
- `jdk.incubator.vector` is `requires static` — optional, never forced on consumers
- Parser is iterative (no recursion), configurable max depth (default 256)
- Numbers preserve original text — lossless conversion via `asInt()`, `asLong()`,
  `asBigDecimal()`, etc.
- Duplicate object keys rejected at both parse and construction time

## Features

| Feature | Slug | Role | Description |
|---------|------|------|-------------|
| JSON-only SIMD on-demand + JSONL streaming | json-only-simd-jsonl | core | Initial implementation of JSON value types, SIMD parser, JSONL streaming, YAML removal |

## Spec coverage

- F15 — 46 requirements (adversarial-hardened)

## Dependencies

- None (pure `jlsm-core`, no external libraries)

## Constraints

- PanamaStage1 currently uses pure-Java carry-less multiply — native FFM downcall
  deferred pending benchmarks
- `fromJson` in `jlsm-table` wraps `JsonParseException` → `IllegalArgumentException`
  for backward compatibility
