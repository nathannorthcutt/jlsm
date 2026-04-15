---
problem: "bulk-data-transfer-channel"
date: "2026-04-13"
version: 1
status: "deferred"
depends_on: ["connection-pooling"]
---

# Bulk Data Transfer Channel — Deferred

## Problem
Large payload transfer (SSTable replication, snapshot transfer) may cause TCP head-of-line
blocking on the multiplexed cluster transport connection, delaying membership pings and
query responses.

## Why Deferred
Scoped out during `connection-pooling` decision. The multiplexed transport is designed for
small cluster messages (pings, query requests/responses). Bulk data transfer needs a separate
mechanism — either dedicated connections, chunked framing, or a streaming protocol.

## Resume When
When `connection-pooling` implementation is stable and SSTable replication or snapshot
transfer becomes a concrete requirement.

## What Is Known So Far
Identified during architecture evaluation of `connection-pooling`. The multiplexed transport
uses Kafka-style framing with no frame-size cap for the initial implementation. Head-of-line
blocking is bounded for small messages but becomes problematic for multi-megabyte SSTable
blocks. Options include: dedicated SocketChannel per bulk transfer, chunked framing with
interleaving, or a streaming extension to the framing protocol.

See `.decisions/connection-pooling/adr.md` for the architectural context.

## Next Step
Run `/architect "bulk-data-transfer-channel"` when ready to evaluate.
