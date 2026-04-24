---
problem: "aad-identifier-normalization"
date: "2026-04-23"
version: 1
status: "deferred"
---

# AAD Identifier Normalization — Deferred

## Problem

Whether `EncryptionContext` string values (tenantId, domainId, tableId, etc.) should be Unicode-normalized (NFC / NFD / NFKC / NFKD) before being encoded into AAD. The canonical encoding ADR (`aad-canonical-encoding`) explicitly pushes this responsibility to the caller ("bytes-as-supplied"). This deferred decision tracks whether that position needs to change.

## Why Deferred

Scoped out during `aad-canonical-encoding` decision. Pushing normalization to callers is the current position and works as long as all callers are Java processes within a single jlsm deployment. If cross-runtime identifier-drift bugs appear (e.g., a Python client normalizes `é` differently from a Java client), this decision becomes blocking.

## Resume When

When `aad-canonical-encoding` implementation is stable AND one of:
- A non-Java client of jlsm wrapping appears (likely via `aad-non-java-consumer-interop`)
- A production incident is traced to Unicode normalization mismatch between clients
- A deployment requirement appears where tenants supply identifiers via an interface that normalizes differently (e.g., a Kubernetes label vs an HTTP header)

## What Is Known So Far

Identified during architecture evaluation of `aad-canonical-encoding`. See `.decisions/aad-canonical-encoding/adr.md` — specifically the "What This Decision Does NOT Solve" and "Why UTF-8 bytes-as-supplied" sections.

The current position: callers own identifier stability. jlsm does not silently transform caller data inside the cryptographic primitive because silent transformation introduces a hidden contract. If normalization becomes required, it belongs at the ingestion boundary (before the caller hands the context to the KmsClient SPI), not inside the AAD encoder.

## Next Step

Run `/architect "AAD identifier normalization"` when ready to evaluate.
