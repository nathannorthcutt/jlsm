---
problem: "encryption-granularity-per-field-vs-per-block"
date: "2026-04-24"
version: 1
status: "deferred"
---

# Encryption Granularity: Per-Field vs Per-Block — Deferred

## Problem

Should jlsm migrate from its current **per-field** encryption model (field
values are individually wrapped in a ciphertext envelope specified by
`encryption.ciphertext-envelope`) to a **per-block** model (whole SSTable
blocks — data, bloom, index — are wrapped in AES-GCM with a per-file
footer carrying key-id)? Or retain per-field, or support a hybrid?

Per-block encryption is what KB research in `.kb/systems/security/sstable-block-level-ciphertext-envelope.md` envisions and is used in
production systems like CockroachDB, RocksDB, and DuckDB 1.4. It would
replace the per-field envelope at the SSTable tier with whole-block
GCM framing, using the GCM auth tag for per-block integrity (replacing
the v3 per-block CRC32C) and binding blocks to their file position via
the GCM AAD.

## Why Deferred

Scoped out during `sstable-footer-scope-format` decision (2026-04-24).
The user explicitly chose NOT to bundle per-block AES-GCM into WD-02
after risks were enumerated:

- **Breaks OPE (OrderPreserving) and DCPE (Distance-Preserving) variants**:
  per-block AES-GCM is semantically secure and destroys the order /
  distance preservation that makes these variants valuable. Hybrid
  specifications possible but multiply complexity.
- **Violates `encryption.ciphertext-envelope` R1a** (APPROVED): the
  "byte-identical across MemTable/WAL/SSTable tiers" invariant breaks
  when the SSTable tier applies an additional block-level cipher.
- **Cascades across 6+ APPROVED specs**: primitives-lifecycle,
  primitives-variants, primitives-dispatch, primitives-key-holder,
  client-side-sdk, wal.encryption — all built on the per-field
  assumption and would require major amendment.
- **Requires WD-03/04 redesign**: rotation and compaction semantics
  fundamentally different between the two models. The R26 "skip if
  already at current version" optimization is not available under
  per-block (every compaction re-encrypts every block).
- **Breaks CSFLE (client-side field encryption) contract**: caller
  pre-encrypted bytes would double-encrypt under per-block or require
  complex carve-outs.
- **Time cost**: 2–3 months of re-architecting + adversarial re-approval
  of 6+ APPROVED specs; blocks WD-03/04/05 until the decision settles.

## Resume When

Pursue this decision if:
- jlsm commits to per-block encryption as a marketed capability (at-rest
  encryption posture aligned with Cockroach/TiKV/RocksDB)
- OPE/DCPE encryption variants are dropped or reclassified (since they
  are fundamentally incompatible with per-block AES-GCM)
- A credible threat model emerges where per-field encryption is
  insufficient (e.g., index-block key-prefix leakage becomes material)
- The primitives-lifecycle implementation is incomplete enough that
  re-architecting is still cheap

Expected window: unlikely within 12 months given current spec and
implementation commitments.

## What Is Known So Far

- Current per-field design is shipped through WD-01 (three-tier key
  hierarchy) and is canonically specified by `encryption.ciphertext-envelope`
  (APPROVED v1) and `encryption.primitives-lifecycle` (DRAFT v9).
- `.kb/systems/security/sstable-block-level-ciphertext-envelope.md`
  contains the reference design for per-block AES-GCM framing with
  per-file footer MAC, deterministic nonces (`file-id ‖ block-counter`),
  and AAD binding `(file-id ‖ block-offset ‖ level ‖ algorithm-tag)`.
- Production systems chosen patterns: CockroachDB uses file-level AES-CTR
  with external registry; RocksDB uses per-file prefix header with
  pluggable cipher; DuckDB 1.4 uses per-block AES-GCM with 40-byte
  block header.
- The footer-scope format confirmed in `sstable-footer-scope-format` is
  compatible with per-block should the transition happen — but per-block
  would add footer fields (DEK handle, algorithm tag, nonce strategy,
  footer MAC) that would likely warrant a v7 format bump.

## Next Step

Run `/architect "encryption-granularity-per-field-vs-per-block"` when
the resume condition is met. Expected evaluation inputs: OPE/DCPE
compatibility as a hard constraint; CSFLE interaction; R1a cross-tier
uniformity decision (keep, amend, abandon); WD-03/04 re-specification
cost; blast radius on currently-APPROVED specs.
