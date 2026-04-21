---
problem: "kms-integration-model"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-21"
---

# kms-integration-model — Decision Index

**Problem:** How jlsm integrates with external KMS implementations (flavor 3 BYO-KMS) and what defaults apply to cache TTL, retry/backoff, timeouts, and encryption context.
**Status:** confirmed
**Current recommendation:** `KmsClient` SPI with wrap / unwrap / isUsable + transient/permanent exception hierarchy; 30min cache TTL default; 3-retry exponential backoff (100ms → 400ms → 1.6s, ±25% jitter) for transient errors only; 10s per-call timeout; encryption context passes `tenantId` + `domainId` + `purpose` as AAD-bound KMS-side audit data; deployer-plumbed observability via `KmsObserver`.
**Last activity:** 2026-04-21 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-21 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-21 |
| [log.md](log.md) | Decision history | 2026-04-21 |

## Prerequisites

| Slug | Relationship |
|------|-------------|
| [three-tier-key-hierarchy](../three-tier-key-hierarchy/adr.md) | KmsClient SPI shape, per-tenant isolation, flavor model |
| [tenant-key-revocation-and-external-rotation](../tenant-key-revocation-and-external-rotation/adr.md) | Three-state failure machine classifier, proof-of-control sentinel pattern |

## Confidence

High. Design space collapsed to defaults-tuning by prior ADRs.
