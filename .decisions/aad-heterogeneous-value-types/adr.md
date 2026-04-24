---
problem: "aad-heterogeneous-value-types"
date: "2026-04-23"
version: 1
status: "deferred"
---

# AAD Heterogeneous Attribute Value Types — Deferred

## Problem

The canonical AAD encoding treats all `EncryptionContext` values as UTF-8 strings. `dekVersion` is already serialized as decimal UTF-8 per R80a-1. If a future `purpose` or context extension needs a non-string value type (binary blob, raw integer, boolean, timestamp), a type-tag extension to the TLV format would be required.

## Why Deferred

Scoped out during `aad-canonical-encoding` decision. All current attribute values are strings; no concrete requirement for heterogeneous types exists. Adding a speculative type tag now would complicate the encoder without benefit and would be incompatible with the current spec-pinned format.

## Resume When

When a concrete requirement appears, such as:
- A new `purpose` value needs a raw binary identifier (e.g., a GUID as 16 bytes rather than 36-char UUID string)
- Performance telemetry identifies decimal-UTF-8 encoding of integers as non-trivial cost (unlikely — payload is tiny)
- A cross-spec alignment requires a specific numeric encoding (e.g., IEEE-754 for a float attribute)

## What Is Known So Far

Identified during architecture evaluation of `aad-canonical-encoding`. See `.decisions/aad-canonical-encoding/adr.md` — specifically the "What This Decision Does NOT Solve" section.

Current position: all attribute values are UTF-8 strings. Numeric values (like `dekVersion`) are encoded as decimal UTF-8 per R80a-1. This is a deliberate choice because:
1. The wire format is simple: one type (string), one length field (4-byte BE)
2. Adding a type tag would require a spec amendment and would introduce implementation-divergence risk
3. Payload is tiny (~200 bytes); encoding efficiency is not a concern

If heterogeneous types become required, the likely evolution is a 1-byte type tag per attribute inserted before the length field, with values: `0x01` = UTF-8 string, `0x02` = 4-byte BE int, `0x03` = 8-byte BE int, `0x04` = variable-length bytes. This would be a wire-format change requiring re-wrap migration.

## Next Step

Run `/architect "AAD heterogeneous value types"` when ready to evaluate.
