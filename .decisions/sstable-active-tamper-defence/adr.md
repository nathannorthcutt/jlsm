---
problem: "sstable-active-tamper-defence"
date: "2026-04-24"
version: 1
status: "deferred"
---

# SSTable Active Tamper Defence — Deferred

## Problem

Should jlsm provide cryptographic integrity defence for SSTable files
independent of the storage substrate? Specifically: should readers be
able to detect and reject SSTable bytes that have been modified by an
attacker with write access to storage, without delegating that
responsibility to the filesystem / object store?

## Why Deferred

Scoped out during `sstable.footer-encryption-scope` spec authoring
(2026-04-24). User confirmed Model 2 threat-model boundary:
`sstable.footer-encryption-scope` R13 declares active on-disk tamper
out of scope, delegating integrity to the storage substrate (local
filesystem + OS ACLs, or object stores with authenticated IAM and
object-lock / WORM configurations).

Rationale for deferral rather than in-spec patch:

- **Partial defence is worse than clear boundary.** A patch that
  closed only the specific F9 attack (v5-swap replacing a v6
  encrypted SSTable with a fabricated v5 plaintext) would leave
  other tamper classes open — e.g., a fabricated v6 SSTable with
  a footer matching a legitimate scope, or tampering with bloom
  filter / key index bytes within a v6 file. R6 would pass the
  forged v6; only per-field HKDF-bound decryption would catch
  the ciphertext attack, leaving plaintext (bloom, index) untrusted.
  Partial integrity gives false confidence.

- **Comprehensive defence is a separate architectural initiative.**
  A full tamper-defence design requires decisions about: MAC
  algorithm (HMAC-SHA256, BLAKE3-MAC, GCM), MAC key derivation
  (from tenant KEK via HKDF, or a separate manifest-signing key
  in the three-tier hierarchy), MAC placement (per-SSTable in the
  footer, or per-file hash in a signed manifest), recovery
  semantics when MAC verification fails (same-failure-class as
  v3 per-block CRC failure? or dedicated recovery path?),
  interaction with the deferred
  `encryption-granularity-per-field-vs-per-block` decision (if
  jlsm adopts per-block AES-GCM, the per-block tag is the
  authenticator and a separate manifest MAC becomes redundant).

- **Industry precedent supports delegation.** CockroachDB, TiKV,
  MySQL InnoDB TDE, MongoDB, and RocksDB all delegate active-tamper
  defence to the storage substrate. Their encryption-at-rest
  features defend confidentiality under correct storage; integrity
  under active tamper is not a claimed property.

## Resume When

Pursue this decision if:
- jlsm commits to a threat model that includes active on-disk
  tamper (e.g., deployment on untrusted multi-tenant object storage
  where IAM / bucket-level isolation is insufficient)
- A concrete security audit or compliance requirement emerges
  that demands file-level integrity independent of storage
- The deferred `encryption-granularity-per-field-vs-per-block`
  decision is revisited — that transition naturally provides
  per-block integrity via GCM tags and may obviate a separate
  manifest MAC for data blocks (still leaves bloom/index concerns
  unless they too move under AEAD)

Expected window: not scheduled. Likely triggered by a specific
deployment context rather than proactive design.

## What Is Known So Far

- `sstable.footer-encryption-scope` R13 declares the threat-model
  boundary explicitly. R6/R11 (HKDF scope binding) defend
  confidentiality of encrypted field values but not integrity of
  plaintext SSTable structure (bloom filter, key index, footer
  metadata outside the CRC32C scope).
- `sstable.v3-format-upgrade` and `sstable.end-to-end-integrity`
  provide per-block CRC32C and per-section CRC32C respectively —
  corruption detection, not cryptographic integrity. CRC32C is
  trivially forgeable by an attacker with write access.
- Three candidate architectures identified in the Pass 2
  falsification of `sstable.footer-encryption-scope`:
  1. Manifest-bound file MAC (MAC over each SSTable's bytes,
     stored in an authenticated manifest, keyed from tenant KEK
     via HKDF)
  2. Per-SSTable authenticated footer (footer carries a MAC
     computed over its own content, keyed from tenant KEK)
  3. Per-block AEAD (full per-block AES-GCM transition — larger
     decision tracked separately by
     `encryption-granularity-per-field-vs-per-block`)

## Next Step

Run `/architect "sstable-active-tamper-defence"` when the resume
condition is met. Expected evaluation inputs: MAC algorithm choice
vs AEAD transition; key derivation approach (tenant-KEK-derived MAC
key via HKDF, or dedicated manifest-signing key); recovery semantics;
interaction with the deferred per-block encryption decision;
backward-compatibility plan for existing v5/v6 files.

Originating decision:
[`.spec/domains/sstable/footer-encryption-scope.md`](../../.spec/domains/sstable/footer-encryption-scope.md) R13
