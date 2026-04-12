---
problem: "codec-negotiation"
date: "2026-03-30"
version: 1
status: "closed"
---

# Codec Negotiation — Closed (Won't Pursue)

## Problem
Codec negotiation between writer and reader — reader must be configured with
all codecs the writer might use.

## Decision
**Will not pursue.** The existing design already solves this.

## Reason
The compression map stores `codecId` per-block. The reader takes
`CompressionCodec... codecs` at open time, builds a `codecId → codec` lookup
map, and validates at open time that every codec ID referenced by any block
has a matching codec. Missing codecs fail immediately with a clear error:
`"unknown compression codec ID 0x%02x in block %d; available codecs: %s"`.

This is the standard approach used by Parquet, ORC, and other columnar formats.
The codec ID in the file metadata IS the negotiation protocol — no additional
handshake or discovery mechanism is needed for a library (as opposed to a
client-server system with live codec version negotiation).

## Context
Parent ADR: `compression-codec-api-design` (confirmed 2026-03-17)
Deferred: 2026-03-30
Closed: 2026-04-12 — already solved by existing design

## Conditions for Reopening
If jlsm adds a network protocol where writer and reader are in different
processes and need to agree on codecs dynamically (rather than at file open
time), this would need revisiting. That scenario is covered by
`message-serialization-format` (deferred).
