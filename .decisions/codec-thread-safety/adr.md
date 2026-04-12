---
problem: "codec-thread-safety"
date: "2026-04-10"
version: 1
status: "accepted"
depends_on: []
---

# Codec Thread Safety

## Problem
Thread safety of codec instances — no formal contract existed when the codec
API was first designed. Concurrent use from compaction, flush, and read paths
requires a clear guarantee.

## Decision
**Codecs MUST be stateless and thread-safe.** Per-call native resources (e.g.
`Deflater`/`Inflater`) must be created and released within each method
invocation. No mutable shared state is permitted.

This contract is documented on the `CompressionCodec` interface Javadoc and
is already satisfied by both built-in implementations (`NoneCodec`,
`DeflateCodec`).

## Rationale
- Both existing codecs are already thread-safe by construction — NoneCodec is
  a stateless singleton, DeflateCodec creates fresh native resources per call.
- `TrieSSTableWriter` and `TrieSSTableReader` may operate concurrently during
  compaction and reads — thread-safety is a practical requirement, not theoretical.
- Future codecs with pre-loaded dictionaries (see `codec-dictionary-support`)
  remain thread-safe as long as the dictionary is immutable after construction.

## Key Assumptions
- Codec instances are long-lived and shared across threads.
- Dictionary-backed codecs will use immutable, pre-loaded dictionaries.

## Conditions for Revision
- A codec implementation requires mutable per-instance state that cannot be
  made thread-safe without synchronization (unlikely given the stateless API
  contract).

## Implementation Guidance
No code changes required — the contract is already documented on
`CompressionCodec` (line 11) and both implementations comply. Future codec
authors must follow the documented contract.

## What This Decision Does NOT Solve
- `codec-dictionary-support` — dictionary loading and lifecycle management
- `codec-negotiation` — reader/writer codec agreement protocol
