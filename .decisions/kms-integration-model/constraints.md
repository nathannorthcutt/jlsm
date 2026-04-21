---
problem: "kms-integration-model"
slug: "kms-integration-model"
captured: "2026-04-21"
status: "confirmed"
---

# Constraint Profile — kms-integration-model

## Problem Statement

Define the KmsClient SPI, cache TTL, retry/backoff, timeout, encryption
context, and observability for jlsm's interaction with external KMS
implementations (flavor 3, BYO-KMS). Includes compatibility with the
shipped flavor 2 `LocalKmsClient` reference implementation.

## Constraints Inherited

- **Per-tenant isolation** (ADR A): KMS traffic is per-tenant; one
  tenant's load cannot affect others
- **Three-flavor KMS model** (ADR A): none / local / external
- **Three-state failure machine** (ADR D): healthy / grace-read-only /
  failed; classifier distinguishes transient from permanent errors
- **Proof-of-control sentinel pattern** (ADR D): requires SPI support
  for wrap and unwrap of sentinel blobs with nonce+timestamp
- **Arena-backed Panama FFM** throughout; zeroize-on-close

## Constraints Confirmed In-Session (2026-04-21)

- Default cache TTL for unwrapped domain KEKs: **30 min**
- Single-call KMS timeout: **10 s**
- Retry: **3 attempts**, exponential backoff (100ms → 400ms → 1.6s),
  ±25% jitter, transient errors only
- Encryption context passed on every wrap/unwrap: `tenantId`,
  `domainId`, `purpose`
- `KmsClient` implementations own their connection pooling
- Observability via `KmsObserver` interface; deployer-plumbed

## Key Constraints (most narrowing)

1. **Per-tenant isolation** — rules out shared global rate-limit queues;
   retries happen per-tenant call chain.
2. **Arena-backed keys throughout** — rules out heap-backed cached
   KEKs; the SPI must return `MemorySegment`-shaped data.
3. **Three-state classifier discipline** — requires the SPI to expose
   an exception hierarchy that permits transient/permanent distinction
   without string-matching error messages.

## Unknowns

None material. Defaults are all configurable; structural choices are
narrowed by the prior ADRs.
