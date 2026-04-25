---
problem: "encryption-disable-policy"
date: "2026-04-24"
version: 1
status: "deferred"
---

# Encryption Disable Policy — Deferred

## Problem

Should jlsm support in-place disabling of encryption for a table that was
previously encrypted? That is, can a caller invoke something like
`Engine.disableEncryption(name)` to return an encrypted table to an
unencrypted state while preserving the handle's identity and data?

The dual operation (enable) is supported. This ADR is specifically
about the reverse — removing encryption from a table that has been
encrypted.

## Why Deferred

Scoped out during `table-handle-scope-exposure` decision (2026-04-24).
The user explicitly chose to make encryption one-way, rejecting in-place
disable as unnecessary now. Rationale:

- **Industry precedent**: CockroachDB, TiKV, MySQL InnoDB TDE,
  MongoDB CSFLE, DuckDB 1.4 either do not support in-place disable or
  implement "disable" as a full table copy under the hood. In-place
  is not a commonly-shipped primitive.
- **State machine complexity**: supporting in-place disable requires
  adding a DRAINING state to `EncryptionMetadata`, compaction
  integration that rewrites v6 → v5 (reverse of WD-04's migration),
  and extended DEK lifecycle that keeps keys alive during drain.
  Significant new spec + impl surface.
- **Compliance risk**: a DRAINING window creates a period where both
  plaintext and ciphertext exist for the same logical data. For
  deployments that encrypted for PCI/HIPAA/data-residency compliance,
  decrypting back can violate the posture that required encryption
  in the first place.
- **No concrete user demand**: the feature is not requested by any
  current or planned use case.

## Resume When

Pursue this decision if:
- A concrete user asks for disable-in-place and cannot use
  `copyTable` + `dropTable` as a workaround
- A compliance framework emerges that requires in-place decryption
  (unlikely — the direction of travel in compliance is usually the
  opposite)
- jlsm's product posture shifts to prioritise developer-ergonomics
  symmetry (enable should have a symmetric disable)

Expected window: no scheduled revisit. Likely never, unless user
demand surfaces.

## What Is Known So Far

- `TableMetadata.encryption` is `Optional<EncryptionMetadata>` per
  `table-handle-scope-exposure`. The sub-record currently holds only
  `TableScope`; it could grow an `EncryptionState` enum if disable
  becomes supported.
- The current SSTable format dispatch (`sstable-footer-scope-format`)
  already supports mixed fleets (some v5 plaintext, some v6
  encrypted) because each file is self-describing. Disable would
  reverse the migration direction — v6 encrypted → v5 plaintext —
  via compaction.
- Callers who need to "disable" today can copy the table to a fresh
  unencrypted one (once a `copyTable` primitive exists — currently
  not scheduled) and drop the source.
- WD-03 (DEK lifecycle + KEK rotation) and WD-04 (compaction-driven
  re-encryption) own the DEK retirement and compaction mechanics
  that an in-place disable would exercise.

## Next Step

Run `/architect "encryption-disable-policy"` when the resume condition
is met. Expected evaluation inputs: state-machine complexity in
`EncryptionMetadata`; DEK lifecycle extension cost in WD-03 work;
compaction reverse-migration cost in WD-04 work; compliance posture
analysis; alternative paths (`copyTable` + drop, export/import,
engine-level re-create).

Originating decision:
[`.decisions/table-handle-scope-exposure/adr.md`](../table-handle-scope-exposure/adr.md)
